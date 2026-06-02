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
    private LinearLayout controlPanel, statsRow;
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
        
        videoView = findViewById(R.id.video_view);
        controlPanel = findViewById(R.id.control_panel);
        statsRow = findViewById(R.id.stats_row);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        statProgress = findViewById(R.id.stat_progress);
        statSpeed = findViewById(R.id.stat_speed);
        statPeers = findViewById(R.id.stat_peers);
        bufferBar = findViewById(R.id.buffer_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        log("═══ APP INICIADO ═══");
        
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                log("✅ Sessão torrent OK");
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        
        videoView.setOnPreparedListener(mp -> {
            log("✅ VideoView preparado!");
            loading(false);
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            log("❌ Erro VideoView: " + what + " extra: " + extra);
            return true;
        });
        videoView.setOnCompletionListener(mp -> log("🏁 Reprodução concluída"));
    }
    
    private void log(String msg) {
        fullLog.append(msg).append("\n");
        handler.post(() -> {
            logText.setText(fullLog.toString());
            logScroll.fullScroll(View.FOCUS_DOWN);
        });
    }
    
    private void loading(boolean show) {
        handler.post(() -> {
            if (show) {
                videoView.setVisibility(View.VISIBLE);
                controlPanel.setVisibility(View.GONE);
                statsRow.setVisibility(View.VISIBLE);
                btnStop.setVisibility(View.VISIBLE);
            }
        });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                log("🌐 Servidor HTTP na porta 8080");
                
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
                send(out, "HTTP/1.1 404\r\nConnection: close\r\n\r\n");
                client.close();
                return;
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 4096) {
                send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\nConnection: close\r\n\r\n");
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
                send(out, "HTTP/1.1 416\r\nConnection: close\r\n\r\n");
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
                send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\nConnection: close\r\n\r\n");
                client.close();
                return;
            }
            
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            
            StringBuilder headers = new StringBuilder();
            headers.append("HTTP/1.1 206\r\n");
            headers.append("Content-Type: ").append(mime).append("\r\n");
            headers.append("Content-Range: bytes ").append(start).append("-")
                .append(start + total - 1).append("/").append(fileLen).append("\r\n");
            headers.append("Content-Length: ").append(total).append("\r\n");
            headers.append("Accept-Ranges: bytes\r\n");
            headers.append("Connection: close\r\n");
            headers.append("Access-Control-Allow-Origin: *\r\n");
            headers.append("\r\n");
            
            out.write(headers.toString().getBytes());
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
        loading(true);
        
        log("═══ INICIANDO ═══");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(0);
                p.setMax_connections(200);
                
                byte_vector pr = new byte_vector();
                pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                log("✅ Magnet enviado");
                
                Thread.sleep(3000);
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrent = h.get(0);
                
                // Espera o arquivo aparecer
                while (downloading && videoFile == null) {
                    handler.post(() -> {
                        File f = find(new File(savePath));
                        if (f != null && f.exists() && f.length() > 4096) {
                            videoFile = f;
                            log("📁 " + f.getName() + " (" + (f.length()/1024) + "KB)");
                            
                            // Conecta o VideoView ao servidor HTTP
                            videoView.setVideoURI(Uri.parse("http://127.0.0.1:8080/video"));
                            videoView.start();
                            log("▶️ VideoView iniciado");
                        }
                    });
                    Thread.sleep(2000);
                }
                
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
        
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (downloading && torrent != null && torrent.is_valid()) {
                    torrent_status ts = torrent.status();
                    statProgress.setText((int)(ts.getProgress() * 100) + "%");
                    long speed = ts.getDownload_rate();
                    if (speed > 1048576)
                        statSpeed.setText(String.format("%.1f MB/s", speed / 1048576.0));
                    else if (speed > 1024)
                        statSpeed.setText(String.format("%.1f KB/s", speed / 1024.0));
                    else
                        statSpeed.setText(speed + " B/s");
                    statPeers.setText(String.valueOf(ts.getNum_peers()));
                    bufferBar.setProgress((int)(ts.getProgress() * 100));
                }
                if (downloading) handler.postDelayed(this, 500);
            }
        });
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE);
        controlPanel.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        log("⏹️ Parado");
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
        if (session != null) session.stop();
    }
}
