package com.meuapp.player.torrent;

import android.os.Handler;
import android.os.Looper;

import com.meuapp.player.model.StreamInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.swig.*;

import java.io.File;

/**
 * Gerencia a sessão do libtorrent.
 * Todas as operações são thread-safe com lock interno.
 */
public class TorrentSession {
    private final Object lock = new Object();
    private SessionManager session;
    private TorrentHandle handle;
    private final StreamInfo info;
    private final Handler mainHandler;
    private final SessionCallback callback;
    
    public interface SessionCallback {
        void onMetadataReady();
        void onError(String error);
        void onLog(String msg);
    }
    
    public TorrentSession(StreamInfo info, SessionCallback callback) {
        this.info = info;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Inicia a sessão libtorrent em background
     */
    public void start() {
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                log("✅ Sessão OK");
            } catch (Exception e) {
                error("❌ Sessão: " + e.getMessage());
            }
        }, "TorrentSession").start();
    }
    
    /**
     * Inicia o download do magnet/torrent
     */
    public void startDownload(String source, String savePath) {
        new Thread(() -> {
            try {
                add_torrent_params p;
                
                if (source.startsWith("magnet:")) {
                    p = libtorrent.parse_magnet_uri(source, new error_code());
                } else {
                    p = add_torrent_params.load_torrent_file(source, new error_code());
                }
                
                p.setSave_path(savePath);
                torrent_flags_t flags = libtorrent.getAuto_managed()
                    .or_(libtorrent.getSequential_download())
                    .or_(libtorrent.getApply_ip_filter());
                p.setFlags(flags);
                p.setDownload_limit(3 * 1024 * 1024);
                
                byte_vector pr = new byte_vector();
                pr.add((byte) 7);
                p.set_file_priorities(pr);
                
                synchronized (lock) {
                    session.swig().async_add_torrent(p);
                    Thread.sleep(2000);
                    
                    torrent_handle_vector h = session.swig().get_torrents();
                    if (h.size() > 0) {
                        handle = new TorrentHandle(h.get(0));
                    }
                }
                
                // Aguardar metadados
                int w = 0;
                while (w < 60) {
                    Thread.sleep(1000);
                    w++;
                    synchronized (lock) {
                        if (handle != null && handle.isValid()) {
                            TorrentInfo ti = handle.torrentFile();
                            if (ti != null && ti.isValid()) {
                                info.pieceLength = ti.pieceLength();
                                info.numPieces = ti.numPieces();
                                info.totalSize = ti.totalSize();
                                info.metadataReady = true;
                                updateStats();
                                log("📊 " + info.sizeToString() + " | " + info.numPieces + 
                                    " peças | " + info.seeds + " Seeds | " + info.peers + " Peers");
                                mainHandler.post(() -> callback.onMetadataReady());
                                return;
                            }
                        }
                    }
                }
                
                error("Timeout metadados");
            } catch (Exception e) {
                error("❌ Download: " + e.getMessage());
            }
        }, "TorrentDownload").start();
    }
    
    /**
     * Atualiza estatísticas de seeds/peers/download
     */
    public void updateStats() {
        synchronized (lock) {
            if (handle != null && handle.isValid()) {
                try {
                    torrent_status st = handle.swig().status();
                    info.seeds = st.getNum_seeds();
                    info.peers = st.getNum_peers();
                    info.downloadRate = st.getDownload_rate();
                    info.totalDownloaded = st.getTotal_done();
                } catch (Exception e) {}
            }
        }
    }
    
    // ==================== OPERAÇÕES THREAD-SAFE ====================
    
    /**
     * Verifica se a peça foi baixada (thread-safe)
     */
    public boolean hasPiece(int piece) {
        synchronized (lock) {
            try {
                return handle != null && handle.isValid() && handle.havePiece(piece);
            } catch (Exception e) {
                return false;
            }
        }
    }
    
    /**
     * Define prioridade de uma peça (0-7)
     */
    public void setPiecePriority(int piece, byte priority) {
        synchronized (lock) {
            try {
                if (handle != null && handle.isValid()) {
                    handle.swig().piece_priority_ex(piece, priority);
                }
            } catch (Exception e) {}
        }
    }
    
    /**
     * Define deadline para download de uma peça (ms)
     */
    public void setPieceDeadline(int piece, int deadline) {
        synchronized (lock) {
            try {
                if (handle != null && handle.isValid()) {
                    handle.swig().set_piece_deadline(piece, deadline);
                }
            } catch (Exception e) {}
        }
    }
    
    /**
     * Força download sequencial de um range de peças
     */
    public void setSequentialRange(int first, int last) {
        synchronized (lock) {
            try {
                if (handle != null && handle.isValid()) {
                    handle.setSequentialRange(first, last);
                }
            } catch (Exception e) {}
        }
    }
    
    /**
     * Define prioridades de todas as peças de uma vez
     */
    public void prioritizePieces(byte_vector priorities) {
        synchronized (lock) {
            try {
                if (handle != null && handle.isValid()) {
                    handle.swig().prioritize_pieces_ex(priorities);
                }
            } catch (Exception e) {}
        }
    }
    
    /**
     * Desativa o download sequencial
     */
    public void disableSequential() {
        synchronized (lock) {
            try {
                if (handle != null && handle.isValid()) {
                    torrent_flags_t f = handle.swig().flags();
                    handle.swig().set_flags(f.and_(libtorrent.getSequential_download().inv()));
                }
            } catch (Exception e) {}
        }
    }
    
    /**
     * Para a sessão e remove o torrent
     */
    public void stop() {
        synchronized (lock) {
            if (handle != null && session != null) {
                try {
                    session.remove(handle);
                } catch (Exception e) {}
                handle = null;
            }
        }
        if (session != null) {
            session.stop();
            session = null;
        }
    }
    
    private void log(String msg) {
        mainHandler.post(() -> callback.onLog(msg));
    }
    
    private void error(String msg) {
        mainHandler.post(() -> callback.onError(msg));
    }
}
