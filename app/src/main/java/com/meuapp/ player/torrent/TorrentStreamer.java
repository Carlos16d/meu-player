package com.meuapp.player.torrent;

import android.os.Handler;
import android.os.Looper;

import com.meuapp.player.model.StreamInfo;

import org.libtorrent4j.swig.byte_vector;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Set;

/**
 * Gerencia o download das peças do torrent.
 * Implementa buffer inteligente e seek rápido.
 */
public class TorrentStreamer {
    private final TorrentSession session;
    private final StreamInfo info;
    private final Handler mainHandler;
    private final StreamerCallback callback;
    
    private boolean sequentialActive = true;
    private boolean seeking = false;
    private int currentPiece = -1;
    
    // Cache para evitar recriação de byte_vector
    private byte_vector cachedPriorities;
    
    public interface StreamerCallback {
        void onReady();
        void onProgress(String msg);
        void onLog(String msg);
    }
    
    public TorrentStreamer(TorrentSession session, StreamInfo info, StreamerCallback callback) {
        this.session = session;
        this.info = info;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Pré-carrega peças essenciais: cabeçalho + final + meio
     */
    public void preload() {
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            
            int inicio = Math.min(35, info.numPieces);
            int fim = Math.min(10, info.numPieces);
            int fimStart = info.numPieces - fim;
            
            log("📋 PRÉ-CARGA: [0-" + (inicio-1) + "] + [" + fimStart + "-" + (info.numPieces-1) + "]");
            
            // Resetar prioridades
            byte_vector z = info.getCachedPriorities(info.numPieces);
            session.prioritizePieces(z);
            
            // Prioridade máxima para cabeçalho e final
            for (int i = 0; i < inicio; i++) {
                session.setPiecePriority(i, (byte)7);
                session.setPieceDeadline(i, 20000);
            }
            for (int i = fimStart; i < info.numPieces; i++) {
                session.setPiecePriority(i, (byte)7);
                session.setPieceDeadline(i, 20000);
            }
            
            // Aguardar
            int doneIni = 0, doneFim = 0;
            while ((doneIni < inicio || doneFim < fim) && !Thread.interrupted()) {
                try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                doneIni = 0;
                for (int i = 0; i < inicio; i++) if (session.hasPiece(i)) doneIni++;
                doneFim = 0;
                for (int i = fimStart; i < info.numPieces; i++) if (session.hasPiece(i)) doneFim++;
            }
            
            long elapsed = (System.currentTimeMillis() - t0) / 1000;
            log("✅ Pré-carga: " + doneIni + "/" + inicio + " | " + doneFim + "/" + fim + " (" + elapsed + "s)");
            
            // Encontrar arquivo de vídeo
            for (int i = 0; i < 15; i++) {
                File f = findVideoFile(new File(info.videoFile.getParent()));
                if (f != null && f.length() > 10 * 1048576) {
                    info.videoFile = f;
                    break;
                }
                try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            }
            
            // Criar sparse file para VLC ver tamanho total
            if (info.videoFile != null && info.videoFile.length() < info.totalSize) {
                try {
                    RandomAccessFile raf = new RandomAccessFile(info.videoFile, "rw");
                    raf.setLength(info.totalSize);
                    raf.close();
                    log("📏 Sparse: " + info.sizeToString());
                } catch (Exception e) {}
            }
            
            // Parsear SeekHead e baixar peças críticas
            SeekHeadParser parser = new SeekHeadParser(info);
            Set<Integer> critical = parser.parse();
            
            if (!critical.isEmpty()) {
                log("📥 Complementando " + critical.size() + " peças críticas");
                z = info.getCachedPriorities(info.numPieces);
                session.prioritizePieces(z);
                
                for (int piece : critical) {
                    if (piece < info.numPieces) {
                        session.setPiecePriority(piece, (byte)7);
                        session.setPieceDeadline(piece, 15000);
                    }
                }
                
                int done = 0;
                int total = critical.size();
                while (done < total && !Thread.interrupted()) {
                    try { Thread.sleep(150); } catch (InterruptedException e) { break; }
                    done = 0;
                    for (int piece : critical) {
                        if (piece < info.numPieces && session.hasPiece(piece)) done++;
                    }
                    
                    int pct = total > 0 ? done * 100 / total : 0;
                    mainHandler.post(() -> callback.onProgress("📥 Metadados: " + pct + "% | " + (info.downloadRate/1024) + " KB/s"));
                }
            }
            
            mainHandler.post(() -> callback.onReady());
        }, "TorrentStreamer").start();
    }
    
    /**
     * Mantém buffer de 5-10 peças à frente da posição atual
     */
    public void maintainBuffer(int piece) {
        if (!info.metadataReady || seeking || sequentialActive) return;
        
        if (piece == currentPiece) return;
        currentPiece = piece;
        
        // Contar buffer disponível
        int buffered = 0;
        for (int i = piece; i < Math.min(info.numPieces, piece + 20); i++) {
            if (session.hasPiece(i)) buffered++;
            else break;
        }
        
        if (buffered < 5) {
            int start = piece + buffered;
            int end = Math.min(info.numPieces - 1, start + 10);
            
            // Usar setSequentialRange para foco total
            session.setSequentialRange(start, end);
            
            for (int i = start; i <= end; i++) {
                if (!session.hasPiece(i)) {
                    session.setPiecePriority(i, (byte)7);
                    session.setPieceDeadline(i, 5000);
                }
            }
            
            log("📥 Buffer " + buffered + " → " + start + "-" + end);
        }
    }
    
    /**
     * Seek rápido: baixa apenas 3 peças (alvo + 1 atrás + 1 frente)
     */
    public boolean seekToPiece(int piece, long timeoutMs) {
        seeking = true;
        
        try {
            // Desativar sequential se ativo
            if (sequentialActive) {
                sequentialActive = false;
                session.disableSequential();
            }
            
            // Verificar se já tem as 3 peças
            boolean hasAll = true;
            for (int i = piece - 1; i <= piece + 1; i++) {
                if (i >= 0 && i < info.numPieces && !session.hasPiece(i)) hasAll = false;
            }
            if (hasAll) {
                currentPiece = piece;
                return true;
            }
            
            // Prioridade ABSOLUTA: 3 peças
            byte_vector z = info.getCachedPriorities(info.numPieces);
            session.prioritizePieces(z);
            
            // Força foco total no range
            session.setSequentialRange(Math.max(0, piece - 1), Math.min(info.numPieces - 1, piece + 1));
            
            for (int i = piece - 1; i <= piece + 1; i++) {
                if (i >= 0 && i < info.numPieces) {
                    session.setPiecePriority(i, (byte)(i == piece ? 7 : 6));
                    session.setPieceDeadline(i, 2000);
                }
            }
            
            // Aguardar com timeout
            long t0 = System.currentTimeMillis();
            while ((System.currentTimeMillis() - t0) < timeoutMs && !Thread.interrupted()) {
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
                
                boolean ok = true;
                StringBuilder sb = new StringBuilder("⏳ ");
                for (int i = piece - 1; i <= piece + 1; i++) {
                    if (i >= 0 && i < info.numPieces) {
                        boolean h = session.hasPiece(i);
                        sb.append("p").append(i).append(h ? "✅ " : "⬜ ");
                        if (!h) ok = false;
                    }
                }
                log(sb.toString().trim());
                
                if (ok) {
                    currentPiece = piece;
                    return true;
                }
                
                // Reforçar deadlines
                for (int i = piece - 1; i <= piece + 1; i++) {
                    if (i >= 0 && i < info.numPieces && !session.hasPiece(i)) {
                        session.setPieceDeadline(i, 2000);
                    }
                }
            }
            
            return false;
        } finally {
            seeking = false;
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
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm)$")) {
                    return f;
                }
            }
        }
        return null;
    }
    
    private void log(String msg) {
        mainHandler.post(() -> callback.onLog(msg));
    }
    
    public void reset() {
        sequentialActive = true;
        seeking = false;
        currentPiece = -1;
    }
}
