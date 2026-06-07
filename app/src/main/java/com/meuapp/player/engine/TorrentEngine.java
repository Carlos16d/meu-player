package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
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
    private String currentSavePath;
    
    public interface EngineCallback {
        void onReady();
        void onError(String error);
        void onProgress(TorrentInfo info);
        void onStreamReady(torrent_handle handle, String savePath);
        void onStatus(String status);
        void onLog(String log);
    }
    
    public TorrentEngine(EngineCallback callback) {
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());
    }
    
    private void log(String msg) {
        Log.d(TAG, msg);
        handler.post(() -> callback.onLog(msg));
    }
    
    public void start() {
        new Thread(() -> {
            try {
                log("Iniciando engine...");
                session = new SessionManager();
                session.start(new SessionParams());
                ready = true;
                log("Engine pronto!");
                notifyReady();
            } catch (Exception e) {
                log("ERRO: " + e.getMessage());
                notifyError(e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) { notifyError("Aguarde..."); return; }
        downloading = true;
        currentSavePath = savePath;
        
        new Thread(() -> {
            try {
                log("Conectando ao tracker...");
                File saveDir = new File(savePath);
                deleteRecursive(saveDir);
                saveDir.mkdirs();
                
                session.download(magnetUri, saveDir, new torrent_flags_t());
                
                log("Aguardando torrent...");
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() == 0) {
                    notifyError("Nenhum peer");
                    downloading = false;
                    return;
                }
                
                torrentHandle = handles.get(0);
                
                torrent_status st = torrentHandle.status();
                int w = 0;
                while (!st.getHas_metadata() && w < 60 && downloading) {
                    Thread.sleep(1000);
                    w++;
                    st = torrentHandle.status();
                }
                
                if (!st.getHas_metadata()) {
                    notifyError("Timeout");
                    downloading = false;
                    return;
                }
                
                long totalSize = st.getTotal();
                torrent_info ti = torrentHandle.torrent_file_ptr();
                int numPieces = ti != null ? ti.num_pieces() : 100;
                
                log("Torrent: " + (totalSize/1048576) + "MB, " + numPieces + " peças");
                
                // Download sequencial
                for (int i = 0; i < numPieces; i++) {
                    torrentHandle.piece_priority_ex(i, (byte)(i < 100 ? 7 : 1));
                }
                for (int i = 0; i < Math.min(50, numPieces); i++) {
                    torrentHandle.set_piece_deadline(i, 2000);
                }
                
                // Aguarda peças iniciais
                int target = Math.min(10, numPieces);
                while (downloading) {
                    Thread.sleep(500);
                    
                    int complete = 0;
                    for (int i = 0; i < target; i++) {
                        if (torrentHandle.have_piece(i)) complete++;
                    }
                    
                    st = torrentHandle.status();
                    TorrentInfo info = new TorrentInfo();
                    info.progress = (complete * 100) / target;
                    info.downloaded = st.getTotal_done();
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    handler.post(() -> callback.onProgress(info));
                    
                    if (complete >= target) {
                        log("Streaming pronto! " + complete + "/" + target + " peças");
                        handler.post(() -> callback.onStreamReady(torrentHandle, currentSavePath));
                        break;
                    }
                }
                
            } catch (Exception e) {
                log("ERRO: " + e.getMessage());
                notifyError(e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void deleteRecursive(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) deleteRecursive(f);
                    else f.delete();
                }
            }
        }
    }
    
    public void stop() { 
        downloading = false;
        deleteRecursive(new File(currentSavePath));
        if (session != null) try { session.stop(); } catch (Exception e) {} 
    }
    
    public void destroy() { stop(); if (session != null) try { session.stop(); } catch (Exception e) {} }
    public boolean isReady() { return ready; }
    private void notifyReady() { handler.post(() -> callback.onReady()); }
    private void notifyError(String msg) { handler.post(() -> callback.onError(msg)); }
}