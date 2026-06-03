package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.concurrent.*;

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
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private File logFile;
    private String currentMagnet = "";
    private ThreadPoolExecutor serverPool;
    private ServerSocket serverSocket;

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
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        
        log("═══ APP INICIADO ═══");
        log("📱 RAM: 12GB | SDK: " + android.os.Build.VERSION.SDK_INT);
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new java.util.Date()) + "] " + msg + "\n";
        fullLog.append(line);
        handler.post(() -> {
            if (logText != null) {
                logText.setText(fullLog.toString());
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
        try { FileWriter fw = new FileWriter(logFile, true); fw.write(line); fw.close(); } catch (Exception e) {}
    }
    
    private boolean isHeaderValid(File f) {
        if (f == null || !f.exists() || f.length() < 4096) return false;
        try {
            byte[] h = new byte[8];
            new RandomAccessFile(f, "r").read(h);
            return (h[4]=='f' && h[5]=='t' && h[6]=='y' && h[7]=='p') ||
                   ((h[0]&0xFF)==0x1A && h[1]==0x45 && h[2]==(byte)0xDF && h[3]==(byte)0xA3);
        } catch (Exception e) { return false; }
    }
    
    private void startServer() {
        if (serverSocket != null) return;
        
        serverPool = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10));
        
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8080, 10);
                serverSocket.setReuseAddress(true);
                log("🌐 HTTP :8080");
                
                while (!serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    serverPool.execute(() -> handleClient(client));
                }
            } catch (IOException e) {
                // Servidor fechado
            }
        }).start();
    }
    
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(5000);
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), 32768);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()), 256);
            
            String req = in.readLine();
            if (req == null || !req.contains("/video")) {
                out.write("HTTP/1.1 404\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            String rangeStr = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.startsWith("Range:")) rangeStr = line.substring(6).trim();
            }
            
            File vf = videoFile;
            if (vf == null || !isHeaderValid(vf)) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long fileLen = vf.length();
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            long start = 0, end = Math.min(65535, fileLen - 1); // 64KB
            
            if (rangeStr != null) {
                String r = rangeStr.replace("bytes=", "");
                String[] parts = r.split("-");
                start = Long.parseLong(parts[0]);
                end = (parts.length > 1 && !parts[1].isEmpty()) ? Long.parseLong(parts[1]) : fileLen - 1;
                if (start >= fileLen) { out.write("HTTP/1.1 416\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
                if (end >= fileLen) end = fileLen - 1;
                if (end - start > 65536) end = start + 65536;
            }
            
            int len = (int)(end - start + 1);
            byte[] buf = new byte[len];
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(start);
            int total = 0;
            while (total < len) {
                int r = raf.read(buf, total, len - total);
                if (r == -1) break;
                total += r;
            }
            raf.close();
            
            if (total <= 0) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            int code = (rangeStr != null) ? 206 : 200;
            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 ").append(code).append(" OK\r\n");
            resp.append("Content-Type: ").append(mime).append("\r\n");
            if (code == 206) resp.append("Content-Range: bytes ").append(start).append("-").append(start+total-1).append("/").append(fileLen).append("\r\n");
            resp.append("Content-Length: ").append(total).append("\r\n");
            resp.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
            
            out.write(resp.toString().getBytes());
            out.write(buf, 0, total);
            out.flush();
            client.close();
            
        } catch (Exception e) {
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private void stopServer() {
        try {
            if (serverSocket != null) serverSocket.close();
            if (serverPool != null) serverPool.shutdownNow();
        } catch (Exception e) {}
        serverSocket = null;
        serverPool = null;
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:")) return;
        
        if (downloading && magnet.equals(currentMagnet) && videoFile != null && isHeaderValid(videoFile)) {
            log("▶️ Reproduzindo...");
            player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
            player.prepare();
            player.play();
            return;
        }
        
        downloading = true;
        currentMagnet = magnet;
        videoFile = null;
        
        handler.post(() -> {
            playerView.setVisibility(View.GONE); logScroll.setVisibility(View.VISIBLE);
            statsRow.setVisibility(View.VISIBLE); btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
        });
        
        log("═══ INICIANDO ═══");
        
        // Inicia serviços
        startServer();
        initSession();
        
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
        
        // Limpa anterior
        if (torrent != null && torrent.is_valid()) {
            try { session.swig().remove_torrent(torrent); } catch (Exception e) {}
            torrent = null;
        }
        
        new Thread(() -> {
            try {
                for (int i = 0; i < 10 && session == null; i++) Thread.sleep(500);
                if (session == null) return;
                
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(0);
                p.setMax_connections(50);
                
                byte_vector pr = new byte_vector();
                pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                log("📤 Magnet enviado");
                
                Thread.sleep(2000);
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrent = h.get(0);
                
                File found = null;
                for (int i = 0; i < 120 && downloading; i++) {
                    if (found == null) found = find(new File(savePath));
                    if (found != null && isHeaderValid(found)) {
                        videoFile = found;
                        log("✅ " + found.getName());
                        break;
                    }
                    Thread.sleep(500);
                }
            } catch (Exception e) { log("❌ " + e.getMessage()); }
        }).start();
        
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
                } catch (Exception e) {}
                if (downloading) handler.postDelayed(this, 1000);
            }
        });
    }
    
    private void initSession() {
        if (session != null) return;
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
            } catch (Exception e) {}
        }).start();
    }
    
    private void stop() {
        log("⏹️ Parando");
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        stopServer();
        if (player != null) { player.stop(); player.clearMediaItems(); }
        if (torrent != null && torrent.is_valid() && session != null) {
            try { session.swig().remove_torrent(torrent); } catch (Exception e) {}
            torrent = null;
        }
        if (session != null) { session.stop(); session = null; }
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
        stop();
        if (player != null) player.release();
        super.onDestroy();
    }
}
