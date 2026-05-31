package com.seuapp;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Motor Torrent usando JNI nativo (libtorrent)
 * Suporte real a UDP, DHT, trackers tradicionais
 */
public class TorrentEngine {
    private static final String TAG = "TorrentEngine";
    
    static {
        try {
            System.loadLibrary("torrent");
            Log.d(TAG, "libtorrent carregada com sucesso!");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Erro ao carregar libtorrent: " + e.getMessage());
        }
    }
    
    private Context context;
    private long sessionPtr = 0;
    private long torrentPtr = 0;
    private String savePath;
    private boolean running = false;
    private ExecutorService executor;
    
    // Métodos nativos JNI
    private native long nativeCreateSession(String listenAddr, int portStart, int portEnd);
    private native long nativeAddMagnet(long sessionPtr, String magnet, String savePath);
    private native void nativeRemoveTorrent(long sessionPtr, long torrentPtr);
    private native float nativeGetProgress(long torrentPtr);
    private native int nativeGetPeers(long torrentPtr);
    private native long nativeGetDownloadSpeed(long torrentPtr);
    private native byte[] nativeReadPiece(long torrentPtr, int pieceIndex);
    private native int nativeGetNumPieces(long torrentPtr);
    private native long nativeGetTotalSize(long torrentPtr);
    private native void nativePause(long torrentPtr);
    private native void nativeResume(long torrentPtr);
    private native void nativeDestroy(long sessionPtr);
    private native long nativeGetFileOffset(long torrentPtr, int fileIndex);
    private native long nativeGetFileSize(long torrentPtr, int fileIndex);
    private native int nativeGetNumFiles(long torrentPtr);
    
    public TorrentEngine(Context context) {
        this.context = context;
        this.savePath = new File(context.getExternalFilesDir(null), "torrents")
            .getAbsolutePath();
        new File(savePath).mkdirs();
        this.executor = Executors.newSingleThreadExecutor();
    }
    
    public void start() {
        running = true;
        executor.execute(() -> {
            try {
                sessionPtr = nativeCreateSession("0.0.0.0", 6881, 6889);
                Log.d(TAG, "Sessão torrent criada com UDP/DHT: " + sessionPtr);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao criar sessão: " + e.getMessage());
            }
        });
    }
    
    public void addMagnet(String magnetURI) {
        executor.execute(() -> {
            try {
                // Remove torrent anterior se existir
                if (torrentPtr != 0 && sessionPtr != 0) {
                    nativeRemoveTorrent(sessionPtr, torrentPtr);
                }
                
                // Adiciona novo magnet
                torrentPtr = nativeAddMagnet(sessionPtr, magnetURI, savePath);
                Log.d(TAG, "Magnet adicionado: " + torrentPtr);
                
                // Aguarda metadados
                Thread.sleep(2000);
                
            } catch (Exception e) {
                Log.e(TAG, "Erro ao adicionar magnet: " + e.getMessage());
            }
        });
    }
    
    public float getProgress() {
        if (torrentPtr != 0) {
            try {
                return nativeGetProgress(torrentPtr);
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }
    
    public int getPeers() {
        if (torrentPtr != 0) {
            try {
                return nativeGetPeers(torrentPtr);
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }
    
    public long getDownloadSpeed() {
        if (torrentPtr != 0) {
            try {
                return nativeGetDownloadSpeed(torrentPtr);
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }
    
    public long getTotalSize() {
        if (torrentPtr != 0) {
            try {
                return nativeGetTotalSize(torrentPtr);
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }
    
    public byte[] readPiece(int pieceIndex) {
        if (torrentPtr != 0) {
            try {
                return nativeReadPiece(torrentPtr, pieceIndex);
            } catch (Exception e) {
                return new byte[0];
            }
        }
        return new byte[0];
    }
    
    public int getNumPieces() {
        if (torrentPtr != 0) {
            try {
                return nativeGetNumPieces(torrentPtr);
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }
    
    public void pause() {
        if (torrentPtr != 0) {
            nativePause(torrentPtr);
        }
    }
    
    public void resume() {
        if (torrentPtr != 0) {
            nativeResume(torrentPtr);
        }
    }
    
    public void stop() {
        if (torrentPtr != 0 && sessionPtr != 0) {
            nativeRemoveTorrent(sessionPtr, torrentPtr);
            torrentPtr = 0;
        }
    }
    
    public void shutdown() {
        running = false;
        stop();
        if (sessionPtr != 0) {
            nativeDestroy(sessionPtr);
            sessionPtr = 0;
        }
        executor.shutdown();
    }
}
