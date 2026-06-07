package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.swig.*;

import java.io.*;

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
    private int currentStreamPiece = 0;
    private static final int BUFFER_PIECES_AHEAD = 30;
    
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
                log("🔧 Criando SessionManager...");
                session = new SessionManager();
                session.start(new SessionParams());
                boolean swigOk = session.swig() != null;
                log("   swig() = " + (swigOk ? "OK" : "NULL!"));
                if (!swigOk) { notifyError("Sessao P2P falhou"); return; }
                ready = true;
                log("✅ Engine P2P pronto!");
                notifyReady();
            } catch (Exception e) {
                log("❌ Erro: " + e.getMessage());
                notifyError(e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) { notifyError("Aguarde..."); return; }
        downloading = true;
        
        new Thread(() -> {
            try {
                log("🔗 Iniciando download...");
                File saveDir = new File(savePath);
                session.download(magnetUri, saveDir, new torrent_flags_t());
                
                log("⏳ Aguardando torrent...");
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                log("   Torrents: " + handles.size());
                
                if (handles.size() == 0) { notifyError("Nenhum peer"); downloading = false; return; }
                
                torrentHandle = handles.get(0);
                log("   Handle: " + (torrentHandle.is_valid() ? "VALIDO" : "INVALIDO"));
                
                if (!torrentHandle.is_valid()) { notifyError("Torrent invalido"); downloading = false; return; }
                
                log("⏳ Aguardando metadados...");
                torrent_status st = torrentHandle.status();
                int waitSeconds = 0;
                
                while (!st.getHas_metadata() && waitSeconds < 120 && downloading) {
                    Thread.sleep(1000);
                    waitSeconds++;
                    st = torrentHandle.status();
                    if (waitSeconds % 10 == 0)
                        log("   " + waitSeconds + "s - metadata=" + st.getHas_metadata() + " peers=" + st.getNum_peers());
                }
                
                log("   Metadados: " + (st.getHas_metadata() ? "RECEBIDOS" : "TIMEOUT"));
                if (!st.getHas_metadata()) { notifyError("Timeout metadados"); downloading = false; return; }
                
                totalSize = st.getTotal();
                
                torrent_info ti = torrentHandle.torrent_file_ptr();
                if (ti != null && ti.is_valid()) {
                    numPieces = ti.num_pieces();
                    pieceLength = ti.piece_length();
                    log("   torrent_info: " + numPieces + " peças, " + (pieceLength/1024) + "KB cada");
                } else {
                    numPieces = st.getNum_pieces();
                    if (numPieces <= 0) { pieceLength = 524288; numPieces = (int)(totalSize / pieceLength) + 1; }
                    else { pieceLength = (int)(totalSize / numPieces); }
                    log("   Fallback: " + numPieces + " peças calculadas");
                }
                
                log("📊 TORRENT:");
                log("   Nome: " + st.getName());
                log("   Tamanho: " + (totalSize/1048576) + "MB");
                log("   Peças: " + numPieces + " x " + (pieceLength/1024) + "KB");
                log("   Peers: " + st.getNum_peers() + " Seeds: " + st.getNum_seeds());
                log("   Speed: " + (st.getDownload_rate()/1024) + "KB/s");
                
                if (numPieces <= 0 || totalSize <= 0) { notifyError("Dados invalidos"); downloading = false; return; }
                
                byte_vector priorities = new byte_vector();
                for (int i = 0; i < numPieces; i++) priorities.add((byte)0);
                torrentHandle.prioritize_pieces_ex(priorities);
                
                int initialPieces = Math.min(20, numPieces);
                for (int i = 0; i < initialPieces; i++) {
                    torrentHandle.piece_priority_ex(i, (byte)7);
                    torrentHandle.set_piece_deadline(i, 2000);
                }
                log("   " + initialPieces + " peças iniciais ATIVADAS");
                
                waitForInitialBuffer(initialPieces);
                
            } catch (Exception e) {
                log("❌ ERRO: " + e.getMessage());
                Log.e(TAG, "Erro", e);
                notifyError(e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void waitForInitialBuffer(int targetPieces) {
        if (targetPieces <= 0) { log("❌ targetPieces=0"); return; }
        long waitStart = System.currentTimeMillis();
        int lastLogPercent = -1;
        
        while (downloading) {
            try {
                Thread.sleep(500);
                
                int complete = 0;
                for (int i = 0; i < targetPieces; i++)
                    if (torrentHandle.have_piece(i)) complete++;
                
                int percent = (complete * 100) / targetPieces;
                torrent_status st = torrentHandle.status();
                long elapsed = (System.currentTimeMillis() - waitStart) / 1000;
                
                if (percent != lastLogPercent && (percent % 25 == 0 || elapsed % 10 == 0)) {
                    lastLogPercent = percent;
                    log("   Buffer: " + percent + "% (" + complete + "/" + targetPieces + ") " + 
                        (st.getTotal_done()/1048576) + "MB " + (st.getDownload_rate()/1024) + "KB/s " + elapsed + "s");
                }
                
                TorrentInfo info = new TorrentInfo();
                info.progress = percent;
                info.downloaded = st.getTotal_done();
                info.total = totalSize;
                info.speed = st.getDownload_rate();
                info.peers = st.getNum_peers();
                handler.post(() -> callback.onProgress(info));
                
                if (complete >= targetPieces * 0.7f) {
                    long fileLen = st.getTotal_done();
                    log("✅ BUFFER OK! " + complete + "/" + targetPieces + " peças, " + (fileLen/1048576) + "MB, " + elapsed + "s");
                    
                    currentStreamPiece = targetPieces;
                    int ahead = Math.min(targetPieces + BUFFER_PIECES_AHEAD, numPieces);
                    for (int i = targetPieces; i < ahead; i++) {
                        torrentHandle.piece_priority_ex(i, (byte)6);
                        torrentHandle.set_piece_deadline(i, 3000);
                    }
                    
                    // Passa o handle diretamente para o servidor
                    handler.post(() -> callback.onStreamReady(torrentHandle));
                    
                    manageStreamBuffer();
                    break;
                }
            } catch (Exception e) { log("❌ Erro buffer: " + e.getMessage()); }
        }
    }
    
    private void manageStreamBuffer() {
        log("🔄 Buffer continuo ativo");
        while (downloading) {
            try {
                Thread.sleep(2000);
                if (torrentHandle != null && torrentHandle.is_valid()) {
                    torrent_status st = torrentHandle.status();
                    long downloaded = st.getTotal_done();
                    int cp = pieceLength > 0 ? (int)(downloaded / pieceLength) : currentStreamPiece;
                    
                    if (cp > currentStreamPiece + 5) {
                        log("   Buffer: " + currentStreamPiece + " -> " + cp);
                        currentStreamPiece = cp;
                        for (int i = 0; i < numPieces; i++) {
                            if (i >= cp && i < Math.min(cp + BUFFER_PIECES_AHEAD, numPieces))
                                torrentHandle.piece_priority_ex(i, (byte)7);
                            else if (i < cp - 10)
                                torrentHandle.piece_priority_ex(i, (byte)0);
                        }
                    }
                    
                    TorrentInfo info = new TorrentInfo();
                    info.progress = (int)(st.getProgress() * 100);
                    info.downloaded = downloaded;
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    handler.post(() -> callback.onProgress(info));
                }
            } catch (Exception e) { log("❌ Erro: " + e.getMessage()); }
        }
    }
    
    public void stop() { downloading = false; if (session != null) try { session.stop(); } catch (Exception e) {} }
    public void destroy() { stop(); if (session != null) try { session.stop(); } catch (Exception e) {} }
    public boolean isReady() { return ready; }
    private void notifyReady() { handler.post(() -> callback.onReady()); }
    private void notifyError(String msg) { handler.post(() -> callback.onError(msg)); }
    private void notifyStatus(String msg) { handler.post(() -> callback.onStatus(msg)); }
}