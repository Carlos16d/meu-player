package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
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
    
    // Buffer pequeno: 2-5MB à frente (aproximadamente 10-20 peças)
    private static final int BUFFER_PIECES = 15;
    private static final int DOWNLOAD_LIMIT = 2 * 1024 * 1024; // 2 MB/s
    private static final int INITIAL_PIECES = 10; // 10 peças iniciais (~2-4MB)
    
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
                
                SettingsPack sp = new SettingsPack();
                sp.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), DOWNLOAD_LIMIT);
                sp.setInteger(settings_pack.int_types.connections_limit.swigValue(), 20);
                sp.setBoolean(settings_pack.bool_types.strict_end_game_mode.swigValue(), true);
                
                session.start(new SessionParams(sp));
                
                ready = true;
                notifyReady();
                notifyStatus("Pronto!");
            } catch (Exception e) {
                notifyError("Erro: " + e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) { notifyError("Aguarde..."); return; }
        downloading = true;
        
        new Thread(() -> {
            try {
                notifyStatus("Obtendo metadados...");
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
                            notifyStatus("Streaming: " + (totalSize/1048576) + "MB");
                            
                            // IGNORA tudo primeiro
                            byte_vector priorities = new byte_vector();
                            for (int i = 0; i < numPieces; i++) {
                                priorities.add((byte)0); // IGNORE
                            }
                            torrentHandle.prioritize_pieces_ex(priorities);
                            
                            // Ativa só as primeiras peças
                            activatePieces(0, INITIAL_PIECES);
                            
                            // Aguarda buffer inicial
                            waitForInitialBuffer(savePath);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro", e);
                notifyError("Erro: " + e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void activatePieces(int startPiece, int count) {
        if (torrentHandle == null || !torrentHandle.is_valid()) return;
        
        int endPiece = Math.min(startPiece + count, numPieces);
        
        // Desativa tudo fora do range
        for (int i = 0; i < numPieces; i++) {
            if (i >= startPiece && i < endPiece) {
                torrentHandle.piece_priority_ex(i, (byte)7);
                torrentHandle.set_piece_deadline(i, 1000);
            } else {
                torrentHandle.piece_priority_ex(i, (byte)0);
                torrentHandle.reset_piece_deadline(i);
            }
        }
        
        Log.d(TAG, "Buffer ativo: peças " + startPiece + "-" + endPiece + " (" + (count * pieceLength / 1048576) + "MB)");
    }
    
    private void waitForInitialBuffer(String savePath) {
        int lastPct = -1;
        
        while (downloading) {
            try {
                Thread.sleep(300); // Verifica rápido (300ms)
                
                // Conta peças completas no range inicial
                int complete = 0;
                for (int i = 0; i < INITIAL_PIECES; i++) {
                    if (torrentHandle.have_piece(i)) complete++;
                }
                
                int pct = (complete * 100) / INITIAL_PIECES;
                
                // Atualiza progresso
                torrent_status st = torrentHandle.status();
                TorrentInfo info = new TorrentInfo();
                info.progress = pct;
                info.downloaded = st.getTotal_done();
                info.speed = st.getDownload_rate();
                info.peers = st.getNum_peers();
                handler.post(() -> callback.onProgress(info));
                
                // Mostra progresso
                if (pct != lastPct && pct % 25 == 0) {
                    lastPct = pct;
                    notifyStatus("Buffer: " + pct + "% (" + (complete * pieceLength / 1048576) + "MB)");
                }
                
                // 80% das peças iniciais já bastam
                if (complete >= INITIAL_PIECES * 0.8f) {
                    File videoFile = findVideoFile(new File(savePath));
                    if (videoFile != null && videoFile.length() > pieceLength * 5) {
                        currentStreamPiece = INITIAL_PIECES;
                        
                        // Ativa mais peças à frente
                        activatePieces(currentStreamPiece, BUFFER_PIECES);
                        
                        notifyStatus("Streaming pronto! Buffer: " + (BUFFER_PIECES * pieceLength / 1048576) + "MB");
                        File f = videoFile;
                        handler.post(() -> callback.onStreamReady(f));
                        
                        // Gerencia buffer contínuo
                        manageStreamBuffer();
                        break;
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Erro buffer", e);
            }
        }
    }
    
    private void manageStreamBuffer() {
        Log.d(TAG, "Streaming ativo - gerenciando buffer...");
        
        while (downloading) {
            try {
                Thread.sleep(1000);
                
                if (torrentHandle != null && torrentHandle.is_valid()) {
                    torrent_status st = torrentHandle.status();
                    long downloaded = st.getTotal_done();
                    
                    // Peça atual baseada no download
                    int newPiece = pieceLength > 0 ? (int)(downloaded / pieceLength) : currentStreamPiece;
                    
                    // Só move o buffer se avançou pelo menos 5 peças
                    if (newPiece > currentStreamPiece + 5) {
                        currentStreamPiece = newPiece;
                        activatePieces(currentStreamPiece, BUFFER_PIECES);
                        Log.d(TAG, "Buffer movido para peça " + currentStreamPiece);
                    }
                    
                    // Atualiza UI
                    TorrentInfo info = new TorrentInfo();
                    info.progress = (int)(st.getProgress() * 100);
                    info.downloaded = downloaded;
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    handler.post(() -> callback.onProgress(info));
                    
                    // Verifica se buffer está cheio (mais de 90%)
                    int ahead = 0;
                    for (int i = currentStreamPiece; i < Math.min(currentStreamPiece + BUFFER_PIECES, numPieces); i++) {
                        if (torrentHandle.have_piece(i)) ahead++;
                    }
                    
                    if (ahead > BUFFER_PIECES * 0.9f) {
                        // Buffer cheio - pausa um pouco
                        Thread.sleep(2000);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro streaming", e);
            }
        }
    }
    
    public void seekTo(long bytePosition) {
        if (torrentHandle != null && pieceLength > 0) {
            int targetPiece = (int)(bytePosition / pieceLength);
            currentStreamPiece = targetPiece;
            activatePieces(targetPiece, BUFFER_PIECES);
            Log.d(TAG, "SEEK: peça " + targetPiece + " (byte " + bytePosition + ")");
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