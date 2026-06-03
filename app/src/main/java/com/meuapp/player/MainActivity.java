package com.meuapp.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer player;
    private LinearLayout statsRow;
    private TextView logText, statProgress, statSpeed, statPeers;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop;
    private ScrollView logScroll;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private volatile boolean downloading = false;
    private volatile File videoFile = null;
    private Thread serverThread;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private int requestCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        logScroll = findViewById(R.id.log_scroll);
        logText = findViewById(R.id.log_text);
        statsRow = findViewById(R.id.stats_row);
        statProgress = findViewById(R.id.stat_progress);
        statSpeed = findViewById(R.id.stat_speed);
        statPeers = findViewById(R.id.stat_peers);
        bufferBar = findViewById(R.id.buffer_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        log("╔══════════════════════════╗");
        log("║   APP INICIADO           ║");
        log("╚══════════════════════════╝");
        log("📱 SDK: " + android.os.Build.VERSION.SDK_INT);
        log("📱 Dispositivo: " + android.os.Build.MODEL);
        log("📁 Pasta: " + savePath);
        
        // ExoPlayer
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        log("✅ ExoPlayer criado");
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                String stateStr = state == Player.STATE_IDLE ? "IDLE" :
                    state == Player.STATE_BUFFERING ? "BUFFERING" :
                    state == Player.STATE_READY ? "READY" :
                    state == Player.STATE_ENDED ? "ENDED" : "?";
                log("🎬 Player: " + stateStr);
                
                if (state == Player.STATE_READY) {
                    log("✅ Player PRONTO para reproduzir!");
                    log("   Duração: " + player.getDuration() + "ms");
                    log("   Formato: " + player.getCurrentMediaItem().localConfiguration.uri);
                    handler.post(() -> {
                        playerView.setVisibility(View.VISIBLE);
                        logScroll.setVisibility(View.GONE);
                    });
                } else if (state == Player.STATE_BUFFERING) {
                    log("⏳ Player: BUFFERING...");
                }
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                log("❌ PLAYER ERROR:");
                log("   Code: " + error.getErrorCodeName());
                log("   Msg: " + error.getMessage());
                log("   Cause: " + (error.getCause() != null ? error.getCause().getMessage() : "null"));
            }
            @Override
            public void onMediaMetadataChanged(androidx.media3.common.MediaMetadata metadata) {
                log("📋 Metadata: " + metadata.title);
            }
        });
        
        // Sessão torrent
        new Thread(() -> {
            try {
                log("🔄 Iniciando libtorrent...");
                session = new SessionManager();
                session.start();
                log("✅ Sessão torrent OK");
                log("   DHT: " + (session.swig().is_dht_running() ? "ON" : "OFF"));
                log("   Porta: " + session.swig().listen_port());
            } catch (Exception e) {
                log("❌ ERRO SESSÃO: " + e.getMessage());
            }
        }).start();
        
        // Servidor HTTP
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        fullLog.append(line);
        handler.post(() -> {
            logText.setText(fullLog.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                log("🌐 SERVIDOR HTTP: porta 8080");
                log("   URL: http://127.0.0.1:8080/video");
                
                while (!Thread.interrupted()) {
                    try {
                        Socket client = server.accept();
                        requestCount++;
                        log("📥 Conexão #" + requestCount + " de " + client.getInetAddress());
                        handleClient(client);
                    } catch (IOException e) {
                        if (!server.isClosed()) log("❌ Accept: " + e.getMessage());
                    }
                }
                server.close();
                log("🌐 Servidor parado");
            } catch (IOException e) {
                log("❌ SERVIDOR: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleClient(Socket client) {
        int reqNum = requestCount;
        try {
            client.setSoTimeout(10000);
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            // Lê request
            String request = in.readLine();
            log("📤 Req #" + reqNum + ": " + (request != null ? request : "NULL"));
            
            // Lê headers
            String range = null;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim().toLowerCase();
                    String val = line.substring(colon + 1).trim();
                    headers.put(key, val);
                }
            }
            
            range = headers.get("range");
            if (range != null) log("   Range: " + range);
            log("   User-Agent: " + headers.getOrDefault("user-agent", "?"));
            
            if (request == null || !request.contains("/video")) {
                log("   ❌ 404 - Não é /video");
                send(out, "HTTP/1.1 404\r\n\r\n");
                client.close();
                return;
            }
            
            File vf = videoFile;
            if (vf == null) {
                log("   ❌ 503 - videoFile é NULL");
                send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n");
                client.close();
                return;
            }
            
            if (!vf.exists()) {
                log("   ❌ 503 - Arquivo não existe");
                send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n");
                client.close();
                return;
            }
            
            long fileLen = vf.length();
            log("   📁 Arquivo: " + vf.getName() + " (" + (fileLen/1024) + "KB)");
            
            if (fileLen < 4096) {
                log("   ❌ 503 - Arquivo muito pequeno: " + fileLen + " bytes");
                send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n");
                client.close();
                return;
            }
            
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            
            if (range != null) {
                // ========== RANGE REQUEST ==========
                String r = range.replace("bytes=", "");
                String[] parts = r.split("-");
                long start = Long.parseLong(parts[0]);
                long end = (parts.length > 1 && !parts[1].isEmpty()) ? 
                    Long.parseLong(parts[1]) : fileLen - 1;
                
                log("   📐 Range: " + start + "-" + end + " / " + fileLen);
                
                if (start >= fileLen) {
                    log("   ❌ 416 - Start >= fileLen");
                    send(out, "HTTP/1.1 416\r\n\r\n");
                    client.close();
                    return;
                }
                if (end >= fileLen) end = fileLen - 1;
                if (end - start > 131072) {
                    end = start + 131072;
                    log("   ⚠️ Limitado a 128KB");
                }
                
                int len = (int)(end - start + 1);
                byte[] buf = new byte[len];
                int total = 0;
                
                try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
                    raf.seek(start);
                    while (total < len) {
                        int r2 = raf.read(buf, total, len - total);
                        if (r2 == -1) break;
                        total += r2;
                    }
                }
                
                if (total == 0) {
                    log("   ❌ 503 - Leu 0 bytes");
                    send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n");
                    client.close();
                    return;
                }
                
                log("   ✅ 206 - Enviando " + total + " bytes");
                
                out.write("HTTP/1.1 206\r\n".getBytes());
                out.write(("Content-Type: " + mime + "\r\n").getBytes());
                out.write(("Content-Range: bytes " + start + "-" + (start + total - 1) + "/" + fileLen + "\r\n").getBytes());
                out.write(("Content-Length: " + total + "\r\n").getBytes());
                out.write("Accept-Ranges: bytes\r\n".getBytes());
                out.write("Connection: close\r\n".getBytes());
                out.write("\r\n".getBytes());
                out.write(buf, 0, total);
                
            } else {
                // ========== PRIMEIRA REQUISIÇÃO (sem range) ==========
                int firstChunk = (int)Math.min(262144, fileLen); // 256KB iniciais
                log("   🆕 Primeira req - Enviando " + (firstChunk/1024) + "KB iniciais");
                
                byte[] buf = new byte[firstChunk];
                int total = 0;
                
                try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
                    while (total < firstChunk) {
                        int r2 = raf.read(buf, total, firstChunk - total);
                        if (r2 == -1) break;
                        total += r2;
                    }
                }
                
                // Lê os primeiros 16 bytes para debug
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(16, total); i++) {
                    hex.append(String.format("%02X ", buf[i]));
                }
                log("   🔍 Header: " + hex.toString());
                
                log("   ✅ 200 - Enviando " + total + " bytes");
                
                out.write("HTTP/1.1 200 OK\r\n".getBytes());
                out.write(("Content-Type: " + mime + "\r\n").getBytes());
                out.write(("Content-Length: " + total + "\r\n").getBytes());
                out.write("Accept-Ranges: bytes\r\n".getBytes());
                out.write("Connection: close\r\n".getBytes());
                out.write("\r\n".getBytes());
                out.write(buf, 0, total);
            }
            
            out.flush();
            client.close();
            log("   🔒 Conexão fechada");
            
        } catch (Exception e) {
            log("   ❌ EXCEÇÃO: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private void send(OutputStream out, String s) throws IOException {
        out.write(s.getBytes());
        out.flush();
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        requestCount = 0;
        
        handler.post(() -> {
            playerView.setVisibility(View.GONE);
            logScroll.setVisibility(View.VISIBLE);
            statsRow.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
        });
        
        log("╔══════════════════════════╗");
        log("║   INICIANDO STREAMING    ║");
        log("╚══════════════════════════╝");
        log("🔗 Magnet: " + magnet.substring(0, Math.min(50, magnet.length())) + "...");
        
        // Conecta ExoPlayer ao servidor
        log("▶️ Conectando ExoPlayer a http://127.0.0.1:8080/video");
        MediaItem item = MediaItem.fromUri("http://127.0.0.1:8080/video");
        player.setMediaItem(item);
        player.prepare();
        player.play();
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(0);
                
                byte_vector pr = new byte_vector();
                pr.add((byte)7);
                p.set_file_priorities(pr);
                
                log("📤 Enviando magnet...");
                session.swig().async_add_torrent(p);
                log("✅ Magnet enviado");
                
                Thread.sleep(3000);
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrent = h.get(0);
                    torrent_status ts = torrent.status();
                    log("✅ Torrent: " + ts.getNum_peers() + " peers, " + (ts.getTotal_wanted()/1048576) + "MB");
                }
                
                log("🔍 Procurando arquivo...");
                while (downloading && videoFile == null) {
                    File f = find(new File(savePath));
                    if (f != null && f.exists() && f.length() > 4096) {
                        videoFile = f;
                        log("📁 ENCONTRADO: " + f.getName() + " (" + (f.length()/1048576) + "MB)");
                        log("   Path: " + f.getAbsolutePath());
                        break;
                    }
                    Thread.sleep(1000);
                }
                
            } catch (Exception e) {
                log("❌ ERRO: " + e.getMessage());
            }
        }).start();
        
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (downloading && torrent != null && torrent.is_valid()) {
                    torrent_status ts = torrent.status();
                    statProgress.setText((int)(ts.getProgress() * 100) + "%");
                    long speed = ts.getDownload_rate();
                    statSpeed.setText(speed > 1048576 ? String.format("%.1f MB/s", speed/1048576.0) :
                        speed > 1024 ? String.format("%.1f KB/s", speed/1024.0) : speed + " B/s");
                    statPeers.setText("👥" + ts.getNum_peers());
                    bufferBar.setProgress((int)(ts.getProgress() * 100));
                }
                if (downloading) handler.postDelayed(this, 500);
            }
        });
    }
    
    private void stop() {
        log("╔══════════════════════════╗");
        log("║   PARANDO                ║");
        log("╚══════════════════════════╝");
        downloading = false;
        player.stop();
        handler.removeCallbacksAndMessages(null);
        playerView.setVisibility(View.GONE);
        logScroll.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
    }
    
    private File find(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = find(f);
                    if (found != null) return found;
                } else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || 
                           f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) {
                    return f;
                }
            }
        }
        return null;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloading = false;
        if (serverThread != null) serverThread.interrupt();
        if (player != null) player.release();
        if (session != null) session.stop();
        log("💀 App destruído");
    }
}
