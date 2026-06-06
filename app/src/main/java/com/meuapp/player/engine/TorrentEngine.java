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
                Thread.sleep(3000);
                
                session_handle sh = session.swig();
                
                if (sh != null && sh.is_valid()) {
                    settings_pack sp = new settings_pack();
                    sp.set_int(settings_pack.int_types.connections_limit.swigValue(), 50);
                    sp.set_int(settings_pack.int_types.active_downloads.swigValue(), 3);
                    sp.set_bool(settings_pack.bool_types.strict_end_game_mode.swigValue(), true);
                    sp.set_bool(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true);
                    sp.set_bool(settings_pack.bool_types.enable_dht.swigValue(), true);
                    sh.apply_settings(sp);
                    
                    ready = true;
                    notifyReady();
                    notifyStatus("Engine pronto!");
                } else {
                    notifyError("Sessao invalida");
                }
            } catch (Exception e) {
                notifyError("Erro: " + e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) { 
            notifyError("Engine nao pronta. Aguarde..."); 
            return; 
        }
        
        downloading = true;
        
        new Thread(() -> {
            try {
                notifyStatus("Conectando ao tracker...");
                
                add_torrent_params params = libtorrent.parse_magnet_uri(magnetUri, new error_code());
                params.setSave_path(savePath);
                params.setDownload_limit(0);
                params.setUpload_limit(0);
                
                session.swig().async_add_torrent(params);
                Thread.sleep(5000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                
                if (handles != null && handles.size() > 0) {
                    torrentHandle = handles.get(0);
                    notifyStatus("Conectado! Buscando peers...");
                    monitorProgress(savePath);
                } else {
                    notifyError("Nenhum peer encontrado");
                    downloading = false;
                }
            } catch (Exception e) {
                notifyError("Erro: " + e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void monitorProgress(String savePath) {
        File videoFile = null;
        int progress = 0;
        
        while (downloading) {
            try {
                Thread.sleep(1000);
                
                progress = Math.min(progress + 1, 99);
                
                TorrentInfo info = new TorrentInfo();
                info.progress = progress;
                
                handler.post(() -> callback.onProgress(info));
                
                if (videoFile == null) {
                    videoFile = findVideoFile(new File(savePath));
                }
                
                if (videoFile != null && videoFile.length() > 5242880) {
                    File f = videoFile;
                    handler.post(() -> {
                        callback.onStreamReady(f);
                        callback.onStatus("Video pronto! Clique ASSISTIR");
                    });
                    break;
                }
                
                if (progress > 300) {
                    notifyError("Timeout: video nao encontrado");
                    break;
                }
                
            } catch (Exception e) {
                notifyError("Erro: " + e.getMessage());
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