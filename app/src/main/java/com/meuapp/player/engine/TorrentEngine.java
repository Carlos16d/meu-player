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
    
    private int numPieces = 0;
    private long totalSize = 0;
    private int pieceLength = 0;
    
    private static final int MIN_PIECES_TO_START = 5;
    
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
                log("Criando SessionManager...");
                session = new SessionManager();
                session.start(new SessionParams());
                
                if (session.swig() == null) {
                    notifyError("Sessao P2P falhou");
                    return;
                }
                
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
                log("Iniciando download...");
                File saveDir = new File(savePath);
                
                session.download(magnetUri, saveDir, new torrent_flags_t());
                
                log("Aguardando torrent aparecer...");
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                log("Torrents encontrados: " + handles.size());
                
                if (handles.size() == 0) {
                    notifyError("Nenhum peer encontrado");
                    downloading = false;
                    return;
                }
                
                torrentHandle = handles.get(0);
                log("Handle: " + (torrentHandle.is_valid() ? "VALIDO" : "INVALIDO"));
                
                if (!torrentHandle.is_valid()) {
                    notifyError("Torrent invalido");
                    downloading = false;
                    return;
                }
                
                log("Aguardando metadados...");
                torrent_status st = torrentHandle.status();
                int waitSeconds = 0;
                
                while (!st.getHas_metadata() && waitSeconds < 120 && downloading) {
                    Thread.sleep(1000);
                    waitSeconds++;
                    st = torrentHandle.status();
                    if (waitSeconds % 10 == 0)
                        log("  " + waitSeconds + "s - metadata=" + st.getHas_metadata() + " peers=" + st.getNum_peers());
                }
                
                if (!st.getHas_metadata()) {
                    notifyError("Timeout metadados");
                    downloading = false;
                    return;
                }
                
                totalSize = st.getTotal();
                
                torrent_info ti = torrentHandle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    numPieces = ti.num_pieces();
                    pieceLength = ti.piece_length();
                } else {
                    numPieces = st.getNum_pieces();
                    pieceLength = numPieces > 0 ? (int)(totalSize / numPieces) : 524288;
                }
                
                log("TORRENT: " + (totalSize/1048576) + "MB, " + numPieces + " peças, " + (pieceLength/1024) + "KB cada");
                log("Peers: " + st.getNum_peers() + " Seeds: " + st.getNum_seeds());
                
                byte_vector priorities = new byte_vector();
                for (int i = 0; i < numPieces; i++) {
                    priorities.add((byte)(i < 50 ? 7 : 4));
                }
                torrentHandle.prioritize_pieces_ex(priorities);
                
                for (int i = 0; i < Math.min(50, numPieces); i++) {
                    torrentHandle.set_piece_deadline(i, 1000);
                }
                
                log("Todas peças ativadas, primeiras 50 com deadline");
                
                waitForInitialPieces();
                
            } catch (Exception e) {
                log("ERRO: " + e.getMessage());
                Log.e(TAG, "Erro", e);
                notifyError(e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void waitForInitialPieces() {
        int targetPieces = Math.min(MIN_PIECES_TO_START, numPieces);
        long waitStart = System.currentTimeMillis();
        
        log("Aguardando " + targetPieces + " peças iniciais...");
        
        while (downloading) {
            try {
                Thread.sleep(500);
                
                int complete = 0;
                for (int i = 0; i < targetPieces; i++) {
                    if (torrentHandle.have_piece(i)) complete++;
                }
                
                torrent_status st = torrentHandle.status();
                long elapsed = (System.currentTimeMillis() - waitStart) / 1000;
                
                TorrentInfo info = new TorrentInfo();
                info.progress = (complete * 100) / targetPieces;
                info.downloaded = st.getTotal_done();
                info.total = totalSize;
                info.speed = st.getDownload_rate();
                info.peers = st.getNum_peers();
                handler.post(() -> callback.onProgress(info));
                
                if (complete >= targetPieces) {
                    log("Peças iniciais prontas! " + complete + "/" + targetPieces + " em " + elapsed + "s");
                    log("Passando handle para servidor HTTP...");
                    
                    handler.post(() -> callback.onStreamReady(torrentHandle));
                    break;
                }
                
                if (elapsed > 60) {
                    log("Timeout aguardando peças iniciais");
                    break;
                }
                
            } catch (Exception e) {
                log("Erro: " + e.getMessage());
            }
        }
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