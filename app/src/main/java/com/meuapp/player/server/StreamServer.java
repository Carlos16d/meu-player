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
    
    // CACHE 100% EM MEMÓRIA
    private Map<Integer, byte[]> pieceCache = new ConcurrentHashMap<>();
    private Set<Integer> pendingPieces = new HashSet<>();
    
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
                
                // Inicia thread de leitura de peças
                startPieceReader();
            }
        }
    }
    
    private void startPieceReader() {
        new Thread(() -> {
            while (torrentHandle != null && torrentHandle.is_valid()) {
                try {
                    // Popula cache com peças disponíveis
                    for (int i = 0; i < numPieces && pieceCache.size() < 100; i++) {
                        if (!pieceCache.containsKey(i) && torrentHandle.have_piece(i)) {
                            // Lê a peça do torrent (read_piece é assíncrono)
                            // Usamos o arquivo em disco como fonte (libtorrent salva automaticamente)
                            byte[] data = readPieceFromTorrent(i);
                            if (data != null) {
                                pieceCache.put(i, data);
                            }
                        }
                    }
                    Thread.sleep(1000);
                } catch (Exception e) {
                    Log.e(TAG, "Erro reader", e);
                }
            }
        }).start();
    }
    
    private byte[] readPieceFromTorrent(int pieceIndex) {
        // O libtorrent armazena as peças no arquivo de save_path
        // Vamos ler diretamente de lá
        if (torrentHandle == null) return null;
        
        try {
            torrent_status st = torrentHandle.status();
            String savePath = st.getSave_path();
            String name = st.getName();
            
            if (savePath != null && name != null) {
                // Procura o arquivo
                File dir = new File(savePath);
                File videoFile = findFileRecursive(dir);
                
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
    
    private File findFileRecursive(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findFileRecursive(f);
                    if (found != null) return found;
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
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
            
            if (start >= fileSize) start = Math.max(0, fileSize - 262144);
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 262144);
            
            // LÊ DO CACHE EM MEMÓRIA
            byte[] data = readFromCache(start, chunkSize);
            bytesServed += data.length;
            
            if (totalRequests % 50 == 0) {
                Log.d(TAG, "#" + totalRequests + " Range:" + start + "+" + data.length + " cache:" + pieceCache.size());
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
        if (pieceLength <= 0) return new byte[0];
        
        int startPiece = (int)(offset / pieceLength);
        int pieceOffset = (int)(offset % pieceLength);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int remaining = size;
        long currentOffset = offset;
        
        for (int i = startPiece; i < numPieces && remaining > 0; i++) {
            byte[] pieceData = pieceCache.get(i);
            
            if (pieceData == null) {
                // Tenta ler na hora
                pieceData = readPieceFromTorrent(i);
                if (pieceData != null) {
                    pieceCache.put(i, pieceData);
                }
            }
            
            if (pieceData != null) {
                int dataOffset = (i == startPiece) ? pieceOffset : 0;
                int dataLen = Math.min(remaining, pieceData.length - dataOffset);
                if (dataLen > 0) {
                    baos.write(pieceData, dataOffset, dataLen);
                    remaining -= dataLen;
                    currentOffset += dataLen;
                }
            } else {
                // Peça não disponível - preenche com zeros (vai dar glitch mas não trava)
                int fillLen = Math.min(remaining, pieceLength - ((i == startPiece) ? pieceOffset : 0));
                if (fillLen > 0) {
                    baos.write(new byte[fillLen], 0, fillLen);
                    remaining -= fillLen;
                }
                break; // Para de tentar se não tem a peça
            }
        }
        
        return baos.toByteArray();
    }
}