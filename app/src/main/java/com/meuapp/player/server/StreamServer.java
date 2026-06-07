package com.meuapp.player.server;

import android.util.Log;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.libtorrent4j.swig.*;

public class StreamServer {
    private static final String TAG = "StreamServer";
    private HttpServer server;
    private torrent_handle torrentHandle;
    private File videoFile;
    private String savePath;
    private int pieceLength = 0;
    private int numPieces = 0;
    private long totalRequests = 0;
    private long bytesServed = 0;
    
    public StreamServer() {}
    
    public void setSavePath(String path) { this.savePath = path; }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        if (handle != null && handle.is_valid()) {
            torrent_info ti = handle.torrent_file_ptr();
            if (ti != null && ti.is_valid()) {
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                Log.d(TAG, "Torrent: " + (ti.total_size()/1048576) + "MB, " + numPieces + " peças");
            }
        }
        findVideoFile();
    }
    
    private void findVideoFile() {
        if (savePath == null) return;
        File dir = new File(savePath);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File[] sub = f.listFiles();
                    if (sub != null) {
                        for (File sf : sub) {
                            if (sf.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && sf.length() > 0) {
                                videoFile = sf;
                                Log.d(TAG, "VIDEO: " + sf.getName() + " " + (sf.length()/1048576) + "MB");
                                return;
                            }
                        }
                    }
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    videoFile = f;
                    Log.d(TAG, "VIDEO: " + f.getName() + " " + (f.length()/1048576) + "MB");
                    return;
                }
            }
        }
        Log.e(TAG, "VIDEO NÃO ENCONTRADO em " + savePath);
    }
    
    public String getStats() {
        return totalRequests + "req " + (bytesServed/1048576) + "MB";
    }
    
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new VideoHandler());
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        Log.d(TAG, "Servidor HTTP iniciado na porta 8080");
    }
    
    public void stop() {
        if (server != null) {
            server.stop(0);
            Log.d(TAG, "Servidor HTTP parado");
        }
    }
    
    class VideoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            totalRequests++;
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            
            Log.d(TAG, "REQ #" + totalRequests + ": " + exchange.getRequestMethod() + " " + exchange.getRequestURI() + " Range:" + rangeHeader);
            
            if (videoFile == null || !videoFile.exists()) {
                findVideoFile();
                if (videoFile == null || !videoFile.exists()) {
                    String resp = "Video not ready";
                    exchange.sendResponseHeaders(404, resp.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(resp.getBytes());
                    os.close();
                    return;
                }
            }
            
            try {
                long fileSize = videoFile.length();
                long start = 0, end = fileSize - 1;
                
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    String[] parts = rangeHeader.substring(6).split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                }
                
                if (start < 0) start = 0;
                if (start >= fileSize) start = Math.max(0, fileSize - 1048576);
                if (end >= fileSize) end = fileSize - 1;
                if (end < start) end = start + 1048575;
                
                int chunkSize = Math.min((int)(end - start + 1), 1048576);
                
                // Força prioridade
                if (torrentHandle != null && pieceLength > 0) {
                    int piece = (int)(start / pieceLength);
                    if (piece < numPieces) {
                        torrentHandle.piece_priority_ex(piece, (byte)7);
                        torrentHandle.set_piece_deadline(piece, 500);
                    }
                }
                
                byte[] data = new byte[chunkSize];
                int bytesRead = 0;
                
                for (int retry = 0; retry < 5 && bytesRead < 4096; retry++) {
                    if (videoFile.length() > start) {
                        RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                        raf.seek(start);
                        bytesRead = raf.read(data);
                        raf.close();
                    }
                    if (bytesRead < 4096 && retry < 4) Thread.sleep(300);
                }
                
                bytesServed += Math.max(0, bytesRead);
                
                Log.d(TAG, "  -> start=" + start + " bytesRead=" + bytesRead + " fileSize=" + fileSize);
                
                if (bytesRead <= 0) {
                    String resp = "Buffering...";
                    exchange.sendResponseHeaders(503, resp.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(resp.getBytes());
                    os.close();
                    return;
                }
                
                String mime = "video/mp4";
                if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
                else if (videoFile.getName().toLowerCase().endsWith(".webm")) mime = "video/webm";
                
                exchange.getResponseHeaders().set("Content-Type", mime);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + (start + bytesRead - 1) + "/" + fileSize);
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytesRead));
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Connection", "keep-alive");
                
                exchange.sendResponseHeaders(206, bytesRead);
                OutputStream os = exchange.getResponseBody();
                os.write(data, 0, bytesRead);
                os.close();
                
            } catch (Exception e) {
                Log.e(TAG, "ERRO", e);
                String resp = "Error: " + e.getMessage();
                exchange.sendResponseHeaders(500, resp.length());
                OutputStream os = exchange.getResponseBody();
                os.write(resp.getBytes());
                os.close();
            }
        }
    }
}