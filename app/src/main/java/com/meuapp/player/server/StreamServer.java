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
    
    @Override
    public void start() throws IOException {
        Log.d(TAG, "🟢 Servidor HTTP porta 8080");
        super.start();
    }
    
    public void setVideoFile(File f) { 
        this.videoFile = f;
        Log.d(TAG, "📁 Video: " + (f != null ? f.getName() + " (" + (f.length()/1048576) + "MB)" : "NULL"));
    }
    
    public void setTorrentInfo(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            try {
                torrent_info ti = handle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    this.pieceLength = ti.piece_length();
                    this.numPieces = ti.num_pieces();
                    Log.d(TAG, "📊 " + numPieces + " peças de " + (pieceLength/1024) + "KB");
                }
            } catch (Exception e) {}
        }
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        totalRequests++;
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        
        if (videoFile == null || !videoFile.exists()) {
            Log.e(TAG, "❌ Video não existe!");
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
                Log.d(TAG, "📡 Range: " + start + "-" + end + " (" + ((end-start+1)/1024) + "KB)");
            } else {
                Log.d(TAG, "📡 Requisição INICIAL (sem Range)");
            }
            
            // SEEK com bloqueio
            if (torrentHandle != null && pieceLength > 0 && isRange && start > 0) {
                int tp = (int)(start / pieceLength);
                if (!torrentHandle.have_piece(tp)) {
                    Log.d(TAG, "⏳ Aguardando peça " + tp + "...");
                    try {
                        for (int i = 0; i < numPieces; i++)
                            torrentHandle.piece_priority_ex(i, (byte)((i >= tp - 3 && i <= tp + 30) ? 7 : 0));
                        torrentHandle.set_piece_deadline(tp, 30000);
                        
                        long ws = System.currentTimeMillis();
                        while (!torrentHandle.have_piece(tp) && (System.currentTimeMillis() - ws) < 30000) {
                            Thread.sleep(500);
                        }
                        Log.d(TAG, "✅ Peça " + tp + " em " + ((System.currentTimeMillis()-ws)/1000) + "s");
                    } catch (Exception e) {}
                }
            }
            
            if (start < 0) start = 0;
            if (end >= fileSize) end = fileSize - 1;
            
            // 5MB para inicial, 2MB para Range
            int maxChunk = isRange ? 2097152 : 5242880;
            int chunkSize = Math.min((int)(end - start + 1), maxChunk);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            for (int retry = 0; retry < 10; retry++) {
                try {
                    if (videoFile.length() > start) {
                        RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                        raf.seek(start);
                        int available = (int)Math.min(chunkSize, videoFile.length() - start);
                        bytesRead = raf.read(data, 0, available);
                        raf.close();
                        if (bytesRead > 0) break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Erro leitura: " + e.getMessage());
                }
                try { Thread.sleep(500); } catch (Exception e) {}
            }
            
            bytesServed += bytesRead;
            Log.d(TAG, "📤 Enviando " + (bytesRead/1024) + "KB (total: " + (bytesServed/1048576) + "MB)");
            
            if (bytesRead <= 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering...");
            }
            
            String mime = "video/x-matroska";
            if (videoFile.getName().toLowerCase().endsWith(".mp4")) mime = "video/mp4";
            
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
            Log.e(TAG, "Erro: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}