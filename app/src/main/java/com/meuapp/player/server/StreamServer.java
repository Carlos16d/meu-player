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
    
    public StreamServer() { super(8080); }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
        Log.d(TAG, "VIDEO: " + (f != null ? f.getName() + " " + f.length() : "NULL"));
    }
    
    public String getStats() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        return totalRequests + "req " + (bytesServed/1048576) + "MB " + uptime + "s";
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        totalRequests++;
        
        Log.d(TAG, "#" + totalRequests + " " + session.getMethod() + " " + uri + " Range:" + rangeHeader);
        
        if (videoFile == null || !videoFile.exists())
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No video");
        
        try {
            long fileSize = videoFile.length();
            long start = 0, end = fileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            }
            
            if (start >= fileSize) start = Math.max(0, fileSize - 1048576);
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 1048576);
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            long waitStart = System.currentTimeMillis();
            
            while (bytesRead < 4096 && (System.currentTimeMillis() - waitStart) < 15000) {
                if (videoFile.length() > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                }
                if (bytesRead < 4096) Thread.sleep(200);
            }
            
            long waited = System.currentTimeMillis() - waitStart;
            bytesServed += bytesRead;
            
            Log.d(TAG, "  -> " + bytesRead + "B waited:" + waited + "ms fileSize:" + fileSize);
            
            if (bytesRead <= 0)
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No data");
            
            String mime = "video/mp4";
            String name = videoFile.getName().toLowerCase();
            if (name.endsWith(".mkv")) mime = "video/x-matroska";
            else if (name.endsWith(".webm")) mime = "video/webm";
            
            byte[] respData = new byte[bytesRead];
            System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + bytesRead - 1) + "/" + fileSize);
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}