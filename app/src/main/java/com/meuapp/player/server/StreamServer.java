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
    private long lastLogTime = 0;
    
    public StreamServer() { 
        super(8080);
        Log.d(TAG, "╔══════════════════════════════╗");
        Log.d(TAG, "║   STREAM SERVER INICIADO     ║");
        Log.d(TAG, "║   Porta: 8080                ║");
        Log.d(TAG, "╚══════════════════════════════╝");
    }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
        Log.d(TAG, "📁 Video: " + (f != null ? f.getName() + " (" + (f.length()/1048576) + "MB)" : "null"));
    }
    
    public void setTorrentInfo(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            try {
                torrent_info ti = handle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    this.pieceLength = ti.piece_length();
                    this.numPieces = ti.num_pieces();
                    Log.d(TAG, "📊 Torrent: " + numPieces + " peças de " + (pieceLength/1024) + "KB cada");
                    Log.d(TAG, "📊 Tamanho total: " + (ti.total_size()/1048576) + "MB");
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro ao obter torrent_info", e);
            }
        }
    }
    
    public String getStats() {
        return totalRequests + "req | " + (bytesServed/1048576) + "MB servidos";
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        totalRequests++;
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        String method = session.getMethod().name();
        
        long now = System.currentTimeMillis();
        boolean shouldLog = (totalRequests % 50 == 0) || (now - lastLogTime > 5000);
        
        if (shouldLog) {
            Log.d(TAG, "📡 REQ #" + totalRequests + " | " + method + " " + uri);
            Log.d(TAG, "   Range: " + rangeHeader);
            Log.d(TAG, "   Arquivo: " + (videoFile != null ? (videoFile.length()/1048576) + "MB" : "null"));
            lastLogTime = now;
        }
        
        if (videoFile == null || !videoFile.exists()) {
            Log.w(TAG, "❌ Vídeo não encontrado!");
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not ready");
        }
        
        try {
            long fileSize = videoFile.length();
            long start = 0, end = fileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                try {
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {}
            }
            
            // 🔥 DETECÇÃO DE SEEK COM VERIFICAÇÃO
            if (torrentHandle != null && pieceLength > 0 && start > 0) {
                long seekDiff = Math.abs(start - lastSeekPosition);
                
                if (seekDiff > 5242880 || lastSeekPosition == 0) {
                    int targetPiece = (int)(start / pieceLength);
                    int pStart = Math.max(0, targetPiece - 10);
                    int pEnd = Math.min(numPieces, targetPiece + 60);
                    
                    Log.d(TAG, "══════════════════════════════════");
                    Log.d(TAG, "🔥 SEEK DETECTADO!");
                    Log.d(TAG, "   Posição: " + (start/1048576) + "MB (byte " + start + ")");
                    Log.d(TAG, "   Peça alvo: " + targetPiece + " de " + numPieces);
                    
                    // Prioriza peças
                    int prioritized = 0;
                    for (int i = pStart; i < pEnd; i++) {
                        try {
                            torrentHandle.piece_priority_ex(i, (byte)7);
                            torrentHandle.set_piece_deadline(i, 500);
                            prioritized++;
                        } catch (Exception e) {}
                    }
                    
                    // Ignora peças antigas
                    int ignored = 0;
                    for (int i = 0; i < pStart - 10; i++) {
                        try {
                            torrentHandle.piece_priority_ex(i, (byte)0);
                            ignored++;
                        } catch (Exception e) {}
                    }
                    
                    Log.d(TAG, "   ✅ " + prioritized + " peças priorizadas (MAX)");
                    Log.d(TAG, "   ❌ " + ignored + " peças ignoradas (0)");
                    
                    // 🔍 VERIFICA SE OS DADOS CHEGARAM (após 3 segundos)
                    final int checkStart = pStart;
                    final int checkEnd = Math.min(pStart + 20, pEnd);
                    
                    new Thread(() -> {
                        try {
                            Thread.sleep(3000);
                            int haveData = 0;
                            int noData = 0;
                            
                            for (int i = checkStart; i < checkEnd; i++) {
                                if (torrentHandle.have_piece(i)) haveData++;
                                else noData++;
                            }
                            
                            Log.d(TAG, "══════════════════════════════════");
                            Log.d(TAG, "🔍 VERIFICAÇÃO PÓS-SEEK (3s):");
                            Log.d(TAG, "   Peças " + checkStart + "-" + checkEnd);
                            Log.d(TAG, "   ✅ Com dados: " + haveData + "/" + (checkEnd - checkStart));
                            Log.d(TAG, "   ❌ Sem dados: " + noData + "/" + (checkEnd - checkStart));
                            
                            if (haveData > 0) {
                                Log.d(TAG, "   🎉 DADOS CONFIRMADOS! Streaming na nova posição!");
                            } else {
                                Log.d(TAG, "   ⏳ Aguardando peers enviarem dados...");
                            }
                            Log.d(TAG, "══════════════════════════════════");
                        } catch (Exception e) {}
                    }).start();
                    
                    Log.d(TAG, "══════════════════════════════════");
                }
                lastSeekPosition = start;
            }
            
            // Ajusta range
            if (start < 0) start = 0;
            if (start >= fileSize) start = Math.max(0, fileSize - 524288);
            if (end >= fileSize) end = fileSize - 1;
            if (end < start) end = start + 524287;
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 524288);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            int retries = 0;
            
            while (bytesRead < 4096 && retries < 15) {
                if (videoFile.length() > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                }
                if (bytesRead < 4096 && retries < 14) {
                    Thread.sleep(200);
                    retries++;
                }
            }
            
            bytesServed += Math.max(0, bytesRead);
            if (bytesRead <= 0) bytesRead = 0;
            
            if (shouldLog) {
                Log.d(TAG, "   ✅ Servido: " + bytesRead + " bytes (posição " + (start/1048576) + "MB)");
            }
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            else if (videoFile.getName().toLowerCase().endsWith(".webm")) mime = "video/webm";
            
            byte[] respData = new byte[bytesRead];
            if (bytesRead > 0) System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(
                bytesRead > 0 ? Response.Status.PARTIAL_CONTENT : Response.Status.NO_CONTENT,
                mime, bais, bytesRead);
            
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + Math.max(0, bytesRead - 1)) + "/" + fileSize);
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ ERRO ao servir: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}