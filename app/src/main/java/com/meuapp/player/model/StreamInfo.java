package com.meuapp.player.model;

import org.libtorrent4j.swig.byte_vector;
import java.io.File;

/**
 * Armazena informações do stream de torrent.
 * Thread-safe para leitura com volatile.
 */
public class StreamInfo {
    // Dados do torrent
    public volatile int pieceLength;
    public volatile int numPieces;
    public volatile long totalSize;
    
    // Dados do vídeo
    public volatile long videoDurationMs;
    public volatile File videoFile;
    public volatile boolean metadataReady;
    public volatile boolean minuteAppeared;
    
    // Posições do SeekHead (metadados MKV)
    public long cuesPosition = -1;
    public long cuesSize = -1;
    public long tracksPosition = -1;
    public long infoPosition = -1;
    public long tagsPosition = -1;
    
    // Estatísticas de download
    public volatile int seeds;
    public volatile int peers;
    public volatile long downloadRate;
    public volatile long totalDownloaded;
    
    // Cache de byte_vector para performance
    private byte_vector cachedPriorities;
    private int cachedPrioritiesSize;
    
    public void reset() {
        pieceLength = 0;
        numPieces = 0;
        totalSize = 0;
        videoDurationMs = 0;
        videoFile = null;
        metadataReady = false;
        minuteAppeared = false;
        cuesPosition = -1;
        cuesSize = -1;
        tracksPosition = -1;
        infoPosition = -1;
        tagsPosition = -1;
        seeds = 0;
        peers = 0;
        downloadRate = 0;
        totalDownloaded = 0;
        cachedPriorities = null;
        cachedPrioritiesSize = 0;
    }
    
    /**
     * Converte posição em bytes para índice da peça
     */
    public int byteToPiece(long bytePos) {
        if (pieceLength <= 0) return -1;
        return (int)(bytePos / pieceLength);
    }
    
    /**
     * Converte tempo do vídeo (ms) para posição em bytes
     */
    public long timeToByte(long timeMs) {
        if (totalSize <= 0 || videoDurationMs <= 0) return -1;
        return timeMs * totalSize / videoDurationMs;
    }
    
    /**
     * Converte tempo do vídeo (ms) para índice da peça
     */
    public int timeToPiece(long timeMs) {
        long bytePos = timeToByte(timeMs);
        return bytePos >= 0 ? byteToPiece(bytePos) : -1;
    }
    
    /**
     * Converte tamanho para string legível
     */
    public String sizeToString() {
        return (totalSize / 1048576) + "MB";
    }
    
    /**
     * Retorna porcentagem de download
     */
    public int downloadPercent() {
        if (totalSize <= 0) return 0;
        return (int)(totalDownloaded * 100 / totalSize);
    }
    
    /**
     * Retorna byte_vector cached para prioridades (evita recriação)
     */
    public byte_vector getCachedPriorities(int size) {
        if (cachedPriorities == null || cachedPrioritiesSize != size) {
            cachedPriorities = new byte_vector();
            for (int i = 0; i < size; i++) {
                cachedPriorities.add((byte)0);
            }
            cachedPrioritiesSize = size;
        } else {
            // Resetar para zero
            for (int i = 0; i < size; i++) {
                cachedPriorities.set(i, (byte)0);
            }
        }
        return cachedPriorities;
    }
}