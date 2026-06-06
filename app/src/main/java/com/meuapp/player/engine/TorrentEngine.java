package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;

import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.torrent_handle;
import org.libtorrent4j.swig.torrent_handle_vector;

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
                
                session = new SessionManager();
                session.start(new SessionParams());
                
                ready = true;
                notifyReady();
                notifyStatus("Motor P2P pronto!");
                
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
                
                File saveDir = new File(savePath);
                
                // Metodo correto: download(String, File)
                session.download(magnetUri, saveDir);
                
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
        
        while (downloading) {
            try {
                Thread.sleep(1000);
                
                TorrentInfo info = new TorrentInfo();
                
                if (session != null && session.swig() != null) {
                    torrent_handle_vector handles = session.swig().get_torrents();
                    if (handles.size() > 0) {
                        torrent_handle th = handles.get(0);
                        if (th.is_valid()) {
                            TorrentHandle torrentHandle = new TorrentHandle(th);
                            TorrentStatus status = torrentHandle.status();
                            
                            info.progress = (int)(status.progress() * 100);
                            info.downloaded = status.totalDone();
                            info.total = status.total();
                            info.speed = status.downloadRate();
                            info.peers = status.numPeers();
                            info.seeds = status.numSeeds();
                        }
                    }
                }
                
                if (info.progress == 0) {
                    info.progress = 5;
                }
                
                handler.post(() -> callback.onProgress(info));
                
                if (videoFile == null) {
                    videoFile = findVideoFile(new File(savePath));
                }
                
                if (videoFile != null && videoFile.length() > 5242880) {
                    File f = videoFile;
                    handler.post(() -> callback.onStreamReady(f));
                    break;
                }
                
            } catch (Exception e) {
                // continua tentando
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
