package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private File videoFile;
    private StringBuilder logs = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    
    public StreamServer() {
        super(8080);
    }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
        log("Video definido: " + (f != null ? f.getName() + " " + f.length() : "null"));
    }
    
    private void log(String msg) {
        String line = sdf.format(new Date()) + " SRV: " + msg;
        Log.d(TAG, msg);
        logs.insert(0, line + "\n");
        if (logs.length() > 2000) logs.setLength(2000);
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        String method = session.getMethod().name();
        
        log(method + " " + uri + " Range:" + rangeHeader);
        
        if (!uri.contains("/video") || videoFile == null || !videoFile.exists()) {
            log("404 - Arquivo não encontrado");
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
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
            
            if (end >= fileSize) end = fileSize - 1;
            int chunkSize = Math.min((int)(end - start + 1), 262144);
            
            log("Pedido: bytes " + start + "-" + end + " (chunk:" + chunkSize + ") fileSize:" + fileSize);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            int retries = 0;
            long lastFileSize = fileSize;
            
            // Aguarda dados com timeout de 30s
            while (bytesRead < 4096 && retries < 60) {
                long currentFileSize = videoFile.length();
                
                if (currentFileSize > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                }
                
                if (bytesRead < 4096) {
                    Thread.sleep(500);
                    retries++;
                    if (retries % 5 == 0) {
                        log("Aguardando... retry " + retries + " lidos:" + bytesRead + " file:" + currentFileSize);
                    }
                }
                lastFileSize = currentFileSize;
            }
            
            log("Resposta: " + bytesRead + " bytes após " + retries + " tentativas");
            
            if (bytesRead < 1024) {
                log("503 - Dados insuficientes");
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering...");
            }
            
            String mime = videoFile.getName().toLowerCase().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            
            byte[] responseData = new byte[bytesRead];
            System.arraycopy(data, 0, responseData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(responseData);
            Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + bytesRead - 1) + "/" + fileSize);
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Cache-Control", "no-cache");
            
            return response;
            
        } catch (Exception e) {
            log("ERRO: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}