package com.meuapp.player.server;

import android.util.Log;

import java.io.*;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private File videoFile;
    private long totalRequests = 0;
    private long bytesServed = 0;
    private long startTime = System.currentTimeMillis();
    
    public StreamServer() {
        super(8080);
        Log.d(TAG, "StreamServer criado na porta 8080");
    }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
        Log.d(TAG, "VIDEO SET: " + (f != null ? f.getName() + " " + f.length() : "NULL"));
    }
    
    public String getStats() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        return totalRequests + "req " + (bytesServed/1048576) + "MB " + uptime + "s";
    }
    
    @Override
    public void start() throws IOException {
        super.start();
        Log.d(TAG, "SERVIDOR INICIADO na porta 8080");
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        String method = session.getMethod().name();
        totalRequests++;
        
        Log.d(TAG, "REQ #" + totalRequests + ": " + method + " " + uri + " Range:" + rangeHeader);
        Log.d(TAG, "  videoFile=" + (videoFile != null ? videoFile.getAbsolutePath() : "NULL"));
        Log.d(TAG, "  videoFile.exists=" + (videoFile != null ? videoFile.exists() : false));
        Log.d(TAG, "  videoFile.length=" + (videoFile != null ? videoFile.length() : 0));
        
        // Responde a qualquer requisição, mesmo sem vídeo
        if (videoFile == null || !videoFile.exists()) {
            Log.w(TAG, "  Video não disponível!");
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", 
                "Video not ready. videoFile=" + (videoFile != null ? videoFile.getAbsolutePath() : "null"));
        }
        
        try {
            long fileSize = videoFile.length();
            long start = 0;
            long end = fileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    end = Long.parseLong(parts[1]);
                }
            }
            
            // Ajusta ranges
            if (start >= fileSize) {
                start = Math.max(0, fileSize - 1048576);
            }
            if (end >= fileSize) {
                end = fileSize - 1;
            }
            
            int chunkSize = Math.min((int)(end - start + 1), 524288); // 512KB
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            // Tenta ler - se não conseguir, retorna o que tem
            if (fileSize > start) {
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                raf.seek(start);
                bytesRead = raf.read(data);
                raf.close();
            }
            
            bytesServed += Math.max(0, bytesRead);
            
            Log.d(TAG, "  RES: start=" + start + " bytesRead=" + bytesRead + " fileSize=" + fileSize);
            
            // Se não leu nada, retorna 206 com zero bytes (melhor que erro)
            if (bytesRead <= 0) {
                bytesRead = 0;
            }
            
            String mime = "video/mp4";
            String name = videoFile.getName().toLowerCase();
            if (name.endsWith(".mkv")) mime = "video/x-matroska";
            else if (name.endsWith(".webm")) mime = "video/webm";
            
            byte[] respData = new byte[bytesRead];
            if (bytesRead > 0) {
                System.arraycopy(data, 0, respData, 0, bytesRead);
            }
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead
            );
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + Math.max(0, bytesRead - 1)) + "/" + fileSize);
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Connection", "keep-alive");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "ERRO ao servir", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}