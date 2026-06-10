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
    private long startTime = 0;
    
    public StreamServer() { 
        super(8080);
    }
    
    @Override
    public void start() throws IOException {
        Log.d(TAG, "🟢 Iniciando servidor HTTP na porta 8080...");
        super.start();
        startTime = System.currentTimeMillis();
    }
    
    public void setVideoFile(File f) { 
        this.videoFile = f;
        Log.d(TAG, "📁 Video: " + (f != null ? f.getAbsolutePath() : "NULL") + " (" + (f != null ? f.length()/1048576 : 0) + "MB)");
    }
    
    public void setTorrentInfo(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            try {
                torrent_info ti = handle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    this.pieceLength = ti.piece_length();
                    this.numPieces = ti.num_pieces();
                }
            } catch (Exception e) {}
        }
    }
    
    public String getStats() { 
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        return totalRequests + "req " + (bytesServed/1048576) + "MB uptime:" + uptime + "s"; 
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        totalRequests++;
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        
        if (videoFile == null || !videoFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not ready");
        }
        
        try {
            long fileSize = videoFile.length();
            long start = 0, end = fileSize - 1;
            boolean isRange = (rangeHeader != null && rangeHeader.startsWith("bytes="));
            
            if (isRange) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            }
            
            // SEEK: priorizar peça necessária
            if (torrentHandle != null && pieceLength > 0 && isRange && start > 0) {
                long seekDiff = Math.abs(start - lastSeekPosition);
                if (seekDiff > 5242880 || lastSeekPosition == 0) {
                    int tp = (int)(start / pieceLength);
                    int rs = Math.max(0, tp - 3), re = Math.min(numPieces - 1, tp + 30);
                    try {
                        for (int i = 0; i < numPieces; i++)
                            torrentHandle.piece_priority_ex(i, (byte)((i >= rs && i <= re) ? 7 : 0));
                        torrentHandle.set_piece_deadline(tp, 30000);
                        Log.d(TAG, "🔥 SEEK: peça " + tp + " (range " + rs + "-" + re + ")");
                        
                        // Aguardar peça chegar
                        long ws = System.currentTimeMillis();
                        while (!torrentHandle.have_piece(tp) && (System.currentTimeMillis() - ws) < 30000) {
                            Thread.sleep(500);
                            try { torrentHandle.set_piece_deadline(tp, 30000); } catch (Exception e) { break; }
                        }
                        if (torrentHandle.have_piece(tp)) {
                            Log.d(TAG, "✅ Peça " + tp + " chegou em " + ((System.currentTimeMillis()-ws)/1000) + "s");
                        }
                    } catch (Exception e) {}
                }
                lastSeekPosition = start;
            }
            
            if (start < 0) start = 0;
            if (end >= fileSize) end = fileSize - 1;
            
            // ==================== CORREÇÃO: 2MB por chunk ====================
            int chunkSize = isRange ? 
                Math.min((int)(end - start + 1), 2097152) :  // 2MB para Range
                Math.min((int)(end - start + 1), 3145728);   // 3MB para inicial
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            for (int retry = 0; retry < 10 && bytesRead < 4096; retry++) {
                try {
                    if (videoFile.length() > start) {
                        RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                        raf.seek(start);
                        bytesRead = raf.read(data, 0, Math.min(chunkSize, (int)(videoFile.length() - start)));
                        raf.close();
                    }
                } catch (Exception e) {}
                if (bytesRead < 4096 && retry < 9) Thread.sleep(300);
            }
            
            bytesServed += Math.max(0, bytesRead);
            
            if (bytesRead <= 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering...");
            }
            
            String mime = "video/x-matroska";
            if (videoFile.getName().toLowerCase().endsWith(".mp4")) mime = "video/mp4";
            else if (videoFile.getName().toLowerCase().endsWith(".webm")) mime = "video/webm";
            
            byte[] respData = new byte[bytesRead];
            System.arraycopy(data, 0, respData, 0, bytesRead);
            
            Response response;
            if (isRange) {
                response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, new ByteArrayInputStream(respData), bytesRead);
                response.addHeader("Content-Range", "bytes " + start + "-" + (start + bytesRead - 1) + "/" + fileSize);
            } else {
                response = newFixedLengthResponse(Response.Status.OK, mime, new ByteArrayInputStream(respData), bytesRead);
            }
            
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Connection", "keep-alive");
            
            return response;
            
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}