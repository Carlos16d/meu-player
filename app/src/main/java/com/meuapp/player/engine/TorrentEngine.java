package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;

import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;

public class TorrentEngine {
    private SessionManager session;
    private boolean ready = false;
    private boolean downloading = false;
    private Handler handler;
    private EngineCallback callback;
    
    public interface EngineCallback {
        void onReady();
        void onError(String error);
        void onProgress(TorrentInfo info);
        void onStreamReady(File videoFile);
        void onStatus(String status);
    }
    
    public TorrentEngine(EngineCallback callback) {
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());
    }
    
    public void start() {
        new Thread(() -> {
            try {
                notifyStatus("Iniciando motor P2P...");
                
                System.loadLibrary("torrent4j");
                
                session = new SessionManager();
                
                Thread.sleep(4000);
                
                if (session != null) {
                    ready = true;
                    notifyReady();
                    notifyStatus("Motor P2P pronto!");
                } else {
                    notifyError("Falha ao criar sessao");
                }
                
            } catch (Exception e) {
                notifyError("Erro: " + e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) { 
            notifyError("Aguarde o motor iniciar..."); 
            return; 
        }
        
        downloading = true;
        
        new Thread(() -> {
            try {
                notifyStatus("Conectando ao tracker...");
                
                session.download(magnetUri, savePath);
                
                notifyStatus("Download iniciado! Aguardando dados...");
                
                monitorProgress(savePath);
                
            } catch (Exception e) {
                notifyError("Erro: " + e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void monitorProgress(String savePath) {
        File videoFile = null;
        int seconds = 0;
        
        while (downloading) {
            try {
                Thread.sleep(1000);
                seconds++;
                
                TorrentInfo info = new TorrentInfo();
                info.progress = Math.min(seconds, 99);
                
                handler.post(() -> callback.onProgress(info));
                
                if (videoFile == null) {
                    videoFile = findVideoFile(new File(savePath));
                }
                
                if (videoFile != null && videoFile.length() > 5242880) {
                    File f = videoFile;
                    handler.post(() -> callback.onStreamReady(f));
                    break;
                }
                
                if (seconds > 300) {
                    notifyError("Timeout - arquivo nao encontrado");
                    break;
                }
                
            } catch (Exception e) {
                break;
            }
        }
    }
    
    private File findVideoFile(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideoFile(f);
                    if (found != null) return found;
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }
    
    public void stop() {
        downloading = false;
        if (session != null) {
            try { session.stop(); } catch (Exception e) {}
        }
    }
    
    public void destroy() {
        stop();
        if (session != null) {
            try { session.stop(); } catch (Exception e) {}
        }
    }
    
    public boolean isReady() { return ready; }
    
    private void notifyReady() { handler.post(() -> callback.onReady()); }
    private void notifyError(String msg) { handler.post(() -> callback.onError(msg)); }
    private void notifyStatus(String msg) { handler.post(() -> callback.onStatus(msg)); }
}