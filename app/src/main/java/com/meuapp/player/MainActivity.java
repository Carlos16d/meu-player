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
    private LinearLayout statsRow;
    private TextView statProgress, statSpeed, statPeers;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop;
    
    private String savePath;
    private SessionManager session;
    private final Object torrentLock = new Object();
    private torrent_handle torrent;
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
        handler = new Handler(Looper.getMainLooper());
        
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(30000);
        
        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
            .build();
        playerView.setPlayer(player);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                handler.postDelayed(() -> {
                    if (downloading && player != null) {
                        player.prepare();
                        player.play();
                    }
                }, 1000);
            }
        });
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        
        initSession();
        startServer();
    }
    
    private void initSession() {
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
            } catch (Exception e) {}
        }).start();
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
            long start = 0, end = fileLen - 1;
            
            if (rangeStr != null) {
                String r = rangeStr.replace("bytes=", "");
                String[] parts = r.split("-");
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                if (start >= fileLen) {
                    out.write("HTTP/1.1 416\r\nConnection: close\r\n\r\n".getBytes());
                    out.flush(); client.close(); return;
                }
                if (end >= fileLen) end = fileLen - 1;
                if (end - start > 65536) end = start + 65536;
            } else {
                end = Math.min(262143, fileLen - 1);
            }
            
            int len = (int)(end - start + 1);
            byte[] buf = new byte[len];
            int total = 0;
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(start);
            while (total < len) {
                int r = raf.read(buf, total, len - total);
                if (r == -1) break;
                total += r;
            }
            raf.close();
            
            int code = (rangeStr != null) ? 206 : 200;
            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 ").append(code).append(" OK\r\n");
            resp.append("Content-Type: ").append(mime).append("\r\n");
            if (code == 206) {
                resp.append("Content-Range: bytes ").append(start).append("-").append(start + total - 1).append("/").append(fileLen).append("\r\n");
            }
            resp.append("Content-Length: ").append(total).append("\r\n");
            resp.append("Accept-Ranges: bytes\r\n");
            resp.append("Connection: close\r\n\r\n");
            
            out.write(resp.toString().getBytes());
            out.write(buf, 0, total);
            out.flush();
            client.close();
            
        } catch (Exception e) {
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        
        handler.post(() -> {
            playerView.setVisibility(View.VISIBLE);
            statsRow.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
        });
        
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
        
        new Thread(() -> {
            try {
                synchronized (torrentLock) {
                    if (torrent != null && torrent.is_valid()) {
                        session.swig().remove_torrent(torrent);
                        torrent = null;
                    }
                }
                
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(0);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(2000);
                
                synchronized (torrentLock) {
                    torrent_handle_vector h = session.swig().get_torrents();
                    if (h.size() > 0) torrent = h.get(0);
                }
                
                File found = null;
                while (downloading && videoFile == null) {
                    if (found == null) found = find(new File(savePath));
                    if (found != null && found.length() > 8192) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(found, "r").read(hdr); } catch (Exception e) { continue; }
                        if ((hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                            ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3)) {
                            videoFile = found;
                        }
                    }
                    Thread.sleep(500);
                }
            } catch (Exception e) { downloading = false; }
        }).start();
        
        handler.post(new Runnable() {
            @Override public void run() {
                if (!downloading) return;
                
                try {
                    synchronized (torrentLock) {
                        if (torrent != null && torrent.is_valid()) {
                            torrent_status ts = torrent.status();
                            statProgress.setText((int)(ts.getProgress()*100) + "%");
                            long speed = ts.getDownload_rate();
                            statSpeed.setText(speed > 1048576 ? String.format("%.1f MB/s", speed/1048576.0) :
                                speed > 1024 ? String.format("%.1f KB/s", speed/1024.0) : speed + " B/s");
                            statPeers.setText("👥" + ts.getNum_peers());
                            bufferBar.setProgress((int)(ts.getProgress()*100));
                        }
                    }
                } catch (Exception e) {}
                
                if (downloading) handler.postDelayed(this, 1000);
            }
        });
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        
        if (player != null) { player.stop(); player.clearMediaItems(); }
        
        synchronized (torrentLock) {
            if (torrent != null && torrent.is_valid() && session != null) {
                try { session.swig().remove_torrent(torrent); } catch (Exception e) {}
                torrent = null;
            }
        }
        
        playerView.setVisibility(View.GONE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
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
