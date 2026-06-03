package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DefaultHttpDataSource;
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
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    
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
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        playerView.post(() -> {
            int width = (int)(getResources().getDisplayMetrics().widthPixels * 0.92);
            int height = (int)(width * 9.0 / 16.0);
            ViewGroup.LayoutParams params = playerView.getLayoutParams();
            params.width = width;
            params.height = height;
            playerView.setLayoutParams(params);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(60000);
        
        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
            .build();
        playerView.setPlayer(player);
        playerView.setVisibility(View.GONE);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    log("▶️ Reproduzindo");
                    loadingOverlay.setVisibility(View.GONE);
                    spinnerBar.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    loadingOverlay.setVisibility(View.VISIBLE);
                    spinnerBar.setVisibility(View.VISIBLE);
                }
            }
        });
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); log("✅ OK"); } 
            catch (Exception e) { log("❌ " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        log("📱 Pronto");
    }
    
    private void log(String msg) {
        handler.post(() -> statusText.setText(msg));
    }
    
    private void startServer() {
        serverPool = Executors.newFixedThreadPool(3);
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
            client.setSoTimeout(5000);
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
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
            if (vf == null || !vf.exists() || vf.length() < 4096) {
                out.write("HTTP/1.1 503\r\nRetry-After: 2\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long fileLen = vf.length();
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            long start = 0, end = Math.min(131071, fileLen - 1);
            
            if (rangeStr != null) {
                String r = rangeStr.replace("bytes=", "");
                String[] parts = r.split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                if (start >= fileLen) {
                    out.write("HTTP/1.1 416\r\n\r\n".getBytes()); out.flush(); client.close(); return;
                }
                if (end >= fileLen) end = fileLen - 1;
                if (end - start > 131072) end = start + 131072;
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
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        log("⏳ Baixando (max 3 MB/s)...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(3 * 1024 * 1024); // 3 MB/s igual Stremio
                p.setMax_connections(50);
                p.setMax_uploads(5);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                
                // Aguarda só 32KB para começar
                for (int i = 0; i < 60 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 32768) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e) { continue; }
                        
                        boolean valid = (hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                                       ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3);
                        
                        if (valid) {
                            videoFile = f;
                            log("▶️ Streaming! (" + (f.length()/1024) + "KB)");
                            
                            handler.post(() -> {
                                playerView.setVisibility(View.VISIBLE);
                                player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
                                player.prepare();
                                player.play();
                                btnWatch.setVisibility(View.GONE);
                            });
                            break;
                        }
                    }
                    Thread.sleep(500);
                }
                
            } catch (Exception e) { log("❌ " + e.getMessage()); }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { log("❌ Arquivo não encontrado"); return; }
        
        log("▶️ " + videoFile.getName());
        handler.post(() -> {
            playerView.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.clearMediaItems(); }
        playerView.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
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
