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
    private Button btnPlay, btnStop, btnWatch;
    private ScrollView logScroll;
    
    private String savePath;
    private SessionManager session;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private long fileLength;
    private int numPieces;
    private long pieceLength = 262144;
    private int lastPiece = -1;
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private torrent_handle torrentHandle; // Só usado na thread de download

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
        btnWatch = findViewById(R.id.btn_watch);
        
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
                } else if (state == Player.STATE_BUFFERING) {
                    loadingOverlay.setVisibility(View.VISIBLE);
                    spinnerBar.setVisibility(View.VISIBLE);
                }
            }
        });
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); } catch (Exception e) {}
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
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
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 5);
                server.setReuseAddress(true);
                while (!Thread.interrupted()) {
                    try { Socket client = server.accept(); handleClient(client); } catch (IOException e) {}
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
            
            String req = in.readLine();
            if (req == null || !req.contains("/video")) {
                out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return;
            }
            
            long start = 0, end = -1;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    String r = line.substring(6).trim().replace("bytes=", "");
                    String[] parts = r.split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                }
            }
            
            if (videoFile == null || !videoFile.exists() || videoFile.length() < 4096) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); out.flush(); client.close(); return;
            }
            
            long len = videoFile.length();
            if (end == -1 || end >= len) end = len - 1;
            if (start >= len) {
                out.write("HTTP/1.1 416\r\n\r\n".getBytes()); out.flush(); client.close(); return;
            }
            
            String mime = videoFile.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            int size = (int)(end - start + 1);
            if (size > 131072) size = 131072;
            
            byte[] buf = new byte[size];
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(start);
            int total = raf.read(buf);
            raf.close();
            
            if (total <= 0) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); out.flush(); client.close(); return;
            }
            
            String resp = "HTTP/1.1 206\r\nContent-Type: " + mime + "\r\n" +
                "Content-Range: bytes " + start + "-" + (start+total-1) + "/" + len + "\r\n" +
                "Content-Length: " + total + "\r\nAccept-Ranges: bytes\r\n\r\n";
            
            out.write(resp.getBytes()); out.write(buf, 0, total); out.flush(); client.close();
            
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        lastPiece = -1;
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        log("⏳ Baixando...");
        
        new Thread(() -> {
            try {
                // Configura download NÃO sequencial (flag 0)
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(0)); // ← ISSO É CRUCIAL! Permite baixar qualquer peça
                p.setDownload_limit(3 * 1024 * 1024);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrentHandle = h.get(0);
                    torrent_status ts = torrentHandle.status();
                    fileLength = ts.getTotal_wanted();
                    
                    if (fileLength > 0) {
                        pieceLength = ts.get_piece_length();
                        numPieces = ts.get_num_pieces();
                        log("📊 " + (fileLength/1048576) + "MB | " + numPieces + " peças | pieceLen=" + pieceLength);
                    }
                }
                
                // Priority updater - roda na thread principal
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (downloading && torrentHandle != null && torrentHandle.is_valid() && 
                            numPieces > 0 && player != null && player.getDuration() > 0) {
                            
                            long pos = player.getCurrentPosition();
                            long dur = player.getDuration();
                            int piece = (int)(((double)pos / dur) * numPieces);
                            if (piece >= numPieces) piece = numPieces - 1;
                            if (piece < 0) piece = 0;
                            
                            if (piece != lastPiece) {
                                lastPiece = piece;
                                int min = (int)(pos / 60000);
                                int sec = (int)((pos % 60000) / 1000);
                                log("🎯 " + min + ":" + String.format("%02d", sec) + " → peça " + piece + "/" + numPieces);
                            }
                            
                            try {
                                byte_vector pv = new byte_vector();
                                for (int i = 0; i < numPieces; i++) {
                                    if (i >= piece - 3 && i <= piece + 25) pv.add((byte)7);
                                    else if (i >= piece - 10 && i <= piece + 50) pv.add((byte)5);
                                    else if (i < 20) pv.add((byte)3);
                                    else pv.add((byte)1);
                                }
                                torrentHandle.prioritize_pieces_ex(pv);
                            } catch (Exception e) {}
                        }
                        if (downloading) handler.postDelayed(this, 1500);
                    }
                }, 1500);
                
                // Aguarda header válido
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 65536) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e) { continue; }
                        
                        if ((hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                            ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3)) {
                            videoFile = f;
                            long mb = f.length()/1048576;
                            log("📁 " + f.getName() + " (" + mb + "MB)");
                            handler.post(() -> {
                                btnWatch.setText("🎬 ASSISTIR (" + mb + "MB)");
                                btnWatch.setVisibility(View.VISIBLE);
                            });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) { log("❌ " + e.getMessage()); }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { log("❌ Arquivo não encontrado"); return; }
        log("▶️ " + videoFile.getName());
        lastPiece = -1;
        handler.post(() -> { playerView.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); });
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.clearMediaItems(); }
        playerView.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); loadingOverlay.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        if (torrentHandle != null && torrentHandle.is_valid() && session != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
            torrentHandle = null;
        }
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