package com.meuapp.player;

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
    private int reqCount = 0;
    private int errCount = 0;
    private int bytesServed = 0;

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
        log("║     APP INICIADO         ║");
        log("╚══════════════════════════╝");
        log("📱 SDK: " + android.os.Build.VERSION.SDK_INT);
        log("📱 Modelo: " + android.os.Build.MODEL);
        log("📁 Pasta: " + savePath);
        
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                log("✅ Sessão torrent criada");
                
                // Verifica DHT periodicamente
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (session != null) {
                            boolean dht = session.swig().is_dht_running();
                            log("📡 DHT: " + (dht ? "ATIVO ✅" : "iniciando..."));
                            if (!dht) handler.postDelayed(this, 2000);
                        }
                    }
                }, 3000);
                
                log("   Porta: " + session.swig().listen_port());
            } catch (Exception e) {
                log("❌ Sessão: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        fullLog.append(line);
        handler.post(() -> {
            if (logText != null) {
                logText.setText(fullLog.toString());
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                log("🌐 Servidor HTTP porta 8080");
                
                while (!Thread.interrupted()) {
                    try {
                        Socket client = server.accept();
                        reqCount++;
                        int n = reqCount;
                        new Thread(() -> handleClient(client, n)).start();
                    } catch (IOException e) {
                        if (!server.isClosed()) log("⚠️ Accept: " + e.getMessage());
                    }
                }
                server.close();
                log("🌐 Servidor parado");
            } catch (IOException e) {
                log("❌ Servidor: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleClient(Socket client, int num) {
        long t0 = System.currentTimeMillis();
        String rangeStr = null;
        
        try {
            client.setSoTimeout(30000);
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String request = in.readLine();
            if (request == null) { client.close(); return; }
            
            String[] reqParts = request.split(" ");
            String method = reqParts[0];
            String path = reqParts[1];
            
            // Lê headers
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    rangeStr = line.substring(6).trim();
                }
            }
            
            // Apenas /video
            if (!path.equals("/video") && !path.startsWith("/video")) {
                send(out, 404, "Not Found", "text/plain", 0, 0, 0, 0);
                client.close();
                return;
            }
            
            // Verifica arquivo
            File vf = videoFile;
            if (vf == null) {
                log("📥 #" + num + ": 503 (videoFile null)");
                send(out, 503, "Aguardando arquivo", "text/plain", 0, 0, 0, 0);
                client.close();
                return;
            }
            
            if (!vf.exists()) {
                log("📥 #" + num + ": 503 (!exists)");
                send(out, 503, "Arquivo nao existe", "text/plain", 0, 0, 0, 0);
                client.close();
                return;
            }
            
            long fileLen = vf.length();
            if (fileLen < 131072) {
                log("📥 #" + num + ": 503 (size=" + fileLen + ")");
                send(out, 503, "Arquivo muito pequeno", "text/plain", 0, 0, 0, 0);
                client.close();
                return;
            }
            
            // Verifica cabeçalho
            byte[] fhdr = new byte[16];
            try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) { raf.read(fhdr); }
            
            boolean isMP4 = (fhdr[4] == 'f' && fhdr[5] == 't' && fhdr[6] == 'y' && fhdr[7] == 'p');
            boolean isMKV = ((fhdr[0] & 0xFF) == 0x1A && fhdr[1] == 0x45 && fhdr[2] == (byte)0xDF && fhdr[3] == (byte)0xA3);
            
            if (!isMP4 && !isMKV) {
                String h = "";
                for (byte b : fhdr) h += String.format("%02X ", b);
                log("📥 #" + num + ": 503 Bad header: " + h);
                send(out, 503, "Cabecalho invalido", "text/plain", 0, 0, 0, 0);
                client.close();
                return;
            }
            
            String mime = isMKV ? "video/x-matroska" : "video/mp4";
            
            // Processa range
            long start = 0, end = fileLen - 1;
            boolean hasRange = (rangeStr != null);
            
            if (hasRange) {
                String r = rangeStr.replace("bytes=", "");
                String[] parts = r.split("-");
                try {
                    start = Long.parseLong(parts[0]);
                    end = (parts.length > 1 && !parts[1].isEmpty()) ? 
                        Long.parseLong(parts[1]) : fileLen - 1;
                } catch (NumberFormatException e) {
                    send(out, 400, "Bad Range", "text/plain", 0, 0, 0, 0);
                    client.close();
                    return;
                }
                
                if (start >= fileLen) {
                    send(out, 416, "Range Not Satisfiable", "text/plain", 0, 0, 0, 0);
                    client.close();
                    return;
                }
                if (end >= fileLen) end = fileLen - 1;
                if (end - start > 262144) end = start + 262144;
            } else {
                end = Math.min(262143, fileLen - 1);
            }
            
            // Lê dados
            int length = (int)(end - start + 1);
            byte[] buffer = new byte[length];
            int totalRead = 0;
            
            try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
                raf.seek(start);
                while (totalRead < length) {
                    int r2 = raf.read(buffer, totalRead, length - totalRead);
                    if (r2 == -1) break;
                    totalRead += r2;
                }
            }
            
            if (totalRead == 0) {
                log("📥 #" + num + ": 503 (read 0)");
                send(out, 503, "Sem dados", "text/plain", 0, 0, 0, 0);
                client.close();
                return;
            }
            
            // Envia resposta
            int code = hasRange ? 206 : 200;
            send(out, code, null, mime, start, start + totalRead - 1, fileLen, totalRead);
            out.write(buffer, 0, totalRead);
            out.flush();
            
            bytesServed += totalRead;
            long elapsed = System.currentTimeMillis() - t0;
            log("📥 #" + num + ": " + code + " | " + (totalRead/1024) + "KB | " + 
                start + "-" + (start+totalRead-1) + " | " + elapsed + "ms | Total: " + (bytesServed/1048576) + "MB");
            
            client.close();
            
        } catch (SocketTimeoutException e) {
            log("⏰ #" + num + ": Timeout");
            try { client.close(); } catch (IOException ex) {}
        } catch (Exception e) {
            log("❌ #" + num + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private void send(OutputStream out, int code, String msg, String mime, 
                      long rangeStart, long rangeEnd, long total, int contentLen) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code);
        if (msg != null) sb.append(" ").append(msg);
        sb.append("\r\n");
        
        if (mime != null) sb.append("Content-Type: ").append(mime).append("\r\n");
        if (code == 206) {
            sb.append("Content-Range: bytes ").append(rangeStart).append("-")
              .append(rangeEnd).append("/").append(total).append("\r\n");
        }
        if (contentLen > 0) sb.append("Content-Length: ").append(contentLen).append("\r\n");
        if (code == 200 || code == 206) sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("\r\n");
        
        out.write(sb.toString().getBytes());
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        reqCount = 0;
        errCount = 0;
        bytesServed = 0;
        
        handler.post(() -> {
            playerView.setVisibility(View.GONE);
            logScroll.setVisibility(View.VISIBLE);
            statsRow.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
        });
        
        log("══════ STREAMING ══════");
        log("🔗 " + magnet.substring(0, Math.min(50, magnet.length())) + "...");
        
        // Cria player novo se necessário
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                String s = state == Player.STATE_IDLE ? "IDLE" : 
                    state == Player.STATE_BUFFERING ? "BUFFERING" : 
                    state == Player.STATE_READY ? "READY ✅" : 
                    state == Player.STATE_ENDED ? "ENDED" : "?";
                log("🎬 Player: " + s);
                
                if (state == Player.STATE_READY) {
                    log("   ⏱️ " + player.getDuration()/1000 + "s | 📍 " + 
                        player.getCurrentPosition()/1000 + "s | 📊 " + 
                        player.getBufferedPercentage() + "%");
                    handler.post(() -> {
                        playerView.setVisibility(View.VISIBLE);
                        logScroll.setVisibility(View.GONE);
                    });
                }
                if (state == Player.STATE_BUFFERING) {
                    log("   📊 Buffer: " + player.getBufferedPercentage() + "%");
                }
                if (state == Player.STATE_IDLE) {
                    log("   ⚠️ Player voltou a IDLE");
                }
            }
            
            @Override
            public void onPlayerError(PlaybackException error) {
                errCount++;
                log("❌ PLAYER ERRO #" + errCount);
                log("   Código: " + error.getErrorCodeName());
                log("   Msg: " + error.getMessage());
                if (error.getCause() != null) {
                    log("   Causa: " + error.getCause().getClass().getSimpleName());
                }
                // Tenta continuar
                player.prepare();
                player.play();
            }
        });
        
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
        log("▶️ Play enviado");
        
        // Inicia torrent
        if (torrent == null || !torrent.is_valid()) {
            videoFile = null;
            new Thread(() -> {
                try {
                    add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                    p.setSave_path(savePath);
                    p.setFlags(torrent_flags_t.from_int(9));
                    p.setDownload_limit(0);
                    
                    byte_vector pr = new byte_vector();
                    pr.add((byte)7);
                    p.set_file_priorities(pr);
                    
                    session.swig().async_add_torrent(p);
                    log("📤 Magnet enviado à sessão");
                    
                    Thread.sleep(3000);
                    torrent_handle_vector h = session.swig().get_torrents();
                    if (h.size() > 0) {
                        torrent = h.get(0);
                        torrent_status ts = torrent.status();
                        log("📊 " + ts.getNum_peers() + " peers | " + 
                            (ts.getTotal_wanted()/1048576) + "MB | " +
                            (int)(ts.getProgress()*100) + "%");
                    }
                    
                    log("🔍 Buscando arquivo...");
                    for (int i = 0; i < 30; i++) {
                        if (!downloading) break;
                        File f = find(new File(savePath));
                        if (f != null && f.exists() && f.length() > 131072) {
                            videoFile = f;
                            log("📁 " + f.getName() + " (" + (f.length()/1048576) + "MB)");
                            break;
                        }
                        Thread.sleep(1000);
                    }
                    
                } catch (Exception e) {
                    log("❌ Download: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }).start();
        } else {
            log("♻️ Reusando torrent existente");
        }
        
        // Stats updater
        handler.post(new Runnable() {
            @Override public void run() {
                if (downloading && torrent != null && torrent.is_valid()) {
                    torrent_status ts = torrent.status();
                    statProgress.setText((int)(ts.getProgress()*100) + "%");
                    long speed = ts.getDownload_rate();
                    statSpeed.setText(speed > 1048576 ? String.format("%.1f MB/s", speed/1048576.0) :
                        speed > 1024 ? String.format("%.1f KB/s", speed/1024.0) : speed + " B/s");
                    statPeers.setText("👥" + ts.getNum_peers());
                    bufferBar.setProgress((int)(ts.getProgress()*100));
                }
                if (downloading) handler.postDelayed(this, 500);
            }
        });
    }
    
    private void stop() {
        log("══════ PARANDO ══════");
        log("📊 Reqs: " + reqCount + " | Erros: " + errCount + " | Dados: " + (bytesServed/1048576) + "MB");
        
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        
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
                } else {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".mp4") || n.endsWith(".mkv") || 
                        n.endsWith(".avi") || n.endsWith(".webm")) {
                        return f;
                    }
                }
            }
        }
        return null;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (serverThread != null) serverThread.interrupt();
        if (player != null) {
            player.stop();
            player.release();
        }
        if (session != null) session.stop();
        log("💀 App finalizado");
    }
}
