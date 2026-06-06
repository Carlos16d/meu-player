package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
    private static final String TAG = "TorrentEngine";
    private SessionManager session;
    private TorrentHandle torrentHandle;
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
        Log.d(TAG, "TorrentEngine criado");
    }
    
    public void start() {
        new Thread(() -> {
            try {
                Log.d(TAG, "Iniciando SessionManager...");
                notifyStatus("Iniciando...");
                
                session = new SessionManager();
                session.start(new SessionParams());
                
                Log.d(TAG, "SessionManager iniciado: " + (session != null));
                Log.d(TAG, "swig(): " + (session.swig() != null));
                
                ready = true;
                notifyReady();
                notifyStatus("Pronto!");
                
            } catch (Exception e) {
                Log.e(TAG, "Erro ao iniciar", e);
                notifyError("Erro: " + e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) { 
            Log.w(TAG, "Engine não está pronta");
            notifyError("Aguarde..."); 
            return; 
        }
        
        downloading = true;
        
        new Thread(() -> {
            try {
                Log.d(TAG, "Iniciando download: " + magnetUri.substring(0, Math.min(60, magnetUri.length())));
                notifyStatus("Conectando...");
                
                File saveDir = new File(savePath);
                session.download(magnetUri, saveDir, new torrent_flags_t());
                
                Log.d(TAG, "Download chamado, aguardando 3s...");
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                Log.d(TAG, "Torrents ativos: " + handles.size());
                
                if (handles.size() > 0) {
                    torrent_handle th = handles.get(0);
                    Log.d(TAG, "Handle válido: " + th.is_valid());
                    
                    if (th.is_valid()) {
                        torrentHandle = new TorrentHandle(th);
                        
                        int w = 0;
                        while (!torrentHandle.status().hasMetadata() && w < 60 && downloading) {
                            Thread.sleep(1000);
                            w++;
                            Log.d(TAG, "Aguardando metadados... " + w + "s");
                            notifyStatus("Metadados... " + w + "s");
                        }
                        
                        Log.d(TAG, "Metadados recebidos: " + torrentHandle.status().hasMetadata());
                        
                        if (torrentHandle.status().hasMetadata()) {
                            int numPieces = torrentHandle.status().numPieces();
                            long totalSize = torrentHandle.status().total();
                            
                            Log.d(TAG, "Peças: " + numPieces + ", Tamanho: " + (totalSize/1048576) + "MB");
                            notifyStatus("Baixando... " + (totalSize/1048576) + "MB");
                            
                            Priority[] p = new Priority[numPieces];
                            for (int i = 0; i < numPieces; i++) {
                                p[i] = (i < 100) ? Priority.TOP_PRIORITY : Priority.IGNORE;
                            }
                            torrentHandle.prioritizePieces(p);
                            Log.d(TAG, "Prioridades configuradas");
                            
                            for (int i = 0; i < Math.min(100, numPieces); i++) {
                                torrentHandle.setPieceDeadline(i, 5000);
                            }
                            Log.d(TAG, "Deadlines configurados");
                            
                            monitorProgress(savePath);
                        }
                    }
                } else {
                    Log.e(TAG, "Nenhum torrent encontrado");
                    notifyError("Nenhum peer encontrado");
                    downloading = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro no download", e);
                notifyError("Erro: " + e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private void monitorProgress(String savePath) {
        File videoFile = null;
        Log.d(TAG, "Monitorando progresso...");
        
        while (downloading) {
            try {
                Thread.sleep(1000);
                
                if (torrentHandle != null && torrentHandle.isValid()) {
                    TorrentStatus st = torrentHandle.status();
                    
                    TorrentInfo info = new TorrentInfo();
                    info.progress = (int)(st.progress() * 100);
                    info.downloaded = st.totalDone();
                    info.speed = st.downloadRate();
                    info.peers = st.numPeers();
                    
                    Log.d(TAG, "Progresso: " + info.progress + "% | " + 
                          (info.downloaded/1048576) + "MB | " + 
                          info.peers + " peers | " + 
                          (info.speed/1024) + "KB/s");
                    
                    handler.post(() -> callback.onProgress(info));
                    
                    if (videoFile == null) {
                        videoFile = findVideoFile(new File(savePath));
                        Log.d(TAG, "Procurando vídeo: " + (videoFile != null ? videoFile.getName() : "não encontrado"));
                    }
                    
                    if (videoFile != null && videoFile.length() > 10485760) {
                        Log.d(TAG, "Vídeo pronto para streaming! " + (videoFile.length()/1048576) + "MB");
                        File f = videoFile;
                        handler.post(() -> callback.onStreamReady(f));
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro no monitor", e);
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
        Log.d(TAG, "Parando engine");
        downloading = false;
        if (session != null) try { session.stop(); } catch (Exception e) {}
    }
    
    public void destroy() {
        Log.d(TAG, "Destruindo engine");
        stop();
        if (session != null) try { session.stop(); } catch (Exception e) {}
    }
    
    public boolean isReady() { return ready; }
    private void notifyReady() { handler.post(() -> callback.onReady()); }
    private void notifyError(String msg) { handler.post(() -> callback.onError(msg)); }
    private void notifyStatus(String msg) { handler.post(() -> callback.onStatus(msg)); }
}