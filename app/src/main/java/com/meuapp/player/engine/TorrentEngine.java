package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.meuapp.player.model.TorrentInfo;
import com.meuapp.player.utils.LogUtils;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.File;

public class TorrentEngine {
    private static final String TAG = "TorrentEngine";
    
    private SessionManager session;
    private torrent_handle torrentHandle;
    private boolean ready = false;
    private boolean downloading = false;
    private Handler handler;
    private EngineCallback callback;
    private PeersManager peersManager;
    private TorrentSession torrentSession;
    
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
        this.peersManager = new PeersManager();
        this.torrentSession = new TorrentSession();
    }
    
    public void start(String savePath) {
        new Thread(() -> {
            try {
                notifyStatus("Iniciando engine...");
                LogUtils.d(TAG, "Criando sessão P2P");
                
                session = new SessionManager();
                Thread.sleep(2000);
                
                if (session != null && session.swig() != null) {
                    torrentSession.applySettings(session);
                    ready = true;
                    notifyReady();
                } else {
                    notifyError("Sessão P2P falhou");
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "Erro engine", e);
                notifyError(e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String source, String savePath) {
        if (!ready) {
            notifyError("Engine não está pronta");
            return;
        }
        
        downloading = true;
        
        new Thread(() -> {
            try {
                add_torrent_params params;
                
                if (source.startsWith("magnet:")) {
                    params = libtorrent.parse_magnet_uri(source, new error_code());
                } else {
                    params = add_torrent_params.create_from_file(source);
                }
                
                params.setSave_path(savePath);
                params.setDownload_limit(0);
                params.setUpload_limit(0);
                
                byte_vector priorities = new byte_vector();
                for (int i = 0; i < 50; i++) priorities.add((byte)7);
                params.set_file_priorities(priorities);
                
                session.swig().async_add_torrent(params);
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() > 0) {
                    torrentHandle = handles.get(0);
                    notifyStatus("✅ Conectado!");
                    monitorProgress(savePath);
                }
            } catch (Exception e) {
                LogUtils.e(TAG, "Erro download", e);
                notifyError(e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void monitorProgress(String savePath) {
        File videoFile = null;
        
        while (downloading) {
            try {
                Thread.sleep(1000);
                
                if (torrentHandle == null || !torrentHandle.is_valid()) continue;
                
                torrent_status status = torrentHandle.status();
                
                TorrentInfo info = new TorrentInfo();
                info.downloaded = status.get_total_download();
                info.total = status.get_total_wanted();
                info.speed = status.get_download_rate();
                info.peers = status.get_num_peers();
                info.seeds = status.get_num_seeds();
                info.progress = info.total > 0 ? (int)(info.downloaded * 100 / info.total) : 0;
                
                handler.post(() -> callback.onProgress(info));
                
                peersManager.update(status);
                
                if (videoFile == null) {
                    videoFile = findVideoFile(new File(savePath));
                }
                
                if (videoFile != null && videoFile.length() > 10485760) {
                    File f = videoFile;
                    handler.post(() -> callback.onStreamReady(f));
                }
                
            } catch (Exception e) {
                LogUtils.e(TAG, "Erro monitor", e);
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
        downloading = false;
        if (torrentHandle != null && session != null && session.swig() != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
            torrentHandle = null;
        }
    }
    
    public void destroy() {
        stop();
        if (session != null) {
            try { session.stop(); } catch (Exception e) {}
            session = null;
        }
        ready = false;
    }
    
    public boolean isReady() { return ready; }
    
    private void notifyReady() { handler.post(() -> callback.onReady()); }
    private void notifyError(String msg) { handler.post(() -> callback.onError(msg)); }
    private void notifyStatus(String msg) { handler.post(() -> callback.onStatus(msg)); }
}