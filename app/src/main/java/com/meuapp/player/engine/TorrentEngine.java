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
    
    private static final int BUFFER_PIECES = 20;
    private static final int DOWNLOAD_LIMIT = 2 * 1024 * 1024;
    private static final int INITIAL_PIECES = 10;
    
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
                Log.e(TAG, "Erro start", e);
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
                Log.d(TAG, "Torrents encontrados: " + handles.size());
                
                if (handles.size() > 0) {
                    torrentHandle = handles.get(0);
                    Log.d(TAG, "Handle válido: " + torrentHandle.is_valid());
                    
                    if (torrentHandle.is_valid()) {
                        int w = 0;
                        torrent_status st = torrentHandle.status();
                        
                        Log.d(TAG, "Aguardando metadados... has_metadata=" + st.getHas_metadata());
                        
                        while (!st.getHas_metadata() && w < 120 && downloading) {
                            Thread.sleep(1000);
                            w++;
                            st = torrentHandle.status();
                            if (w % 5 == 0) {
                                Log.d(TAG, "Metadados... " + w + "s has_metadata=" + st.getHas_metadata());
                                notifyStatus("Metadados... " + w + "s");
                            }
                        }
                        
                        Log.d(TAG, "Metadados: " + st.getHas_metadata() + " após " + w + "s");
                        
                        if (st.getHas_metadata()) {
                            numPieces = st.getNum_pieces();
                            totalSize = st.getTotal();
                            pieceLength = (int)(totalSize / Math.max(numPieces, 1));
                            
                            Log.d(TAG, "Peças: " + numPieces + " Tamanho: " + (totalSize/1048576) + "MB PieceLen: " + pieceLength);
                            notifyStatus("Streaming: " + (totalSize/1048576) + "MB");
                            
                            // IGNORA tudo
                            byte_vector priorities = new byte_vector();
                            for (int i = 0; i < numPieces; i++) {
                                priorities.add((byte)0);
                            }
                            torrentHandle.prioritize_pieces_ex(priorities);
                            Log.d(TAG, "Todas peças IGNORADAS");
                            
                            // Ativa só as primeiras
                            activatePieces(0, INITIAL_PIECES);
                            
                            waitForInitialBuffer(savePath);
                        }
                    }
                } else {
                    Log.e(TAG, "NENHUM TORRENT!");
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
    
    private void activatePieces(int startPiece, int count) {
        if (torrentHandle == null || !torrentHandle.is_valid()) {
            Log.w(TAG, "activatePieces: handle inválido");
            return;
        }
        
        int endPiece = Math.min(startPiece + count, numPieces);
        
        for (int i = 0; i < numPieces; i++) {
            if (i >= startPiece && i < endPiece) {
                torrentHandle.piece_priority_ex(i, (byte)7);
                torrentHandle.set_piece_deadline(i, 1000);
            } else {
                torrentHandle.piece_priority_ex(i, (byte)0);
                torrentHandle.reset_piece_deadline(i);
            }
        }
        
        Log.d(TAG, "Buffer ativo: " + startPiece + "-" + endPiece + " (" + (count * pieceLength / 1048576) + "MB)");
    }
    
    private void waitForInitialBuffer(String savePath) {
        Log.d(TAG, "Aguardando buffer inicial de " + INITIAL_PIECES + " peças...");
        
        while (downloading) {
            try {
                Thread.sleep(500);
                
                int complete = 0;
                for (int i = 0; i < INITIAL_PIECES; i++) {
                    if (torrentHandle.have_piece(i)) complete++;
                }
                
                int pct = (complete * 100) / INITIAL_PIECES;
                
                if (complete >= INITIAL_PIECES * 0.7f) {
                    File videoFile = findVideoFile(new File(savePath));
                    long fileLen = videoFile != null ? videoFile.length() : 0;
                    
                    Log.d(TAG, "Buffer OK! Peças: " + complete + "/" + INITIAL_PIECES + " Arquivo: " + (fileLen/1048576) + "MB");
                    
                    if (videoFile != null && fileLen > pieceLength * 5) {
                        currentStreamPiece = INITIAL_PIECES;
                        activatePieces(currentStreamPiece, BUFFER_PIECES);
                        
                        notifyStatus("Streaming pronto! Buffer: " + (BUFFER_PIECES * pieceLength / 1048576) + "MB");
                        File f = videoFile;
                        handler.post(() -> callback.onStreamReady(f));
                        
                        manageStreamBuffer();
                        break;
                    }
                }
                
                // Progresso
                torrent_status st = torrentHandle.status();
                TorrentInfo info = new TorrentInfo();
                info.progress = pct;
                info.downloaded = st.getTotal_done();
                info.speed = st.getDownload_rate();
                info.peers = st.getNum_peers();
                handler.post(() -> callback.onProgress(info));
                
                if (pct % 25 == 0) {
                    Log.d(TAG, "Buffer: " + pct + "% (" + complete + "/" + INITIAL_PIECES + " peças)");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Erro buffer", e);
            }
        }
    }
    
    private void manageStreamBuffer() {
        Log.d(TAG, "Gerenciando buffer de streaming...");
        
        while (downloading) {
            try {
                Thread.sleep(2000);
                
                if (torrentHandle != null && torrentHandle.is_valid()) {
                    torrent_status st = torrentHandle.status();
                    long downloaded = st.getTotal_done();
                    
                    int newPiece = pieceLength > 0 ? (int)(downloaded / pieceLength) : currentStreamPiece;
                    
                    if (newPiece > currentStreamPiece + 5) {
                        Log.d(TAG, "Avançando buffer: " + currentStreamPiece + " -> " + newPiece);
                        currentStreamPiece = newPiece;
                        activatePieces(currentStreamPiece, BUFFER_PIECES);
                    }
                    
                    // Conta peças disponíveis
                    int ahead = 0;
                    for (int i = currentStreamPiece; i < Math.min(currentStreamPiece + BUFFER_PIECES, numPieces); i++) {
                        if (torrentHandle.have_piece(i)) ahead++;
                    }
                    
                    TorrentInfo info = new TorrentInfo();
                    info.progress = (int)(st.getProgress() * 100);
                    info.downloaded = downloaded;
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    handler.post(() -> callback.onProgress(info));
                    
                    if (ahead > BUFFER_PIECES * 0.9f) {
                        Log.d(TAG, "Buffer cheio (" + ahead + "/" + BUFFER_PIECES + "), pausando...");
                        Thread.sleep(3000);
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
            Log.d(TAG, "SEEK: byte " + bytePosition + " -> peça " + targetPiece);
            currentStreamPiece = targetPiece;
            activatePieces(targetPiece, BUFFER_PIECES);
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