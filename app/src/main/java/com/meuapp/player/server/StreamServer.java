package com.meuapp.player.server;

import java.io.*;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
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
        
        if (!uri.contains("/video") || videoFile == null || !videoFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        }
        
        try {
            long fileSize = videoFile.length();
            String rangeHeader = session.getHeaders().get("range");
            
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
            
            int chunkSize = Math.min((int)(end - start + 1), 2097152);
            
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(start);
            byte[] data = new byte[chunkSize];
            int bytesRead = raf.read(data);
            raf.close();
            
            if (bytesRead < 4096) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering...");
            }
            
            String mime = videoFile.getName().toLowerCase().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            
            ByteArrayInputStream bais = new ByteArrayInputStream(data, 0, bytesRead);
            Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + bytesRead - 1) + "/" + fileSize);
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            
            return response;
            
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}
