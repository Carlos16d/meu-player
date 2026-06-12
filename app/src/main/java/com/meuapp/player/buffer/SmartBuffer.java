package com.meuapp.player.buffer;

import com.meuapp.player.model.StreamInfo;
import com.meuapp.player.torrent.TorrentStreamer;

public class SmartBuffer {
    private final TorrentStreamer streamer;
    private final StreamInfo info;
    private boolean enabled = false;
    
    public SmartBuffer(TorrentStreamer streamer, StreamInfo info) {
        this.streamer = streamer;
        this.info = info;
    }
    
    public void enable() {
        this.enabled = true;
    }
    
    public void disable() {
        this.enabled = false;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void checkAndBuffer(long currentTimeMs) {
        if (!enabled || !info.metadataReady) return;
        int currentPiece = info.timeToPiece(currentTimeMs);
        if (currentPiece < 0 || currentPiece >= info.numPieces) return;
        streamer.maintainBuffer(currentPiece);
    }
}