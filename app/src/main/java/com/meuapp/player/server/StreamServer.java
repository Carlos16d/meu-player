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
        
        Log.d(TAG, "REQ #" + totalRequests + " | Range: " + rangeHeader);
        
        if (videoFile == null || !videoFile.exists())
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not ready");
        
        try {
            long fileSize = videoFile.length();
            long start = 0, end = fileSize - 1;
            boolean isRange = (rangeHeader != null && rangeHeader.startsWith("bytes="));
            
            if (isRange) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            }
            
            // SEEK
            if (torrentHandle != null && pieceLength > 0 && isRange && start > 0) {
                long seekDiff = Math.abs(start - lastSeekPosition);
                if (seekDiff > 5242880 || lastSeekPosition == 0) {
                    int tp = (int)(start / pieceLength);
                    int rs = Math.max(0, tp - 5), re = Math.min(numPieces, tp + 60);
                    torrentHandle.set_sequential_range(rs, re);
                    for (int i = 0; i < numPieces; i++) torrentHandle.piece_priority_ex(i, (byte)0);
                    for (int i = rs; i < re; i++) { torrentHandle.piece_priority_ex(i, (byte)7); torrentHandle.set_piece_deadline(i, 500); }
                    Log.d(TAG, "🔥 SEEK: " + (start/1048576) + "MB | peças " + rs + "-" + re);
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
            int bytesRead = 0, retries = 0;
            while (bytesRead < 4096 && retries < 15) {
                if (videoFile.length() > start) { RandomAccessFile raf = new RandomAccessFile(videoFile, "r"); raf.seek(start); bytesRead = raf.read(data); raf.close(); }
                if (bytesRead < 4096 && retries < 14) { Thread.sleep(200); retries++; }
            }
            bytesServed += Math.max(0, bytesRead);
            if (bytesRead <= 0) bytesRead = 0;
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            
            byte[] respData = new byte[bytesRead];
            if (bytesRead > 0) System.arraycopy(data, 0, respData, 0, bytesRead);
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            
            Response response;
            if (isRange) {
                response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
                response.addHeader("Content-Range", "bytes " + start + "-" + (start + Math.max(0, bytesRead - 1)) + "/" + fileSize);
            } else {
                response = newFixedLengthResponse(Response.Status.OK, mime, bais, bytesRead);
            }
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error"); }
    }
}