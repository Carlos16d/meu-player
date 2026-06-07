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
    private boolean cacheRunning = false;
    
    public StreamServer() { 
        super(8080);
        Log.d(TAG, "DASH Server criado");
    }
    
    public void setSavePath(String path) {
        this.savePath = path;
        if (path != null) {
            this.segmentsDir = new File(path, "dash_segments");
            segmentsDir.mkdirs();
            Log.d(TAG, "Segmentos dir: " + segmentsDir.getAbsolutePath() + " (existe: " + segmentsDir.exists() + ")");
        }
    }
    
    public void setTorrent(torrent_handle handle) {
        Log.d(TAG, "setTorrent chamado, handle=" + (handle != null));
        if (handle == null) {
            Log.e(TAG, "Handle é NULL!");
            return;
        }
        
        this.torrentHandle = handle;
        
        try {
            if (!handle.is_valid()) {
                Log.e(TAG, "Handle inválido!");
                return;
            }
            
            torrent_info ti = handle.torrent_file_ptr();
            if (ti != null && ti.is_valid()) {
                this.fileSize = ti.total_size();
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                Log.d(TAG, "Torrent OK: " + (fileSize/1048576) + "MB, " + numPieces + " peças, " + (pieceLength/1024) + "KB");
            } else {
                Log.e(TAG, "torrent_info é null ou inválido!");
                // Tenta obter do status
                torrent_status st = handle.status();
                this.fileSize = st.getTotal();
                this.numPieces = st.getNum_pieces();
                this.pieceLength = numPieces > 0 ? (int)(fileSize / numPieces) : 524288;
                Log.d(TAG, "Fallback: " + (fileSize/1048576) + "MB, " + numPieces + " peças");
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro em setTorrent: " + e.getMessage(), e);
        }
        
        // Inicia cache apenas se não estiver rodando
        if (!cacheRunning) {
            cacheRunning = true;
            startCacheThread();
        }
    }
    
    private void startCacheThread() {
        new Thread(() -> {
            Log.d(TAG, "Cache thread iniciada");
            int lastLogCount = 0;
            
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
                    
                    if (loaded > 0 && pieceCache.size() - lastLogCount >= 10) {
                        lastLogCount = pieceCache.size();
                        Log.d(TAG, "📦 Cache: " + pieceCache.size() + " peças (" + (pieceCache.size()*pieceLength/1048576) + "MB RAM)");
                    }
                    
                    Thread.sleep(500);
                } catch (Exception e) {
                    Log.e(TAG, "Cache error: " + e.getMessage());
                }
            }
            Log.d(TAG, "Cache thread finalizada");
            cacheRunning = false;
        }).start();
    }
    
    private byte[] readPieceFromDisk(int pieceIndex) {
        try {
            if (torrentHandle == null || !torrentHandle.is_valid()) return null;
            
            torrent_status st = torrentHandle.status();
            String sp = st.getSave_path();
            
            if (sp == null) {
                Log.w(TAG, "save_path é null!");
                return null;
            }
            
            File dir = new File(sp);
            if (!dir.exists()) {
                Log.w(TAG, "Diretório não existe: " + sp);
                return null;
            }
            
            File videoFile = findVideoFile(dir);
            if (videoFile == null) {
                Log.w(TAG, "Video não encontrado em: " + sp);
                return null;
            }
            
            long offset = (long)pieceIndex * pieceLength;
            if (offset >= videoFile.length()) return null;
            
            int len = (int)Math.min(pieceLength, videoFile.length() - offset);
            if (len <= 0) return null;
            
            byte[] data = new byte[len];
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(offset);
            raf.readFully(data);
            raf.close();
            return data;
            
        } catch (Exception e) {
            // Não loga toda vez para não floodar
            return null;
        }
    }
    
    private File findVideoFile(File dir) {
        try {
            if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        File found = findVideoFile(f);
                        if (found != null) return found;
                    } else if (f.getName().toLowerCase().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
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
            if (end < start) end = start + 262143;
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 262144);
            
            byte[] data = readFromCache(start, chunkSize);
            bytesServed += data.length;
            
            // CRIA SEGMENTO VISÍVEL
            if (segmentsDir != null && data.length > 0) {
                int segmentNum = (int)(start / 500000);
                File segmentFile = new File(segmentsDir, 
                    String.format("seg_%04d_%d_%d.m4s", segmentNum, start, start + data.length));
                
                if (!segmentFile.exists()) {
                    try {
                        FileOutputStream fos = new FileOutputStream(segmentFile);
                        fos.write(data);
                        fos.close();
                        Log.d(TAG, "📁 " + segmentFile.getName() + " (" + data.length + " bytes)");
                    } catch (Exception e) {
                        Log.e(TAG, "Erro salvando segmento: " + e.getMessage());
                    }
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
            Log.e(TAG, "Error serve: " + e.getMessage());
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