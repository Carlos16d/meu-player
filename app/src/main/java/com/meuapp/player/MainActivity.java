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
    private TextView statusText, logText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private EditText magnetInput;
    private Button btnPlay, btnStop;
    private ScrollView logScroll;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private long fileLength;
    private long pieceLength;
    private int numPieces;
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        
        playerView.post(() -> {
            int width = (int)(getResources().getDisplayMetrics().widthPixels * 0.92);
            int height = (int)(width * 9.0 / 16.0);
            ViewGroup.LayoutParams p = playerView.getLayoutParams();
            p.width = width;
            p.height = height;
            playerView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setVisibility(View.GONE);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loadingOverlay.setVisibility(View.GONE);
                    spinnerBar.setVisibility(View.GONE);
                    updatePriority();
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
        
        log("📱 Pronto");
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        fullLog.append(line);
        handler.post(() -> {
            statusText.setText(msg);
            logText.setText(fullLog.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    private void updatePriority() {
        if (torrent == null || !torrent.is_valid() || player == null || numPieces <= 0) return;
        
        long pos = player.getCurrentPosition();
        long duration = player.getDuration();
        if (duration <= 0) return;
        
        int currentPiece = (int)(((double)pos / duration) * numPieces);
        
        byte_vector priorities = new byte_vector();
        for (int i = 0; i < numPieces; i++) {
            byte priority;
            if (i >= currentPiece && i < currentPiece + 20) priority = 7;
            else if (i >= currentPiece - 5 && i < currentPiece + 50) priority = 5;
            else priority = 1;
            priorities.add(priority);
        }
        
        try {
            torrent.prioritize_pieces_ex(priorities);
        } catch (Exception e) {}
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 5);
                server.setReuseAddress(true);
                
                while (!Thread.interrupted()) {
                    try {
                        Socket client = server.accept();
                        handleClient(client);
                    } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleClient(Socket client) {
        try {
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String request = in.readLine();
            if (request == null || !request.contains("/video")) {
                out.write("HTTP/1.1 404\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long rangeStart = 0, rangeEnd = -1;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    String r = line.substring(6).trim();
                    if (r.startsWith("bytes=")) {
                        r = r.substring(6);
                        String[] parts = r.split("-");
                        rangeStart = Long.parseLong(parts[0]);
                        if (parts.length > 1 && !parts[1].isEmpty()) rangeEnd = Long.parseLong(parts[1]);
                    }
                }
            }
            
            if (videoFile == null || !videoFile.exists() || videoFile.length() < 4096) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long fileLen = videoFile.length();
            if (rangeEnd == -1 || rangeEnd >= fileLen) rangeEnd = fileLen - 1;
            
            String mime = videoFile.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            int len = (int)(rangeEnd - rangeStart + 1);
            if (len > 131072) len = 131072;
            
            byte[] buf = new byte[len];
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(rangeStart);
            int total = raf.read(buf);
            raf.close();
            
            if (total <= 0) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            String resp = "HTTP/1.1 206 Partial Content\r\n";
            resp += "Content-Type: " + mime + "\r\n";
            resp += "Content-Range: bytes " + rangeStart + "-" + (rangeStart + total - 1) + "/" + fileLen + "\r\n";
            resp += "Content-Length: " + total + "\r\n";
            resp += "Accept-Ranges: bytes\r\n\r\n";
            
            out.write(resp.getBytes());
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
        torrent = null;
        numPieces = 0;
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
        });
        
        log("⏳ Baixando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(3 * 1024 * 1024);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrent = h.get(0);
                    torrent_status ts = torrent.status();
                    fileLength = ts.getTotal_wanted();
                    if (fileLength > 0) {
                        // Estima piece length e número de peças
                        pieceLength = 262144; // 256KB típico
                        numPieces = (int)(fileLength / pieceLength) + 1;
                        log("📊 ~" + numPieces + " peças | " + (fileLength/1048576) + "MB");
                    }
                }
                
                for (int i = 0; i < 90 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 65536) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e) { continue; }
                        
                        boolean valid = (hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                                       ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3);
                        
                        if (valid) {
                            videoFile = f;
                            log("▶️ " + f.getName());
                            
                            // Prioriza primeiras peças
                            if (torrent != null && torrent.is_valid() && numPieces > 0) {
                                byte_vector priorities = new byte_vector();
                                for (int j = 0; j < numPieces; j++) {
                                    priorities.add((byte)(j < 30 ? 7 : j < 60 ? 5 : 1));
                                }
                                torrent.prioritize_pieces_ex(priorities);
                            }
                            
                            handler.post(() -> {
                                playerView.setVisibility(View.VISIBLE);
                                player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
                                player.prepare();
                                player.play();
                            });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) { log("❌ " + e.getMessage()); }
        }).start();
        
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (downloading) {
                    updatePriority();
                    handler.postDelayed(this, 2000);
                }
            }
        }, 2000);
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.clearMediaItems(); }
        playerView.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
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
        if (serverThread != null) serverThread.interrupt();
        if (player != null) player.release();
        if (session != null) session.stop();
        super.onDestroy();
    }
}
