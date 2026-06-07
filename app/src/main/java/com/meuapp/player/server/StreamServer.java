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
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    
    public StreamServer() { 
        super(8080);
        Log.d(TAG, "==========================================");
        Log.d(TAG, "StreamServer CONSTRUTOR chamado");
        Log.d(TAG, "Porta: 8080");
    }
    
    @Override
    public void start() throws IOException {
        Log.d(TAG, "start() chamado - iniciando servidor...");
        super.start();
        Log.d(TAG, "start() OK - servidor rodando");
    }
    
    public void setSavePath(String path) {
        this.savePath = path;
        Log.d(TAG, "setSavePath: " + path);
        Log.d(TAG, "  Exists: " + new File(path).exists());
    }
    
    public void setTorrent(torrent_handle handle) {
        this.torrentHandle = handle;
        Log.d(TAG, "setTorrent: handle=" + (handle != null));
        Log.d(TAG, "  isValid: " + (handle != null ? handle.is_valid() : "N/A"));
        
        if (handle != null && handle.is_valid()) {
            torrent_info ti = handle.torrent_file_ptr();
            Log.d(TAG, "  torrent_info: " + (ti != null));
            
            if (ti != null && ti.is_valid()) {
                this.fileSize = ti.total_size();
                this.pieceLength = ti.piece_length();
                this.numPieces = ti.num_pieces();
                Log.d(TAG, "  FileSize: " + fileSize + " bytes (" + (fileSize/1048576) + "MB)");
                Log.d(TAG, "  PieceLength: " + pieceLength + " bytes (" + (pieceLength/1024) + "KB)");
                Log.d(TAG, "  NumPieces: " + numPieces);
                Log.d(TAG, "  Name: " + ti.name());
            } else {
                Log.e(TAG, "  torrent_info é NULL ou inválido!");
            }
            
            findVideoFile();
        }
    }
    
    private void findVideoFile() {
        Log.d(TAG, "=== findVideoFile ===");
        Log.d(TAG, "savePath: " + savePath);
        
        if (savePath == null) {
            Log.e(TAG, "savePath é NULL - não posso procurar!");
            return;
        }
        
        File dir = new File(savePath);
        Log.d(TAG, "Dir: " + dir.getAbsolutePath());
        Log.d(TAG, "  exists: " + dir.exists());
        Log.d(TAG, "  isDirectory: " + dir.isDirectory());
        Log.d(TAG, "  canRead: " + dir.canRead());
        
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            Log.d(TAG, "  listFiles: " + (files != null ? files.length : 0) + " arquivos");
            
            if (files != null) {
                for (File f : files) {
                    Log.d(TAG, "    [" + (f.isDirectory() ? "DIR" : "FILE") + "] " + 
                          f.getName() + " (" + f.length() + " bytes, " + (f.length()/1048576) + "MB)");
                }
            }
        }
        
        videoFile = findRecursive(dir);
        
        if (videoFile != null) {
            Log.d(TAG, "VIDEO ENCONTRADO:");
            Log.d(TAG, "  Nome: " + videoFile.getName());
            Log.d(TAG, "  Path: " + videoFile.getAbsolutePath());
            Log.d(TAG, "  Size: " + videoFile.length() + " bytes (" + (videoFile.length()/1048576) + "MB)");
            Log.d(TAG, "  exists: " + videoFile.exists());
            Log.d(TAG, "  canRead: " + videoFile.canRead());
            Log.d(TAG, "  canWrite: " + videoFile.canWrite());
            Log.d(TAG, "  isFile: " + videoFile.isFile());
        } else {
            Log.e(TAG, "VIDEO NÃO ENCONTRADO!");
            Log.e(TAG, "  Padrão: .*\\.(mp4|mkv|avi|webm|mov)$");
        }
    }
    
    private File findRecursive(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            Log.d(TAG, "  findRecursive: dir inválido - " + (dir != null ? dir.getAbsolutePath() : "null"));
            return null;
        }
        
        File[] files = dir.listFiles();
        if (files == null) {
            Log.d(TAG, "  findRecursive: listFiles() retornou null para " + dir.getAbsolutePath());
            return null;
        }
        
        Log.d(TAG, "  findRecursive: " + dir.getAbsolutePath() + " tem " + files.length + " itens");
        
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findRecursive(f);
                if (found != null) return found;
            } else {
                String name = f.getName().toLowerCase();
                boolean matches = name.matches(".*\\.(mp4|mkv|avi|webm|mov)$");
                if (matches && f.length() > 0) {
                    Log.d(TAG, "  findRecursive: ENCONTRADO " + f.getName() + " (" + f.length() + " bytes)");
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
        totalRequests++;
        String uri = session.getUri();
        String rangeHeader = session.getHeaders().get("range");
        String method = session.getMethod().name();
        
        Log.d(TAG, "╔══════════════════════════════════════╗");
        Log.d(TAG, "║ REQ #" + totalRequests + " - " + method + " " + uri);
        Log.d(TAG, "╠══════════════════════════════════════╝");
        Log.d(TAG, "  Range: " + rangeHeader);
        Log.d(TAG, "  Headers: " + session.getHeaders());
        Log.d(TAG, "  videoFile: " + (videoFile != null ? videoFile.getAbsolutePath() : "NULL"));
        Log.d(TAG, "  videoFile.exists: " + (videoFile != null ? videoFile.exists() : false));
        Log.d(TAG, "  videoFile.length: " + (videoFile != null ? videoFile.length() : 0));
        
        if (videoFile == null || !videoFile.exists()) {
            Log.e(TAG, "  >>> 404 <<<");
            findVideoFile();
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", 
                "Video not found. videoFile=" + (videoFile != null ? videoFile.getAbsolutePath() : "null"));
        }
        
        try {
            long actualFileSize = videoFile.length();
            long start = 0, end = actualFileSize - 1;
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                try {
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "  Range inválido: " + rangeHeader);
                }
            }
            
            Log.d(TAG, "  Request: bytes " + start + "-" + end + " / " + actualFileSize);
            
            if (start >= actualFileSize) {
                Log.w(TAG, "  start >= fileSize, ajustando...");
                start = Math.max(0, actualFileSize - 262144);
            }
            if (end >= actualFileSize) end = actualFileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 262144);
            Log.d(TAG, "  ChunkSize: " + chunkSize);
            
            byte[] data = new byte[chunkSize];
            int bytesRead = 0;
            
            if (actualFileSize > start) {
                try {
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    Log.d(TAG, "  RandomAccessFile aberto, seek(" + start + ")");
                    raf.seek(start);
                    bytesRead = raf.read(data);
                    raf.close();
                    Log.d(TAG, "  read() retornou: " + bytesRead + " bytes");
                } catch (Exception e) {
                    Log.e(TAG, "  ERRO ao ler arquivo: " + e.getMessage(), e);
                }
            } else {
                Log.w(TAG, "  actualFileSize (" + actualFileSize + ") <= start (" + start + ")");
            }
            
            bytesServed += Math.max(0, bytesRead);
            
            if (bytesRead > 0) {
                Log.d(TAG, "  Primeiros 4 bytes: " + String.format("0x%02X 0x%02X 0x%02X 0x%02X", 
                    data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF, data[3] & 0xFF));
                Log.d(TAG, "  Como chars: '" + (char)(data[0] & 0xFF) + (char)(data[1] & 0xFF) + 
                    (char)(data[2] & 0xFF) + (char)(data[3] & 0xFF) + "'");
            }
            
            if (bytesRead <= 0) {
                Log.w(TAG, "  >>> 503 - ZERO BYTES <<<");
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", 
                    "Zero bytes read. FileSize=" + actualFileSize + " Start=" + start);
            }
            
            String mime;
            String name = videoFile.getName().toLowerCase();
            if (name.endsWith(".mkv")) {
                mime = "video/x-matroska";
                Log.d(TAG, "  MIME: video/x-matroska (MKV)");
            } else if (name.endsWith(".webm")) {
                mime = "video/webm";
            } else if (name.endsWith(".avi")) {
                mime = "video/x-msvideo";
            } else {
                mime = "video/mp4";
                Log.d(TAG, "  MIME: video/mp4 (default)");
            }
            
            byte[] respData = new byte[bytesRead];
            System.arraycopy(data, 0, respData, 0, bytesRead);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(respData);
            Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, bais, bytesRead);
            response.addHeader("Content-Range", "bytes " + start + "-" + (start + bytesRead - 1) + "/" + actualFileSize);
            response.addHeader("Content-Length", String.valueOf(bytesRead));
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            
            Log.d(TAG, "  >>> 206 OK - " + bytesRead + " bytes <<<");
            
            return response;
            
        } catch (Exception e) {
            Log.e(TAG, "  >>> ERRO FATAL <<<", e);
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            Log.e(TAG, sw.toString());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}