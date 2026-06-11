package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;

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
    private int pieceLength = 0;
    private int numPieces = 0;
    private long totalSize = 0;
    
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
        try { handler.post(() -> callback.onLog(msg)); } catch (Exception e) {}
    }
    
    public void start() {
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start(new SessionParams());
                try {
                    SettingsPack sp = new SettingsPack();
                    sp.activeDownloads(2); sp.activeSeeds(2);
                    sp.connectionsLimit(30);
                    sp.downloadRateLimit(3145728); sp.uploadRateLimit(524288);
                    session.applySettings(sp);
                } catch (Exception e) {}
                ready = true;
                log("Engine pronto! Limite: 3MB/s");
                try { handler.post(() -> callback.onReady()); } catch (Exception e) {}
            } catch (Exception e) { log("ERRO: " + e.getMessage()); }
        }).start();
    }
    
    public void startDownload(String source, String savePath) {
        if (!ready) return;
        downloading = true;
        currentSavePath = savePath;
        
        new Thread(() -> {
            try {
                File saveDir = new File(savePath);
                if (saveDir.exists()) {
                    File[] files = saveDir.listFiles();
                    if (files != null) for (File f : files) {
                        if (f.isDirectory()) deleteRecursive(f);
                        else f.delete();
                    }
                }
                saveDir.mkdirs();
                
                if (source.startsWith("magnet:")) {
                    add_torrent_params p = libtorrent.parse_magnet_uri(source, new error_code());
                    p.setSave_path(savePath);
                    torrent_flags_t flags = libtorrent.getAuto_managed().or_(libtorrent.getSequential_download()).or_(libtorrent.getApply_ip_filter());
                    p.setFlags(flags);
                    p.setDownload_limit(3*1024*1024);
                    session.swig().async_add_torrent(p);
                } else {
                    add_torrent_params params = add_torrent_params.load_torrent_file(source, new error_code());
                    params.setSave_path(savePath);
                    torrent_flags_t flags = libtorrent.getAuto_managed().or_(libtorrent.getSequential_download()).or_(libtorrent.getApply_ip_filter());
                    params.setFlags(flags);
                    params.setDownload_limit(3*1024*1024);
                    session.swig().async_add_torrent(params);
                }
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
                
                totalSize = st.getTotal();
                try {
                    torrent_info ti = torrentHandle.torrent_file_ptr();
                    if (ti != null && ti.is_valid()) {
                        numPieces = ti.num_pieces();
                        pieceLength = ti.piece_length();
                    }
                } catch (Exception e) {}
                
                log("📊 " + (totalSize/1048576) + "MB, " + numPieces + " peças, " + st.getNum_peers() + " peers");
                
                // Cabeçalho + cues
                int headerPieces = Math.min(30, Math.max(20, numPieces / 30));
                int cuePieces = Math.min(30, Math.max(10, numPieces / 20));
                int cueStart = Math.max(0, numPieces - cuePieces);
                
                log("📋 " + headerPieces + " cabeçalho + " + cuePieces + " cues = " + (headerPieces + cuePieces) + " peças");
                
                // Prioridade MÁXIMA para cabeçalho+cues
                try {
                    for (int i = 0; i < numPieces; i++) {
                        if (i < headerPieces || i >= cueStart)
                            torrentHandle.piece_priority_ex(i, (byte)7);
                        else
                            torrentHandle.piece_priority_ex(i, (byte)0);
                    }
                    for (int i = 0; i < headerPieces; i++)
                        torrentHandle.set_piece_deadline(i, 500);
                    for (int i = cueStart; i < numPieces; i++)
                        torrentHandle.set_piece_deadline(i, 500);
                } catch (Exception e) {}
                
                // Aguardar download REAL (usando getTotal_done)
                long lastDone = 0;
                int sameCount = 0;
                boolean streamReady = false;
                
                while (downloading) {
                    Thread.sleep(1000);
                    if (torrentHandle == null || !torrentHandle.is_valid()) break;
                    
                    st = torrentHandle.status();
                    long done = st.getTotal_done();
                    int pct = totalSize > 0 ? (int)(done * 100 / totalSize) : 0;
                    
                    // Verificar se o download está progredindo
                    if (done == lastDone) {
                        sameCount++;
                    } else {
                        sameCount = 0;
                        lastDone = done;
                        log("📥 " + (done/1048576) + "MB / " + (totalSize/1048576) + "MB (" + pct + "%)");
                    }
                    
                    // Liberar streaming quando tiver pelo menos 35MB (cabeçalho+cues)
                    if (!streamReady && done > 36700160) { // 35MB
                        streamReady = true;
                        log("✅ Streaming liberado! " + (done/1048576) + "MB baixados");
                        
                        try {
                            for (int i = 0; i < numPieces; i++) {
                                if (i < headerPieces + 30 || i >= cueStart)
                                    torrentHandle.piece_priority_ex(i, (byte)4);
                                else
                                    torrentHandle.piece_priority_ex(i, (byte)0);
                            }
                        } catch (Exception e) {}
                        
                        final String sp = currentSavePath;
                        final torrent_handle th = torrentHandle;
                        try { handler.post(() -> callback.onStreamReady(th, sp)); } catch (Exception e) {}
                        break;
                    }
                    
                    // Timeout se não houver progresso por 30 segundos
                    if (sameCount > 30 && done < 10485760) {
                        log("⚠️ Download muito lento! Liberando mesmo assim...");
                        final String sp = currentSavePath;
                        final torrent_handle th = torrentHandle;
                        try { handler.post(() -> callback.onStreamReady(th, sp)); } catch (Exception e) {}
                        break;
                    }
                    
                    TorrentInfo info = new TorrentInfo();
                    info.progress = pct;
                    info.downloaded = done;
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    try { handler.post(() -> callback.onProgress(info)); } catch (Exception e) {}
                }
            } catch (Exception e) { log("ERRO: " + e.getMessage()); downloading = false; }
        }).start();
    }
    
    private void deleteRecursive(File dir) {
        if (dir.exists()) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) deleteRecursive(f); else f.delete(); } }
    }
    
    public void stop() { downloading = false; if (session != null) try { session.stop(); } catch (Exception e) {} }
    public void destroy() { stop(); if (session != null) try { session.stop(); } catch (Exception e) {} }
    public boolean isReady() { return ready; }
}