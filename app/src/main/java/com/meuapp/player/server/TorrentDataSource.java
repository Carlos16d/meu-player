package com.meuapp.player.server;

import android.net.Uri;
import android.util.Log;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.libtorrent4j.swig.*;

import java.io.*;

public class TorrentDataSource implements DataSource {
    private static final String TAG = "TorrentDataSource";
    private torrent_handle torrentHandle;
    private File videoFile;
    private DataSpec dataSpec;
    private long bytesRemaining;
    private boolean opened;
    private Uri uri;
    private RandomAccessFile raf;
    private long totalRead = 0;
    
    public TorrentDataSource() {}
    
    public void setTorrentHandle(torrent_handle handle) { this.torrentHandle = handle; }
    public void setVideoFile(File file) { this.videoFile = file; }
    
    @Override
    public void addTransferListener(TransferListener transferListener) {}
    
    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        this.opened = true;
        this.uri = dataSpec.uri;
        this.totalRead = 0;
        
        long position = dataSpec.position;
        long length = dataSpec.length;
        
        if (length == -1) {
            length = videoFile != null ? videoFile.length() - position : 0;
        }
        
        this.bytesRemaining = length;
        
        Log.d(TAG, "══════════════════════════════════");
        Log.d(TAG, "open() chamado!");
        Log.d(TAG, "  position: " + position + " (" + (position/1048576) + "MB)");
        Log.d(TAG, "  length: " + length);
        Log.d(TAG, "  videoFile: " + (videoFile != null ? videoFile.getAbsolutePath() : "NULL"));
        Log.d(TAG, "  videoFile.exists: " + (videoFile != null ? videoFile.exists() : false));
        Log.d(TAG, "  videoFile.length: " + (videoFile != null ? videoFile.length() : 0));
        
        // Abre o arquivo
        if (videoFile != null && videoFile.exists()) {
            this.raf = new RandomAccessFile(videoFile, "r");
            Log.d(TAG, "  RandomAccessFile ABERTO com sucesso!");
            
            // Teste de leitura
            try {
                byte[] test = new byte[16];
                raf.seek(0);
                int testRead = raf.read(test);
                Log.d(TAG, "  Teste leitura pos 0: " + testRead + " bytes");
                Log.d(TAG, "  Magic: " + String.format("0x%02X 0x%02X 0x%02X 0x%02X", 
                    test[0] & 0xFF, test[1] & 0xFF, test[2] & 0xFF, test[3] & 0xFF));
            } catch (Exception e) {
                Log.e(TAG, "  Teste leitura FALHOU: " + e.getMessage());
            }
        } else {
            Log.e(TAG, "  NÃO FOI POSSÍVEL ABRIR O ARQUIVO!");
        }
        
        return length;
    }
    
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (!opened || raf == null || videoFile == null || !videoFile.exists()) {
            Log.w(TAG, "read() -> -1 (não aberto)");
            return -1;
        }
        
        long position = dataSpec.position + (dataSpec.length - bytesRemaining);
        
        // Aguarda dados
        int retries = 0;
        long fileLen = videoFile.length();
        while (fileLen <= position && retries < 30) {
            try { Thread.sleep(200); retries++; fileLen = videoFile.length(); } 
            catch (InterruptedException e) { break; }
        }
        
        int bytesToRead = (int) Math.min(length, Math.min(bytesRemaining, fileLen - position));
        
        if (totalRead < 5 || position < 1048576) { // Log nas primeiras leituras
            Log.d(TAG, "read() pos=" + position + " req=" + length + " toRead=" + bytesToRead + 
                  " fileLen=" + fileLen + " remaining=" + bytesRemaining + " retries=" + retries);
        }
        
        if (bytesToRead <= 0) {
            Log.w(TAG, "read() -> -1 (bytesToRead=" + bytesToRead + ")");
            return -1;
        }
        
        try {
            raf.seek(position);
            int bytesRead = raf.read(buffer, offset, bytesToRead);
            
            if (bytesRead > 0) {
                bytesRemaining -= bytesRead;
                totalRead += bytesRead;
            }
            
            if (totalRead < 5 || position < 1048576) {
                Log.d(TAG, "  -> retornou " + bytesRead + " bytes (total=" + totalRead + ")");
            }
            
            return bytesRead > 0 ? bytesRead : -1;
            
        } catch (Exception e) {
            Log.e(TAG, "read() ERRO: " + e.getMessage());
            return -1;
        }
    }
    
    @Override
    public Uri getUri() { return uri; }
    
    @Override
    public void close() throws IOException {
        Log.d(TAG, "close() - total lido: " + totalRead + " bytes");
        opened = false;
        bytesRemaining = 0;
        totalRead = 0;
        if (raf != null) {
            try { raf.close(); } catch (Exception e) {}
            raf = null;
        }
    }
}