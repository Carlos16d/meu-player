package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

import fi.iki.elonen.NanoHTTPD;

import org.libtorrent4j.swig.*;

public class StreamServer extends NanoHTTPD {
    private static final String TAG = "StreamServer";
    private torrent_handle torrentHandle;
    private File videoFile;
    private long fileSize = 0;
    private int pieceLength = 0;
    private int numPieces = 0;
    private long totalRequests = 0;
    private long bytesServed = 0;
    private String savePath;
    
    public StreamServer() { 
        super(8080);
        Log.d(TAG, "=== StreamServer CRIADO ===");
    }
    
    public void setSavePath(String path) {
        this.savePath = path;
        Log.d(TAG, "SavePath: " + path);
    }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        Log.d(TAG, "setTorrent: handle=" + (handle != null ? handle.is_valid() : "NULL"));
        
        if (handle != null && handle.is_valid()) {
            torrent_info ti = handle.torrent_file_ptr();
            if (ti != null && ti.is_valid()) {
                this.fileSize = ti.total_size();
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                Log.d(TAG, "TorrentInfo: " + (fileSize/1048576) + "MB, " + numPieces + " peças, " + (pieceLength/1024) + "KB");
            } else {
                Log.e(TAG, "torrent_info é NULL ou inválido!");
            }
            findVideoFile();
        }
    }
    
    private void findVideoFile() {
        if (savePath == null) {
            Log.e(TAG, "savePath é NULL!");
            return;
        }
        
        File dir = new File(savePath);
        Log.d(TAG, "Procurando vídeo em: " + dir.getAbsolutePath());
        Log.d(TAG, "  Existe: " + dir.exists());
        
        if (dir.exists()) {
            File[] files = dir.listFiles();
            Log.d(TAG, "  Arquivos na pasta: " + (files != null ? files.length : 0));
            if (files != null) {
                for (File f : files) {
                    Log.d(TAG, "    " + f.getName() + " (" + f.length() + " bytes) " + (f.isDirectory() ? "[DIR]" : ""));
                }
            }
        }
        
        videoFile = findRecursive(dir);
        
        if (videoFile != null) {
            Log.d(TAG, "VIDEO ENCONTRADO: " + videoFile.getName() + " (" + (videoFile.length()/1048576) + "MB)");
            Log.d(TAG, "  Caminho: " + videoFile.getAbsolutePath());
        } else {
            Log.e(TAG, "VIDEO NÃO ENCONTRADO em " + savePath);
        }
    }
    
    private File findRecursive(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findRecursive(f);
                    if (found != null) return found;
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }
    
    public String getStats() {
        return totalRequests + "req " + (bytesServed/1048576) + "MB";
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        String method = session.getMethod().name();
        totalRequests++;
        
        Log.d(TAG, "========================================");
        Log.d(TAG, "REQ #" + totalRequests + ": " + method + " " + uri);
        Log.d(TAG, "  Range: " + rangeHeader);
        Log.d(TAG, "  videoFile: " + (videoFile != null ? videoFile.getAbsolutePath() : "NULL"));
        Log.d(TAG, "  videoFile.exists: " + (videoFile != null ? videoFile.exists() : false));
        Log.d(TAG, "  videoFile.length: " + (videoFile != null ? videoFile.length() : 0));
        
        if (videoFile == null || !videoFile.exists()) {
            Log.e(TAG, "  >>> 404 - VIDEO NÃO ENCONTRADO <<<");
            // Tenta encontrar de novo
            findVideoFile();
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", 
                "Video not found. Path: " + savePath);
        }
        
        try {
            long actualFileSize = videoFile.length();
            long start = 0, end = actualFileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            }
            
            Log.d(TAG, "  Solicitado: bytes " + start + "-" + end);
            Log.d(TAG, "  Tamanho real do arquivo: " + actualFileSize);
            
            if (start >= actualFileSize) {
                Log.w(TAG, "  Start " + start + " >= fileSize " + actualFileSize + " - ajustando");
                start = Math.max(0, actualFileSize - 262144);
            }
            if (end >= actualFileSize) end = actualFileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 262144);
            Log.d(TAG, "  Lendo " + chunkSize + " bytes a partir de " + start);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            if (actualFileSize > start) {
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                raf.seek(start);
                bytesRead = raf.read(data);
                raf.close();
            }
            
            bytesServed += Math.max(0, bytesRead);
            
            Log.d(TAG, "  Lido: " + bytesRead + " bytes");
            Log.d(TAG, "  Primeiros bytes: " + (bytesRead > 0 ? String.format("%02X %02X %02X %02X", data[0], data[1], data[2], data[3]) : "N/A"));
            
            if (bytesRead <= 0) {
                Log.w(TAG, "  >>> 503 - ZERO BYTES LIDOS <<<");
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Buffering...");
            }
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            else if (videoFile.getName().toLowerCase().endsWith(".webm")) mime = "video/webm";
            
            Log.d(TAG, "  MIME: " + mime);
            
            byte[] respData = new byte[bytesRead];
            System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + bytesRead - 1) + "/" + actualFileSize);
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            
            Log.d(TAG, "  >>> 206 OK - " + bytesRead + " bytes enviados <<<");
            Log.d(TAG, "========================================");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "  >>> ERRO: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}