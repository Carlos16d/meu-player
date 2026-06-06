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
    private boolean streaming = false;
    private Handler handler;
    private EngineCallback callback;
    private int numPieces = 0;
    private long totalSize = 0;
    private int pieceLength = 0;
    
    // Pre-buffer: 5% do arquivo ou 8MB (o que for menor)
    private static final float PRE_BUFFER_PERCENT = 0.05f;
    private static final long MIN_PRE_BUFFER = 8 * 1024 * 1024; // 8MB
    
    public interface EngineCallback {
        void onReady();
        void onError(String error);
        void onProgress(TorrentInfo info);
        void onStreamReady(File videoFile);
        void onStatus(String status);
    }
    
    public TorrentEngine(EngineCallback callback) {
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());
    }
    
    public void start() {
        new Thread(() -> {
            try {
                notifyStatus("Iniciando...");
                session = new SessionManager();
                session.start(new SessionParams());
                ready = true;
                notifyReady();
                notifyStatus("Pronto!");
            } catch (Exception e) {
                Log.e(TAG, "Erro", e);
                notifyError("Erro: " + e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) { notifyError("Aguarde..."); return; }
        downloading = true;
        
        new Thread(() -> {
            try {
                notifyStatus("Conectando...");
                File saveDir = new File(savePath);
                session.download(magnetUri, saveDir, new torrent_flags_t());
                Thread.sleep(5000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                
                if (handles.size() > 0) {
                    torrentHandle = handles.get(0);
                    
                    if (torrentHandle.is_valid()) {
                        int w = 0;
                        torrent_status st = torrentHandle.status();
                        
                        while (!st.getHas_metadata() && w < 120 && downloading) {
                            Thread.sleep(1000);
                            w++;
                            st = torrentHandle.status();
                            notifyStatus("Metadados... " + w + "s");
                        }
                        
                        if (st.getHas_metadata()) {
                            numPieces = st.getNum_pieces();
                            totalSize = st.getTotal();
                            pieceLength = (int)(totalSize / Math.max(numPieces, 1));
                            
                            Log.d(TAG, "Peças: " + numPieces + " Tamanho: " + (totalSize/1048576) + "MB PieceLen: " + pieceLength);
                            notifyStatus("Metadados OK! " + (totalSize/1048576) + "MB");
                            
                            // PRIORIDADE INTELIGENTE PARA STREAMING:
                            // - Primeiras 5% das peças: MÁXIMA prioridade + deadline imediato
                            // - Próximas 15%: ALTA prioridade
                            // - Resto: prioridade NORMAL (baixa naturalmente)
                            
                            int p5 = Math.max((int)(numPieces * 0.05), 10);  // 5% ou pelo menos 10 peças
                            int p20 = (int)(numPieces * 0.20);                // 20%
                            
                            byte_vector priorities = new byte_vector();
                            for (int i = 0; i < numPieces; i++) {
                                if (i < p5) {
                                    priorities.add((byte)7); // TOP - precisa AGORA
                                } else if (i < p20) {
                                    priorities.add((byte)6); // HIGH - precisa em breve
                                } else {
                                    priorities.add((byte)4); // DEFAULT - resto
                                }
                            }
                            torrentHandle.prioritize_pieces_ex(priorities);
                            
                            // Deadlines URGENTES nas primeiras peças
                            for (int i = 0; i < p5; i++) {
                                torrentHandle.set_piece_deadline(i, 1000); // 1 segundo!
                            }
                            // Deadlines normais nas próximas
                            for (int i = p5; i < Math.min(p20, p5 + 100); i++) {
                                torrentHandle.set_piece_deadline(i, 5000);
                            }
                            
                            Log.d(TAG, "Prioridades: 0-" + p5 + ":TOP, " + p5 + "-" + p20 + ":HIGH, restante:DEFAULT");
                            
                            // Aguarda pre-buffer de 5%
                            waitForPreBuffer(savePath, p5);
                        }
                    }
                } else {
                    notifyError("Nenhum peer encontrado");
                    downloading = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro download", e);
                notifyError("Erro: " + e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void waitForPreBuffer(String savePath, int targetPieces) {
        long preBufferTarget = Math.max(
            (long)(totalSize * PRE_BUFFER_PERCENT),
            MIN_PRE_BUFFER
        );
        
        Log.d(TAG, "Pre-buffer: " + (preBufferTarget/1048576) + "MB ou " + targetPieces + " peças");
        
        File videoFile = null;
        int lastLogPercent = -1;
        
        while (downloading) {
            try {
                Thread.sleep(500); // Verifica a cada 500ms
                
                if (torrentHandle != null && torrentHandle.is_valid()) {
                    torrent_status st = torrentHandle.status();
                    
                    long downloaded = st.getTotal_done();
                    int progress = (int)(st.getProgress() * 100);
                    
                    TorrentInfo info = new TorrentInfo();
                    info.progress = progress;
                    info.downloaded = downloaded;
                    info.total = totalSize;
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    
                    handler.post(() -> callback.onProgress(info));
                    
                    // Verifica peças completas no início
                    int piecesComplete = 0;
                    for (int i = 0; i < targetPieces; i++) {
                        if (torrentHandle.have_piece(i)) piecesComplete++;
                    }
                    
                    int piecePercent = (piecesComplete * 100) / targetPieces;
                    
                    // Mostra progresso do pre-buffer
                    if (piecePercent != lastLogPercent && piecePercent % 10 == 0) {
                        lastLogPercent = piecePercent;
                        notifyStatus("Pre-buffer: " + piecePercent + "% (" + (downloaded/1048576) + "MB)");
                    }
                    
                    // Libera quando 90% das peças alvo estiverem completas
                    if (piecesComplete >= targetPieces * 0.9f || downloaded >= preBufferTarget) {
                        videoFile = findVideoFile(new File(savePath));
                        if (videoFile != null && videoFile.length() >= MIN_PRE_BUFFER) {
                            streaming = true;
                            Log.d(TAG, "STREAMING LIBERADO! " + piecesComplete + "/" + targetPieces + " peças, " + (downloaded/1048576) + "MB");
                            File f = videoFile;
                            handler.post(() -> {
                                callback.onStreamReady(f);
                                callback.onStatus("Streaming pronto! " + (downloaded/1048576) + "MB iniciais");
                            });
                            break;
                        }
                    }
                    
                    // Atualiza deadlines para manter fluxo
                    if (pieceLength > 0) {
                        int currentPiece = (int)(downloaded / pieceLength);
                        for (int i = currentPiece; i < Math.min(currentPiece + 50, numPieces); i++) {
                            torrentHandle.set_piece_deadline(i, 3000);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro pre-buffer", e);
            }
        }
        
        // Continua monitorando após liberar
        if (streaming && downloading) {
            continueStreaming(savePath);
        }
    }
    
    private void continueStreaming(String savePath) {
        Log.d(TAG, "Streaming contínuo ativo");
        
        while (downloading && streaming) {
            try {
                Thread.sleep(1000);
                
                if (torrentHandle != null && torrentHandle.is_valid()) {
                    torrent_status st = torrentHandle.status();
                    
                    TorrentInfo info = new TorrentInfo();
                    info.progress = (int)(st.getProgress() * 100);
                    info.downloaded = st.getTotal_done();
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    
                    handler.post(() -> callback.onProgress(info));
                    
                    // Mantém deadlines nas peças à frente
                    if (pieceLength > 0) {
                        int currentPiece = (int)(st.getTotal_done() / pieceLength);
                        // Prioridade máxima nas próximas 20 peças
                        for (int i = currentPiece; i < Math.min(currentPiece + 20, numPieces); i++) {
                            torrentHandle.piece_priority_ex(i, (byte)7);
                            torrentHandle.set_piece_deadline(i, 2000);
                        }
                        // Prioridade alta nas próximas 50
                        for (int i = currentPiece + 20; i < Math.min(currentPiece + 70, numPieces); i++) {
                            torrentHandle.piece_priority_ex(i, (byte)6);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro streaming", e);
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
        streaming = false;
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