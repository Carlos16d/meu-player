package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class TorrentEngine {
    private static final String TAG = "TorrentEngine";
    
    private SessionManager session;
    private torrent_handle currentTorrent;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private EngineListener listener;
    private String savePath;
    
    // Configurações Ace Stream
    private static final int MAX_CONNECTIONS = 50;
    private static final int CACHE_SIZE_MB = 1024; // 1GB
    private static final int PRELOAD_PIECES = 50;
    
    public interface EngineListener {
        void onEngineReady();
        void onEngineError(String error);
        void onMetadata(String name, long size);
        void onProgress(long downloaded, long total, int speed, int peers);
        void onStreamReady(File videoFile);
        void onStatus(String status);
    }
    
    public TorrentEngine(EngineListener listener) {
        this.listener = listener;
    }
    
    public void init(String savePath) {
        this.savePath = savePath;
        new File(savePath).mkdirs();
        
        new Thread(() -> {
            try {
                notifyStatus("Iniciando motor P2P...");
                
                session = new SessionManager();
                Thread.sleep(2000);
                
                if (session != null && session.swig() != null) {
                    applySettings();
                    ready.set(true);
                    mainHandler.post(() -> listener.onEngineReady());
                    notifyStatus("Motor P2P pronto!");
                } else {
                    notifyError("Falha ao iniciar motor P2P");
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro init", e);
                notifyError(e.getMessage());
            }
        }).start();
    }
    
    private void applySettings() {
        settings_pack sp = new settings_pack();
        
        sp.set_int(settings_pack.int_types.connections_limit.swigValue(), MAX_CONNECTIONS);
        sp.set_int(settings_pack.int_types.cache_size.swigValue(), CACHE_SIZE_MB * 1024 * 1024);
        sp.set_int(settings_pack.int_types.active_downloads.swigValue(), 3);
        sp.set_int(settings_pack.int_types.active_seeds.swigValue(), 5);
        sp.set_int(settings_pack.int_types.request_timeout.swigValue(), 3);
        sp.set_int(settings_pack.int_types.peer_timeout.swigValue(), 30);
        sp.set_int(settings_pack.int_types.max_out_request_queue.swigValue(), 10000);
        
        sp.set_bool(settings_pack.bool_types.strict_end_game_mode.swigValue(), true);
        sp.set_bool(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true);
        sp.set_bool(settings_pack.bool_types.prioritize_partial_pieces.swigValue(), true);
        sp.set_bool(settings_pack.bool_types.use_read_cache.swigValue(), true);
        sp.set_bool(settings_pack.bool_types.use_write_cache.swigValue(), true);
        
        session.swig().apply_settings(sp);
    }
    
    public void startDownload(String magnetOrFile) {
        if (!ready.get()) {
            notifyError("Motor não está pronto");
            return;
        }
        
        downloading.set(true);
        
        new Thread(() -> {
            try {
                add_torrent_params params;
                
                if (magnetOrFile.startsWith("magnet:")) {
                    params = libtorrent.parse_magnet_uri(magnetOrFile, new error_code());
                } else {
                    params = add_torrent_params.create_from_file(magnetOrFile);
                }
                
                params.setSave_path(savePath);
                params.setDownload_limit(0);
                params.setUpload_limit(0);
                
                // Prioridade nas primeiras peças (streaming)
                byte_vector priorities = new byte_vector();
                for (int i = 0; i < PRELOAD_PIECES; i++) {
                    priorities.add((byte)7);
                }
                params.set_file_priorities(priorities);
                
                session.swig().async_add_torrent(params);
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() > 0) {
                    currentTorrent = handles.get(0);
                    notifyStatus("Conectado aos peers!");
                    monitorProgress();
                } else {
                    notifyError("Não foi possível iniciar o torrent");
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro download", e);
                notifyError(e.getMessage());
                downloading.set(false);
            }
        }).start();
    }
    
    private void monitorProgress() {
        File videoFile = null;
        
        while (downloading.get()) {
            try {
                Thread.sleep(1000);
                
                if (currentTorrent == null || !currentTorrent.is_valid()) continue;
                
                torrent_status status = currentTorrent.status();
                
                long downloaded = status.get_total_download();
                long total = status.get_total_wanted();
                int speed = status.get_download_rate();
                int peers = status.get_num_peers();
                
                mainHandler.post(() -> listener.onProgress(downloaded, total, speed, peers));
                
                // Procura arquivo de vídeo
                if (videoFile == null) {
                    videoFile = findVideoFile(new File(savePath));
                }
                
                // Streaming pronto com 10MB
                if (videoFile != null && videoFile.length() > 10485760) {
                    File f = videoFile;
                    mainHandler.post(() -> listener.onStreamReady(f));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Erro monitor", e);
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
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$")) {
                    return f;
                }
            }
        }
        return null;
    }
    
    public void stop() {
        downloading.set(false);
        if (currentTorrent != null && session != null && session.swig() != null) {
            try { session.swig().remove_torrent(currentTorrent); } catch (Exception e) {}
        }
    }
    
    public void destroy() {
        stop();
        if (session != null) {
            try { session.stop(); } catch (Exception e) {}
        }
        ready.set(false);
    }
    
    public boolean isReady() { return ready.get(); }
    public boolean isDownloading() { return downloading.get(); }
    
    private void notifyStatus(String msg) { mainHandler.post(() -> listener.onStatus(msg)); }
    private void notifyError(String msg) { mainHandler.post(() -> listener.onError(msg)); }
}
