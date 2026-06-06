package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private File videoFile;
    
    public StreamServer() {
        super(8080);
    }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
        Log.d(TAG, "Video set: " + (f != null ? f.getName() + " " + f.length() : "null"));
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        String method = session.getMethod().name();
        
        if (!uri.contains("/video") || videoFile == null || !videoFile.exists()) {
            Log.w(TAG, "404");
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
            
            // Não pede além do disponível
            if (start >= fileSize) start = Math.max(0, fileSize - 1048576);
            if (end >= fileSize) end = fileSize - 1;
            
            // Chunk maior: até 1MB
            int maxChunk = 1048576;
            int chunkSize = Math.min((int)(end - start + 1), maxChunk);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            // Tenta ler - se não conseguir, espera um pouco
            int retries = 0;
            while (bytesRead < 8192 && retries < 20) {
                if (videoFile.length() > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                }
                if (bytesRead < 8192) {
                    Thread.sleep(300);
                    retries++;
                }
            }
            
            // Se ainda não tem dados, retorna 206 com o que tem
            if (bytesRead <= 0) bytesRead = 0;
            
            String mime = "video/mp4";
            String name = videoFile.getName().toLowerCase();
            if (name.endsWith(".mkv")) mime = "video/x-matroska";
            else if (name.endsWith(".webm")) mime = "video/webm";
            else if (name.endsWith(".avi")) mime = "video/x-msvideo";
            
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
            
            // Log a cada 10 requisições
            if (start % 10485760 < 1048576) {
                Log.d(TAG, "Served: " + start + "+" + bytesRead + " file:" + fileSize);
            }
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}