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
        // Garante que o handler está na Main thread
        this.handler = new Handler(Looper.getMainLooper());
    }
    
    private void log(String msg) {
        Log.d(TAG, msg);
        // NÃO chama callback aqui - apenas loga no Logcat
    }
    
    public void start() {
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start(new SessionParams());
                ready = true;
                Log.d(TAG, "Engine pronto!");
                safePost(() -> callback.onReady());
            } catch (Exception e) {
                Log.e(TAG, "Erro start: " + e.getMessage());
                safePost(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }
    
    private void safePost(Runnable r) {
        try {
            handler.post(r);
        } catch (Exception e) {
            Log.e(TAG, "Erro safePost: " + e.getMessage());
        }
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) return;
        downloading = true;
        currentSavePath = savePath;
        
        new Thread(() -> {
            try {
                Log.d(TAG, "Conectando ao tracker...");
                File saveDir = new File(savePath);
                try {
                    if (saveDir.exists()) {
                        File[] files = saveDir.listFiles();
                        if (files != null) for (File f : files) deleteRecursive(f);
                    }
                } catch (Exception e) {}
                saveDir.mkdirs();
                
                session.download(magnetUri, saveDir, new torrent_flags_t());
                
                Log.d(TAG, "Aguardando torrent...");
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                Log.d(TAG, "Torrents: " + handles.size());
                
                if (handles.size() == 0) {
                    safePost(() -> callback.onError("Nenhum peer"));
                    downloading = false;
                    return;
                }
                
                torrentHandle = handles.get(0);
                Log.d(TAG, "Handle valid: " + torrentHandle.is_valid());
                
                torrent_status st = torrentHandle.status();
                int w = 0;
                while (!st.getHas_metadata() && w < 60 && downloading) {
                    Thread.sleep(1000);
                    w++;
                    st = torrentHandle.status();
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
                
                Log.d(TAG, "Torrent: " + (totalSize/1048576) + "MB, " + numPieces + " peças, " + st.getNum_peers() + " peers");
                
                // Prioridades
                try {
                    for (int i = 0; i < numPieces; i++)
                        torrentHandle.piece_priority_ex(i, (byte)(i < 200 ? 7 : 1));
                    for (int i = 0; i < Math.min(100, numPieces); i++)
                        torrentHandle.set_piece_deadline(i, 2000);
                } catch (Exception e) {}
                
                int target = Math.min(10, numPieces);
                Log.d(TAG, "Aguardando " + target + " peças...");
                
                while (downloading) {
                    Thread.sleep(500);
                    
                    if (torrentHandle == null || !torrentHandle.is_valid()) break;
                    
                    int complete = 0;
                    for (int i = 0; i < target; i++) {
                        try { if (torrentHandle.have_piece(i)) complete++; } 
                        catch (Exception e) {}
                    }
                    
                    st = torrentHandle.status();
                    
                    // Envia progresso
                    final TorrentInfo info = new TorrentInfo();
                    info.progress = (complete * 100) / target;
                    info.downloaded = st.getTotal_done();
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    safePost(() -> callback.onProgress(info));
                    
                    if (complete >= target) {
                        Log.d(TAG, "Streaming pronto! " + complete + "/" + target);
                        
                        // Envia onStreamReady na thread principal
                        final torrent_handle th = torrentHandle;
                        final String sp = currentSavePath;
                        
                        safePost(() -> {
                            try {
                                Log.d(TAG, "Chamando callback.onStreamReady...");
                                callback.onStreamReady(th, sp);
                                Log.d(TAG, "callback.onStreamReady retornou com sucesso");
                            } catch (Exception e) {
                                Log.e(TAG, "ERRO no onStreamReady: " + e.getMessage(), e);
                            }
                        });
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "ERRO download: " + e.getMessage(), e);
                downloading = false;
            }
        }).start();
    }
    
    private void deleteRecursive(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) {
                if (f.isDirectory()) deleteRecursive(f);
                else f.delete();
            }
        }
    }
    
    public void stop() { 
        downloading = false;
        if (session != null) try { session.stop(); } catch (Exception e) {} 
    }
    
    public void destroy() { stop(); if (session != null) try { session.stop(); } catch (Exception e) {} }
    public boolean isReady() { return ready; }
}