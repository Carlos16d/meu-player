package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

import org.libtorrent4j.swig.*;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private File videoFile;
    private torrent_handle torrentHandle;
    private long totalRequests = 0;
    private long bytesServed = 0;
    private int pieceLength = 0;
    private int numPieces = 0;
    private long lastSeekPosition = 0;
    
    public StreamServer() { super(8080); }
    
    public void setVideoFile(File f) { 
        this.videoFile = f;
        Log.d(TAG, "========================================");
        Log.d(TAG, "VIDEO SET: " + (f != null ? f.getAbsolutePath() : "NULL"));
        Log.d(TAG, "  exists: " + (f != null ? f.exists() : false));
        Log.d(TAG, "  length: " + (f != null ? f.length() : 0) + " bytes (" + (f != null ? f.length()/1048576 : 0) + "MB)");
        Log.d(TAG, "========================================");
    }
    
    public void setTorrentInfo(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            try {
                torrent_info ti = handle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    this.pieceLength = ti.piece_length();
                    this.numPieces = ti.num_pieces();
                    Log.d(TAG, "TORRENT INFO: " + numPieces + " peças de " + (pieceLength/1024) + "KB");
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro setTorrentInfo: " + e.getMessage());
            }
        }
    }
    
    public String getStats() { 
        return totalRequests + "req " + (bytesServed/1048576) + "MB"; 
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        totalRequests++;
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        String method = session.getMethod().name();
        
        // LOG SEMPRE
        Log.d(TAG, "══════════════════════════════════");
        Log.d(TAG, "REQ #" + totalRequests + " | " + method + " " + uri);
        Log.d(TAG, "  Range: " + rangeHeader);
        Log.d(TAG, "  videoFile: " + (videoFile != null ? videoFile.getAbsolutePath() : "NULL"));
        
        if (videoFile == null || !videoFile.exists()) {
            Log.e(TAG, "  >>> 404 - VIDEO NÃO EXISTE <<<");
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", 
                "Video not ready. Exists: " + (videoFile != null ? videoFile.exists() : false));
        }
        
        try {
            long fileSize = videoFile.length();
            long start = 0, end = fileSize - 1;
            boolean isRange = (rangeHeader != null && rangeHeader.startsWith("bytes="));
            
            Log.d(TAG, "  fileSize: " + fileSize + " bytes (" + (fileSize/1048576) + "MB)");
            
            if (isRange) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                Log.d(TAG, "  Range solicitado: " + start + "-" + end);
            } else {
                Log.d(TAG, "  Requisição inicial (sem Range)");
            }
            
            // SEEK detection
            if (torrentHandle != null && pieceLength > 0 && isRange && start > 0) {
                long seekDiff = Math.abs(start - lastSeekPosition);
                if (seekDiff > 5242880 || lastSeekPosition == 0) {
                    int tp = (int)(start / pieceLength);
                    int rs = Math.max(0, tp - 5), re = Math.min(numPieces, tp + 60);
                    torrentHandle.set_sequential_range(rs, re);
                    for (int i = 0; i < numPieces; i++) torrentHandle.piece_priority_ex(i, (byte)0);
                    for (int i = rs; i < re; i++) { 
                        torrentHandle.piece_priority_ex(i, (byte)7); 
                        torrentHandle.set_piece_deadline(i, 500); 
                    }
                    Log.d(TAG, "  🔥 SEEK: " + (start/1048576) + "MB | peças " + rs + "-" + re);
                }
                lastSeekPosition = start;
            }
            
            if (start < 0) start = 0;
            if (start >= fileSize) start = Math.max(0, fileSize - 524288);
            if (end >= fileSize) end = fileSize - 1;
            if (end < start) end = start + 524287;
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 524288);
            Log.d(TAG, "  Lendo chunk de " + chunkSize + " bytes em offset " + start);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            for (int retry = 0; retry < 5 && bytesRead < 4096; retry++) {
                if (videoFile.length() > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                    Log.d(TAG, "  Tentativa " + (retry+1) + ": leu " + bytesRead + " bytes");
                }
                if (bytesRead < 4096 && retry < 4) Thread.sleep(200);
            }
            
            bytesServed += Math.max(0, bytesRead);
            
            Log.d(TAG, "  Bytes lidos: " + bytesRead);
            if (bytesRead > 4) {
                Log.d(TAG, "  Magic bytes: " + String.format("0x%02X 0x%02X 0x%02X 0x%02X", 
                    data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF, data[3] & 0xFF));
            }
            
            if (bytesRead <= 0) {
                Log.e(TAG, "  >>> 503 - ZERO BYTES <<<");
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", 
                    "Buffering... (fileSize=" + fileSize + " start=" + start + ")");
            }
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            Log.d(TAG, "  MIME: " + mime);
            
            byte[] respData = new byte[bytesRead];
            if (bytesRead > 0) System.arraycopy(data, 0, respData, 0, bytesRead);
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            
            Response response;
            if (isRange) {
                response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
                response.addHeader("Content-Range", "bytes " + start + "-" + (start + Math.max(0, bytesRead - 1)) + "/" + fileSize);
                Log.d(TAG, "  >>> 206 PARTIAL CONTENT (" + bytesRead + " bytes)");
            } else {
                response = newFixedLengthResponse(Response.Status.OK, mime, bais, bytesRead);
                Log.d(TAG, "  >>> 200 OK (" + bytesRead + " bytes)");
            }
            
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Connection", "keep-alive");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "  >>> ERRO: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}