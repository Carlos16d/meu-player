package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

import org.libtorrent4j.swig.*;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private torrent_handle torrentHandle;
    private File videoFile;
    private long fileSize = 0;
    private int pieceLength = 0;
    private int numPieces = 0;
    private long totalRequests = 0;
    private long bytesServed = 0;
    private String savePath;
    
    public StreamServer() { 
        super(8080); 
    }
    
    public void setSavePath(String path) {
        this.savePath = path;
    }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            torrent_info ti = handle.torrent_file_ptr();
            if (ti != null && ti.is_valid()) {
                this.fileSize = ti.total_size();
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                Log.d(TAG, "Torrent: " + (fileSize/1048576) + "MB, " + numPieces + " peças, " + (pieceLength/1024) + "KB");
            }
            findVideoFile();
        }
    }
    
    private void findVideoFile() {
        if (savePath == null) return;
        File dir = new File(savePath);
        videoFile = findRecursive(dir);
        if (videoFile != null) {
            Log.d(TAG, "Video: " + videoFile.getName() + " " + videoFile.length());
        }
    }
    
    private File findRecursive(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findRecursive(f);
                    if (found != null) return found;
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }
    
    public String getStats() {
        return totalRequests + "req " + (bytesServed/1048576) + "MB";
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        totalRequests++;
        
        // Procura arquivo se necessário
        if (videoFile == null || !videoFile.exists()) {
            findVideoFile();
        }
        
        if (videoFile == null || !videoFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not ready");
        }
        
        try {
            long fileSize = videoFile.length();
            long start = 0, end = fileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            }
            
            // SEEK: quando o player pula para uma posição distante
            if (torrentHandle != null && pieceLength > 0 && start > 0) {
                int targetPiece = (int)(start / pieceLength);
                
                // Força prioridade MÁXIMA na peça que o player pediu
                if (targetPiece < numPieces) {
                    torrentHandle.piece_priority_ex(targetPiece, (byte)7);
                    torrentHandle.set_piece_deadline(targetPiece, 1000); // 1 segundo!
                    
                    // Prioridade ALTA nas próximas 20 peças
                    for (int i = targetPiece + 1; i < Math.min(targetPiece + 20, numPieces); i++) {
                        torrentHandle.piece_priority_ex(i, (byte)6);
                        torrentHandle.set_piece_deadline(i, 3000);
                    }
                    
                    if (totalRequests % 10 == 0) {
                        Log.d(TAG, "SEEK! Peça " + targetPiece + " prioridade MAX, deadline 1s");
                    }
                }
            }
            
            if (start >= fileSize) start = Math.max(0, fileSize - 262144);
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 262144);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            int retries = 0;
            
            // Aguarda dados (especialmente importante para seek)
            while (bytesRead < 4096 && retries < 15) {
                if (videoFile.length() > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                }
                if (bytesRead < 4096) {
                    Thread.sleep(500);
                    retries++;
                }
            }
            
            bytesServed += Math.max(0, bytesRead);
            
            if (bytesRead <= 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering...");
            }
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            else if (videoFile.getName().toLowerCase().endsWith(".webm")) mime = "video/webm";
            
            byte[] respData = new byte[bytesRead];
            System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + bytesRead - 1) + "/" + fileSize);
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Cache-Control", "no-cache");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}