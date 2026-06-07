package com.meuapp.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class TorrentEngine {
    private static final String TAG = "TorrentEngine";
    private SessionManager session;
    private torrent_handle torrentHandle;
    private boolean ready = false;
    private boolean downloading = false;
    private Handler handler;
    private EngineCallback callback;
    
    // Estatísticas detalhadas
    private int numPieces = 0;
    private long totalSize = 0;
    private int pieceLength = 0;
    private long downloadStartTime = 0;
    private long totalBytesDownloaded = 0;
    private int currentStreamPiece = 0;
    
    private static final int BUFFER_PIECES_AHEAD = 30;
    private static final int DOWNLOAD_LIMIT = 3 * 1024 * 1024; // 3 MB/s
    
    public interface EngineCallback {
        void onReady();
        void onError(String error);
        void onProgress(TorrentInfo info);
        void onStreamReady(File videoFile);
        void onStatus(String status);
        void onLog(String log); // NOVO: log detalhado
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
                SessionParams params = new SessionParams();
                session.start(params);
                
                // Verifica se a sessão está viva
                boolean swigOk = session.swig() != null;
                log("   session.swig() = " + (swigOk ? "OK" : "NULL!"));
                
                if (!swigOk) {
                    log("❌ session.swig() retornou NULL - sessão não inicializou!");
                    notifyError("Sessão P2P falhou ao inicializar");
                    return;
                }
                
                ready = true;
                log("✅ Engine P2P pronto!");
                notifyReady();
                
            } catch (Exception e) {
                log("❌ Erro ao iniciar: " + e.getMessage());
                Log.e(TAG, "Erro start", e);
                notifyError(e.getMessage());
            }
        }).start();
    }
    
    public void startDownload(String magnetUri, String savePath) {
        if (!ready) {
            log("❌ Engine não está pronto!");
            notifyError("Aguarde a engine iniciar...");
            return;
        }
        
        downloading = true;
        downloadStartTime = System.currentTimeMillis();
        
        new Thread(() -> {
            try {
                log("🔗 Iniciando download do magnet...");
                log("   URI: " + magnetUri.substring(0, Math.min(80, magnetUri.length())) + "...");
                log("   SavePath: " + savePath);
                
                File saveDir = new File(savePath);
                
                // PASSO 1: Adiciona o torrent
                log("📥 Adicionando torrent à sessão...");
                session.download(magnetUri, saveDir, new torrent_flags_t());
                
                // PASSO 2: Aguarda o torrent aparecer
                log("⏳ Aguardando torrent aparecer na lista...");
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                log("   Torrents na sessão: " + handles.size());
                
                if (handles.size() == 0) {
                    log("❌ Nenhum torrent encontrado após 3 segundos!");
                    log("   Possíveis causas: tracker offline, sem peers, magnet inválido");
                    notifyError("Nenhum peer encontrado. Tente outro magnet.");
                    downloading = false;
                    return;
                }
                
                // PASSO 3: Obtém o handle
                torrentHandle = handles.get(0);
                log("   Handle obtido: " + (torrentHandle.is_valid() ? "VÁLIDO" : "INVÁLIDO"));
                
                if (!torrentHandle.is_valid()) {
                    log("❌ Handle do torrent é inválido!");
                    notifyError("Torrent inválido");
                    downloading = false;
                    return;
                }
                
                // PASSO 4: Aguarda metadados
                log("⏳ Aguardando metadados (nome, tamanho, peças)...");
                torrent_status st = torrentHandle.status();
                int waitSeconds = 0;
                
                while (!st.getHas_metadata() && waitSeconds < 120 && downloading) {
                    Thread.sleep(1000);
                    waitSeconds++;
                    st = torrentHandle.status();
                    
                    if (waitSeconds % 5 == 0) {
                        log("   Aguardando metadados... " + waitSeconds + "s");
                        log("   has_metadata=" + st.getHas_metadata() + 
                            " state=" + st.getState() +
                            " peers=" + st.getNum_peers());
                    }
                }
                
                log("   Metadados após " + waitSeconds + "s: " + (st.getHas_metadata() ? "RECEBIDOS" : "TIMEOUT"));
                
                if (!st.getHas_metadata()) {
                    log("❌ Timeout ao obter metadados (120s)");
                    notifyError("Timeout - não foi possível obter metadados");
                    downloading = false;
                    return;
                }
                
                // PASSO 5: Extrai informações
                numPieces = st.getNum_pieces();
                totalSize = st.getTotal();
                pieceLength = (int)(totalSize / Math.max(numPieces, 1));
                
                log("📊 INFORMAÇÕES DO TORRENT:");
                log("   Nome: " + st.getName());
                log("   Tamanho: " + (totalSize/1048576) + " MB");
                log("   Peças: " + numPieces);
                log("   Tamanho por peça: " + (pieceLength/1024) + " KB");
                log("   Peers conectados: " + st.getNum_peers());
                log("   Seeds: " + st.getNum_seeds());
                log("   Download rate: " + (st.getDownload_rate()/1024) + " KB/s");
                
                // PASSO 6: Configura prioridades para streaming
                log("🎯 Configurando prioridades para streaming...");
                
                // IGNORA todas as peças primeiro
                byte_vector priorities = new byte_vector();
                for (int i = 0; i < numPieces; i++) {
                    priorities.add((byte)0);
                }
                torrentHandle.prioritize_pieces_ex(priorities);
                log("   Todas as " + numPieces + " peças marcadas como IGNORE");
                
                // Ativa apenas as primeiras peças
                int initialPieces = Math.min(20, numPieces);
                for (int i = 0; i < initialPieces; i++) {
                    torrentHandle.piece_priority_ex(i, (byte)7);
                    torrentHandle.set_piece_deadline(i, 2000);
                }
                log("   Primeiras " + initialPieces + " peças ativadas com prioridade MÁXIMA");
                
                // PASSO 7: Aguarda buffer inicial
                log("⏳ Aguardando buffer inicial (" + initialPieces + " peças)...");
                waitForInitialBuffer(savePath, initialPieces);
                
            } catch (Exception e) {
                log("❌ ERRO FATAL: " + e.getMessage());
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
                
                // Conta peças completas
                int complete = 0;
                for (int i = 0; i < targetPieces; i++) {
                    if (torrentHandle.have_piece(i)) complete++;
                }
                
                int percent = (complete * 100) / targetPieces;
                torrent_status st = torrentHandle.status();
                
                // Log a cada 10% ou a cada 5 segundos
                long elapsed = (System.currentTimeMillis() - waitStart) / 1000;
                if (percent != lastLogPercent && (percent % 10 == 0 || elapsed % 5 == 0)) {
                    lastLogPercent = percent;
                    log("   Buffer: " + percent + "% (" + complete + "/" + targetPieces + " peças)" +
                        " | Download: " + (st.getTotal_done()/1048576) + "MB" +
                        " | Speed: " + (st.getDownload_rate()/1024) + "KB/s" +
                        " | Peers: " + st.getNum_peers() +
                        " | Tempo: " + elapsed + "s");
                }
                
                // Atualiza progresso na UI
                TorrentInfo info = new TorrentInfo();
                info.progress = percent;
                info.downloaded = st.getTotal_done();
                info.total = totalSize;
                info.speed = st.getDownload_rate();
                info.peers = st.getNum_peers();
                info.seeds = st.getNum_seeds();
                handler.post(() -> callback.onProgress(info));
                
                // 80% das peças iniciais = suficiente
                if (complete >= targetPieces * 0.8f) {
                    long bufferTime = (System.currentTimeMillis() - waitStart) / 1000;
                    
                    File videoFile = findVideoFile(new File(savePath));
                    long fileLen = videoFile != null ? videoFile.length() : 0;
                    
                    log("✅ BUFFER INICIAL COMPLETO!");
                    log("   Peças: " + complete + "/" + targetPieces);
                    log("   Tempo: " + bufferTime + "s");
                    log("   Arquivo: " + (fileLen/1048576) + "MB");
                    log("   Download total: " + (st.getTotal_done()/1048576) + "MB");
                    log("   Velocidade média: " + (st.getTotal_done()/Math.max(bufferTime,1)/1024) + "KB/s");
                    
                    if (videoFile != null && fileLen > pieceLength * 5) {
                        currentStreamPiece = targetPieces;
                        
                        // Ativa mais peças à frente
                        for (int i = targetPieces; i < Math.min(targetPieces + BUFFER_PIECES_AHEAD, numPieces); i++) {
                            torrentHandle.piece_priority_ex(i, (byte)6);
                            torrentHandle.set_piece_deadline(i, 3000);
                        }
                        log("   Buffer estendido para " + BUFFER_PIECES_AHEAD + " peças à frente");
                        
                        File f = videoFile;
                        handler.post(() -> callback.onStreamReady(f));
                        
                        // Continua gerenciando buffer
                        manageStreamBuffer();
                        break;
                    }
                }
                
            } catch (Exception e) {
                log("❌ Erro no buffer: " + e.getMessage());
            }
        }
    }
    
    private void manageStreamBuffer() {
        log("🔄 Gerenciando buffer de streaming...");
        
        while (downloading) {
            try {
                Thread.sleep(2000);
                
                if (torrentHandle != null && torrentHandle.is_valid()) {
                    torrent_status st = torrentHandle.status();
                    long downloaded = st.getTotal_done();
                    
                    // Calcula peça atual
                    int currentPiece = pieceLength > 0 ? (int)(downloaded / pieceLength) : currentStreamPiece;
                    
                    // Move o buffer se avançou
                    if (currentPiece > currentStreamPiece + 5) {
                        log("   Buffer movendo: " + currentStreamPiece + " -> " + currentPiece);
                        currentStreamPiece = currentPiece;
                        
                        // Ativa novas peças, desativa antigas
                        for (int i = 0; i < numPieces; i++) {
                            if (i >= currentStreamPiece && i < currentStreamPiece + BUFFER_PIECES_AHEAD) {
                                torrentHandle.piece_priority_ex(i, (byte)7);
                            } else if (i < currentStreamPiece - 10) {
                                torrentHandle.piece_priority_ex(i, (byte)0);
                            }
                        }
                    }
                    
                    // Atualiza UI
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