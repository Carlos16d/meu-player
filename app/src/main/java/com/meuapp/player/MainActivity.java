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
import java.util.*;

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
        
        try {
            session = new SessionManager();
            session.start();
            
            // Inicia servidor HTTP customizado
            httpServer = new HttpStreamServer(8080);
            httpServer.start();
            
            Toast.makeText(this, "UDP + Servidor HTTP OK!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        
        btnPlay.setOnClickListener(v -> startStream());
        btnStop.setOnClickListener(v -> stopStream());
    }
    
    // ==================== SERVIDOR HTTP CUSTOMIZADO ====================
    class HttpStreamServer {
        private int port;
        private ServerSocket serverSocket;
        private boolean running = false;
        
        public HttpStreamServer(int port) {
            this.port = port;
        }
        
        public void start() {
            running = true;
            new Thread(() -> {
                try {
                    serverSocket = new ServerSocket(port);
                    serverSocket.setReuseAddress(true);
                    
                    while (running) {
                        try {
                            Socket client = serverSocket.accept();
                            new Thread(() -> handleClient(client)).start();
                        } catch (IOException e) {
                            if (running) e.printStackTrace();
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();
        }
        
        public void stop() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException e) {}
        }
        
        private void handleClient(Socket client) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                OutputStream out = client.getOutputStream();
                
                // Lê a requisição HTTP
                String requestLine = reader.readLine();
                if (requestLine == null) { client.close(); return; }
                
                // Lê headers para encontrar Range
                String line;
                String rangeHeader = null;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("range:")) {
                        rangeHeader = line.substring(6).trim();
                    }
                }
                
                // Só aceita /video
                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 2 || !"/video".equals(requestParts[1])) {
                    sendError(out, 404, "Not Found");
                    client.close();
                    return;
                }
                
                // Verifica se arquivo existe
                if (videoFile == null || !videoFile.exists()) {
                    sendError(out, 503, "Arquivo ainda nao disponivel");
                    client.close();
                    return;
                }
                
                long fileLength = videoFile.length();
                if (fileLength < 1024) {
                    sendError(out, 503, "Arquivo muito pequeno");
                    client.close();
                    return;
                }
                
                // Processa Range
                long start = 0;
                long end = fileLength - 1;
                int chunkSize = 256 * 1024; // 256KB
                
                if (rangeHeader != null) {
                    String range = rangeHeader.replace("bytes=", "");
                    String[] parts = range.split("-");
                    try {
                        start = Long.parseLong(parts[0]);
                        if (parts.length > 1 && !parts[1].isEmpty()) {
                            end = Long.parseLong(parts[1]);
                        } else {
                            end = Math.min(start + chunkSize, fileLength - 1);
                        }
                    } catch (NumberFormatException e) {}
                } else {
                    end = Math.min(chunkSize, fileLength - 1);
                }
                
                // Limita tamanho
                if (end - start > chunkSize) {
                    end = start + chunkSize;
                }
                if (start >= fileLength) {
                    sendError(out, 416, "Range Not Satisfiable");
                    client.close();
                    return;
                }
                if (end >= fileLength) end = fileLength - 1;
                
                // Lê os bytes do arquivo
                int length = (int)(end - start + 1);
                byte[] buffer = new byte[length];
                int totalRead = 0;
                
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                raf.seek(start);
                
                while (totalRead < length) {
                    int read = raf.read(buffer, totalRead, length - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
                raf.close();
                
                // Se não leu nada
                if (totalRead == 0) {
                    sendError(out, 503, "Parte ainda nao baixada");
                    client.close();
                    return;
                }
                
                // Envia resposta HTTP
                String statusLine = "HTTP/1.1 206 Partial Content\r\n";
                out.write(statusLine.getBytes());
                out.write(("Content-Type: video/mp4\r\n").getBytes());
                out.write(("Content-Range: bytes " + start + "-" + (start + totalRead - 1) + "/" + fileLength + "\r\n").getBytes());
                out.write(("Content-Length: " + totalRead + "\r\n").getBytes());
                out.write(("Accept-Ranges: bytes\r\n").getBytes());
                out.write(("Connection: close\r\n").getBytes());
                out.write(("Access-Control-Allow-Origin: *\r\n").getBytes());
                out.write(("\r\n").getBytes());
                
                // Envia os dados
                out.write(buffer, 0, totalRead);
                out.flush();
                
                client.close();
                
            } catch (Exception e) {
                try { client.close(); } catch (IOException ex) {}
            }
        }
        
        private void sendError(OutputStream out, int code, String message) {
            try {
                String response = "HTTP/1.1 " + code + " " + message + "\r\n";
                response += "Content-Type: text/plain\r\n";
                response += "Connection: close\r\n";
                response += "\r\n";
                response += message;
                out.write(response.getBytes());
                out.flush();
            } catch (IOException e) {}
        }
    }
    // ==================== FIM DO SERVIDOR ====================
    
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
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                
                string_vector trackers = new string_vector();
                trackers.add("udp://tracker.opentrackr.org:1337/announce");
                trackers.add("udp://tracker.openbittorrent.com:6969/announce");
                p.setTrackers(trackers);
                
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(3 * 1024 * 1024);
                p.setMax_connections(200);
                p.setMax_uploads(10);
                
                byte_vector priorities = new byte_vector();
                priorities.add((byte)7);
                p.set_file_priorities(priorities);
                
                session.swig().async_add_torrent(p);
                
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() > 0) {
                    torrent = handles.get(0);
                }
                
                handler.post(() -> startFileWatcher());
                
            } catch (Exception e) {
                downloading = false;
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
                
                if (videoFile != null && videoFile.exists() && videoFile.length() > 50000) {
                    handler.post(() -> {
                        videoView.setVideoURI(Uri.parse("http://127.0.0.1:8080/video"));
                        videoView.start();
                        loadingOverlay.setVisibility(View.GONE);
                    });
                    return;
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
