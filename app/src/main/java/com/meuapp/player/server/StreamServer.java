package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private File videoFile;
    private int actualPort;
    
    public StreamServer() {
        super(8080);
        this.actualPort = 8080;
    }
    
    public int getPort() {
        return actualPort;
    }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
        Log.d(TAG, "Video: " + (f != null ? f.getName() + " " + f.length() : "null"));
    }
    
    @Override
    public void start() throws IOException {
        try {
            super.start();
            Log.d(TAG, "Servidor iniciado na porta " + actualPort);
        } catch (IOException e) {
            if (e.getMessage().contains("EADDRINUSE") || e.getMessage().contains("Address already in use")) {
                // Tenta porta alternativa
                Log.w(TAG, "Porta 8080 ocupada, tentando 8081...");
                // Fecha e recria na nova porta
                try {
                    stop();
                } catch (Exception ex) {}
                
                // Não tem como mudar a porta no NanoHTTPD depois de criado
                // Então vamos usar a porta 0 para auto-assign
                Log.w(TAG, "Use porta 8080 - mate o processo anterior");
                throw e;
            }
            throw e;
        }
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        
        Log.d(TAG, "REQ: " + uri + " Range:" + rangeHeader);
        
        if (!uri.contains("/video") || videoFile == null || !videoFile.exists()) {
            Log.w(TAG, "404 - videoFile=" + (videoFile != null ? videoFile.exists() : "null"));
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
            
            // Ajusta para não pedir além do disponível
            if (start >= fileSize) {
                start = fileSize - 262144;
                if (start < 0) start = 0;
            }
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 524288); // 512KB
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            int retries = 0;
            
            // Aguarda dados (máximo 15 segundos)
            while (bytesRead < 4096 && retries < 30) {
                long currentSize = videoFile.length();
                
                if (currentSize > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                }
                
                if (bytesRead < 4096) {
                    Thread.sleep(500);
                    retries++;
                }
            }
            
            Log.d(TAG, "Serve: " + start + "+" + bytesRead + " (file:" + fileSize + " retries:" + retries + ")");
            
            // SEMPRE retorna o que tem, mesmo que pouco
            if (bytesRead <= 0) {
                bytesRead = 0;
            }
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            else if (videoFile.getName().toLowerCase().endsWith(".webm")) mime = "video/webm";
            
            byte[] respData = new byte[bytesRead];
            if (bytesRead > 0) {
                System.arraycopy(data, 0, respData, 0, bytesRead);
            }
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(
                bytesRead > 0 ? Response.Status.PARTIAL_CONTENT : Response.Status.NO_CONTENT, 
                mime, 
                bais, 
                bytesRead
            );
            
            if (bytesRead > 0) {
                response.addHeader("Content-Range", "bytes " + start + "-" + (start + bytesRead - 1) + "/" + fileSize);
                response.addHeader("Content-Length", String.valueOf(bytesRead));
            }
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Erro serve", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}