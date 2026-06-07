package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import fi.iki.elonen.NanoHTTPD;

import org.libtorrent4j.swig.*;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private torrent_handle torrentHandle;
    private long fileSize = 0;
    private int pieceLength = 0;
    private int numPieces = 0;
    private long totalRequests = 0;
    private long bytesServed = 0;
    private String fileName = "video.mp4";
    
    // Cache simples
    private Map<Integer, byte[]> pieceCache = new ConcurrentHashMap<>();
    private File videoFile;
    
    public StreamServer() { 
        super(8080); 
    }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            try {
                torrent_info ti = handle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    this.fileSize = ti.total_size();
                    this.pieceLength = ti.piece_length();
                    this.numPieces = ti.num_pieces();
                    this.fileName = ti.name();
                    Log.d(TAG, "Torrent OK: " + (fileSize/1048576) + "MB, " + numPieces + " peças");
                }
                
                // Procura o arquivo que o libtorrent está salvando
                torrent_status st = handle.status();
                String savePath = st.getSave_path();
                if (savePath != null) {
                    findVideoFile(new File(savePath));
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro setTorrent", e);
            }
        }
    }
    
    private void findVideoFile(File dir) {
        if (dir == null || !dir.exists()) return;
        try {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        findVideoFile(f);
                    } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                        videoFile = f;
                        Log.d(TAG, "Arquivo encontrado: " + f.getName() + " " + f.length());
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro findVideoFile", e);
        }
    }
    
    public String getStats() {
        return totalRequests + "req " + (bytesServed/1048576) + "MB";
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        totalRequests++;
        
        if (torrentHandle == null || !torrentHandle.is_valid() || fileSize == 0) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not ready");
        }
        
        // Tenta encontrar o arquivo se ainda não achou
        if (videoFile == null || !videoFile.exists()) {
            try {
                torrent_status st = torrentHandle.status();
                String savePath = st.getSave_path();
                if (savePath != null) {
                    findVideoFile(new File(savePath));
                }
            } catch (Exception e) {}
        }
        
        if (videoFile == null || !videoFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video file not found");
        }
        
        try {
            long fileSize = videoFile.length();
            long start = 0, end = fileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            }
            
            if (start >= fileSize) start = Math.max(0, fileSize - 262144);
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 262144);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            if (fileSize > start) {
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                raf.seek(start);
                bytesRead = raf.read(data);
                raf.close();
            }
            
            bytesServed += Math.max(0, bytesRead);
            
            if (bytesRead <= 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering...");
            }
            
            String mime = "video/mp4";
            if (fileName.toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            else if (fileName.toLowerCase().endsWith(".webm")) mime = "video/webm";
            
            byte[] respData = new byte[bytesRead];
            System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + bytesRead - 1) + "/" + fileSize);
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}