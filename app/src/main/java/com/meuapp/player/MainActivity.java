package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private VideoView videoView;
    private TextView statusText, debugText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    private ScrollView debugScroll;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrentHandle;
    private volatile boolean downloading;
    private volatile boolean isPlaying = false;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoView = findViewById(R.id.video_view);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        debugScroll = findViewById(R.id.debug_scroll);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        videoView.post(() -> {
            int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.92);
            int h = (int)(w * 9.0 / 16.0);
            ViewGroup.LayoutParams p = videoView.getLayoutParams();
            p.width = w;
            p.height = h;
            videoView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        // MediaController sobre o VideoView
        MediaController mediaController = new MediaController(this, false);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);
        
        videoView.setOnPreparedListener(mp -> {
            loadingOverlay.setVisibility(View.GONE);
            spinnerBar.setVisibility(View.GONE);
            isPlaying = true;
            setSpeedLimit(4 * 1024 * 1024);
            log("✅ Player preparado - reproduzindo");
            debug("📹 Vídeo: " + videoView.getWidth() + "x" + videoView.getHeight());
            debug("⏱️ Duração: " + videoView.getDuration() + "ms");
        });
        
        videoView.setOnErrorListener((mp, what, extra) -> {
            String errorMsg;
            switch (what) {
                case -1: errorMsg = "UNKNOWN"; break;
                case 1: errorMsg = "MEDIA_ERROR_UNKNOWN"; break;
                case 100: errorMsg = "MEDIA_ERROR_SERVER_DIED"; break;
                case 200: errorMsg = "MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK"; break;
                case -2147483648: errorMsg = "MEDIA_ERROR_IO"; break;
                default: errorMsg = "CÓDIGO " + what;
            }
            debug("❌ Erro VideoView: " + errorMsg + " | extra=" + extra);
            loadingOverlay.setVisibility(View.VISIBLE);
            spinnerBar.setVisibility(View.VISIBLE);
            handler.postDelayed(() -> {
                if (downloading && videoFile != null && videoFile.exists()) {
                    debug("🔄 Retry...");
                    videoView.setVideoPath(videoFile.getAbsolutePath());
                    videoView.start();
                }
            }, 2000);
            return true;
        });
        
        videoView.setOnCompletionListener(mp -> {
            isPlaying = false;
            setSpeedLimit(2 * 1024 * 1024);
            debug("🏁 Vídeo finalizado");
        });
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); debug("✅ Sessão torrent OK"); } 
            catch (Exception e) { debug("❌ Sessão: " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        log("📱 Pronto");
        debug("📱 App iniciado");
    }
    
    private void setSpeedLimit(int bytesPerSec) {
        if (torrentHandle != null && torrentHandle.is_valid()) {
            try {
                torrentHandle.set_download_limit(bytesPerSec);
                debug("⚡ Velocidade: " + (bytesPerSec/1048576) + " MB/s");
            } catch (Exception e) {}
        }
    }
    
    private void log(String msg) {
        handler.post(() -> statusText.setText("[" + sdf.format(new Date()) + "] " + msg));
    }
    
    private void debug(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        debugLog.append(line);
        handler.post(() -> {
            debugText.setText(debugLog.toString());
            debugScroll.post(() -> debugScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 5);
                server.setReuseAddress(true);
                debug("🌐 Servidor HTTP :8080");
                while (!Thread.interrupted()) {
                    try { Socket c = server.accept(); handleHttp(c); } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {
                debug("❌ Servidor: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleHttp(Socket c) {
        try {
            OutputStream o = c.getOutputStream();
            BufferedReader i = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String r = i.readLine();
            if (r == null || !r.contains("/video")) { o.write("HTTP/1.1 404\r\n\r\n".getBytes()); o.flush(); c.close(); return; }
            
            long s = 0, e = -1;
            String l;
            while ((l = i.readLine()) != null && !l.isEmpty()) {
                if (l.toLowerCase().startsWith("range:")) {
                    String x = l.substring(6).trim().replace("bytes=", "");
                    String[] p = x.split("-");
                    s = Long.parseLong(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) e = Long.parseLong(p[1]);
                }
            }
            
            // 🎯 PRIORIZA AS PEÇAS COM URGÊNCIA MÁXIMA
            if (torrentHandle != null && torrentHandle.is_valid()) {
                int pieceLength = 262144;
                int startPiece = (int)(s / pieceLength);
                int endPiece = Math.min(startPiece + 30, 9999);
                for (int j = startPiece; j <= endPiece; j++) {
                    try { torrentHandle.set_piece_deadline(j, 50); } catch (Exception ex) {}
                }
                debug("🎯 Priorizando peças " + startPiece + "-" + endPiece + " (pos " + (s/1048576) + "MB)");
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 4096) {
                o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close();
                debug("⛔ 503 - Arquivo não pronto");
                return;
            }
            
            long len = vf.length();
            if (e == -1 || e >= len) e = len - 1;
            if (s >= len) { o.write("HTTP/1.1 416\r\n\r\n".getBytes()); o.flush(); c.close(); return; }
            
            String m = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            int sz = (int)(e - s + 1);
            if (sz > 131072) sz = 131072;
            
            byte[] b = new byte[sz];
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(s);
            int t = raf.read(b);
            raf.close();
            
            if (t <= 0) { o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close(); return; }
            
            String resp = "HTTP/1.1 206\r\nContent-Type: " + m + "\r\nContent-Range: bytes " + s + "-" + (s+t-1) + "/" + len + "\r\nContent-Length: " + t + "\r\nAccept-Ranges: bytes\r\n\r\n";
            o.write(resp.getBytes()); o.write(b, 0, t); o.flush(); c.close();
            
        } catch (Exception ex) { try { c.close(); } catch (IOException ex2) {} }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        isPlaying = false;
        debugLog.setLength(0);
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        log("⏳ Baixando...");
        debug("📥 Download iniciado (sequencial + priorização)");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(8)); // SEQUENCIAL
                p.setDownload_limit(2 * 1024 * 1024);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrentHandle = h.get(0);
                    debug("✅ Torrent conectado");
                }
                
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 5242880) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; }
                        if ((hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                            ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3)) {
                            videoFile = f;
                            long mb = f.length()/1048576;
                            log("📁 " + f.getName() + " (" + mb + "MB)");
                            debug("📁 Arquivo: " + f.getName() + " (" + mb + "MB)");
                            handler.post(() -> {
                                btnWatch.setText("🎬 ASSISTIR (" + mb + "MB)");
                                btnWatch.setVisibility(View.VISIBLE);
                            });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e2) { debug("❌ " + e2.getMessage()); }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { log("❌ Arquivo não encontrado"); return; }
        log("▶️ " + videoFile.getName());
        debug("▶️ Iniciando player: " + videoFile.getAbsolutePath());
        handler.post(() -> { 
            videoView.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE);
            loadingOverlay.setVisibility(View.VISIBLE);
            spinnerBar.setVisibility(View.VISIBLE);
        });
        videoView.setVideoPath(videoFile.getAbsolutePath());
        videoView.start();
        videoView.requestFocus();
    }
    
    private void stop() {
        downloading = false;
        isPlaying = false;
        handler.removeCallbacksAndMessages(null);
        if (torrentHandle != null && session != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
            torrentHandle = null;
        }
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); loadingOverlay.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        debug("⏹️ Parado");
        log("⏹️ Parado");
    }
    
    private File find(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) { File found = find(f); if (found != null) return found; }
            else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f;
        }
        return null;
    }
    
    @Override protected void onDestroy() {
        stop();
        if (serverThread != null) serverThread.interrupt();
        if (session != null) session.stop();
        super.onDestroy();
    }
}