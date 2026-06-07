package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.util.*;

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
    
    // Cache em memória das peças
    private Map<Integer, byte[]> pieceCache = new HashMap<>();
    
    public StreamServer() { 
        super(8080); 
    }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            torrent_info ti = handle.torrent_file_ptr();
            if (ti != null && ti.is_valid()) {
                this.fileSize = ti.total_size();
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                Log.d(TAG, "Torrent: " + (fileSize/1048576) + "MB, " + numPieces + " peças, " + (pieceLength/1024) + "KB");
            }
        }
    }
    
    public void setSavePath(String path) {
        // Não usamos mais
    }
    
    public String getStats() {
        return totalRequests + "req " + (bytesServed/1048576) + "MB cache:" + pieceCache.size();
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        totalRequests++;
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
            
            if (start >= fileSize) start = Math.max(0, fileSize - 1048576);
            if (end >= fileSize) end = fileSize - 1;
            if (start < 0) start = 0;
            
            int chunkSize = Math.min((int)(end - start + 1), 1048576);
            
            // LÊ DAS PEÇAS EM MEMÓRIA
            byte[] data = readFromPieces(start, chunkSize);
            bytesServed += data.length;
            
            if (data.length == 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering...");
            }
            
            String mime = "video/mp4";
            if (torrentHandle.is_valid()) {
                torrent_info ti = torrentHandle.torrent_file_ptr();
                if (ti != null && ti.name().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            }
            
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
    
    private byte[] readFromPieces(long offset, int size) {
        if (torrentHandle == null || pieceLength <= 0) return new byte[0];
        
        int startPiece = (int)(offset / pieceLength);
        int pieceOffset = (int)(offset % pieceLength);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int remaining = size;
        int currentPiece = startPiece;
        
        while (remaining > 0 && currentPiece < numPieces) {
            byte[] pieceData = pieceCache.get(currentPiece);
            
            if (pieceData == null && torrentHandle.have_piece(currentPiece)) {
                // Força prioridade e deadline
                torrentHandle.piece_priority_ex(currentPiece, (byte)7);
                torrentHandle.set_piece_deadline(currentPiece, 500);
            }
            
            if (pieceData != null) {
                int dataOffset = (currentPiece == startPiece) ? pieceOffset : 0;
                int dataLen = Math.min(remaining, pieceData.length - dataOffset);
                if (dataLen > 0) {
                    baos.write(pieceData, dataOffset, dataLen);
                    remaining -= dataLen;
                }
            } else {
                break; // Peça não disponível
            }
            
            currentPiece++;
        }
        
        return baos.toByteArray();
    }
}