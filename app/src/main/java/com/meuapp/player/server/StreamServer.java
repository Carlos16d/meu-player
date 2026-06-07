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
    private Map<Integer, byte[]> pieceCache = new ConcurrentHashMap<>();
    private String savePath;
    private File segmentsDir;
    
    public StreamServer() { 
        super(8080);
        Log.d(TAG, "DASH Server - porta 8080");
    }
    
    public void setSavePath(String path) {
        this.savePath = path;
        this.segmentsDir = new File(path, "dash_segments");
        segmentsDir.mkdirs();
        Log.d(TAG, "Segmentos: " + segmentsDir.getAbsolutePath());
    }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            try {
                torrent_info ti = handle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    this.fileSize = ti.total_size();
                    this.pieceLength = ti.piece_length();
                    this.numPieces = ti.num_pieces();
                    Log.d(TAG, "Torrent: " + (fileSize/1048576) + "MB, " + numPieces + " peças");
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro setTorrent", e);
            }
        }
        startCacheThread();
    }
    
    private void startCacheThread() {
        new Thread(() -> {
            while (torrentHandle != null && torrentHandle.is_valid()) {
                try {
                    for (int i = 0; i < numPieces && pieceCache.size() < 300; i++) {
                        if (!pieceCache.containsKey(i) && torrentHandle.have_piece(i)) {
                            byte[] data = readPieceFromDisk(i);
                            if (data != null) {
                                pieceCache.put(i, data);
                            }
                        }
                    }
                    Thread.sleep(500);
                } catch (Exception e) {}
            }
        }).start();
    }
    
    private byte[] readPieceFromDisk(int pieceIndex) {
        try {
            if (torrentHandle == null) return null;
            torrent_status st = torrentHandle.status();
            String sp = st.getSave_path();
            if (sp != null) {
                File dir = new File(sp);
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
        } catch (Exception e) {}
        return null;
    }
    
    private File findVideoFile(File dir) {
        try {
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
        } catch (Exception e) {}
        return null;
    }
    
    public String getStats() {
        return totalRequests + "req " + (bytesServed/1048576) + "MB cache:" + pieceCache.size() + "pcs";
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        totalRequests++;
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        
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
            
            // CRIA SEGMENTO VISÍVEL NA PASTA
            int segmentNum = (int)(start / 500000);
            File segmentFile = new File(segmentsDir, String.format("segment_%04d_%d_%d.m4s", segmentNum, start, start + chunkSize));
            
            byte[] data = readFromCache(start, chunkSize);
            bytesServed += data.length;
            
            // Salva o segmento na pasta (visível!)
            if (data.length > 0 && !segmentFile.exists()) {
                try {
                    FileOutputStream fos = new FileOutputStream(segmentFile);
                    fos.write(data);
                    fos.close();
                    Log.d(TAG, "📁 Segmento salvo: " + segmentFile.getName() + " (" + data.length + " bytes)");
                } catch (Exception e) {
                    Log.e(TAG, "Erro salvando segmento", e);
                }
            }
            
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
        
        try {
            int startPiece = (int)(offset / pieceLength);
            int pieceOffset = (int)(offset % pieceLength);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int remaining = size;
            int currentPiece = startPiece;
            
            while (remaining > 0 && currentPiece < numPieces) {
                byte[] pieceData = pieceCache.get(currentPiece);
                
                if (pieceData == null && torrentHandle.have_piece(currentPiece)) {
                    pieceData = readPieceFromDisk(currentPiece);
                    if (pieceData != null) {
                        pieceCache.put(currentPiece, pieceData);
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
        } catch (Exception e) {
            return new byte[0];
        }
    }
}