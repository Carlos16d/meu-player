package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;

import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;

public class TorrentEngine {
    private SessionManager session;
    private torrent_handle torrentHandle;
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
    
    public void start(String savePath) {
        new Thread(() -> {
            try {
                notifyStatus("Iniciando engine...");
                session = new SessionManager();
                Thread.sleep(2000);
                
                if (session != null && session.swig() != null) {
                    ready = true;
                    notifyReady();
                } else {
                    notifyError("Sessao P2P falhou");
                }
            } catch (Exception e) {
                notifyError(e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String source, String savePath) {
        if (!ready) { notifyError("Engine nao pronta"); return; }
        
        downloading = true;
        
        new Thread(() -> {
            try {
                add_torrent_params params = null;
                
                if (source.startsWith("magnet:")) {
                    params = libtorrent.parse_magnet_uri(source, new error_code());
                } else {
                    // Arquivo .torrent - usa file
                    params = add_torrent_params.create_from_file(source);
                }
                
                if (params != null) {
                    params.setSave_path(savePath);
                    params.setDownload_limit(0);
                    params.setUpload_limit(0);
                    
                    session.swig().async_add_torrent(params);
                    Thread.sleep(3000);
                    
                    torrent_handle_vector handles = session.swig().get_torrents();
                    if (handles.size() > 0) {
                        torrentHandle = handles.get(0);
                        notifyStatus("Conectado!");
                        monitorProgress(savePath);
                    }
                }
            } catch (Exception e) {
                notifyError(e.getMessage());
            }
        }).start();
    }
    
    private void monitorProgress(String savePath) {
        File videoFile = null;
        int progress = 0;
        
        while (downloading) {
            try {
                Thread.sleep(1000);
                if (torrentHandle == null || !torrentHandle.is_valid()) continue;
                
                // Apenas atualiza progresso simples
                progress = Math.min(progress + 1, 99);
                
                TorrentInfo info = new TorrentInfo();
                info.progress = progress;
                info.peers = 0;
                info.seeds = 0;
                
                handler.post(() -> callback.onProgress(info));
                
                if (videoFile == null) {
                    videoFile = findVideoFile(new File(savePath));
                }
                
                if (videoFile != null && videoFile.length() > 5242880) {
                    File f = videoFile;
                    handler.post(() -> callback.onStreamReady(f));
                }
                
            } catch (Exception e) {}
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
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$")) {
                    return f;
                }
            }
        }
        return null;
    }
    
    public void stop() {
        downloading = false;
        if (torrentHandle != null && session != null && session.swig() != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
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