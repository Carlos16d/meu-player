package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

public class HlsStreamServer extends NanoHTTPD {
    private static final String TAG = "HlsServer";
    private File videoFile;
    private String m3u8Content;
    private long totalRequests = 0;
    private long bytesServed = 0;
    private int segmentDuration = 5; // 5 segundos por segmento
    private long lastM3u8Update = 0;
    
    public HlsStreamServer() {
        super(8080);
    }
    
    public void setVideoFile(File f) {
        this.videoFile = f;
        Log.d(TAG, "Video: " + (f != null ? f.getName() + " " + f.length() : "null"));
        updateM3u8();
    }
    
    private void updateM3u8() {
        if (videoFile == null) return;
        
        long now = System.currentTimeMillis();
        if (now - lastM3u8Update < 2000) return; // Atualiza a cada 2s
        lastM3u8Update = now;
        
        long fileSize = videoFile.length();
        int totalSegments = (int)(fileSize / (segmentDuration * 500000)); // ~500KB por segmento
        if (totalSegments < 3) totalSegments = 3;
        
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:").append(segmentDuration).append("\n");
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n");
        
        for (int i = 0; i < totalSegments; i++) {
            long segStart = i * segmentDuration * 500000L;
            long segEnd = Math.min(segStart + segmentDuration * 500000L, fileSize);
            if (segStart < fileSize) {
                sb.append("#EXTINF:").append(segmentDuration).append(".0,\n");
                sb.append("segment_").append(i).append(".ts?start=").append(segStart).append("&end=").append(segEnd).append("\n");
            }
        }
        sb.append("#EXT-X-ENDLIST\n");
        
        m3u8Content = sb.toString();
        Log.d(TAG, "M3U8 atualizado: " + totalSegments + " segmentos, " + (fileSize/1048576) + "MB");
    }
    
    public String getStats() {
        return "HLS: " + totalRequests + " req, " + (bytesServed/1048576) + "MB";
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        totalRequests++;
        
        try {
            // Manifesto .m3u8
            if (uri.endsWith(".m3u8") || uri.equals("/video") || uri.equals("/video.m3u8")) {
                updateM3u8();
                if (m3u8Content == null) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No video");
                }
                return newFixedLengthResponse(Response.Status.OK, 
                    "application/vnd.apple.mpegurl", m3u8Content);
            }
            
            // Segmentos .ts
            if (uri.contains("segment_") || uri.contains(".ts")) {
                return serveSegment(session);
            }
            
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
            
        } catch (Exception e) {
            Log.e(TAG, "Erro", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
    
    private Response serveSegment(IHTTPSession session) {
        String uri = session.getUri();
        String params = session.getQueryParameterString();
        
        long start = 0;
        long end = videoFile != null ? videoFile.length() - 1 : 0;
        
        // Parse parâmetros
        if (params != null) {
            for (String param : params.split("&")) {
                String[] kv = param.split("=");
                if (kv.length == 2) {
                    if (kv[0].equals("start")) start = Long.parseLong(kv[1]);
                    else if (kv[0].equals("end")) end = Long.parseLong(kv[1]);
                }
            }
        }
        
        if (videoFile == null || !videoFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No video");
        }
        
        try {
            long fileSize = videoFile.length();
            if (start >= fileSize) start = Math.max(0, fileSize - 524288);
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 524288);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            int retries = 0;
            
            while (bytesRead < 4096 && retries < 20) {
                if (videoFile.length() > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                }
                if (bytesRead < 4096) {
                    Thread.sleep(300);
                    retries++;
                }
            }
            
            bytesServed += bytesRead;
            
            if (bytesRead <= 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No data");
            }
            
            byte[] respData = new byte[bytesRead];
            System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(Response.Status.OK, "video/mp2t", bais, bytesRead);
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Cache-Control", "no-cache");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Erro segmento", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
}
