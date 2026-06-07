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
    private String fileName = "video.mp4";
    
    public StreamServer() { super(8080); }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            torrent_info ti = handle.torrent_file_ptr();
            if (ti != null && ti.is_valid()) {
                this.fileSize = ti.total_size();
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                this.fileName = ti.name();
                Log.d(TAG, "Torrent: " + (fileSize/1048576) + "MB, " + numPieces + " peças, " + (pieceLength/1024) + "KB");
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
            
            if (start >= fileSize) start = Math.max(0, fileSize - 524288);
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 262144); // 256KB
            
            // LÊ DIRETO DAS PEÇAS
            byte[] data = readFromPieces(start, chunkSize);
            bytesServed += data.length;
            
            if (totalRequests % 20 == 0) {
                Log.d(TAG, "#" + totalRequests + " Range:" + start + "+" + data.length + " fileSize:" + fileSize);
            }
            
            if (data.length == 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Piece not available");
            }
            
            String mime = "video/mp4";
            if (fileName.toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            else if (fileName.toLowerCase().endsWith(".webm")) mime = "video/webm";
            
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
        
        // Verifica se a peça está disponível
        if (startPiece < numPieces && torrentHandle.have_piece(startPiece)) {
            // Força prioridade
            torrentHandle.piece_priority_ex(startPiece, (byte)7);
            
            // Tenta ler a peça via read_piece (assíncrono no libtorrent)
            // Como read_piece é assíncrono, vamos usar o arquivo em disco como fallback
            // O libtorrent salva as peças em disco automaticamente
            
            // Por enquanto, retorna bytes vazios (placeholder)
            // O correto seria implementar read_piece com callback
            int availableBytes = Math.min(size, pieceLength - pieceOffset);
            return new byte[availableBytes];
        }
        
        // Peça não disponível - força prioridade
        if (startPiece < numPieces) {
            torrentHandle.piece_priority_ex(startPiece, (byte)7);
            torrentHandle.set_piece_deadline(startPiece, 500);
        }
        
        return new byte[0];
    }
}