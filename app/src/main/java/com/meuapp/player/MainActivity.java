package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView statusText, logText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    private ScrollView logScroll;
    
    private String savePath;
    private SessionManager session;
    private AtomicReference<torrent_handle> torrentRef = new AtomicReference<>();
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private long fileLength;
    private int numPieces;
    private int lastPrioritizedPiece = -1;
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        playerView.post(() -> {
            int width = (int)(getResources().getDisplayMetrics().widthPixels * 0.92);
            int height = (int)(width * 9.0 / 16.0);
            ViewGroup.LayoutParams p = playerView.getLayoutParams();
            p.width = width;
            p.height = height;
            playerView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setVisibility(View.GONE);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loadingOverlay.setVisibility(View.GONE);
                    spinnerBar.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    loadingOverlay.setVisibility(View.VISIBLE);
                    spinnerBar.setVisibility(View.VISIBLE);
                }
            }
        });
        
        // Atualiza prioridade a cada 3 segundos
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (downloading && player != null && player.isPlaying()) {
                    prioritizePlayerPosition();
                }
                if (downloading) handler.postDelayed(this, 3000);
            }
        }, 3000);
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); log("✅ Sessão OK"); } 
            catch (Exception e) { log("❌ Sessão: " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        log("📱 Pronto");
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        fullLog.append(line);
        handler.post(() -> {
            statusText.setText(msg);
            logText.setText(fullLog.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    private void prioritizePlayerPosition() {
        torrent_handle th = torrentRef.get();
        if (th == null || !th.is_valid() || numPieces <= 0) return;
        
        long pos = player.getCurrentPosition();
        long duration = player.getDuration();
        if (duration <= 0) return;
        
        int currentPiece = (int)(((double)pos / duration) * numPieces);
        
        // Só loga se mudou a posição
        if (currentPiece != lastPrioritizedPiece) {
            lastPrioritizedPiece = currentPiece;
            
            // Calcula qual parte do vídeo (em minutos)
            int minutos = (int)(pos / 60000);
            int segundos = (int)((pos % 60000) / 1000);
            
            log("🎯 Player em " + minutos + ":" + String.format("%02d", segundos) + 
                " → Priorizando peça " + currentPiece + "/" + numPieces);
            
            // Mostra quais peças estão sendo baixadas
            try {
                torrent_status ts = th.status();
                float progress = ts.getProgress() * 100;
                int downloadRate = (int)(ts.getDownload_rate() / 1024);
                log("   📥 " + String.format("%.1f", progress) + "% | " + downloadRate + " KB/s | " + 
                    ts.getNum_peers() + " peers");
                
                // Verifica se a peça atual já foi baixada
                boolean hasPiece = th.have_piece(currentPiece);
                log("   🔍 Peça " + currentPiece + ": " + (hasPiece ? "✅ JÁ BAIXADA" : "⏳ BAIXANDO..."));
                
                // Verifica peças ao redor
                int baixadas = 0;
                int total = 0;
                for (int i = Math.max(0, currentPiece - 10); i < Math.min(numPieces, currentPiece + 30); i++) {
                    total++;
                    if (th.have_piece(i)) baixadas++;
                }
                log("   📊 Peças " + Math.max(0, currentPiece - 10) + "-" + 
                    Math.min(numPieces - 1, currentPiece + 29) + ": " + baixadas + "/" + total + " baixadas");
                
            } catch (Exception e) {
                log("   ⚠️ Erro ao verificar status");
            }
        }
        
        // Prioriza peças
        try {
            byte_vector priorities = new byte_vector();
            for (int i = 0; i < numPieces; i++) {
                if (i >= currentPiece && i < currentPiece + 20) {
                    priorities.add((byte)7); // Onde o player está
                } else if (i >= currentPiece - 5 && i < currentPiece + 40) {
                    priorities.add((byte)5); // Ao redor
                } else if (i < 10) {
                    priorities.add((byte)6); // Cabeçalho sempre importante
                } else {
                    priorities.add((byte)1); // Resto
                }
            }
            th.prioritize_pieces_ex(priorities);
        } catch (Exception e) {}
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 5);
                server.setReuseAddress(true);
                while (!Thread.interrupted()) {
                    try { Socket client = server.accept(); handleClient(client); } 
                    catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleClient(Socket client) {
        try {
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String request = in.readLine();
            if (request == null || !request.contains("/video")) {
                out.write("HTTP/1.1 404\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long rangeStart = 0, rangeEnd = -1;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    String r = line.substring(6).trim();
                    if (r.startsWith("bytes=")) {
                        r = r.substring(6);
                        String[] parts = r.split("-");
                        rangeStart = Long.parseLong(parts[0]);
                        if (parts.length > 1 && !parts[1].isEmpty()) rangeEnd = Long.parseLong(parts[1]);
                    }
                }
            }
            
            if (videoFile == null || !videoFile.exists() || videoFile.length() < 4096) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long fileLen = videoFile.length();
            if (rangeEnd == -1 || rangeEnd >= fileLen) rangeEnd = fileLen - 1;
            
            String mime = videoFile.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            int len = (int)(rangeEnd - rangeStart + 1);
            if (len > 131072) len = 131072;
            
            byte[] buf = new byte[len];
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(rangeStart);
            int total = raf.read(buf);
            raf.close();
            
            if (total <= 0) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            String resp = "HTTP/1.1 206 Partial Content\r\n";
            resp += "Content-Type: " + mime + "\r\n";
            resp += "Content-Range: bytes " + rangeStart + "-" + (rangeStart + total - 1) + "/" + fileLen + "\r\n";
            resp += "Content-Length: " + total + "\r\n";
            resp += "Accept-Ranges: bytes\r\n\r\n";
            
            out.write(resp.getBytes());
            out.write(buf, 0, total);
            out.flush();
            client.close();
            
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        numPieces = 0;
        lastPrioritizedPiece = -1;
        torrentRef.set(null);
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        log("═══ INICIANDO ═══");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(1)); // auto_managed, NÃO sequential
                p.setDownload_limit(3 * 1024 * 1024);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                log("📤 Magnet enviado");
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrentRef.set(h.get(0));
                    torrent_handle th = torrentRef.get();
                    torrent_status ts = th.status();
                    fileLength = ts.getTotal_wanted();
                    
                    if (fileLength > 0) {
                        numPieces = (int)(fileLength / 262144) + 1;
                        log("📊 Arquivo: " + (fileLength/1048576) + "MB | ~" + numPieces + " peças");
                        
                        // Prioridade MÁXIMA para cabeçalho (primeiras 50 peças)
                        byte_vector priorities = new byte_vector();
                        for (int i = 0; i < numPieces; i++) {
                            priorities.add((byte)(i < 50 ? 7 : 1));
                        }
                        th.prioritize_pieces_ex(priorities);
                        log("🎯 Prioridade inicial: primeiras 50 peças (cabeçalho)");
                    }
                }
                
                log("🔍 Aguardando header válido...");
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 65536) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e) { continue; }
                        
                        boolean valid = (hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                                       ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3);
                        
                        if (valid) {
                            videoFile = f;
                            long mb = f.length() / 1048576;
                            log("✅ Header válido! " + f.getName() + " (" + mb + "MB)");
                            
                            handler.post(() -> {
                                btnWatch.setText("🎬 ASSISTIR (" + mb + "MB)");
                                btnWatch.setVisibility(View.VISIBLE);
                            });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) { log("❌ " + e.getMessage()); }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) {
            log("❌ Arquivo não encontrado");
            return;
        }
        
        log("▶️ " + videoFile.getName());
        lastPrioritizedPiece = -1; // Força log na primeira priorização
        
        handler.post(() -> {
            playerView.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
    }
    
    private void stop() {
        log("⏹️ Parando...");
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.clearMediaItems(); }
        playerView.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        
        torrent_handle th = torrentRef.getAndSet(null);
        if (th != null && th.is_valid() && session != null) {
            try { session.swig().remove_torrent(th); } catch (Exception e) {}
        }
    }
    
    private File find(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) { File found = find(f); if (found != null) return found; }
            else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || 
                      f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f;
        }
        return null;
    }
    
    @Override protected void onDestroy() {
        stop();
        if (serverThread != null) serverThread.interrupt();
        if (player != null) player.release();
        if (session != null) session.stop();
        super.onDestroy();
    }
}
