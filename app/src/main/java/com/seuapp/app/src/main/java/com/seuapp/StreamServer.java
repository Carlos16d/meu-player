package com.seuapp;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private TorrentEngine torrentEngine;
    private boolean running = false;
    private long currentPosition = 0;
    
    public StreamServer(int port, TorrentEngine engine) {
        super(port);
        this.torrentEngine = engine;
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        
        // CORS
        if (session.getMethod() == Method.OPTIONS) {
            Response resp = newFixedLengthResponse(Response.Status.OK, 
                "text/plain", "");
            addCorsHeaders(resp);
            return resp;
        }
        
        Log.d(TAG, "Request: " + uri);
        
        if ("/stream".equals(uri)) {
            return serveVideo(session);
        } else if ("/status".equals(uri)) {
            String json = "{\"progress\":" + torrentEngine.getProgress() + 
                         ",\"peers\":" + torrentEngine.getPeers() + 
                         ",\"speed\":\"" + formatSpeed(torrentEngine.getDownloadSpeed()) + "\"}";
            Response resp = newFixedLengthResponse(Response.Status.OK, 
                "application/json", json);
            addCorsHeaders(resp);
            return resp;
        }
        
        return newFixedLengthResponse(Response.Status.NOT_FOUND, 
            "text/plain", "Not Found");
    }
    
    private Response serveVideo(IHTTPSession session) {
        try {
            long totalSize = torrentEngine.getTotalSize();
            
            if (totalSize == 0) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain", "Torrent ainda não iniciado");
            }
            
            int numPieces = torrentEngine.getNumPieces();
            long pieceLength = totalSize / Math.max(numPieces, 1);
            
            // Range request support
            String rangeHeader = session.getHeaders().get("range");
            
            if (rangeHeader != null) {
                String range = rangeHeader.replace("bytes=", "");
                String[] parts = range.split("-");
                long start = Long.parseLong(parts[0]);
                
                // Calcula qual peça contém o byte inicial
                int startPiece = (int)(start / pieceLength);
                
                // Lê a peça via JNI
                byte[] pieceData = torrentEngine.readPiece(startPiece);
                
                if (pieceData.length > 0) {
                    long offset = start % pieceLength;
                    int length = (int)Math.min(pieceData.length - offset, 1024 * 1024);
                    
                    byte[] chunk = new byte[length];
                    System.arraycopy(pieceData, (int)offset, chunk, 0, length);
                    
                    Response resp = newFixedLengthResponse(
                        Response.Status.PARTIAL_CONTENT, 
                        "video/mp4",
                        new ByteArrayInputStream(chunk), 
                        chunk.length
                    );
                    
                    resp.addHeader("Content-Range", 
                        "bytes " + start + "-" + (start + length - 1) + "/" + totalSize);
                    resp.addHeader("Accept-Ranges", "bytes");
                    addCorsHeaders(resp);
                    return resp;
                }
            }
            
            // Sem range - envia primeira peça
            byte[] firstPiece = torrentEngine.readPiece(0);
            if (firstPiece.length > 0) {
                Response resp = newFixedLengthResponse(
                    Response.Status.OK,
                    "video/mp4",
                    new ByteArrayInputStream(firstPiece),
                    firstPiece.length
                );
                resp.addHeader("Accept-Ranges", "bytes");
                resp.addHeader("Content-Length", String.valueOf(totalSize));
                addCorsHeaders(resp);
                return resp;
            }
            
            return newFixedLengthResponse(Response.Status.NO_CONTENT,
                "text/plain", "Aguardando dados...");
                
        } catch (Exception e) {
            Log.e(TAG, "Erro ao servir vídeo: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                "text/plain", "Erro interno");
        }
    }
    
    private void addCorsHeaders(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range");
        response.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range");
    }
    
    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec > 1048576)
            return String.format("%.1f MB/s", bytesPerSec / 1048576.0);
        else if (bytesPerSec > 1024)
            return String.format("%.1f KB/s", bytesPerSec / 1024.0);
        else
            return bytesPerSec + " B/s";
    }
}
