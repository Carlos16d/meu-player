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
        Log.d(TAG, "StreamServer CRIADO na porta 8080");
    }
    
    public void setSavePath(String path) {
        this.savePath = path;
        Log.d(TAG, "setSavePath: " + path);
    }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            torrent_info ti = handle.torrent_file_ptr();
            if (ti != null && ti.is_valid()) {
                this.fileSize = ti.total_size();
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                Log.d(TAG, "TorrentInfo: " + (fileSize/1048576) + "MB, " + numPieces + " peças");
            }
            findVideoFile();
        }
    }
    
    private void findVideoFile() {
        if (savePath == null) return;
        File dir = new File(savePath);
        videoFile = findRecursive(dir);
        if (videoFile != null) {
            Log.d(TAG, "VIDEO: " + videoFile.getName() + " " + videoFile.length());
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
        totalRequests++;
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        
        if (videoFile == null || !videoFile.exists()) {
            findVideoFile();
            if (videoFile == null || !videoFile.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not ready");
            }
        }
        
        try {
            long actualFileSize = videoFile.length();
            long start = 0, end = actualFileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            }
            
            // CORREÇÃO: Garante que o range é válido
            if (start >= actualFileSize) start = Math.max(0, actualFileSize - 1048576);
            if (end >= actualFileSize) end = actualFileSize - 1;
            if (start < 0) start = 0;
            if (end < start) end = start + 262143;
            if (end >= actualFileSize) end = actualFileSize - 1;
            
            // Chunk maior para MKV (precisa de mais dados para parsing)
            int chunkSize = Math.min((int)(end - start + 1), 1048576); // 1MB
            
            // Força prioridade nas peças necessárias
            if (torrentHandle != null && pieceLength > 0 && start > 0) {
                int targetPiece = (int)(start / pieceLength);
                if (targetPiece < numPieces) {
                    torrentHandle.piece_priority_ex(targetPiece, (byte)7);
                    torrentHandle.set_piece_deadline(targetPiece, 1000);
                }
            }
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            int retries = 0;
            
            // AGUARDA ATÉ TER DADOS SUFICIENTES (máximo 15 segundos)
            while (bytesRead < 8192 && retries < 30) {
                long currentSize = videoFile.length();
                
                if (currentSize > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                }
                
                if (bytesRead < 8192) {
                    Thread.sleep(500);
                    retries++;
                }
            }
            
            bytesServed += bytesRead;
            
            if (totalRequests % 50 == 0) {
                Log.d(TAG, "#" + totalRequests + " Range:" + start + "+" + bytesRead + " fileSize:" + actualFileSize + " retries:" + retries);
            }
            
            // SEMPRE retorna dados, mesmo que menos que o pedido
            if (bytesRead <= 0) bytesRead = 0;
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            else if (videoFile.getName().toLowerCase().endsWith(".webm")) mime = "video/webm";
            
            byte[] respData = new byte[bytesRead];
            if (bytesRead > 0) System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + Math.max(0, bytesRead - 1)) + "/" + actualFileSize);
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Connection", "keep-alive");
            response.addHeader("Cache-Control", "no-cache");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}