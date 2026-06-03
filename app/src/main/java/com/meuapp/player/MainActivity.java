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
        
        // ExoPlayer
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    log("✅ Player pronto!");
                    playerView.setVisibility(View.VISIBLE);
                    logScroll.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    log("⏳ Buffer...");
                }
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                log("❌ Player: " + error.getErrorCodeName());
            }
        });
        
        log("APP INICIADO");
        
        // Sessão torrent
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                log("Sessao OK");
            } catch (Exception e) {
                log("ERRO: " + e.getMessage());
            }
        }).start();
        
        // Servidor HTTP
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
    }
    
    private void log(String msg) {
        fullLog.append(msg).append("\n");
        handler.post(() -> {
            logText.setText(fullLog.toString());
            logScroll.fullScroll(View.FOCUS_DOWN);
        });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                log("🌐 Servidor HTTP :8080");
                
                while (!Thread.interrupted()) {
                    try {
                        Socket client = server.accept();
                        handleClient(client);
                    } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {
                log("❌ Servidor: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(10000);
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String request = in.readLine();
            String range = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    range = line.substring(6).trim();
                }
            }
            
            if (request == null || !request.contains("/video")) {
                send(out, "HTTP/1.1 404\r\n\r\n");
                client.close();
                return;
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 4096) {
                send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n");
                client.close();
                return;
            }
            
            long fileLen = vf.length();
            long start = 0, end = fileLen - 1;
            
            if (range != null) {
                String r = range.replace("bytes=", "");
                String[] parts = r.split("-");
                start = Long.parseLong(parts[0]);
                end = (parts.length > 1 && !parts[1].isEmpty()) ? 
                    Long.parseLong(parts[1]) : fileLen - 1;
            }
            
            if (start >= fileLen) {
                send(out, "HTTP/1.1 416\r\n\r\n");
                client.close();
                return;
            }
            if (end >= fileLen) end = fileLen - 1;
            if (end - start > 131072) end = start + 131072;
            
            int len = (int)(end - start + 1);
            byte[] buf = new byte[len];
            int total = 0;
            
            try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
                raf.seek(start);
                while (total < len) {
                    int r = raf.read(buf, total, len - total);
                    if (r == -1) break;
                    total += r;
                }
            }
            
            if (total == 0) {
                send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n");
                client.close();
                return;
            }
            
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            
            out.write("HTTP/1.1 206\r\n".getBytes());
            out.write(("Content-Type: " + mime + "\r\n").getBytes());
            out.write(("Content-Range: bytes " + start + "-" + (start + total - 1) + "/" + fileLen + "\r\n").getBytes());
            out.write(("Content-Length: " + total + "\r\n").getBytes());
            out.write("Accept-Ranges: bytes\r\n".getBytes());
            out.write("Connection: close\r\n".getBytes());
            out.write("\r\n".getBytes());
            out.write(buf, 0, total);
            out.flush();
            client.close();
            
        } catch (Exception e) {
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
        
        handler.post(() -> {
            playerView.setVisibility(View.GONE);
            logScroll.setVisibility(View.VISIBLE);
            statsRow.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
        });
        
        log("INICIANDO STREAMING...");
        
        // Conecta o ExoPlayer ao servidor HTTP
        MediaItem item = MediaItem.fromUri("http://127.0.0.1:8080/video");
        player.setMediaItem(item);
        player.prepare();
        player.play();
        log("▶️ ExoPlayer conectado ao servidor");
        
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
                log("Magnet enviado");
                
                Thread.sleep(3000);
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrent = h.get(0);
                
                while (downloading && videoFile == null) {
                    File f = find(new File(savePath));
                    if (f != null && f.exists() && f.length() > 4096) {
                        videoFile = f;
                        log("📁 " + f.getName() + " (" + (f.length()/1048576) + "MB)");
                        break;
                    }
                    Thread.sleep(1000);
                }
                
            } catch (Exception e) {
                log("ERRO: " + e.getMessage());
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
        downloading = false;
        player.stop();
        handler.removeCallbacksAndMessages(null);
        playerView.setVisibility(View.GONE);
        logScroll.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        log("Parado");
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
    }
}
