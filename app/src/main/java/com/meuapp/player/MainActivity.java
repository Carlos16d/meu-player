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
import java.util.concurrent.*;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView statusText;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop;
    
    private String savePath;
    private SessionManager session;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private ServerSocket serverSocket;
    private ExecutorService serverPool;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        bufferBar = findViewById(R.id.buffer_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        
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
        
        log("📱 Pronto");
    }
    
    private void log(String msg) {
        handler.post(() -> {
            statusText.setText(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }
    
    private void startServer() {
        serverPool = Executors.newFixedThreadPool(4);
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8080, 10);
                serverSocket.setReuseAddress(true);
                while (!serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    serverPool.execute(() -> handleClient(client));
                }
            } catch (IOException e) {}
        }).start();
    }
    
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(10000);
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String req = in.readLine();
            if (req == null || !req.contains("/video")) {
                out.write("HTTP/1.1 404\r\nConnection: close\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            String rangeStr = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.startsWith("Range:")) rangeStr = line.substring(6).trim();
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 8192) {
                out.write("HTTP/1.1 503\r\nRetry-After: 2\r\nConnection: close\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long fileLen = vf.length();
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            long start = 0, end = Math.min(262143, fileLen - 1);
            
            if (rangeStr != null) {
                String r = rangeStr.replace("bytes=", "");
                String[] parts = r.split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                if (start >= fileLen) {
                    out.write("HTTP/1.1 416\r\n\r\n".getBytes()); out.flush(); client.close(); return;
                }
                if (end >= fileLen) end = fileLen - 1;
                if (end - start > 65536) end = start + 65536;
            }
            
            int len = (int)(end - start + 1);
            byte[] buf = new byte[len];
            int total = 0;
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(start);
            while (total < len) { int r = raf.read(buf, total, len - total); if (r == -1) break; total += r; }
            raf.close();
            
            if (total == 0) {
                out.write("HTTP/1.1 503\r\nRetry-After: 2\r\n\r\n".getBytes()); out.flush(); client.close(); return;
            }
            
            int code = (rangeStr != null) ? 206 : 200;
            String resp = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + mime + "\r\n";
            if (code == 206) resp += "Content-Range: bytes " + start + "-" + (start + total - 1) + "/" + fileLen + "\r\n";
            resp += "Content-Length: " + total + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n";
            
            out.write(resp.getBytes());
            out.write(buf, 0, total);
            out.flush();
            client.close();
            
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        
        handler.post(() -> {
            playerView.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
        });
        
        log("⏳ Iniciando...");
        
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(0);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                
                // Procura arquivo
                for (int i = 0; i < 60 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 8192 && !isAllZeros(f)) {
                        videoFile = f;
                        log("📁 " + f.getName() + " (" + (f.length()/1048576) + "MB)");
                        break;
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
    }
    
    private boolean isAllZeros(File f) {
        try {
            byte[] h = new byte[8];
            new RandomAccessFile(f, "r").read(h);
            for (byte b : h) if (b != 0) return false;
            return true;
        } catch (Exception e) { return true; }
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.clearMediaItems(); }
        if (session != null) {
            try {
                torrent_handle_vector h = session.swig().get_torrents();
                for (int i = 0; i < h.size(); i++) {
                    session.swig().remove_torrent(h.get(i));
                }
            } catch (Exception e) {}
        }
        playerView.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        log("⏹️ Parado");
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
        try { serverSocket.close(); } catch (Exception e) {}
        try { serverPool.shutdown(); } catch (Exception e) {}
        if (player != null) player.release();
        if (session != null) session.stop();
        super.onDestroy();
    }
}
