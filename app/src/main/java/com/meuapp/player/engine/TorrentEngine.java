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
    private long downloadStartTime = 0;
    private int currentStreamPiece = 0;
    
    private static final int BUFFER_PIECES_AHEAD = 30;
    
    public interface EngineCallback {
        void onReady();
        void onError(String error);
        void onProgress(TorrentInfo info);
        void onStreamReady(File videoFile);
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
                
                if (!swigOk) {
                    log("❌ Sessão P2P falhou!");
                    notifyError("Sessão P2P falhou");
                    return;
                }
                
                ready = true;
                log("✅ Engine P2P pronto!");
                notifyReady();
            } catch (Exception e) {
                log("❌ Erro: " + e.getMessage());
                Log.e(TAG, "Erro start", e);
                notifyError(e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) {
            log("❌ Engine não está pronto!");
            notifyError("Aguarde...");
            return;
        }
        
        downloading = true;
        downloadStartTime = System.currentTimeMillis();
        
        new Thread(() -> {
            try {
                log("🔗 Iniciando download...");
                log("   URI: " + magnetUri.substring(0, Math.min(60, magnetUri.length())) + "...");
                
                File saveDir = new File(savePath);
                session.download(magnetUri, saveDir, new torrent_flags_t());
                
                log("⏳ Aguardando torrent...");
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                log("   Torrents: " + handles.size());
                
                if (handles.size() == 0) {
                    log("❌ Nenhum torrent encontrado!");
                    notifyError("Nenhum peer");
                    downloading = false;
                    return;
                }
                
                torrentHandle = handles.get(0);
                log("   Handle: " + (torrentHandle.is_valid() ? "VÁLIDO" : "INVÁLIDO"));
                
                if (!torrentHandle.is_valid()) {
                    notifyError("Torrent inválido");
                    downloading = false;
                    return;
                }
                
                log("⏳ Aguardando metadados...");
                torrent_status st = torrentHandle.status();
                int waitSeconds = 0;
                
                while (!st.getHas_metadata() && waitSeconds < 120 && downloading) {
                    Thread.sleep(1000);
                    waitSeconds++;
                    st = torrentHandle.status();
                    
                    if (waitSeconds % 10 == 0) {
                        log("   " + waitSeconds + "s - has_metadata=" + st.getHas_metadata() + 
                            " peers=" + st.getNum_peers() + " state=" + st.getState());
                    }
                }
                
                log("   Metadados: " + (st.getHas_metadata() ? "RECEBIDOS" : "TIMEOUT") + " após " + waitSeconds + "s");
                
                if (!st.getHas_metadata()) {
                    notifyError("Timeout metadados");
                    downloading = false;
                    return;
                }
                
                numPieces = st.getNum_pieces();
                totalSize = st.getTotal();
                pieceLength = (int)(totalSize / Math.max(numPieces, 1));
                
                log("📊 TORRENT INFO:");
                log("   Nome: " + st.getName());
                log("   Tamanho: " + (totalSize/1048576) + " MB");
                log("   Peças: " + numPieces + " (" + (pieceLength/1024) + " KB cada)");
                log("   Peers: " + st.getNum_peers() + " | Seeds: " + st.getNum_seeds());
                log("   Speed: " + (st.getDownload_rate()/1024) + " KB/s");
                
                // Configura prioridades
                byte_vector priorities = new byte_vector();
                for (int i = 0; i < numPieces; i++) priorities.add((byte)0);
                torrentHandle.prioritize_pieces_ex(priorities);
                log("   Todas peças IGNORADAS");
                
                int initialPieces = Math.min(20, numPieces);
                for (int i = 0; i < initialPieces; i++) {
                    torrentHandle.piece_priority_ex(i, (byte)7);
                    torrentHandle.set_piece_deadline(i, 2000);
                }
                log("   Primeiras " + initialPieces + " peças ATIVADAS (MAX)");
                
                waitForInitialBuffer(savePath, initialPieces);
                
            } catch (Exception e) {
                log("❌ ERRO: " + e.getMessage());
                Log.e(TAG, "Erro download", e);
                notifyError(e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void waitForInitialBuffer(String savePath, int targetPieces) {
        int lastLogPercent = -1;
        long waitStart = System.currentTimeMillis();
        
        while (downloading) {
            try {
                Thread.sleep(500);
                
                int complete = 0;
                for (int i = 0; i < targetPieces; i++) {
                    if (torrentHandle.have_piece(i)) complete++;
                }
                
                int percent = (complete * 100) / targetPieces;
                torrent_status st = torrentHandle.status();
                long elapsed = (System.currentTimeMillis() - waitStart) / 1000;
                
                if (percent != lastLogPercent && (percent % 20 == 0 || elapsed % 5 == 0)) {
                    lastLogPercent = percent;
                    log("   Buffer: " + percent + "% (" + complete + "/" + targetPieces + 
                        ") | " + (st.getTotal_done()/1048576) + "MB | " + 
                        (st.getDownload_rate()/1024) + "KB/s | " + elapsed + "s");
                }
                
                TorrentInfo info = new TorrentInfo();
                info.progress = percent;
                info.downloaded = st.getTotal_done();
                info.total = totalSize;
                info.speed = st.getDownload_rate();
                info.peers = st.getNum_peers();
                info.seeds = st.getNum_seeds();
                handler.post(() -> callback.onProgress(info));
                
                if (complete >= targetPieces * 0.8f) {
                    File videoFile = findVideoFile(new File(savePath));
                    long fileLen = videoFile != null ? videoFile.length() : 0;
                    
                    log("✅ BUFFER COMPLETO!");
                    log("   Peças: " + complete + "/" + targetPieces);
                    log("   Tempo: " + elapsed + "s");
                    log("   Arquivo: " + (fileLen/1048576) + "MB");
                    
                    if (videoFile != null && fileLen > pieceLength * 5) {
                        currentStreamPiece = targetPieces;
                        
                        for (int i = targetPieces; i < Math.min(targetPieces + BUFFER_PIECES_AHEAD, numPieces); i++) {
                            torrentHandle.piece_priority_ex(i, (byte)6);
                            torrentHandle.set_piece_deadline(i, 3000);
                        }
                        log("   +" + BUFFER_PIECES_AHEAD + " peças à frente ativadas");
                        
                        File f = videoFile;
                        handler.post(() -> callback.onStreamReady(f));
                        
                        manageStreamBuffer();
                        break;
                    }
                }
            } catch (Exception e) {
                log("❌ Erro buffer: " + e.getMessage());
            }
        }
    }
    
    private void manageStreamBuffer() {
        log("🔄 Gerenciando buffer...");
        
        while (downloading) {
            try {
                Thread.sleep(2000);
                
                if (torrentHandle != null && torrentHandle.is_valid()) {
                    torrent_status st = torrentHandle.status();
                    long downloaded = st.getTotal_done();
                    
                    int currentPiece = pieceLength > 0 ? (int)(downloaded / pieceLength) : currentStreamPiece;
                    
                    if (currentPiece > currentStreamPiece + 5) {
                        log("   Buffer: " + currentStreamPiece + " -> " + currentPiece);
                        currentStreamPiece = currentPiece;
                        
                        for (int i = 0; i < numPieces; i++) {
                            if (i >= currentStreamPiece && i < currentStreamPiece + BUFFER_PIECES_AHEAD) {
                                torrentHandle.piece_priority_ex(i, (byte)7);
                            } else if (i < currentStreamPiece - 10) {
                                torrentHandle.piece_priority_ex(i, (byte)0);
                            }
                        }
                    }
                    
                    TorrentInfo info = new TorrentInfo();
                    info.progress = (int)(st.getProgress() * 100);
                    info.downloaded = downloaded;
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    handler.post(() -> callback.onProgress(info));
                }
            } catch (Exception e) {
                log("❌ Erro streaming: " + e.getMessage());
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
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
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
    private void notifyStatus(String msg) { handler.post(() -> callback.onStatus(msg)); }
}