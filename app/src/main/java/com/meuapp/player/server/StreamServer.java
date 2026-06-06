package com.meuapp.player.server;

import android.util.Log;

import java.io.*;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private File videoFile;
    
    public StreamServer() {
        super(8080);
        Log.d(TAG, "Servidor criado na porta 8080");
    }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
        Log.d(TAG, "Arquivo de vídeo definido: " + (f != null ? f.getName() : "null") + 
              " (" + (f != null ? f.length()/1048576 : 0) + "MB)");
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String method = session.getMethod().name();
        String rangeHeader = session.getHeaders().get("range");
        
        Log.d(TAG, "Request: " + method + " " + uri + " Range: " + rangeHeader);
        
        if (!uri.contains("/video") || videoFile == null || !videoFile.exists()) {
            Log.w(TAG, "Arquivo não encontrado: " + (videoFile != null ? videoFile.getAbsolutePath() : "null"));
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
            
            int chunkSize = Math.min((int)(end - start + 1), 1048576);
            
            Log.d(TAG, "Servindo bytes " + start + "-" + end + " (chunk: " + chunkSize + " bytes)");
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            int retries = 0;
            
            while (bytesRead < 8192 && retries < 30) {
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                raf.seek(start);
                bytesRead = raf.read(data);
                raf.close();
                
                Log.d(TAG, "Tentativa " + retries + ": leu " + bytesRead + " bytes");
                
                if (bytesRead < 8192) {
                    Thread.sleep(500);
                    retries++;
                }
            }
            
            if (bytesRead < 4096) {
                Log.w(TAG, "Dados insuficientes após " + retries + " tentativas");
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
            
            Log.d(TAG, "Resposta: 206, " + bytesRead + " bytes, tipo: " + mime);
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao servir vídeo", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}