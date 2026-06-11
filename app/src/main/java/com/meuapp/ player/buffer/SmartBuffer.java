package com.meuapp.player.buffer;

import com.meuapp.player.model.StreamInfo;
import com.meuapp.player.torrent.TorrentStreamer;

/**
 * Buffer inteligente: baixa 10 peças, para,
 * quando só tiver 5 de buffer, baixa mais 10.
 */
public class SmartBuffer {
    private final TorrentStreamer streamer;
    private final StreamInfo info;
    
    private int lastBufferedEnd = -1;
    private boolean enabled = false;
    
    public SmartBuffer(TorrentStreamer streamer, StreamInfo info) {
        this.streamer = streamer;
        this.info = info;
    }
    
    public void enable() {
        enabled = true;
    }
    
    public void disable() {
        enabled = false;
    }
    
    /**
     * Verifica e mantém o buffer baseado na posição atual do VLC
     */
    public void checkAndBuffer(long currentTimeMs) {
        if (!enabled || !info.metadataReady) return;
        
        int currentPiece = info.timeToPiece(currentTimeMs);
        if (currentPiece < 0 || currentPiece >= info.numPieces) return;
        
        streamer.maintainBuffer(currentPiece);
    }
    
    /**
     * Força download de um range específico (usado após seek)
     */
    public void forceRange(int startPiece, int count) {
        int end = Math.min(info.numPieces - 1, startPiece + count - 1);
        streamer.maintainBuffer(startPiece);
    }
}
