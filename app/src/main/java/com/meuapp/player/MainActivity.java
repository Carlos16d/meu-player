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
    private int bytesServed = 0;
    private File logFile;

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
        logFile = new File(getExternalFilesDir(null), "app_log.txt");
        
        log("═══ APP INICIADO ═══");
        log("📝 Log: " + logFile.getAbsolutePath());
        
        // Recupera log anterior
        if (logFile.exists() && logFile.length() > 0) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(logFile));
                String line;
                while ((line = br.readLine()) != null) fullLog.append(line).append("\n");
                br.close();
            } catch (Exception e) {}
        }
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    log("✅ READY | " + player.getDuration()/1000 + "s");
                    handler.post(() -> { playerView.setVisibility(View.VISIBLE); logScroll.setVisibility(View.GONE); });
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                log("❌ Player: " + error.getErrorCodeName());
            }
        });
        
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                log("✅ Sessão OK");
            } catch (Exception e) {
                log("❌ Sessão: " + e.getMessage());
            }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        
        // Salva log periodicamente
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                saveLog();
                handler.postDelayed(this, 5000);
            }
        }, 5000);
    }
    
    private void saveLog() {
        try {
            FileWriter fw = new FileWriter(logFile);
            fw.write(fullLog.toString());
            fw.close();
        } catch (Exception e) {}
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
    
    private boolean isHeaderValid(File f) {
        if (f == null || !f.exists() || f.length() < 8192) return false;
        try {
            byte[] h = new byte[8];
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            raf.read(h);
            raf.close();
            // MP4: ....ftyp
            if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return true;
            // MKV: 0x1A 0x45 0xDF 0xA3
            if ((h[0] & 0xFF) == 0x1A && h[1] == 0x45 && h[2] == (byte)0xDF && h[3] == (byte)0xA3) return true;
            return false;
        } catch (Exception e) { return false; }
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
                        reqCount++;
                        new Thread(() -> handleClient(client)).start();
                    } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) { log("❌ Servidor: " + e.getMessage()); }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(15000);
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String request = in.readLine();
            if (request == null) { client.close(); return; }
            
            String rangeStr = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) rangeStr = line.substring(6).trim();
            }
            
            if (!request.contains("/video")) {
                out.write("HTTP/1.1 404\r\nConnection: close\r\n\r\n".getBytes());
                client.close(); return;
            }
            
            File vf = videoFile;
            if (vf == null || !isHeaderValid(vf)) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\nConnection: close\r\n\r\n".getBytes());
                client.close(); return;
            }
            
            long fileLen = vf.length();
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            long start = 0, end = fileLen - 1;
            
            if (rangeStr != null) {
                String r = rangeStr.replace("bytes=", "");
                String[] parts = r.split("-");
                start = Long.parseLong(parts[0]);
                end = (parts.length > 1 && !parts[1].isEmpty()) ? Long.parseLong(parts[1]) : fileLen - 1;
                if (start >= fileLen) { out.write("HTTP/1.1 416\r\n\r\n".getBytes()); client.close(); return; }
                if (end >= fileLen) end = fileLen - 1;
                if (end - start > 262144) end = start + 262144;
            } else {
                end = Math.min(262143, fileLen - 1);
            }
            
            int len = (int)(end - start + 1);
            byte[] buf = new byte[len];
            int total = 0;
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(start);
            while (total < len) { int r2 = raf.read(buf, total, len - total); if (r2 == -1) break; total += r2; }
            raf.close();
            
            if (total == 0) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\nConnection: close\r\n\r\n".getBytes());
                client.close(); return;
            }
            
            int code = (rangeStr != null) ? 206 : 200;
            String resp = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + mime + "\r\n";
            if (code == 206) resp += "Content-Range: bytes " + start + "-" + (start+total-1) + "/" + fileLen + "\r\n";
            resp += "Content-Length: " + total + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
            
            out.write(resp.getBytes());
            out.write(buf, 0, total);
            out.flush();
            bytesServed += total;
            client.close();
            
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        reqCount = 0;
        bytesServed = 0;
        videoFile = null;
        
        handler.post(() -> {
            playerView.setVisibility(View.GONE); logScroll.setVisibility(View.VISIBLE);
            statsRow.setVisibility(View.VISIBLE); btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
        });
        
        log("═══ INICIANDO ═══");
        
        if (player != null) { player.stop(); player.clearMediaItems(); player.release(); }
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    log("✅ READY | " + player.getDuration()/1000 + "s | Buffer: " + player.getBufferedPercentage() + "%");
                    handler.post(() -> { playerView.setVisibility(View.VISIBLE); logScroll.setVisibility(View.GONE); });
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                log("❌ Player: " + error.getErrorCodeName());
                handler.postDelayed(() -> { if (downloading && player != null) { player.prepare(); player.play(); } }, 2000);
            }
        });
        
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
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
                
                session.swig().async_add_torrent(p);
                log("📤 Magnet enviado");
                
                Thread.sleep(3000);
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrent = h.get(0);
                
                // Aguarda cabeçalho válido
                log("🔍 Aguardando header válido...");
                File found = null;
                for (int i = 0; i < 60 && downloading; i++) {
                    if (found == null) found = find(new File(savePath));
                    if (found != null && isHeaderValid(found)) {
                        videoFile = found;
                        log("✅ Header OK: " + found.getName() + " (" + (found.length()/1048576) + "MB)");
                        break;
                    }
                    Thread.sleep(1000);
                }
                
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
        
        // Stats - COM TRY/CATCH para evitar crash
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    if (downloading && torrent != null && torrent.is_valid()) {
                        torrent_status ts = torrent.status();
                        statProgress.setText((int)(ts.getProgress()*100) + "%");
                        long speed = ts.getDownload_rate();
                        statSpeed.setText(speed > 1048576 ? String.format("%.1f MB/s", speed/1048576.0) :
                            speed > 1024 ? String.format("%.1f KB/s", speed/1024.0) : speed + " B/s");
                        statPeers.setText("👥" + ts.getNum_peers());
                        bufferBar.setProgress((int)(ts.getProgress()*100));
                    }
                } catch (Exception e) {
                    // Ignora erros de status
                }
                if (downloading) handler.postDelayed(this, 500);
            }
        });
    }
    
    private void stop() {
        log("═══ PARANDO ═══ Reqs:" + reqCount + " Dados:" + (bytesServed/1048576) + "MB");
        saveLog();
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.clearMediaItems(); }
        playerView.setVisibility(View.GONE); logScroll.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); bufferBar.setVisibility(View.GONE);
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
        saveLog();
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (serverThread != null) serverThread.interrupt();
        if (player != null) { player.stop(); player.release(); }
        if (session != null) session.stop();
    }
}
