package com.meuapp.player.model;

public class StreamInfo {
    public String url;
    public String mimeType;
    public long contentLength;
    public boolean isReady;
    public int bufferPercent;
    public String videoFileName;
    
    // Informações de faixas
    public int audioTrackCount;
    public int subtitleTrackCount;
    public String[] audioLanguages;
    public String[] subtitleLanguages;
    
    // Estatísticas
    public long bytesDownloaded;
    public long bytesTotal;
    public int downloadSpeed;
    public int connectedPeers;
    
    public StreamInfo() {
        this.isReady = false;
        this.bufferPercent = 0;
        this.audioTrackCount = 0;
        this.subtitleTrackCount = 0;
    }
    
    public String getStatusMessage() {
        if (isReady) {
            return "🎬 Pronto para streaming";
        } else {
            return "⬇️ Baixando... " + bufferPercent + "%";
        }
    }
    
    public boolean hasMultipleAudio() {
        return audioTrackCount > 1;
    }
    
    public boolean hasSubtitles() {
        return subtitleTrackCount > 0;
    }
}
