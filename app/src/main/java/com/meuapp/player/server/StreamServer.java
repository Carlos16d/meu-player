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
    
    public void setVideoFile(File f) { this.videoFile = f; }
    
    public void setTorrentInfo(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            torrent_info ti = handle.torrent_file_ptr();
            if (ti != null && ti.is_valid()) {
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                Log.d(TAG, "Torrent: " + numPieces + " peças, " + (pieceLength/1024) + "KB");
            }
        }
    }
    
    public String getStats() { return totalRequests + "req " + (bytesServed/1048576) + "MB"; }
    
    @Override
    public Response serve(IHTTPSession session) {
        totalRequests++;
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        String method = session.getMethod().name();
        
        // LOG sempre
        Log.d(TAG, "REQ #" + totalRequests + " | " + method + " " + uri + " | Range: " + rangeHeader);
        
        if (videoFile == null || !videoFile.exists()) {
            Log.w(TAG, "  -> 404 Video not ready");
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not ready");
        }
        
        try {
            long fileSize = videoFile.length();
            long start = 0, end = fileSize - 1;
            boolean isRangeRequest = (rangeHeader != null && rangeHeader.startsWith("bytes="));
            
            if (isRangeRequest) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                
                Log.d(TAG, "  Range: bytes " + start + "-" + end);
            } else {
                Log.d(TAG, "  Request inicial (sem Range) - enviando headers para habilitar seek");
            }
            
            // SEEK: download sequencial no range alvo
            if (torrentHandle != null && pieceLength > 0 && isRangeRequest && start > 0) {
                long seekDiff = Math.abs(start - lastSeekPosition);
                if (seekDiff > 5242880 || lastSeekPosition == 0) {
                    int targetPiece = (int)(start / pieceLength);
                    int rangeStart = Math.max(0, targetPiece - 5);
                    int rangeEnd = Math.min(numPieces, targetPiece + 60);
                    
                    torrentHandle.set_sequential_range(rangeStart, rangeEnd);
                    
                    for (int i = 0; i < numPieces; i++)
                        torrentHandle.piece_priority_ex(i, (byte)0);
                    
                    for (int i = rangeStart; i < rangeEnd; i++) {
                        torrentHandle.piece_priority_ex(i, (byte)7);
                        torrentHandle.set_piece_deadline(i, 500);
                    }
                    
                    Log.d(TAG, "  🔥 SEEK DETECTADO! Pos: " + (start/1048576) + "MB | Peças: " + rangeStart + "-" + rangeEnd + " (sequencial)");
                }
                lastSeekPosition = start;
            }
            
            if (start < 0) start = 0;
            if (start >= fileSize) start = Math.max(0, fileSize - 524288);
            if (end >= fileSize) end = fileSize - 1;
            if (end < start) end = start + 524287;
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = isRangeRequest ? Math.min((int)(end - start + 1), 524288) : 1048576;
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            int retries = 0;
            while (bytesRead < 4096 && retries < 15) {
                if (videoFile.length() > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start); bytesRead = raf.read(data); raf.close();
                }
                if (bytesRead < 4096 && retries < 14) { Thread.sleep(200); retries++; }
            }
            
            bytesServed += Math.max(0, bytesRead);
            if (bytesRead <= 0) bytesRead = 0;
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            
            byte[] respData = new byte[bytesRead];
            if (bytesRead > 0) System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response;
            
            if (isRangeRequest) {
                response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
                response.addHeader("Content-Range", "bytes " + start + "-" + (start + Math.max(0, bytesRead - 1)) + "/" + fileSize);
                Log.d(TAG, "  <- 206 Partial Content (" + bytesRead + " bytes)");
            } else {
                response = newFixedLengthResponse(Response.Status.OK, mime, bais, bytesRead);
                response.addHeader("Accept-Ranges", "bytes");
                Log.d(TAG, "  <- 200 OK (" + bytesRead + " bytes) + Accept-Ranges: bytes");
            }
            
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Pragma", "no-cache");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}