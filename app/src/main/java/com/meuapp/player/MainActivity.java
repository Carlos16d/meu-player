package com.meuapp.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private StringBuilder debugLog = new StringBuilder();
    private int httpReqCount = 0;

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
            p.width = w; p.height = h;
            videoView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        MediaController mediaController = new MediaController(this, false);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);
        
        videoView.setOnPreparedListener(mp -> {
            debug("✅ Reproduzindo | " + videoView.getDuration()/1000 + "s | " +
                  mp.getVideoWidth() + "x" + mp.getVideoHeight());
            loadingOverlay.setVisibility(View.GONE);
            spinnerBar.setVisibility(View.GONE);
        });
        
        videoView.setOnErrorListener((mp, what, extra) -> {
            debug("⏳ Aguardando dados... (erro " + what + ")");
            loadingOverlay.setVisibility(View.VISIBLE);
            spinnerBar.setVisibility(View.VISIBLE);
            handler.postDelayed(() -> {
                if (downloading && videoFile != null) {
                    videoView.setVideoURI(Uri.parse("http://127.0.0.1:8080/video"));
                    videoView.start();
                }
            }, 1000);
            return true;
        });
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); debug("✅ Sessão OK"); } 
            catch (Exception e) { debug("❌ " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("══════ APP INICIADO ══════");
    }
    
    private void debug(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        debugLog.append(line);
        handler.post(() -> {
            statusText.setText(msg);
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
                    try { 
                        Socket c = server.accept(); 
                        httpReqCount++;
                        handleHttp(c); 
                    } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {}
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
            
            debug("📥 #" + httpReqCount + " | Range: " + s + "-" + (e == -1 ? "?" : e));
            
            // 🎯 Prioriza a região que o player está pedindo
            if (torrentHandle != null && torrentHandle.is_valid()) {
                int pieceLen = 262144;
                int startP = (int)(s / pieceLen);
                int endP = Math.min(startP + 30, 9999);
                for (int j = startP; j <= endP; j++) {
                    try { torrentHandle.set_piece_deadline(j, 50); } catch (Exception ex) {}
                }
                debug("   🎯 Priorizando peças " + startP + "-" + endP);
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 4096) {
                o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close();
                debug("   ↪ 503 (arquivo não pronto)");
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
            
            if (t <= 1024) {
                o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close();
                debug("   ↪ 503 (dados insuficientes: " + t + " bytes)");
                return;
            }
            
            String resp = "HTTP/1.1 206\r\nContent-Type: " + m + "\r\n" +
                "Content-Range: bytes " + s + "-" + (s+t-1) + "/" + len + "\r\n" +
                "Content-Length: " + t + "\r\nAccept-Ranges: bytes\r\n\r\n";
            o.write(resp.getBytes()); o.write(b, 0, t); o.flush(); c.close();
            
            debug("   ✅ 206 | " + t + " bytes enviados");
            
        } catch (Exception ex) { 
            try { c.close(); } catch (IOException ex2) {}
            debug("   ❌ " + ex.getClass().getSimpleName());
        }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        httpReqCount = 0;
        debugLog.setLength(0);
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        debug("══════ INICIANDO ══════");
        debug("📡 " + magnet.substring(0, Math.min(50, magnet.length())) + "...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(0));
                p.setDownload_limit(2 * 1024 * 1024);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrentHandle = h.get(0);
                    torrent_status ts = torrentHandle.status();
                    debug("📊 " + ts.getNum_peers() + " peers | " + 
                          (ts.getTotal_wanted()/1048576) + "MB");
                }
                
                debug("🔍 Procurando arquivo...");
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 5242880) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; }
                        
                        boolean valid = (hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                                       ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3);
                        
                        if (valid) {
                            videoFile = f;
                            long mb = f.length()/1048576;
                            debug("✅ " + f.getName() + " (" + mb + "MB)");
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
        if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não encontrado"); return; }
        debug("▶️ Streaming HTTP: http://127.0.0.1:8080/video");
        handler.post(() -> { 
            videoView.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE);
        });
        videoView.setVideoURI(Uri.parse("http://127.0.0.1:8080/video"));
        videoView.start();
    }
    
    private void stop() {
        debug("══════ PARANDO ══════");
        debug("📊 Total reqs HTTP: " + httpReqCount);
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (torrentHandle != null && session != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
            torrentHandle = null;
        }
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); loadingOverlay.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
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