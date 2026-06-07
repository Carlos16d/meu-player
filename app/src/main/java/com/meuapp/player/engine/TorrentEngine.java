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
                log("Iniciando engine...");
                session = new SessionManager();
                session.start(new SessionParams());
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
                log("Conectando ao tracker...");
                File saveDir = new File(savePath);
                try {
                    if (saveDir.exists()) {
                        File[] files = saveDir.listFiles();
                        if (files != null) for (File f : files) deleteRecursive(f);
                    }
                } catch (Exception e) {}
                saveDir.mkdirs();
                
                log("Chamando session.download()...");
                session.download(magnetUri, saveDir, new torrent_flags_t());
                
                log("Aguardando torrent aparecer...");
                Thread.sleep(3000);
                
                log("Obtendo lista de torrents...");
                torrent_handle_vector handles = session.swig().get_torrents();
                log("Torrents encontrados: " + handles.size());
                
                if (handles.size() == 0) {
                    log("Nenhum peer encontrado");
                    downloading = false;
                    return;
                }
                
                torrentHandle = handles.get(0);
                log("Handle obtido, isValid=" + torrentHandle.is_valid());
                
                torrent_status st = torrentHandle.status();
                int w = 0;
                log("Aguardando metadados... has_metadata=" + st.getHas_metadata());
                
                while (!st.getHas_metadata() && w < 60 && downloading) {
                    Thread.sleep(1000);
                    w++;
                    st = torrentHandle.status();
                    if (w % 10 == 0) log("  " + w + "s... has_metadata=" + st.getHas_metadata() + " peers=" + st.getNum_peers());
                }
                
                log("Metadados: " + (st.getHas_metadata() ? "RECEBIDOS" : "TIMEOUT") + " após " + w + "s");
                if (!st.getHas_metadata()) { downloading = false; return; }
                
                long totalSize = st.getTotal();
                int numPieces = 100;
                try {
                    torrent_info ti = torrentHandle.torrent_file_ptr();
                    if (ti != null && ti.is_valid()) numPieces = ti.num_pieces();
                    else numPieces = st.getNum_pieces();
                } catch (Exception e) {}
                if (numPieces <= 0) numPieces = 100;
                
                log("Torrent: " + (totalSize/1048576) + "MB, " + numPieces + " peças, " + st.getNum_peers() + " peers");
                
                // Prioridades
                try {
                    for (int i = 0; i < numPieces; i++)
                        torrentHandle.piece_priority_ex(i, (byte)(i < 200 ? 7 : 1));
                    for (int i = 0; i < Math.min(100, numPieces); i++)
                        torrentHandle.set_piece_deadline(i, 2000);
                    log("Prioridades configuradas");
                } catch (Exception e) {
                    log("Aviso prioridades: " + e.getMessage());
                }
                
                // Aguarda peças iniciais
                int target = Math.min(10, numPieces);
                log("Aguardando " + target + " peças iniciais...");
                
                int lastComplete = -1;
                
                while (downloading) {
                    Thread.sleep(500);
                    
                    // Verifica se handle ainda é válido
                    if (torrentHandle == null || !torrentHandle.is_valid()) {
                        log("Handle ficou inválido durante espera!");
                        break;
                    }
                    
                    // Conta peças com segurança
                    int complete = 0;
                    try {
                        for (int i = 0; i < target; i++) {
                            try {
                                if (torrentHandle.have_piece(i)) complete++;
                            } catch (Exception e) {}
                        }
                    } catch (Exception e) {
                        log("Erro contando peças: " + e.getMessage());
                    }
                    
                    // Atualiza status
                    try {
                        st = torrentHandle.status();
                    } catch (Exception e) {
                        log("Erro status: " + e.getMessage());
                        continue;
                    }
                    
                    // Log apenas quando mudar
                    if (complete != lastComplete) {
                        lastComplete = complete;
                        log("Peças: " + complete + "/" + target + " | " + (st.getDownload_rate()/1024) + "KB/s");
                    }
                    
                    // Atualiza UI
                    try {
                        TorrentInfo info = new TorrentInfo();
                        info.progress = (complete * 100) / target;
                        info.downloaded = st.getTotal_done();
                        info.speed = st.getDownload_rate();
                        info.peers = st.getNum_peers();
                        handler.post(() -> callback.onProgress(info));
                    } catch (Exception e) {}
                    
                    // Verifica se atingiu o target
                    if (complete >= target) {
                        log("✅ Streaming pronto! " + complete + "/" + target + " peças");
                        
                        final String sp = currentSavePath;
                        final torrent_handle th = torrentHandle;
                        
                        try {
                            handler.post(() -> {
                                try {
                                    callback.onStreamReady(th, sp);
                                } catch (Exception e) {
                                    log("❌ ERRO no callback onStreamReady: " + e.getMessage());
                                    Log.e(TAG, "Callback error", e);
                                }
                            });
                            log("Callback onStreamReady enviado com sucesso");
                        } catch (Exception e) {
                            log("❌ ERRO ao enviar callback: " + e.getMessage());
                        }
                        break;
                    }
                    
                    // Timeout de segurança
                    if (st.getDownload_rate() == 0 && complete == 0 && 
                        (System.currentTimeMillis() - startTime) > 30000) {
                        log("Timeout - sem progresso por 30s");
                        break;
                    }
                }
            } catch (Exception e) {
                log("❌ ERRO FATAL: " + e.getMessage());
                Log.e(TAG, "Erro download", e);
                downloading = false;
            }
        }).start();
    }
    
    private long startTime;
    
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