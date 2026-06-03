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
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    log("✅ Player PRONTO");
                    handler.post(() -> { playerView.setVisibility(View.VISIBLE); logScroll.setVisibility(View.GONE); });
                } else if (state == Player.STATE_BUFFERING) {
                    log("⏳ Buffer...");
                }
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                log("❌ Player: " + error.getErrorCodeName() + " - " + error.getMessage());
            }
        });
        
        log("APP INICIADO");
        
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                log("✅ Sessão OK");
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        fullLog.append(line);
        handler.post(() -> { logText.setText(fullLog.toString()); logScroll.fullScroll(View.FOCUS_DOWN); });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                log("🌐 Servidor HTTP :8080");
                while (!Thread.interrupted()) {
                    try { Socket client = server.accept(); requestCount++; handleClient(client); }
                    catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) { log("❌ Servidor: " + e.getMessage()); }
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
                if (line.toLowerCase().startsWith("range:")) range = line.substring(6).trim();
            }
            
            if (request == null || !request.contains("/video")) {
                send(out, "HTTP/1.1 404\r\n\r\n"); client.close(); return;
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 131072) {
                log("❌ 503 - videoFile indisponível (" + (vf != null ? vf.length() : 0) + " bytes)");
                send(out, "HTTP/1.1 503\r\nRetry-After: 2\r\n\r\n"); client.close(); return;
            }
            
            // Verifica cabeçalho do arquivo
            byte[] hdr = new byte[16];
            try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) { raf.read(hdr); }
            
            boolean valid = false;
            if (hdr[4] == 'f' && hdr[5] == 't' && hdr[6] == 'y' && hdr[7] == 'p') valid = true; // MP4
            if ((hdr[0] & 0xFF) == 0x1A && hdr[1] == 0x45 && hdr[2] == (byte)0xDF && hdr[3] == (byte)0xA3) valid = true; // MKV
            
            if (!valid) {
                log("❌ 503 - Header inválido: " + bytesToHex(hdr));
                send(out, "HTTP/1.1 503\r\nRetry-After: 2\r\n\r\n"); client.close(); return;
            }
            
            long fileLen = vf.length();
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            
            if (range != null) {
                String r = range.replace("bytes=", "");
                String[] parts = r.split("-");
                long start = Long.parseLong(parts[0]);
                long end = (parts.length > 1 && !parts[1].isEmpty()) ? Long.parseLong(parts[1]) : fileLen - 1;
                if (start >= fileLen) { send(out, "HTTP/1.1 416\r\n\r\n"); client.close(); return; }
                if (end >= fileLen) end = fileLen - 1;
                if (end - start > 131072) end = start + 131072;
                
                int len = (int)(end - start + 1);
                byte[] buf = new byte[len];
                int total = 0;
                try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
                    raf.seek(start);
                    while (total < len) { int r2 = raf.read(buf, total, len - total); if (r2 == -1) break; total += r2; }
                }
                if (total == 0) { send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n"); client.close(); return; }
                
                out.write("HTTP/1.1 206\r\n".getBytes());
                out.write(("Content-Type: " + mime + "\r\n").getBytes());
                out.write(("Content-Range: bytes " + start + "-" + (start + total - 1) + "/" + fileLen + "\r\n").getBytes());
                out.write(("Content-Length: " + total + "\r\n").getBytes());
                out.write("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n".getBytes());
                out.write(buf, 0, total);
            } else {
                int firstChunk = (int)Math.min(262144, fileLen);
                byte[] buf = new byte[firstChunk];
                int total = 0;
                try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
                    while (total < firstChunk) { int r2 = raf.read(buf, total, firstChunk - total); if (r2 == -1) break; total += r2; }
                }
                log("✅ 200 - " + total + " bytes, Header: " + bytesToHex(hdr));
                out.write("HTTP/1.1 200 OK\r\n".getBytes());
                out.write(("Content-Type: " + mime + "\r\n").getBytes());
                out.write(("Content-Length: " + total + "\r\n").getBytes());
                out.write("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n".getBytes());
                out.write(buf, 0, total);
            }
            out.flush();
            client.close();
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString();
    }
    
    private void send(OutputStream out, String s) throws IOException { out.write(s.getBytes()); out.flush(); }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        downloading = true; videoFile = null;
        
        handler.post(() -> { playerView.setVisibility(View.GONE); logScroll.setVisibility(View.VISIBLE); statsRow.setVisibility(View.VISIBLE); btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); });
        
        log("INICIANDO STREAMING...");
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath); p.setFlags(torrent_flags_t.from_int(9)); p.setDownload_limit(0);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrent = h.get(0);
                while (downloading && videoFile == null) {
                    File f = find(new File(savePath));
                    if (f != null && f.exists() && f.length() > 131072) { videoFile = f; log("📁 " + f.getName() + " (" + (f.length()/1048576) + "MB)"); break; }
                    Thread.sleep(1000);
                }
            } catch (Exception e) { log("❌ " + e.getMessage()); }
        }).start();
        
        handler.post(new Runnable() { @Override public void run() {
            if (downloading && torrent != null && torrent.is_valid()) {
                torrent_status ts = torrent.status();
                statProgress.setText((int)(ts.getProgress()*100)+"%");
                long speed = ts.getDownload_rate();
                statSpeed.setText(speed>1048576?String.format("%.1f MB/s",speed/1048576.0):speed>1024?String.format("%.1f KB/s",speed/1024.0):speed+" B/s");
                statPeers.setText("👥"+ts.getNum_peers());
                bufferBar.setProgress((int)(ts.getProgress()*100));
            }
            if (downloading) handler.postDelayed(this, 500);
        }});
    }
    
    private void stop() {
        downloading = false; player.stop(); handler.removeCallbacksAndMessages(null);
        playerView.setVisibility(View.GONE); logScroll.setVisibility(View.VISIBLE); statsRow.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); bufferBar.setVisibility(View.GONE);
        log("⏹️ Parado");
    }
    
    private File find(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) { File found = find(f); if (found != null) return found; }
            else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f;
        }
        return null;
    }
    
    @Override protected void onDestroy() {
        super.onDestroy(); downloading = false;
        if (serverThread != null) serverThread.interrupt();
        if (player != null) player.release();
        if (session != null) session.stop();
    }
}
