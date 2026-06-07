package com.meuapp.player.server;

import android.util.Log;

import java.io.*;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private File videoFile;
    
    public StreamServer() {
        super(8080);
    }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        
        if (!uri.contains("/video") || videoFile == null || !videoFile.exists()) {
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
            
            if (start >= fileSize) start = Math.max(0, fileSize - 524288);
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 524288);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            // Aguarda dados com timeout
            int retries = 0;
            while (bytesRead < 4096 && retries < 20) {
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
            
            // Sempre retorna algo
            if (bytesRead <= 0) bytesRead = 0;
            
            String mime = "video/mp4";
            String name = videoFile.getName().toLowerCase();
            if (name.endsWith(".mkv")) mime = "video/x-matroska";
            else if (name.endsWith(".webm")) mime = "video/webm";
            
            byte[] respData = new byte[bytesRead];
            if (bytesRead > 0) System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead
            );
            
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + Math.max(0, bytesRead - 1)) + "/" + fileSize);
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            
            return response;
            
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}