package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private File videoFile;
    private long totalRequests = 0;
    private long bytesServed = 0;
    
    public StreamServer() { 
        super(8080);
        Log.d(TAG, "DASH Server criado na porta 8080");
    }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
        Log.d(TAG, "Video: " + (f != null ? f.getName() + " " + f.length() : "null"));
    }
    
    public String getStats() {
        return totalRequests + "req " + (bytesServed/1048576) + "MB";
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        totalRequests++;
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        
        // Manifesto DASH
        if (uri.endsWith(".mpd") || uri.equals("/dash")) {
            return serveManifest();
        }
        
        // Segmentos
        if (uri.contains("segment")) {
            return serveSegment(uri);
        }
        
        // Range request normal (fallback)
        if (videoFile == null || !videoFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not ready");
        }
        
        try {
            long fileSize = videoFile.length();
            long start = 0, end = fileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            }
            
            if (start < 0) start = 0;
            if (start >= fileSize) start = fileSize - 524288;
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 524288);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            if (videoFile.length() > start) {
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                raf.seek(start);
                bytesRead = raf.read(data);
                raf.close();
            }
            
            bytesServed += Math.max(0, bytesRead);
            if (bytesRead <= 0) bytesRead = 0;
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            
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
            Log.e(TAG, "Error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
    
    private Response serveManifest() {
        if (videoFile == null || !videoFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not ready");
        }
        
        long fileSize = videoFile.length();
        int segmentDuration = 5; // 5 segundos por segmento
        int segmentSize = 500000; // ~500KB por segmento
        int totalSegments = (int)(fileSize / segmentSize) + 1;
        
        StringBuilder mpd = new StringBuilder();
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        mpd.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" minBufferTime=\"PT2S\" type=\"static\">\n");
        mpd.append("<Period duration=\"PT").append(totalSegments * segmentDuration).append("S\">\n");
        mpd.append("<AdaptationSet mimeType=\"video/mp4\" contentType=\"video\">\n");
        
        for (int i = 0; i < totalSegments; i++) {
            long start = i * segmentSize;
            long end = Math.min(start + segmentSize, fileSize);
            mpd.append("<SegmentTemplate startNumber=\"").append(i)
               .append("\" duration=\"").append(segmentDuration)
               .append("\" media=\"segment_").append(i)
               .append("_").append(start).append("_").append(end).append(".m4s\"/>\n");
        }
        
        mpd.append("</AdaptationSet>\n");
        mpd.append("</Period>\n");
        mpd.append("</MPD>");
        
        return newFixedLengthResponse(Response.Status.OK, "application/dash+xml", mpd.toString());
    }
    
    private Response serveSegment(String uri) {
        if (videoFile == null || !videoFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not ready");
        }
        
        try {
            // Parse: segment_X_START_END.m4s
            String[] parts = uri.replace(".m4s", "").split("_");
            if (parts.length >= 3) {
                long start = Long.parseLong(parts[1]);
                long end = Long.parseLong(parts[2]);
                
                long fileSize = videoFile.length();
                if (start >= fileSize) start = fileSize - 524288;
                if (end > fileSize) end = fileSize;
                
                int size = (int)(end - start);
                if (size <= 0) size = 524288;
                if (size > 1048576) size = 1048576;
                
                byte[] data = new byte[size];
                int bytesRead = 0;
                
                if (videoFile.length() > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data, 0, size);
                    raf.close();
                }
                
                bytesServed += Math.max(0, bytesRead);
                
                byte[] respData = new byte[Math.max(0, bytesRead)];
                if (bytesRead > 0) System.arraycopy(data, 0, respData, 0, bytesRead);
                
                ByteArrayInputStream bais = new ByteArrayInputStream(respData);
                return newFixedLengthResponse(Response.Status.OK, "video/mp4", bais, respData.length);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error segment", e);
        }
        
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Invalid segment");
    }
}