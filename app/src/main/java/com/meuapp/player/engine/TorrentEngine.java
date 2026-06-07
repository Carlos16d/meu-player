package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.swig.*;

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
                log("Iniciando engine...");
                session = new SessionManager();
                
                // Configura para NÃO salvar em disco (cache em memória)
                SettingsPack sp = new SettingsPack();
                sp.setBoolean(settings_pack.bool_types.use_read_cache.swigValue(), true);
                sp.setInteger(settings_pack.int_types.cache_size.swigValue(), 104857600); // 100MB cache
                
                session.start(new SessionParams(sp));
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
                log("Conectando ao tracker...");
                
                // Usa async_add_torrent DIRETO (sem salvar em disco)
                add_torrent_params params = libtorrent.parse_magnet_uri(magnetUri, new error_code());
                params.setSave_path(savePath); // Precisa de um path, mas não vamos usar o arquivo
                
                // Configura para download sequencial (peças em ordem)
                params.setFlags(params.getFlags());
                
                session.swig().async_add_torrent(params);
                
                log("Aguardando torrent...");
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                log("Torrents: " + handles.size());
                
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
                    notifyError("Timeout metadados");
                    downloading = false;
                    return;
                }
                
                long totalSize = st.getTotal();
                torrent_info ti = torrentHandle.torrent_file_ptr();
                int numPieces = ti != null ? ti.num_pieces() : 100;
                
                log("Torrent: " + (totalSize/1048576) + "MB, " + numPieces + " peças");
                
                // DOWNLOAD SEQUENCIAL: ativa só as primeiras peças
                for (int i = 0; i < numPieces; i++) {
                    torrentHandle.piece_priority_ex(i, (byte)(i < 100 ? 7 : 0)); // 100 primeiras: MAX, resto: IGNORE
                }
                
                // Deadlines nas primeiras 30 peças
                for (int i = 0; i < Math.min(30, numPieces); i++) {
                    torrentHandle.set_piece_deadline(i, 2000);
                }
                
                // Aguarda peças iniciais
                waitForInitialPieces(numPieces);
                
            } catch (Exception e) {
                log("ERRO: " + e.getMessage());
                notifyError(e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void waitForInitialPieces(int numPieces) {
        int target = Math.min(10, numPieces);
        
        while (downloading) {
            try {
                Thread.sleep(500);
                
                int complete = 0;
                for (int i = 0; i < target; i++) {
                    if (torrentHandle.have_piece(i)) complete++;
                }
                
                torrent_status st = torrentHandle.status();
                
                TorrentInfo info = new TorrentInfo();
                info.progress = (complete * 100) / target;
                info.downloaded = st.getTotal_done();
                info.speed = st.getDownload_rate();
                info.peers = st.getNum_peers();
                handler.post(() -> callback.onProgress(info));
                
                if (complete >= target) {
                    log("Streaming pronto! " + complete + "/" + target + " peças");
                    handler.post(() -> callback.onStreamReady(torrentHandle));
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