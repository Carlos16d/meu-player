package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

import org.libtorrent4j.swig.*;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private File videoFile;
    private torrent_handle torrentHandle;
    private long totalRequests = 0;
    private long bytesServed = 0;
    private int pieceLength = 0;
    private int numPieces = 0;
    private long lastSeekPosition = 0;
    
    public StreamServer() { super(8080); }
    
    public void setVideoFile(File f) { this.videoFile = f; }
    
    public void setTorrentInfo(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            torrent_info ti = handle.torrent_file_ptr();
            if (ti != null && ti.is_valid()) {
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                Log.d(TAG, "Torrent: " + numPieces + " peças, " + (pieceLength/1024) + "KB");
            }
        }
    }
    
    public String getStats() { return totalRequests + "req " + (bytesServed/1048576) + "MB"; }
    
    @Override
    public Response serve(IHTTPSession session) {
        totalRequests++;
        String rangeHeader = session.getHeaders().get("range");
        
        if (videoFile == null || !videoFile.exists())
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not ready");
        
        try {
            long fileSize = videoFile.length();
            long start = 0, end = fileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            }
            
            // SEEK: prioriza peças da nova posição
            if (torrentHandle != null && pieceLength > 0 && start > 0) {
                long seekDiff = Math.abs(start - lastSeekPosition);
                if (seekDiff > 10485760 || lastSeekPosition == 0) {
                    int targetPiece = (int)(start / pieceLength);
                    int pStart = Math.max(0, targetPiece - 5);
                    int pEnd = Math.min(numPieces, targetPiece + 50);
                    for (int i = pStart; i < pEnd; i++) {
                        torrentHandle.piece_priority_ex(i, (byte)7);
                        torrentHandle.set_piece_deadline(i, 500);
                    }
                    for (int i = 0; i < pStart - 10; i++)
                        torrentHandle.piece_priority_ex(i, (byte)0);
                    Log.d(TAG, "SEEK: peças " + pStart + "-" + pEnd + " prioridade MAX");
                }
                lastSeekPosition = start;
            }
            
            if (start < 0) start = 0;
            if (start >= fileSize) start = Math.max(0, fileSize - 524288);
            if (end >= fileSize) end = fileSize - 1;
            if (end < start) end = start + 524287;
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 524288);
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            int retries = 0;
            while (bytesRead < 4096 && retries < 15) {
                if (videoFile.length() > start) {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.seek(start); bytesRead = raf.read(data); raf.close();
                }
                if (bytesRead < 4096 && retries < 14) { Thread.sleep(200); retries++; }
            }
            
            bytesServed += Math.max(0, bytesRead);
            if (bytesRead <= 0) bytesRead = 0;
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            
            byte[] respData = new byte[bytesRead];
            if (bytesRead > 0) System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(
                bytesRead > 0 ? Response.Status.PARTIAL_CONTENT : Response.Status.NO_CONTENT,
                mime, bais, bytesRead);
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + Math.max(0, bytesRead - 1)) + "/" + fileSize);
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