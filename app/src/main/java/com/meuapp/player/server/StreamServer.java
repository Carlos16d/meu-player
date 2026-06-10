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
        Log.d(TAG, "╔══════════════════════════════════════╗");
        Log.d(TAG, "║   StreamServer CONSTRUTOR            ║");
        Log.d(TAG, "║   Porta: 8080                        ║");
        Log.d(TAG, "╚══════════════════════════════════════╝");
    }
    
    @Override
    public void start() throws IOException {
        Log.d(TAG, "🟢 start() chamado - iniciando servidor...");
        super.start();
        startTime = System.currentTimeMillis();
        Log.d(TAG, "🟢 start() concluído!");
        Log.d(TAG, "   isAlive: " + this.isAlive());
        Log.d(TAG, "   wasStarted: " + this.wasStarted());
        Log.d(TAG, "   Listening: " + (this.isAlive() && this.wasStarted()));
        
        // Teste rápido
        try {
            java.net.Socket s = new java.net.Socket("127.0.0.1", 8080);
            Log.d(TAG, "   ✅ Teste Socket 127.0.0.1:8080 = OK");
            s.close();
        } catch (Exception e) {
            Log.e(TAG, "   ❌ Teste Socket 127.0.0.1:8080 FALHOU: " + e.getMessage());
        }
    }
    
    public void setVideoFile(File f) { 
        this.videoFile = f;
        Log.d(TAG, "══════════════════════════════════════");
        Log.d(TAG, "📁 VIDEO SET");
        Log.d(TAG, "   Path: " + (f != null ? f.getAbsolutePath() : "NULL"));
        Log.d(TAG, "   exists: " + (f != null ? f.exists() : false));
        Log.d(TAG, "   isFile: " + (f != null ? f.isFile() : false));
        Log.d(TAG, "   canRead: " + (f != null ? f.canRead() : false));
        Log.d(TAG, "   length: " + (f != null ? f.length() : 0) + " bytes");
        Log.d(TAG, "   lengthMB: " + (f != null ? f.length()/1048576 : 0) + "MB");
        
        // Verifica permissões de leitura
        if (f != null && f.exists()) {
            try {
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                byte[] test = new byte[4];
                raf.read(test);
                raf.close();
                Log.d(TAG, "   ✅ Leitura OK! Magic: " + String.format("0x%02X 0x%02X 0x%02X 0x%02X", 
                    test[0] & 0xFF, test[1] & 0xFF, test[2] & 0xFF, test[3] & 0xFF));
            } catch (Exception e) {
                Log.e(TAG, "   ❌ Leitura FALHOU: " + e.getMessage());
            }
        }
        Log.d(TAG, "══════════════════════════════════════");
    }
    
    public void setTorrentInfo(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            try {
                torrent_info ti = handle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    this.pieceLength = ti.piece_length();
                    this.numPieces = ti.num_pieces();
                    Log.d(TAG, "📊 Torrent: " + numPieces + " peças de " + (pieceLength/1024) + "KB");
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro setTorrentInfo: " + e.getMessage());
            }
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
        String method = session.getMethod().name();
        Map<String, String> headers = session.getHeaders();
        
        // LOG COMPLETO DE CADA REQUISIÇÃO
        Log.d(TAG, "╔══════════════════════════════════════╗");
        Log.d(TAG, "║ REQ #" + totalRequests + " | " + method + " " + uri);
        Log.d(TAG, "╠══════════════════════════════════════╣");
        Log.d(TAG, "║ Range: " + rangeHeader);
        Log.d(TAG, "║ Headers: " + headers.toString());
        Log.d(TAG, "║ videoFile: " + (videoFile != null ? videoFile.getAbsolutePath() : "NULL"));
        Log.d(TAG, "║ videoFile.exists: " + (videoFile != null ? videoFile.exists() : false));
        Log.d(TAG, "║ videoFile.length: " + (videoFile != null ? videoFile.length() : 0));
        Log.d(TAG, "║ videoFile.canRead: " + (videoFile != null ? videoFile.canRead() : false));
        Log.d(TAG, "║ Total servido até agora: " + (bytesServed/1048576) + "MB");
        
        // Se for a primeira requisição, faz um teste completo
        if (totalRequests == 1) {
            Log.d(TAG, "║ === TESTE DE ACESSO AO ARQUIVO ===");
            if (videoFile != null) {
                Log.d(TAG, "║ Path completo: " + videoFile.getAbsolutePath());
                Log.d(TAG, "║ Parent: " + videoFile.getParent());
                Log.d(TAG, "║ Parent existe: " + (videoFile.getParentFile() != null ? videoFile.getParentFile().exists() : false));
                
                // Lista arquivos na mesma pasta
                File parent = videoFile.getParentFile();
                if (parent != null && parent.exists()) {
                    File[] siblings = parent.listFiles();
                    Log.d(TAG, "║ Arquivos na pasta: " + (siblings != null ? siblings.length : 0));
                    if (siblings != null) {
                        for (File s : siblings) {
                            Log.d(TAG, "║   " + s.getName() + " (" + s.length() + " bytes)");
                        }
                    }
                }
            }
        }
        
        if (videoFile == null || !videoFile.exists()) {
            Log.e(TAG, "║ >>> 404 - VIDEO NÃO EXISTE <<<");
            Log.d(TAG, "╚══════════════════════════════════════╝");
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", 
                "Video not ready. Exists: " + (videoFile != null ? videoFile.exists() : false));
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
            
            Log.d(TAG, "║ fileSize: " + fileSize + " (" + (fileSize/1048576) + "MB)");
            Log.d(TAG, "║ isRange: " + isRange);
            Log.d(TAG, "║ start: " + start + " (" + (start/1048576) + "MB)");
            Log.d(TAG, "║ end: " + end + " (" + (end/1048576) + "MB)");
            
            // SEEK detection
            if (torrentHandle != null && pieceLength > 0 && isRange && start > 0) {
                long seekDiff = Math.abs(start - lastSeekPosition);
                if (seekDiff > 5242880 || lastSeekPosition == 0) {
                    int tp = (int)(start / pieceLength);
                    int rs = Math.max(0, tp - 5), re = Math.min(numPieces, tp + 60);
                    try {
                        torrentHandle.set_sequential_range(rs, re);
                        for (int i = 0; i < numPieces; i++) torrentHandle.piece_priority_ex(i, (byte)0);
                        for (int i = rs; i < re; i++) { 
                            torrentHandle.piece_priority_ex(i, (byte)7); 
                            torrentHandle.set_piece_deadline(i, 500); 
                        }
                        Log.d(TAG, "║ 🔥 SEEK: " + (start/1048576) + "MB | peças " + rs + "-" + re);
                    } catch (Exception e) {
                        Log.e(TAG, "║ Erro SEEK: " + e.getMessage());
                    }
                }
                lastSeekPosition = start;
            }
            
            if (start < 0) start = 0;
            if (start >= fileSize) start = Math.max(0, fileSize - 524288);
            if (end >= fileSize) end = fileSize - 1;
            if (end < start) end = start + 524287;
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 524288);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            // Tenta ler
            for (int retry = 0; retry < 5 && bytesRead < 4096; retry++) {
                try {
                    if (videoFile.length() > start) {
                        RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                        raf.seek(start);
                        bytesRead = raf.read(data);
                        raf.close();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "║ Erro leitura retry " + retry + ": " + e.getMessage());
                }
                if (bytesRead < 4096 && retry < 4) Thread.sleep(200);
            }
            
            bytesServed += Math.max(0, bytesRead);
            
            Log.d(TAG, "║ Bytes lidos: " + bytesRead);
            if (bytesRead > 4) {
                Log.d(TAG, "║ Magic: " + String.format("0x%02X 0x%02X 0x%02X 0x%02X", 
                    data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF, data[3] & 0xFF));
            }
            
            if (bytesRead <= 0) {
                Log.e(TAG, "║ >>> 503 - ZERO BYTES <<<");
                Log.d(TAG, "╚══════════════════════════════════════╝");
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", 
                    "Buffering... (fileSize=" + fileSize + " start=" + start + ")");
            }
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            else if (videoFile.getName().toLowerCase().endsWith(".webm")) mime = "video/webm";
            
            Log.d(TAG, "║ MIME: " + mime);
            
            byte[] respData = new byte[bytesRead];
            if (bytesRead > 0) System.arraycopy(data, 0, respData, 0, bytesRead);
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            
            Response response;
            if (isRange) {
                response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
                response.addHeader("Content-Range", "bytes " + start + "-" + (start + Math.max(0, bytesRead - 1)) + "/" + fileSize);
                Log.d(TAG, "║ >>> 206 PARTIAL CONTENT (" + bytesRead + " bytes)");
            } else {
                response = newFixedLengthResponse(Response.Status.OK, mime, bais, bytesRead);
                Log.d(TAG, "║ >>> 200 OK (" + bytesRead + " bytes)");
            }
            
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Connection", "keep-alive");
            
            Log.d(TAG, "╚══════════════════════════════════════╝");
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "║ >>> ERRO FATAL: " + e.getMessage());
            Log.e(TAG, "║ Stack: " + android.util.Log.getStackTraceString(e));
            Log.d(TAG, "╚══════════════════════════════════════╝");
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}
