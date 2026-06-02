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

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer player;
    private LinearLayout loadingOverlay, controlPanel, statsRow;
    private TextView loadingTitle, loadingProgress, loadingSpeed, loadingPeers, loadingStatus;
    private TextView statProgress, statSpeed, statPeers;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private boolean downloading = false;
    private File videoFile = null;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statsUpdater;
    private MiniHttpServer httpServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        loadingOverlay = findViewById(R.id.loading_overlay);
        controlPanel = findViewById(R.id.control_panel);
        statsRow = findViewById(R.id.stats_row);
        loadingTitle = findViewById(R.id.loading_title);
        loadingProgress = findViewById(R.id.loading_progress);
        loadingSpeed = findViewById(R.id.loading_speed);
        loadingPeers = findViewById(R.id.loading_peers);
        loadingStatus = findViewById(R.id.loading_status);
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
        
        // Inicia servidor HTTP
        httpServer = new MiniHttpServer(8080);
        httpServer.start();
        
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                showLog("✅ UDP iniciado");
            } catch (Exception e) {
                showLog("❌ Erro: " + e.getMessage());
            }
        }).start();
        
        btnPlay.setOnClickListener(v -> startStream());
        btnStop.setOnClickListener(v -> stopStream());
        showLog("📱 App pronto");
    }
    
    private void showLog(String msg) {
        handler.post(() -> {
            if (loadingStatus != null) loadingStatus.setText(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }
    
    class MiniHttpServer {
        private ServerSocket serverSocket;
        private boolean running = false;
        private int port;
        
        public MiniHttpServer(int port) { this.port = port; }
        
        public void start() {
            running = true;
            new Thread(() -> {
                try {
                    serverSocket = new ServerSocket(port);
                    serverSocket.setReuseAddress(true);
                    while (running) {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handle(client)).start();
                    }
                } catch (IOException e) { e.printStackTrace(); }
            }).start();
        }
        
        public void stop() {
            running = false;
            try { serverSocket.close(); } catch (IOException e) {}
        }
        
        private void handle(Socket client) {
            try {
                OutputStream out = client.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                
                String line = in.readLine();
                if (line == null || !line.contains("/video")) {
                    write(out, "HTTP/1.1 404 Not Found\r\n\r\n");
                    client.close();
                    return;
                }
                
                // Lê headers
                String rangeStr = null;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("Range:")) rangeStr = line.substring(6).trim();
                }
                
                if (videoFile == null || !videoFile.exists() || videoFile.length() < 4096) {
                    write(out, "HTTP/1.1 503 Unavailable\r\nRetry-After: 2\r\n\r\n");
                    client.close();
                    return;
                }
                
                long fileLen = videoFile.length();
                long start = 0, end = Math.min(65536, fileLen - 1); // 64KB
                
                if (rangeStr != null) {
                    String[] p = rangeStr.replace("bytes=", "").split("-");
                    start = Long.parseLong(p[0]);
                    end = p.length > 1 && !p[1].isEmpty() ? Long.parseLong(p[1]) : Math.min(start + 65536, fileLen - 1);
                }
                
                if (start >= fileLen) { write(out, "HTTP/1.1 416\r\n\r\n"); client.close(); return; }
                if (end >= fileLen) end = fileLen - 1;
                
                int len = (int)(end - start + 1);
                byte[] buf = new byte[len];
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                raf.seek(start);
                int read = raf.read(buf);
                raf.close();
                
                if (read < 0) read = 0;
                
                String mime = videoFile.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
                String header = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: " + mime + "\r\n" +
                    "Content-Range: bytes " + start + "-" + (start + read - 1) + "/" + fileLen + "\r\n" +
                    "Content-Length: " + read + "\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n\r\n";
                
                out.write(header.getBytes());
                if (read > 0) out.write(buf, 0, read);
                out.flush();
                client.close();
                
            } catch (Exception e) {
                try { client.close(); } catch (IOException ex) {}
            }
        }
        
        private void write(OutputStream out, String s) throws IOException {
            out.write(s.getBytes());
            out.flush();
        }
    }
    
    private void startStream() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:")) {
            Toast.makeText(this, "Cole um magnet link!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (downloading) return;
        downloading = true;
        videoFile = null;
        
        playerView.setVisibility(View.VISIBLE);
        controlPanel.setVisibility(View.GONE);
        statsRow.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        
        // Configura o player para usar o servidor HTTP
        MediaItem item = MediaItem.fromUri("http://127.0.0.1:8080/video");
        player.setMediaItem(item);
        player.prepare();
        player.play();
        
        showLog("⏳ Iniciando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                
                string_vector trackers = new string_vector();
                trackers.add("udp://tracker.opentrackr.org:1337/announce");
                trackers.add("udp://tracker.openbittorrent.com:6969/announce");
                trackers.add("udp://open.stealth.si:80/announce");
                trackers.add("udp://tracker.torrent.eu.org:451/announce");
                trackers.add("udp://explodie.org:6969/announce");
                p.setTrackers(trackers);
                
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(0); // Sem limite
                p.setMax_connections(200);
                
                byte_vector priorities = new byte_vector();
                priorities.add((byte)7);
                p.set_file_priorities(priorities);
                
                session.swig().async_add_torrent(p);
                
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() > 0) {
                    torrent = handles.get(0);
                }
                
                // Procura o arquivo
                for (int i = 0; i < 30; i++) {
                    if (videoFile == null) {
                        videoFile = findVideoFile(new File(savePath));
                    }
                    if (videoFile != null && videoFile.exists() && videoFile.length() > 4096) {
                        showLog("✅ Streaming iniciado!");
                        break;
                    }
                    Thread.sleep(1000);
                }
                
            } catch (Exception e) {
                downloading = false;
                showLog("❌ " + e.getMessage());
            }
        }).start();
        
        statsUpdater = new Runnable() {
            @Override
            public void run() {
                if (torrent != null && torrent.is_valid()) {
                    int prog = (int)(torrent.status().getProgress() * 100);
                    long speed = torrent.status().getDownload_rate();
                    int peers = torrent.status().getNum_peers();
                    
                    String speedStr = speed > 1048576 ? 
                        String.format("%.1f MB/s", speed / 1048576.0) :
                        speed > 1024 ? String.format("%.1f KB/s", speed / 1024.0) :
                        speed + " B/s";
                    
                    statProgress.setText(prog + "%");
                    statSpeed.setText(speedStr);
                    statPeers.setText(String.valueOf(peers));
                    loadingProgress.setText(prog + "%");
                    loadingSpeed.setText(speedStr);
                    loadingPeers.setText(peers + " peers");
                    bufferBar.setProgress(prog);
                    
                    if (videoFile != null) {
                        loadingTitle.setText("📁 " + videoFile.getName() + " (" + (videoFile.length()/1024) + "KB)");
                    }
                    
                    if (player.isPlaying()) loadingOverlay.setVisibility(View.GONE);
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(statsUpdater);
    }
    
    private void stopStream() {
        downloading = false;
        player.stop();
        player.clearMediaItems();
        handler.removeCallbacks(statsUpdater);
        playerView.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        controlPanel.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
    }
    
    private File findVideoFile(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideoFile(f);
                    if (found != null) return found;
                } else {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".webm")) {
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
        handler.removeCallbacks(statsUpdater);
        httpServer.stop();
        player.release();
        session.stop();
    }
}
