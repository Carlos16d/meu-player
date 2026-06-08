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
        
        long position = dataSpec.position;
        long length = dataSpec.length;
        
        if (length == -1) {
            length = videoFile != null ? videoFile.length() - position : 0;
        }
        
        this.bytesRemaining = length;
        
        // Abre o arquivo uma vez
        if (videoFile != null && videoFile.exists()) {
            this.raf = new RandomAccessFile(videoFile, "r");
        }
        
        Log.d(TAG, "open() pos=" + position + " len=" + length + " fileSize=" + (videoFile != null ? videoFile.length() : 0));
        
        // Força prioridade nas peças
        if (torrentHandle != null && torrentHandle.is_valid() && position > 0) {
            try {
                torrent_info ti = torrentHandle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    int pieceLength = ti.piece_length();
                    int numPieces = ti.num_pieces();
                    int targetPiece = (int)(position / pieceLength);
                    int rs = Math.max(0, targetPiece - 5);
                    int re = Math.min(numPieces, targetPiece + 60);
                    torrentHandle.set_sequential_range(rs, re);
                    for (int i = 0; i < numPieces; i++) torrentHandle.piece_priority_ex(i, (byte)0);
                    for (int i = rs; i < re; i++) {
                        torrentHandle.piece_priority_ex(i, (byte)7);
                        torrentHandle.set_piece_deadline(i, 500);
                    }
                }
            } catch (Exception e) {}
        }
        
        return length;
    }
    
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (!opened || raf == null || videoFile == null || !videoFile.exists()) {
            Log.w(TAG, "read() - não aberto ou arquivo não existe");
            return -1;
        }
        
        long position = dataSpec.position + (dataSpec.length - bytesRemaining);
        
        // Aguarda dados se necessário
        int retries = 0;
        while (videoFile.length() <= position && retries < 30) {
            try { Thread.sleep(200); retries++; } 
            catch (InterruptedException e) { break; }
        }
        
        int bytesToRead = (int) Math.min(length, Math.min(bytesRemaining, videoFile.length() - position));
        if (bytesToRead <= 0) {
            Log.w(TAG, "read() - bytesToRead=" + bytesToRead + " pos=" + position + " fileLen=" + videoFile.length());
            return -1;
        }
        
        try {
            raf.seek(position);
            int bytesRead = raf.read(buffer, offset, bytesToRead);
            
            if (bytesRead > 0) {
                bytesRemaining -= bytesRead;
            }
            
            if (position < 524288) { // Log só nos primeiros 512KB
                Log.d(TAG, "read() pos=" + position + " req=" + bytesToRead + " got=" + bytesRead);
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
        opened = false;
        bytesRemaining = 0;
        if (raf != null) {
            try { raf.close(); } catch (Exception e) {}
            raf = null;
        }
    }
}