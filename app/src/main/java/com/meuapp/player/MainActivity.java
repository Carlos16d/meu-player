package com.meuapp.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;

public class MainActivity extends AppCompatActivity {
    private VideoView videoView;
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
    private Runnable fileWatcher;
    private HttpStreamServer httpServer;
    private StringBuilder debugLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoView = findViewById(R.id.video_view);
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
        
        // Inicia sessão torrent
        try {
            session = new SessionManager();
            session.start();
            log("✅ Sessão torrent iniciada");
        } catch (Exception e) {
            log("❌ Erro sessão: " + e.getMessage());
        }
        
        // Inicia servidor HTTP
        try {
            httpServer = new HttpStreamServer(8080);
            httpServer.start();
            log("✅ Servidor HTTP na porta 8080");
        } catch (Exception e) {
            log("❌ Erro servidor: " + e.getMessage());
        }
        
        btnPlay.setOnClickListener(v -> startStream());
        btnStop.setOnClickListener(v -> stopStream());
        
        updateDebug();
    }
    
    private void log(String msg) {
        debugLog.append(msg).append("\n");
        if (loadingStatus != null) {
            loadingStatus.setText(msg);
        }
    }
    
    private void updateDebug() {
        // Atualiza o debug a cada 2 segundos
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (loadingStatus != null && torrent != null && torrent.is_valid()) {
                    int prog = (int)(torrent.status().getProgress() * 100);
                    long speed = torrent.status().getDownload_rate();
                    int peers = torrent.status().getNum_peers();
                    String speedStr = speed > 1048576 ? String.format("%.1f MB/s", speed/1048576.0) :
                        speed > 1024 ? String.format("%.1f KB/s", speed/1024.0) : speed + " B/s";
                    
                    loadingStatus.setText("📥 " + prog + "% | " + speedStr + " | " + peers + " peers");
                }
                handler.postDelayed(this, 2000);
            }
        }, 2000);
    }
    
    class HttpStreamServer {
        private int port;
        private ServerSocket serverSocket;
        private boolean running = false;
        
        public HttpStreamServer(int port) { this.port = port; }
        
        public void start() {
            running = true;
            new Thread(() -> {
                try {
                    serverSocket = new ServerSocket(port);
                    log("🔗 Servidor aguardando conexões...");
                    while (running) {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client)).start();
                    }
                } catch (IOException e) {
                    log("❌ Servidor: " + e.getMessage());
                }
            }).start();
        }
        
        public void stop() {
            running = false;
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) {}
        }
        
        private void handleClient(Socket client) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                OutputStream out = client.getOutputStream();
                
                String requestLine = reader.readLine();
                String rangeHeader = null;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("range:")) {
                        rangeHeader = line.substring(6).trim();
                    }
                }
                
                if (requestLine == null || !requestLine.contains("/video")) {
                    sendText(out, 404, "Not Found");
                    client.close();
                    return;
                }
                
                if (videoFile == null || !videoFile.exists() || videoFile.length() < 1024) {
                    sendText(out, 503, "Arquivo indisponivel");
                    client.close();
                    return;
                }
                
                long fileLength = videoFile.length();
                long start = 0;
                long end = Math.min(131072, fileLength - 1);
                
                if (rangeHeader != null) {
                    String[] parts = rangeHeader.replace("bytes=", "").split("-");
                    start = Long.parseLong(parts[0]);
                    end = (parts.length > 1 && !parts[1].isEmpty()) ? 
                        Math.min(Long.parseLong(parts[1]), start + 131072) : 
                        Math.min(start + 131072, fileLength - 1);
                }
                
                if (start >= fileLength) { sendText(out, 416, "Range"); client.close(); return; }
                if (end >= fileLength) end = fileLength - 1;
                
                int length = (int)(end - start + 1);
                byte[] data = new byte[length];
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                raf.seek(start);
                int read = raf.read(data);
                raf.close();
                
                if (read <= 0) { sendText(out, 503, "Aguardando"); client.close(); return; }
                
                if (read < length) {
                    byte[] trimmed = new byte[read];
                    System.arraycopy(data, 0, trimmed, 0, read);
                    data = trimmed;
                }
                
                String mime = videoFile.getName().toLowerCase().endsWith(".mkv") ? 
                    "video/x-matroska" : "video/mp4";
                
                String header = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: " + mime + "\r\n" +
                    "Content-Range: bytes " + start + "-" + (start + data.length - 1) + "/" + fileLength + "\r\n" +
                    "Content-Length: " + data.length + "\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n";
                
                out.write(header.getBytes());
                out.write(data);
                out.flush();
                client.close();
                
            } catch (Exception e) {
                try { client.close(); } catch (IOException ex) {}
            }
        }
        
        private void sendText(OutputStream out, int code, String msg) {
            try {
                String resp = "HTTP/1.1 " + code + " " + msg + "\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n" + msg;
                out.write(resp.getBytes());
                out.flush();
            } catch (IOException e) {}
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
        
        loadingOverlay.setVisibility(View.VISIBLE);
        videoView.setVisibility(View.VISIBLE);
        controlPanel.setVisibility(View.GONE);
        statsRow.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        
        log("⏳ Iniciando download...");
        
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
                p.setDownload_limit(3 * 1024 * 1024);
                p.setMax_connections(200);
                
                byte_vector priorities = new byte_vector();
                priorities.add((byte)7);
                p.set_file_priorities(priorities);
                
                session.swig().async_add_torrent(p);
                
                log("📡 Magnet enviado, aguardando...");
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() > 0) {
                    torrent = handles.get(0);
                    log("✅ Torrent adicionado! Peers: " + torrent.status().getNum_peers());
                } else {
                    log("⚠️ Nenhum torrent encontrado ainda");
                }
                
                handler.post(() -> startFileWatcher());
                
            } catch (Exception e) {
                downloading = false;
                log("❌ Erro: " + e.getMessage());
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
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(statsUpdater);
    }
    
    private void startFileWatcher() {
        fileWatcher = new Runnable() {
            @Override
            public void run() {
                if (!downloading) return;
                
                if (videoFile == null) {
                    videoFile = findVideoFile(new File(savePath));
                }
                
                if (videoFile != null && videoFile.exists()) {
                    log("📁 Arquivo: " + videoFile.getName() + " (" + (videoFile.length()/1024) + "KB)");
                    
                    if (videoFile.length() > 100000) {
                        handler.post(() -> {
                            videoView.setVideoURI(Uri.parse("http://127.0.0.1:8080/video"));
                            videoView.start();
                            loadingOverlay.setVisibility(View.GONE);
                            log("▶️ Reproduzindo via servidor HTTP!");
                        });
                        return;
                    }
                }
                
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(fileWatcher);
    }
    
    private void stopStream() {
        downloading = false;
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE);
        handler.removeCallbacks(statsUpdater);
        handler.removeCallbacks(fileWatcher);
        loadingOverlay.setVisibility(View.GONE);
        controlPanel.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        log("⏹️ Parado");
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
        handler.removeCallbacks(fileWatcher);
        if (httpServer != null) httpServer.stop();
        if (session != null) session.stop();
    }
}
