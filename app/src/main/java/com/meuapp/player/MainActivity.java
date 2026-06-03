package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

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
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private ServerSocket serverSocket;
    private ExecutorService serverPool;
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private int httpRequests = 0;

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
        
        // Listener SUPER detalhado
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                String s;
                switch (state) {
                    case Player.STATE_IDLE: s = "IDLE"; break;
                    case Player.STATE_BUFFERING: s = "BUFFERING"; break;
                    case Player.STATE_READY: s = "READY ✅"; break;
                    case Player.STATE_ENDED: s = "ENDED"; break;
                    default: s = "?";
                }
                log("🎬 PLAYER STATE: " + s);
                
                if (state == Player.STATE_READY) {
                    log("   ✅ Player PRONTO!");
                    log("   ⏱️ Duração: " + player.getDuration() + "ms");
                    log("   📍 Posição: " + player.getCurrentPosition() + "ms");
                    log("   📊 Buffer: " + player.getBufferedPercentage() + "%");
                    log("   🎞️ Formato: " + (player.getCurrentMediaItem() != null ? 
                        player.getCurrentMediaItem().localConfiguration.uri : "null"));
                    loadingOverlay.setVisibility(View.GONE);
                    spinnerBar.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    log("   ⏳ BUFFERING...");
                    log("   📊 Buffer: " + player.getBufferedPercentage() + "%");
                    loadingOverlay.setVisibility(View.VISIBLE);
                    spinnerBar.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_IDLE) {
                    log("   💤 IDLE - player parado");
                } else if (state == Player.STATE_ENDED) {
                    log("   🏁 ENDED - vídeo terminou");
                }
            }
            
            @Override
            public void onPlayerError(PlaybackException error) {
                log("❌❌❌ PLAYER ERROR ❌❌❌");
                log("   📋 Código: " + error.getErrorCodeName());
                log("   📋 Mensagem: " + error.getMessage());
                log("   📋 Localized: " + error.getLocalizedMessage());
                
                if (error.getCause() != null) {
                    Throwable cause = error.getCause();
                    log("   🔍 Causa: " + cause.getClass().getName());
                    log("   🔍 Msg causa: " + cause.getMessage());
                    
                    // Desce na cadeia de causas
                    Throwable root = cause;
                    int level = 0;
                    while (root.getCause() != null && level < 5) {
                        root = root.getCause();
                        level++;
                        log("   🔍 Nível " + level + ": " + root.getClass().getSimpleName() + " - " + root.getMessage());
                    }
                }
                
                log("   📊 Timestamp: " + error.timestampMs);
                loadingOverlay.setVisibility(View.VISIBLE);
                spinnerBar.setVisibility(View.VISIBLE);
            }
            
            @Override
            public void onIsLoadingChanged(boolean isLoading) {
                log("   🔄 isLoading: " + isLoading);
            }
            
            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                log("   📺 MediaItem: " + (mediaItem != null ? mediaItem.localConfiguration.uri : "null"));
            }
        });
        
        new Thread(() -> {
            try { 
                session = new SessionManager(); 
                session.start();
                log("✅ Sessão torrent OK");
            } catch (Exception e) { 
                log("❌ Sessão: " + e.getMessage()); 
            }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        
        log("╔══════════════════════╗");
        log("║   APP INICIADO       ║");
        log("╚══════════════════════╝");
        log("📱 SDK: " + android.os.Build.VERSION.SDK_INT);
        log("📱 Modelo: " + android.os.Build.MODEL);
        log("📁 Pasta: " + savePath);
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        fullLog.append(line);
        Log.d("APP_DEBUG", msg);
        handler.post(() -> {
            statusText.setText(msg);
            logText.setText(fullLog.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    private void startServer() {
        serverPool = Executors.newFixedThreadPool(2);
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8080, 5);
                serverSocket.setReuseAddress(true);
                log("🌐 SERVIDOR HTTP: porta 8080");
                log("   URL: http://127.0.0.1:8080/video");
                
                while (!serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    httpRequests++;
                    int reqNum = httpRequests;
                    log("📥 HTTP #" + reqNum + " - Nova conexão de " + client.getInetAddress());
                    serverPool.execute(() -> handle(client, reqNum));
                }
            } catch (IOException e) {
                log("❌ SERVIDOR: " + e.getMessage());
            }
        }).start();
    }
    
    private void handle(Socket client, int reqNum) {
        long t0 = System.currentTimeMillis();
        try {
            client.setSoTimeout(5000);
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            // Lê request completa
            String req = in.readLine();
            log("   #" + reqNum + " 📤 " + (req != null ? req : "NULL"));
            
            if (req == null || !req.contains("/video")) {
                out.write("HTTP/1.1 404\r\n\r\n".getBytes());
                out.flush(); client.close();
                log("   #" + reqNum + " ↪ 404 Not Found");
                return;
            }
            
            // Lê TODOS os headers
            String rangeStr = null;
            String line;
            int headerCount = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                headerCount++;
                if (line.startsWith("Range:")) {
                    rangeStr = line.substring(6).trim();
                    log("   #" + reqNum + " 📐 Range: " + rangeStr);
                }
            }
            log("   #" + reqNum + " 📋 " + headerCount + " headers");
            
            // Verifica arquivo
            File vf = videoFile;
            if (vf == null) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush(); client.close();
                log("   #" + reqNum + " ↪ 503 (videoFile=NULL)");
                return;
            }
            if (!vf.exists()) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush(); client.close();
                log("   #" + reqNum + " ↪ 503 (!exists)");
                return;
            }
            
            long fileLen = vf.length();
            if (fileLen < 4096) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush(); client.close();
                log("   #" + reqNum + " ↪ 503 (size=" + fileLen + ")");
                return;
            }
            
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            long start = 0, end = fileLen - 1;
            
            if (rangeStr != null) {
                String r = rangeStr.replace("bytes=", "");
                String[] parts = r.split("-");
                try {
                    start = Long.parseLong(parts[0]);
                    end = (parts.length > 1 && !parts[1].isEmpty()) ? Long.parseLong(parts[1]) : fileLen - 1;
                } catch (NumberFormatException e) {
                    out.write("HTTP/1.1 400 Bad Range\r\n\r\n".getBytes());
                    out.flush(); client.close();
                    log("   #" + reqNum + " ↪ 400 Bad Range format");
                    return;
                }
                
                if (start >= fileLen) {
                    out.write("HTTP/1.1 416\r\n\r\n".getBytes()); out.flush(); client.close();
                    log("   #" + reqNum + " ↪ 416 (start=" + start + " >= len=" + fileLen + ")");
                    return;
                }
                if (end >= fileLen) end = fileLen - 1;
                if (end - start > 131072) end = start + 131072;
            } else {
                end = Math.min(131071, fileLen - 1);
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
                out.flush(); client.close();
                log("   #" + reqNum + " ↪ 503 (read 0 bytes)");
                return;
            }
            
            // Monta resposta com Content-Range sempre
            String resp;
            if (rangeStr != null) {
                resp = "HTTP/1.1 206 Partial Content\r\n";
                resp += "Content-Type: " + mime + "\r\n";
                resp += "Content-Range: bytes " + start + "-" + (start + total - 1) + "/" + fileLen + "\r\n";
                resp += "Content-Length: " + total + "\r\n";
            } else {
                resp = "HTTP/1.1 206 Partial Content\r\n";
                resp += "Content-Type: " + mime + "\r\n";
                resp += "Content-Range: bytes 0-" + (total - 1) + "/*\r\n";
                resp += "Content-Length: " + total + "\r\n";
            }
            resp += "Accept-Ranges: bytes\r\n";
            resp += "Connection: close\r\n";
            resp += "Access-Control-Allow-Origin: *\r\n\r\n";
            
            out.write(resp.getBytes());
            out.write(buf, 0, total);
            out.flush();
            long elapsed = System.currentTimeMillis() - t0;
            
            log("   #" + reqNum + " ✅ " + (rangeStr != null ? "206" : "200") + 
                " | " + total + " bytes | " + start + "-" + (start+total-1) + 
                " | " + elapsed + "ms");
            
            client.close();
            
        } catch (SocketTimeoutException e) {
            log("   #" + reqNum + " ⏰ TIMEOUT");
            try { client.close(); } catch (IOException ex) {}
        } catch (Exception e) {
            log("   #" + reqNum + " ❌ " + e.getClass().getSimpleName() + ": " + e.getMessage());
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        httpRequests = 0;
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
        });
        
        log("╔══════════════════════╗");
        log("║   INICIANDO          ║");
        log("╚══════════════════════╝");
        log("🔗 " + magnet.substring(0, Math.min(50, magnet.length())) + "...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(3 * 1024 * 1024);
                p.setMax_connections(50);
                p.setMax_uploads(5);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                log("📤 Magnet enviado à sessão");
                
                Thread.sleep(2000);
                log("🔍 Procurando arquivo com header válido...");
                
                for (int i = 0; i < 90 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 32768) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e) { continue; }
                        
                        boolean isMP4 = (hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p');
                        boolean isMKV = ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3);
                        
                        if (isMP4 || isMKV) {
                            videoFile = f;
                            log("✅ Header " + (isMP4 ? "MP4" : "MKV") + " válido!");
                            log("   📁 " + f.getName());
                            log("   📏 " + (f.length()/1024) + "KB");
                            log("   📂 " + f.getAbsolutePath());
                            
                            handler.post(() -> {
                                log("▶️ Conectando player ao servidor...");
                                playerView.setVisibility(View.VISIBLE);
                                loadingOverlay.setVisibility(View.VISIBLE);
                                spinnerBar.setVisibility(View.VISIBLE);
                                
                                String url = "http://127.0.0.1:8080/video";
                                log("   🔗 URL: " + url);
                                
                                player.setMediaItem(MediaItem.fromUri(url));
                                player.prepare();
                                player.play();
                                log("   ▶️ play() chamado");
                            });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
                
                if (videoFile == null) {
                    log("⚠️ Timeout: arquivo não encontrado");
                }
                
            } catch (Exception e) { 
                log("❌ ERRO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }).start();
    }
    
    private void stop() {
        log("╔══════════════════════╗");
        log("║   PARANDO            ║");
        log("╚══════════════════════╝");
        log("📊 Total reqs HTTP: " + httpRequests);
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.clearMediaItems(); }
        playerView.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
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
