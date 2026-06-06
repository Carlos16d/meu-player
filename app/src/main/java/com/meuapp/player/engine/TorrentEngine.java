package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;

import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.Priority;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.torrent_flags_t;
import org.libtorrent4j.swig.torrent_handle;
import org.libtorrent4j.swig.torrent_handle_vector;

import java.io.*;

public class TorrentEngine {
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private boolean ready = false;
    private boolean downloading = false;
    private Handler handler;
    private EngineCallback callback;
    
    private int pieceLength = 0;
    private long fileSize = 0;
    
    // Aguarda pelo menos 30MB antes de liberar streaming
    private static final long MIN_STREAMING_BYTES = 30 * 1024 * 1024;
    
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
                notifyStatus("Iniciando motor P2P...");
                
                session = new SessionManager();
                SessionParams params = new SessionParams();
                params.settings().setConnectionsLimit(50);
                params.settings().setActiveDownloads(3);
                params.settings().setDownloadRateLimit(0);
                params.settings().setUploadRateLimit(1024 * 1024); // 1MB upload
                
                session.start(params);
                
                ready = true;
                notifyReady();
                notifyStatus("Motor P2P pronto!");
                
            } catch (Exception e) {
                notifyError("Erro: " + e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) { 
            notifyError("Aguarde o motor iniciar..."); 
            return; 
        }
        
        downloading = true;
        
        new Thread(() -> {
            try {
                notifyStatus("Obtendo metadados...");
                
                File saveDir = new File(savePath);
                torrent_flags_t flags = new torrent_flags_t();
                flags = flags.or_(torrent_flags_t.sequential_download);
                
                session.download(magnetUri, saveDir, flags);
                Thread.sleep(5000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() > 0) {
                    torrent_handle th = handles.get(0);
                    if (th.is_valid()) {
                        torrentHandle = new TorrentHandle(th);
                        
                        // Aguarda metadados
                        int waitCount = 0;
                        while (!torrentHandle.status().hasMetadata() && waitCount < 60 && downloading) {
                            Thread.sleep(1000);
                            waitCount++;
                            notifyStatus("Metadados... " + waitCount + "s");
                        }
                        
                        if (torrentHandle.status().hasMetadata()) {
                            fileSize = torrentHandle.status().total();
                            pieceLength = fileSize / Math.max(torrentHandle.status().numPieces(), 1);
                            
                            notifyStatus("Metadados recebidos! " + (fileSize/1048576) + "MB");
                            
                            // Prioridade máxima nas primeiras peças
                            int numPieces = torrentHandle.status().numPieces();
                            int priorityPieces = Math.min(300, numPieces);
                            
                            Priority[] priorities = new Priority[numPieces];
                            for (int i = 0; i < numPieces; i++) {
                                if (i < priorityPieces) {
                                    priorities[i] = Priority.MAX;
                                } else {
                                    priorities[i] = Priority.LOW;
                                }
                            }
                            torrentHandle.prioritizePieces(priorities);
                            
                            // Deadlines agressivos nas primeiras peças
                            for (int i = 0; i < Math.min(100, numPieces); i++) {
                                torrentHandle.setPieceDeadline(i, 3000);
                            }
                            
                            notifyStatus("Baixando para streaming...");
                            monitorProgress(savePath);
                        }
                    }
                }
            } catch (Exception e) {
                notifyError("Erro: " + e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void monitorProgress(String savePath) {
        File videoFile = null;
        boolean streamReady = false;
        
        while (downloading) {
            try {
                Thread.sleep(1000);
                
                TorrentInfo info = new TorrentInfo();
                
                if (torrentHandle != null && torrentHandle.isValid()) {
                    TorrentStatus status = torrentHandle.status();
                    
                    info.progress = (int)(status.progress() * 100);
                    info.downloaded = status.totalDone();
                    info.total = status.total();
                    info.speed = status.downloadRate();
                    info.peers = status.numPeers();
                    info.seeds = status.numSeeds();
                    
                    // Verifica se já pode fazer streaming
                    if (!streamReady && status.totalDone() >= MIN_STREAMING_BYTES) {
                        // Verifica primeiras peças
                        int firstPieces = 0;
                        int totalFirstPieces = Math.min(100, status.numPieces());
                        for (int i = 0; i < totalFirstPieces; i++) {
                            if (torrentHandle.havePiece(i)) firstPieces++;
                        }
                        
                        // Precisa ter pelo menos 80% das primeiras peças
                        if (firstPieces >= totalFirstPieces * 0.8) {
                            streamReady = true;
                            videoFile = findVideoFile(new File(savePath));
                            if (videoFile != null && videoFile.length() >= MIN_STREAMING_BYTES) {
                                File f = videoFile;
                                long mb = status.totalDone() / 1048576;
                                notifyStatus("Streaming liberado! " + mb + "MB iniciais");
                                handler.post(() -> callback.onStreamReady(f));
                            }
                        }
                    }
                    
                    // Atualiza deadlines conforme o download avança
                    if (streamReady && pieceLength > 0) {
                        long downloadedBytes = status.totalDone();
                        int currentPiece = (int)(downloadedBytes / pieceLength);
                        // Mantém deadline nas próximas peças
                        for (int i = currentPiece; i < Math.min(currentPiece + 50, status.numPieces()); i++) {
                            torrentHandle.setPieceDeadline(i, 5000);
                        }
                    }
                }
                
                if (info.progress == 0) info.progress = 2;
                
                handler.post(() -> callback.onProgress(info));
                
            } catch (Exception e) {
                // continua
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
        if (session != null) {
            try { session.stop(); } catch (Exception e) {}
        }
    }
    
    public void destroy() {
        stop();
        if (session != null) {
            try { session.stop(); } catch (Exception e) {}
        }
    }
    
    public boolean isReady() { return ready; }
    
    private void notifyReady() { handler.post(() -> callback.onReady()); }
    private void notifyError(String msg) { handler.post(() -> callback.onError(msg)); }
    private void notifyStatus(String msg) { handler.post(() -> callback.onStatus(msg)); }
}