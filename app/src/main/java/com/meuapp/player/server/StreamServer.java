package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import fi.iki.elonen.NanoHTTPD;

import org.libtorrent4j.swig.*;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private torrent_handle torrentHandle;
    private long fileSize = 0;
    private int pieceLength = 0;
    private int numPieces = 0;
    private long totalRequests = 0;
    private long bytesServed = 0;
    
    // Cache de peças em memória RAM
    private Map<Integer, byte[]> pieceCache = new ConcurrentHashMap<>();
    private int cacheHits = 0;
    private int cacheMisses = 0;
    
    private static final int SEGMENT_SIZE = 500000;
    
    public StreamServer() { 
        super(8080);
        Log.d(TAG, "╔══════════════════════════════╗");
        Log.d(TAG, "║ DASH SERVER - MEMÓRIA RAM   ║");
        Log.d(TAG, "║ Porta 8080                  ║");
        Log.d(TAG, "╚══════════════════════════════╝");
    }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            torrent_info ti = handle.torrent_file_ptr();
            if (ti != null && ti.is_valid()) {
                this.fileSize = ti.total_size();
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                Log.d(TAG, "📊 Torrent: " + (fileSize/1048576) + "MB | " + numPieces + " peças | " + (pieceLength/1024) + "KB/peça");
            }
            startCacheThread();
        }
    }
    
    private void startCacheThread() {
        new Thread(() -> {
            Log.d(TAG, "🔄 Thread de cache iniciada");
            while (torrentHandle != null && torrentHandle.is_valid()) {
                try {
                    int loaded = 0;
                    for (int i = 0; i < numPieces && pieceCache.size() < 300; i++) {
                        if (!pieceCache.containsKey(i) && torrentHandle.have_piece(i)) {
                            byte[] data = readPieceFromDisk(i);
                            if (data != null) {
                                pieceCache.put(i, data);
                                loaded++;
                            }
                        }
                    }
                    if (loaded > 0) {
                        Log.d(TAG, "📦 Cache: +" + loaded + " peças | Total: " + pieceCache.size() + " | RAM: " + (pieceCache.size() * pieceLength / 1048576) + "MB");
                    }
                    Thread.sleep(500);
                } catch (Exception e) {
                    Log.e(TAG, "Cache error", e);
                }
            }
            Log.d(TAG, "🔄 Thread de cache finalizada");
        }).start();
    }
    
    private byte[] readPieceFromDisk(int pieceIndex) {
        if (torrentHandle == null) return null;
        try {
            torrent_status st = torrentHandle.status();
            String savePath = st.getSave_path();
            if (savePath != null) {
                File dir = new File(savePath);
                File videoFile = findVideoFile(dir);
                if (videoFile != null && videoFile.exists()) {
                    long offset = (long)pieceIndex * pieceLength;
                    int len = (int)Math.min(pieceLength, videoFile.length() - offset);
                    if (len > 0 && offset < videoFile.length()) {
                        byte[] data = new byte[len];
                        RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                        raf.seek(offset);
                        raf.readFully(data);
                        raf.close();
                        return data;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro lendo peça " + pieceIndex, e);
        }
        return null;
    }
    
    private File findVideoFile(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideoFile(f);
                    if (found != null) return found;
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }
    
    public String getStats() {
        return totalRequests + "req | " + (bytesServed/1048576) + "MB | cache:" + pieceCache.size() + "pcs | hits:" + cacheHits + " miss:" + cacheMisses;
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        totalRequests++;
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        
        // Manifesto DASH
        if (uri.endsWith(".mpd") || uri.equals("/dash")) {
            Log.d(TAG, "📋 REQ #" + totalRequests + ": MANIFESTO .mpd");
            return serveManifest();
        }
        
        // Segmento DASH
        if (uri.contains("segment_")) {
            Log.d(TAG, "🎬 REQ #" + totalRequests + ": SEGMENTO " + uri);
            return serveSegment(uri);
        }
        
        // Range request (fallback)
        Log.d(TAG, "📡 REQ #" + totalRequests + ": RANGE " + rangeHeader);
        
        if (torrentHandle == null || !torrentHandle.is_valid() || fileSize == 0) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not ready");
        }
        
        try {
            long start = 0, end = fileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            }
            
            if (start < 0) start = 0;
            if (start >= fileSize) start = fileSize - 262144;
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 262144);
            
            byte[] data = readFromCache(start, chunkSize);
            bytesServed += data.length;
            
            Log.d(TAG, "  -> " + data.length + " bytes | cache:" + pieceCache.size() + "pcs | " + (bytesServed/1048576) + "MB total");
            
            if (data.length == 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering...");
            }
            
            String mime = "video/mp4";
            
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, data.length);
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + data.length - 1) + "/" + fileSize);
            response.addHeader("Content-Length", String.valueOf(data.length));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
        }
    }
    
    private byte[] readFromCache(long offset, int size) {
        if (pieceLength <= 0) return new byte[0];
        
        int startPiece = (int)(offset / pieceLength);
        int pieceOffset = (int)(offset % pieceLength);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int remaining = size;
        int currentPiece = startPiece;
        
        while (remaining > 0 && currentPiece < numPieces) {
            byte[] pieceData = pieceCache.get(currentPiece);
            
            if (pieceData != null) {
                cacheHits++;
            } else {
                cacheMisses++;
                if (torrentHandle.have_piece(currentPiece)) {
                    pieceData = readPieceFromDisk(currentPiece);
                    if (pieceData != null) {
                        pieceCache.put(currentPiece, pieceData);
                    }
                }
            }
            
            if (pieceData != null) {
                int dataOffset = (currentPiece == startPiece) ? pieceOffset : 0;
                int dataLen = Math.min(remaining, pieceData.length - dataOffset);
                if (dataLen > 0) {
                    baos.write(pieceData, dataOffset, dataLen);
                    remaining -= dataLen;
                }
            } else {
                break;
            }
            
            currentPiece++;
        }
        
        return baos.toByteArray();
    }
    
    private Response serveManifest() {
        if (fileSize == 0) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not ready");
        }
        
        int totalSegments = Math.max(1, (int)(fileSize / SEGMENT_SIZE));
        Log.d(TAG, "  Manifesto: " + totalSegments + " segmentos, " + (fileSize/1048576) + "MB");
        
        StringBuilder mpd = new StringBuilder();
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        mpd.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" minBufferTime=\"PT2S\" type=\"dynamic\">\n");
        mpd.append("<Period id=\"0\" start=\"PT0S\">\n");
        mpd.append("<AdaptationSet mimeType=\"video/mp4\" contentType=\"video\">\n");
        mpd.append("<SegmentTemplate startNumber=\"0\" duration=\"5\"");
        mpd.append(" initialization=\"init.mp4\" media=\"segment_$Number$.m4s\"/>\n");
        mpd.append("</AdaptationSet>\n");
        mpd.append("</Period>\n");
        mpd.append("</MPD>");
        
        return newFixedLengthResponse(Response.Status.OK, "application/dash+xml", mpd.toString());
    }
    
    private Response serveSegment(String uri) {
        try {
            String numStr = uri.replaceAll("[^0-9]", "");
            if (numStr.isEmpty()) numStr = "0";
            
            int segNum = Integer.parseInt(numStr);
            long start = segNum * SEGMENT_SIZE;
            long end = Math.min(start + SEGMENT_SIZE, fileSize);
            int size = (int)(end - start);
            
            if (size <= 0 || start >= fileSize) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Segment not available");
            }
            
            byte[] data = readFromCache(start, size);
            bytesServed += data.length;
            
            Log.d(TAG, "  Segmento " + segNum + ": " + data.length + " bytes (cache:" + pieceCache.size() + "pcs)");
            
            if (data.length == 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering...");
            }
            
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            return newFixedLengthResponse(Response.Status.OK, "video/mp4", bais, data.length);
            
        } catch (Exception e) {
            Log.e(TAG, "Error segment", e);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Error");
        }
    }
}