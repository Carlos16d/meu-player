package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

import org.libtorrent4j.swig.*;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private torrent_handle torrentHandle;
    private torrent_info torrentInfo;
    private long fileSize = 0;
    private int pieceLength = 0;
    private int numPieces = 0;
    private long totalRequests = 0;
    private long bytesServed = 0;
    
    public StreamServer() {
        super(8080);
    }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            this.torrentInfo = handle.torrent_file_ptr();
            if (torrentInfo != null && torrentInfo.is_valid()) {
                this.fileSize = torrentInfo.total_size();
                this.pieceLength = torrentInfo.piece_length();
                this.numPieces = torrentInfo.num_pieces();
                Log.d(TAG, "Torrent configurado: " + (fileSize/1048576) + "MB, " + numPieces + " peças, " + (pieceLength/1024) + "KB cada");
            }
        }
    }
    
    public String getStats() {
        return totalRequests + "req " + (bytesServed/1048576) + "MB";
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        totalRequests++;
        
        if (!uri.contains("/video") || torrentHandle == null || !torrentHandle.is_valid()) {
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
            
            int chunkSize = Math.min((int)(end - start + 1), 262144); // 256KB
            
            // Lê dados DIRETAMENTE das peças do torrent
            byte[] data = readFromPieces(start, chunkSize);
            
            bytesServed += data.length;
            
            if (totalRequests % 10 == 0) {
                Log.d(TAG, "#" + totalRequests + " Range:" + start + "+" + data.length + " fileSize:" + fileSize);
            }
            
            if (data.length == 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Piece not available yet");
            }
            
            String mime = "video/mp4";
            if (torrentInfo != null) {
                String name = torrentInfo.name().toLowerCase();
                if (name.endsWith(".mkv")) mime = "video/x-matroska";
                else if (name.endsWith(".webm")) mime = "video/webm";
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
        int endPiece = (int)((offset + size) / pieceLength);
        
        // Verifica se as peças necessárias estão disponíveis
        for (int i = startPiece; i <= Math.min(endPiece, numPieces - 1); i++) {
            if (!torrentHandle.have_piece(i)) {
                // Força prioridade máxima nesta peça
                torrentHandle.piece_priority_ex(i, (byte)7);
                torrentHandle.set_piece_deadline(i, 1000);
                // Retorna o que já tem
                break;
            }
        }
        
        // Tenta ler as peças disponíveis
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long currentOffset = offset;
        int remaining = size;
        int maxPieces = Math.min(endPiece - startPiece + 1, 10); // Máximo 10 peças
        
        for (int i = startPiece; i < startPiece + maxPieces && i < numPieces && remaining > 0; i++) {
            if (torrentHandle.have_piece(i)) {
                // Calcula o offset dentro da peça
                long pieceStart = (long)i * pieceLength;
                int pieceOffset = (int)(currentOffset - pieceStart);
                if (pieceOffset < 0) pieceOffset = 0;
                
                int bytesFromThisPiece = Math.min(remaining, pieceLength - pieceOffset);
                
                // Lê a peça via read_piece (assíncrono, mas tentamos)
                // Por enquanto, retorna bytes vazios para peças não lidas
                byte[] pieceData = new byte[bytesFromThisPiece];
                baos.write(pieceData, 0, bytesFromThisPiece); // Placeholder
                
                currentOffset += bytesFromThisPiece;
                remaining -= bytesFromThisPiece;
            }
        }
        
        return baos.toByteArray();
    }
}