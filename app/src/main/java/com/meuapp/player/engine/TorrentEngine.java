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
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                Log.d(TAG, "Torrents: " + handles.size());
                
                if (handles.size() > 0) {
                    torrentHandle = handles.get(0);
                    
                    if (torrentHandle.is_valid()) {
                        int w = 0;
                        torrent_status st = torrentHandle.status();
                        
                        while (!st.getHas_metadata() && w < 60 && downloading) {
                            Thread.sleep(1000);
                            w++;
                            st = torrentHandle.status();
                            notifyStatus("Metadados... " + w + "s");
                        }
                        
                        if (st.getHas_metadata()) {
                            int numPieces = st.getNum_pieces();
                            notifyStatus("Baixando... " + (st.getTotal()/1048576) + "MB");
                            
                            byte_vector priorities = new byte_vector();
                            for (int i = 0; i < numPieces; i++) {
                                priorities.add((byte)(i < 100 ? 7 : 0));
                            }
                            torrentHandle.prioritize_pieces_ex(priorities);
                            
                            for (int i = 0; i < Math.min(50, numPieces); i++) {
                                torrentHandle.set_piece_deadline(i, 3000);
                            }
                            
                            monitorProgress(savePath);
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
    
    private void monitorProgress(String savePath) {
        File videoFile = null;
        
        while (downloading) {
            try {
                Thread.sleep(1000);
                
                if (torrentHandle != null && torrentHandle.is_valid()) {
                    torrent_status st = torrentHandle.status();
                    
                    TorrentInfo info = new TorrentInfo();
                    info.progress = (int)(st.getProgress() * 100);
                    info.downloaded = st.getTotal_done();
                    info.total = st.getTotal();
                    info.speed = st.getDownload_rate();
                    info.peers = st.getNum_peers();
                    
                    handler.post(() -> callback.onProgress(info));
                    
                    if (videoFile == null) {
                        videoFile = findVideoFile(new File(savePath));
                    }
                    
                    if (videoFile != null && videoFile.length() > 5242880) {
                        File f = videoFile;
                        handler.post(() -> callback.onStreamReady(f));
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro monitor", e);
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