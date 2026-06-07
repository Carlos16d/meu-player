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
    
    public interface EngineCallback {
        void onReady();
        void onError(String error);
        void onProgress(TorrentInfo info);
        void onStreamReady(torrent_handle handle);
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
                log("Iniciando...");
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
        
        new Thread(() -> {
            try {
                log("Baixando...");
                File saveDir = new File(savePath);
                session.download(magnetUri, saveDir, new torrent_flags_t());
                
                // Aguarda torrent
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() == 0) {
                    notifyError("Nenhum peer");
                    downloading = false;
                    return;
                }
                
                torrentHandle = handles.get(0);
                
                // Aguarda metadados
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
                
                log("Metadados OK: " + (st.getTotal()/1048576) + "MB");
                
                // Ativa prioridade em tudo
                torrent_info ti = torrentHandle.torrent_file_ptr();
                int numPieces = ti != null ? ti.num_pieces() : 100;
                
                byte_vector priorities = new byte_vector();
                for (int i = 0; i < numPieces; i++) {
                    priorities.add((byte)4); // Tudo prioridade normal
                }
                torrentHandle.prioritize_pieces_ex(priorities);
                
                // Aguarda algumas peças
                int complete = 0;
                int target = Math.min(10, numPieces);
                long startWait = System.currentTimeMillis();
                
                while (complete < target && downloading) {
                    Thread.sleep(500);
                    complete = 0;
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
                    
                    if ((System.currentTimeMillis() - startWait) > 60000) break;
                }
                
                log("Peças iniciais: " + complete + "/" + target);
                
                // Passa o handle para o servidor
                handler.post(() -> callback.onStreamReady(torrentHandle));
                
            } catch (Exception e) {
                log("ERRO: " + e.getMessage());
                notifyError(e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    public void stop() { 
        downloading = false; 
        if (session != null) try { session.stop(); } catch (Exception e) {} 
    }
    
    public void destroy() { 
        stop(); 
        if (session != null) try { session.stop(); } catch (Exception e) {} 
    }
    
    public boolean isReady() { return ready; }
    private void notifyReady() { handler.post(() -> callback.onReady()); }
    private void notifyError(String msg) { handler.post(() -> callback.onError(msg)); }
}