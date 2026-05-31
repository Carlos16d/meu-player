package com.seuapp;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
    private byte[] videoBuffer = new byte[0];
    private long contentLength = 0;
    private String mimeType = "video/mp4";
    private float progress = 0;
    private int peerCount = 0;
    private String downloadSpeed = "0 KB/s";
    
    public StreamServer(int port) {
        super(port);
    }
    
    public void setVideoData(String mimeType, long contentLength) {
        this.mimeType = mimeType;
        this.contentLength = contentLength;
    }
    
    public void updateStats(String progress, String peers, String speed) {
        try {
            this.progress = Float.parseFloat(progress);
            this.peerCount = Integer.parseInt(peers);
            this.downloadSpeed = speed;
        } catch (NumberFormatException e) {
            // Ignora erros de parsing
        }
    }
    
    public float getProgress() { return progress; }
    public int getPeerCount() { return peerCount; }
    public String getDownloadSpeed() { return downloadSpeed; }
    
    public void updateVideoChunk(byte[] chunk) {
        // Concatena novos dados ao buffer
        byte[] newBuffer = new byte[videoBuffer.length + chunk.length];
        System.arraycopy(videoBuffer, 0, newBuffer, 0, videoBuffer.length);
        System.arraycopy(chunk, 0, newBuffer, videoBuffer.length, chunk.length);
        videoBuffer = newBuffer;
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        
        // CORS headers
        if (session.getMethod() == Method.OPTIONS) {
            Response response = newFixedLengthResponse(Response.Status.OK, 
                "text/plain", "");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range");
            return response;
        }
        
        if ("/stream".equals(uri)) {
            return serveVideo(session);
        } else if ("/status".equals(uri)) {
            String json = "{\"progress\":" + progress + 
                         ",\"peers\":" + peerCount + 
                         ",\"speed\":\"" + downloadSpeed + "\"}";
            Response response = newFixedLengthResponse(Response.Status.OK, 
                "application/json", json);
            response.addHeader("Access-Control-Allow-Origin", "*");
            return response;
        }
        
        return newFixedLengthResponse(Response.Status.NOT_FOUND, 
            "text/plain", "Not Found");
    }
    
    private Response serveVideo(IHTTPSession session) {
        try {
            String rangeHeader = session.getHeaders().get("range");
            
            if (rangeHeader != null) {
                // Suporte a Range requests
                long start = 0;
                long end = videoBuffer.length - 1;
                
                String range = rangeHeader.replace("bytes=", "");
                String[] parts = range.split("-");
                
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    start = Long.parseLong(parts[0]);
                }
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    end = Long.parseLong(parts[1]);
                }
                
                if (start >= videoBuffer.length) {
                    return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, 
                        "text/plain", "Range Not Satisfiable");
                }
                
                end = Math.min(end, videoBuffer.length - 1);
                int length = (int)(end - start + 1);
                
                byte[] data = new byte[length];
                System.arraycopy(videoBuffer, (int)start, data, 0, length);
                
                Response response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mimeType,
                    new ByteArrayInputStream(data), data.length
                );
                
                response.addHeader("Content-Range", 
                    "bytes " + start + "-" + end + "/" + videoBuffer.length);
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("Content-Length", String.valueOf(length));
                response.addHeader("Access-Control-Allow-Origin", "*");
                
                return response;
            } else {
                // Resposta completa
                Response response = newFixedLengthResponse(
                    Response.Status.OK, mimeType,
                    new ByteArrayInputStream(videoBuffer), videoBuffer.length
                );
                
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("Content-Length", String.valueOf(videoBuffer.length));
                response.addHeader("Access-Control-Allow-Origin", "*");
                
                return response;
            }
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, 
                "text/plain", "Internal Server Error");
        }
    }
}
