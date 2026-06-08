package com.meuapp.player.server;

import android.net.Uri;
import android.util.Log;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.libtorrent4j.swig.*;

import java.io.*;
import java.util.*;

public class TorrentDataSource implements DataSource {
    private static final String TAG = "TorrentDataSource";
    private torrent_handle torrentHandle;
    private File videoFile;
    private DataSpec dataSpec;
    private long bytesRemaining;
    private boolean opened;
    private Uri uri;
    
    public TorrentDataSource() {}
    
    public void setTorrentHandle(torrent_handle handle) {
        this.torrentHandle = handle;
    }
    
    public void setVideoFile(File file) {
        this.videoFile = file;
    }
    
    @Override
    public void addTransferListener(TransferListener transferListener) {}
    
    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        this.opened = true;
        this.uri = dataSpec.uri;
        
        long position = dataSpec.position;
        long length = dataSpec.length;
        
        if (length == -1) { // C.LENGTH_UNSET = -1
            length = videoFile != null ? videoFile.length() - position : 0;
        }
        
        this.bytesRemaining = length;
        
        Log.d(TAG, "open() pos=" + position + " len=" + length);
        
        // Força prioridade
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
                    Log.d(TAG, "🔥 Peças " + rs + "-" + re);
                }
            } catch (Exception e) {}
        }
        
        return length;
    }
    
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (!opened || videoFile == null || !videoFile.exists()) {
            return -1; // C.RESULT_END_OF_INPUT
        }
        
        long position = dataSpec.position + (dataSpec.length - bytesRemaining);
        
        // Aguarda dados
        int retries = 0;
        while (videoFile.length() <= position && retries < 20) {
            try { Thread.sleep(200); retries++; } 
            catch (InterruptedException e) { break; }
        }
        
        int bytesToRead = (int) Math.min(length, Math.min(bytesRemaining, videoFile.length() - position));
        if (bytesToRead <= 0) return -1;
        
        try {
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(position);
            int bytesRead = raf.read(buffer, offset, bytesToRead);
            raf.close();
            
            if (bytesRead > 0) bytesRemaining -= bytesRead;
            
            if (position < 1048576) {
                Log.d(TAG, "read() pos=" + position + " bytes=" + bytesRead);
            }
            
            return bytesRead;
        } catch (Exception e) {
            Log.e(TAG, "Erro read: " + e.getMessage());
            return -1;
        }
    }
    
    @Override
    public Uri getUri() { return uri; }
    
    @Override
    public void close() throws IOException {
        opened = false;
        bytesRemaining = 0;
    }
}