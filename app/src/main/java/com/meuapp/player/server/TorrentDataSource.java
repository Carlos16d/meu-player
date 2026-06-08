package com.meuapp.player.server;

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
        
        long position = dataSpec.position;
        long length = dataSpec.length;
        
        if (length == DataSpec.LENGTH_UNSET) {
            length = videoFile != null ? videoFile.length() - position : 0;
        }
        
        this.bytesRemaining = length;
        
        Log.d(TAG, "open() position=" + position + " length=" + length + " fileSize=" + (videoFile != null ? videoFile.length() : 0));
        
        // Força prioridade nas peças necessárias
        if (torrentHandle != null && torrentHandle.is_valid() && position > 0) {
            try {
                torrent_info ti = torrentHandle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    int pieceLength = ti.piece_length();
                    int numPieces = ti.num_pieces();
                    int targetPiece = (int)(position / pieceLength);
                    int rangeStart = Math.max(0, targetPiece - 5);
                    int rangeEnd = Math.min(numPieces, targetPiece + 60);
                    
                    torrentHandle.set_sequential_range(rangeStart, rangeEnd);
                    
                    for (int i = 0; i < numPieces; i++) {
                        torrentHandle.piece_priority_ex(i, (byte)0);
                    }
                    for (int i = rangeStart; i < rangeEnd; i++) {
                        torrentHandle.piece_priority_ex(i, (byte)7);
                        torrentHandle.set_piece_deadline(i, 500);
                    }
                    
                    Log.d(TAG, "🔥 Prioridade: peças " + rangeStart + "-" + rangeEnd + " (pos " + (position/1048576) + "MB)");
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro prioridade: " + e.getMessage());
            }
        }
        
        return length;
    }
    
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (!opened || videoFile == null || !videoFile.exists()) {
            return DataSource.RESULT_END_OF_INPUT;
        }
        
        long position = dataSpec.position + (dataSpec.length - bytesRemaining);
        
        // Aguarda dados se necessário
        int retries = 0;
        while (videoFile.length() <= position && retries < 20) {
            try {
                Thread.sleep(200);
                retries++;
            } catch (InterruptedException e) {
                break;
            }
        }
        
        int bytesToRead = (int) Math.min(length, Math.min(bytesRemaining, videoFile.length() - position));
        if (bytesToRead <= 0) {
            return DataSource.RESULT_END_OF_INPUT;
        }
        
        try {
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(position);
            int bytesRead = raf.read(buffer, offset, bytesToRead);
            raf.close();
            
            if (bytesRead > 0) {
                bytesRemaining -= bytesRead;
            }
            
            if (bytesRead > 0 && position < 1048576) {
                Log.d(TAG, "read() pos=" + position + " bytes=" + bytesRead + " remaining=" + bytesRemaining);
            }
            
            return bytesRead;
            
        } catch (Exception e) {
            Log.e(TAG, "Erro read: " + e.getMessage());
            return DataSource.RESULT_END_OF_INPUT;
        }
    }
    
    @Override
    public Uri getUri() {
        return dataSpec != null ? dataSpec.uri : null;
    }
    
    @Override
    public void close() throws IOException {
        opened = false;
        bytesRemaining = 0;
    }
}