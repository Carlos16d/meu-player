package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
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
        try { handler.post(() -> callback.onLog(msg)); } catch (Exception e) {}
    }
    
    public void start() {
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start(new SessionParams());
                try {
                    SettingsPack sp = new SettingsPack();
                    sp.activeDownloads(2);
                    sp.activeSeeds(2);
                    sp.connectionsLimit(30);
                    sp.downloadRateLimit(2097152);
                    sp.uploadRateLimit(524288);
                    session.applySettings(sp);
                } catch (Exception e) {}
                ready = true;
                log("Engine pronto!");
                try { handler.post(() -> callback.onReady()); } catch (Exception e) {}
            } catch (Exception e) {
                log("ERRO: " + e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) return;
        downloading = true;
        currentSavePath = savePath;
        
        new Thread(() -> {
            try {
                File saveDir = new File(savePath);
                try { if (saveDir.exists()) { File[] files = saveDir.listFiles(); if (files != null) for (File f : files) deleteRecursive(f); } } catch (Exception e) {}
                saveDir.mkdirs();
                
                session.download(magnetUri, saveDir, new torrent_flags_t());
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() == 0) { downloading = false; return; }
                
                torrentHandle = handles.get(0);
                torrent_status st = torrentHandle.status();
                int w = 0;
                while (!st.getHas_metadata() && w < 60 && downloading) {
                    Thread.sleep(1000); w++; st = torrentHandle.status();
                }
                if (!st.getHas_metadata()) { downloading = false; return; }
                
                long totalSize = st.getTotal();
                int numPieces = 100;
                try {
                    torrent_info ti = torrentHandle.torrent_file_ptr();
                    if (ti != null && ti.is_valid()) numPieces = ti.num_pieces();
                    else numPieces = st.getNum_pieces();
                } catch (Exception e) {}
                if (numPieces <= 0) numPieces = 100;
                
                log("Torrent: " + (totalSize/1048576) + "MB, " + numPieces + " peças");
                
                // ATIVA TODAS as peças (não ignora nenhuma)
                try {
                    for (int i = 0; i < numPieces; i++)
                        torrentHandle.piece_priority_ex(i, (byte)4); // Todas prioridade NORMAL
                    // Primeiras 100 com prioridade ALTA
                    for (int i = 0; i < Math.min(100, numPieces); i++)
                        torrentHandle.piece_priority_ex(i, (byte)7);
                    for (int i = 0; i < Math.min(100, numPieces); i++)
                        torrentHandle.set_piece_deadline(i, 2000);
                } catch (Exception e) {}
                
                // Aguarda primeiras peças e depois libera
                int target = Math.min(10, numPieces);
                boolean streamReady = false;
                
                while (downloading) {
                    Thread.sleep(500);
                    
                    int complete = 0;
                    for (int i = 0; i < target; i++)
                        if (torrentHandle.have_piece(i)) complete++;
                    
                    st = torrentHandle.status();
                    TorrentInfo info = new TorrentInfo();
                    info.progress = (complete * 100) / target;
                    info.downloaded = st.getTotal_done();
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    try { handler.post(() -> callback.onProgress(info)); } catch (Exception e) {}
                    
                    // Libera streaming e CONTINUA baixando
                    if (complete >= target && !streamReady) {
                        streamReady = true;
                        log("Streaming liberado! Download continua...");
                        final String sp = currentSavePath;
                        final torrent_handle th = torrentHandle;
                        try { handler.post(() -> callback.onStreamReady(th, sp)); } catch (Exception e) {}
                    }
                    
                    // Atualiza prioridades conforme baixa
                    if (streamReady && pieceLength > 0) {
                        long downloaded = st.getTotal_done();
                        int currentPiece = (int)(downloaded / (totalSize / Math.max(numPieces, 1)));
                        // Mantém prioridade nas próximas peças
                        for (int i = currentPiece; i < Math.min(currentPiece + 50, numPieces); i++) {
                            torrentHandle.piece_priority_ex(i, (byte)7);
                        }
                    }
                }
            } catch (Exception e) {
                log("ERRO: " + e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void deleteRecursive(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) {
                if (f.isDirectory()) deleteRecursive(f); else f.delete();
            }
        }
    }
    
    public void stop() { downloading = false; if (session != null) try { session.stop(); } catch (Exception e) {} }
    public void destroy() { stop(); if (session != null) try { session.stop(); } catch (Exception e) {} }
    public boolean isReady() { return ready; }
}