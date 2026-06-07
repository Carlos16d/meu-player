package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private File videoFile;
    private long totalRequests = 0;
    private long failedRequests = 0;
    private long bytesServed = 0;
    
    public StreamServer() {
        super(8080);
    }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
        Log.d(TAG, "VIDEO: " + (f != null ? f.getName() + " " + f.length() : "NULL"));
    }
    
    public String getStats() {
        return "Requests: " + totalRequests + " OK, " + failedRequests + " FAIL, " + (bytesServed/1048576) + "MB";
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        totalRequests++;
        
        if (!uri.contains("/video") || videoFile == null || !videoFile.exists()) {
            failedRequests++;
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
            
            // Ajusta ranges inválidos
            if (start >= fileSize) {
                Log.w(TAG, "Start " + start + " >= fileSize " + fileSize + " - ajustando");
                start = Math.max(0, fileSize - 262144);
            }
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 524288);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            int retries = 0;
            long waitedMs = 0;
            
            // Aguarda dados disponíveis
            while (bytesRead < 4096 && retries < 30) {
                long currentSize = videoFile.length();
                
                if (currentSize > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                }
                
                if (bytesRead < 4096) {
                    Thread.sleep(200);
                    waitedMs += 200;
                    retries++;
                }
            }
            
            bytesServed += bytesRead;
            
            // Log a cada 10 requisições ou se teve que esperar
            if (totalRequests % 10 == 0 || waitedMs > 0) {
                Log.d(TAG, "#" + totalRequests + " Range:" + start + "-" + end + 
                      " bytes:" + bytesRead + " waited:" + waitedMs + "ms fileSize:" + fileSize);
            }
            
            if (bytesRead <= 0) {
                failedRequests++;
                Log.w(TAG, "ZERO bytes after " + retries + " retries. FileSize:" + fileSize + " Start:" + start);
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No data yet");
            }
            
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
            failedRequests++;
            Log.e(TAG, "ERRO", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}