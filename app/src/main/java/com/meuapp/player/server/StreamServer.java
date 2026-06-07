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
    private String fileName = "video.mp4";
    
    // CACHE EM MEMÓRIA
    private Map<Integer, byte[]> pieceCache = new ConcurrentHashMap<>();
    private Set<Integer> requestedPieces = new HashSet<>();
    
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
                this.fileName = ti.name();
                Log.d(TAG, "Torrent: " + (fileSize/1048576) + "MB, " + numPieces + " peças, " + (pieceLength/1024) + "KB");
            }
        }
    }
    
    public String getStats() {
        return totalRequests + "req " + (bytesServed/1048576) + "MB cache:" + pieceCache.size() + "pcs";
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
            
            int chunkSize = Math.min((int)(end - start + 1), 262144);
            
            // LÊ DO CACHE EM MEMÓRIA
            byte[] data = readFromCache(start, chunkSize);
            bytesServed += data.length;
            
            if (totalRequests % 50 == 0) {
                Log.d(TAG, "#" + totalRequests + " Range:" + start + "+" + data.length + " fileSize:" + fileSize);
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
    
    private byte[] readFromCache(long offset, int size) {
        if (torrentHandle == null || pieceLength <= 0) return new byte[0];
        
        int startPiece = (int)(offset / pieceLength);
        int endPiece = (int)((offset + size - 1) / pieceLength);
        int pieceOffset = (int)(offset % pieceLength);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long currentOffset = offset;
        int remaining = size;
        
        for (int i = startPiece; i <= Math.min(endPiece, numPieces - 1) && remaining > 0; i++) {
            byte[] pieceData = pieceCache.get(i);
            
            if (pieceData == null) {
                // Peça não está no cache - tenta ler do torrent
                if (torrentHandle.have_piece(i)) {
                    // Lê a peça do disco (libtorrent salva automaticamente)
                    pieceData = readPieceFromDisk(i);
                    if (pieceData != null) {
                        pieceCache.put(i, pieceData);
                        Log.d(TAG, "Peça " + i + " carregada no cache (" + (pieceData.length/1024) + "KB)");
                    }
                } else {
                    // Força prioridade nesta peça
                    if (!requestedPieces.contains(i)) {
                        torrentHandle.piece_priority_ex(i, (byte)7);
                        torrentHandle.set_piece_deadline(i, 500);
                        requestedPieces.add(i);
                        Log.d(TAG, "Solicitando peça " + i);
                    }
                }
            }
            
            if (pieceData != null) {
                int dataOffset = (i == startPiece) ? pieceOffset : 0;
                int dataLen = Math.min(remaining, pieceData.length - dataOffset);
                if (dataLen > 0) {
                    baos.write(pieceData, dataOffset, dataLen);
                    currentOffset += dataLen;
                    remaining -= dataLen;
                }
            } else {
                // Peça não disponível - preenche com zeros
                int fillLen = Math.min(remaining, pieceLength - ((i == startPiece) ? pieceOffset : 0));
                if (fillLen > 0) {
                    baos.write(new byte[fillLen], 0, fillLen);
                    remaining -= fillLen;
                }
            }
        }
        
        // Limpa requestedPieces se muito grande
        if (requestedPieces.size() > 200) {
            requestedPieces.clear();
        }
        
        return baos.toByteArray();
    }
    
    private byte[] readPieceFromDisk(int pieceIndex) {
        if (torrentHandle == null || !torrentHandle.is_valid()) return null;
        
        try {
            // O libtorrent salva as peças em um arquivo oculto
            // Tenta encontrar o arquivo de dados
            torrent_status st = torrentHandle.status();
            String savePath = st.getSave_path();
            String name = st.getName();
            
            if (savePath != null && name != null) {
                File dir = new File(savePath);
                File videoFile = findVideoFile(dir, name);
                
                if (videoFile != null && videoFile.exists()) {
                    long offset = (long)pieceIndex * pieceLength;
                    int len = (int)Math.min(pieceLength, videoFile.length() - offset);
                    
                    if (len > 0 && offset < videoFile.length()) {
                        byte[] data = new byte[len];
                        RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                        raf.seek(offset);
                        raf.readFully(data);
                        raf.close();
                        return data;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro lendo peça " + pieceIndex, e);
        }
        return null;
    }
    
    private File findVideoFile(File dir, String name) {
        if (dir == null || !dir.exists()) return null;
        
        // Primeiro procura pelo nome exato
        File exact = new File(dir, name);
        if (exact.exists()) return exact;
        
        // Depois procura recursivamente
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideoFile(f, name);
                    if (found != null) return found;
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }
}