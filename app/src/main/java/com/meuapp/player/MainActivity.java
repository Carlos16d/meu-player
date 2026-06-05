package com.meuapp.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private TextView statusText;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private Runnable reloadRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        webView = findViewById(R.id.webview);
        statusText = findViewById(R.id.status_text);
        bufferBar = findViewById(R.id.buffer_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        webView.post(() -> {
            int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.92);
            int h = (int)(w * 9.0 / 16.0);
            ViewGroup.LayoutParams p = webView.getLayoutParams();
            p.width = w; p.height = h;
            webView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setAllowFileAccess(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.setVisibility(View.GONE);
        
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
        handler.post(() -> statusText.setText("[" + sdf.format(new Date()) + "] " + msg));
    }
    
    private void deleteRecursive(File f) {
        if (f.isDirectory()) for (File child : f.listFiles()) deleteRecursive(child);
        f.delete();
    }
    
    private void startServer() {
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                while (!Thread.interrupted()) {
                    try { Socket c = server.accept(); new Thread(() -> handleHttp(c)).start(); } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {}
        }).start();
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
            
            // 🎯 Prioriza a região que o player pediu
            if (torrentHandle != null && torrentHandle.is_valid()) {
                int pieceLen = 262144;
                int startP = (int)(s / pieceLen);
                int endP = Math.min(startP + 50, 9999);
                for (int j = startP; j <= endP; j++) {
                    try { torrentHandle.set_piece_deadline(j, 20); } catch (Exception ex) {}
                }
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 4096) {
                o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close(); return;
            }
            
            long len = vf.length();
            if (e == -1 || e >= len) e = len - 1;
            
            String m = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            int sz = (int)(e - s + 1);
            if (sz > 262144) sz = 262144;
            
            byte[] b = new byte[sz];
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(s);
            int t = raf.read(b);
            raf.close();
            
            if (t <= 0) { o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close(); return; }
            
            String resp = "HTTP/1.1 206\r\nContent-Type: " + m + "\r\n" +
                "Content-Range: bytes " + s + "-" + (s+t-1) + "/" + len + "\r\n" +
                "Content-Length: " + t + "\r\nAccept-Ranges: bytes\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
            o.write(resp.getBytes()); o.write(b, 0, t); o.flush(); c.close();
            
        } catch (Exception ex) { try { c.close(); } catch (IOException ex2) {} }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        File torrentDir = new File(savePath);
        if (torrentDir.exists()) for (File f : torrentDir.listFiles()) deleteRecursive(f);
        new File(savePath).mkdirs();
        
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        log("⏳ Baixando (2 MB/s)...");
        
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
                if (h.size() > 0) torrentHandle = h.get(0);
                
                for (int i = 0; i < 300 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 5242880) {
                        videoFile = f;
                        long mb = f.length()/1048576;
                        log("📁 " + f.getName() + " (" + mb + "MB)");
                        handler.post(() -> {
                            btnWatch.setText("🎬 ASSISTIR (" + mb + "MB)");
                            btnWatch.setVisibility(View.VISIBLE);
                        });
                        break;
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e2) { log("❌ " + e2.getMessage()); }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { log("❌ Arquivo não encontrado"); return; }
        log("▶️ Player WebView");
        
        handler.post(() -> { 
            webView.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE);
        });
        
        String html = "<!DOCTYPE html><html><head><style>" +
            "body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh;}" +
            "video{width:100%;max-height:100vh;}" +
            "</style></head><body>" +
            "<video id='v' controls autoplay playsinline>" +
            "<source src='http://127.0.0.1:8080/video' type='video/mp4'>" +
            "</video>" +
            "<script>" +
            "var v=document.getElementById('v');" +
            "v.addEventListener('error',function(){document.title='❌ '+v.error.code});" +
            "v.addEventListener('waiting',function(){document.title='⏳'});" +
            "v.addEventListener('playing',function(){document.title='▶️'});" +
            "</script></body></html>";
        
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        
        // Recarrega periodicamente
        reloadRunnable = new Runnable() {
            @Override public void run() {
                if (downloading && videoFile != null) {
                    webView.loadUrl("javascript:var v=document.getElementById('v');" +
                        "var p=v.currentTime;var w=v.paused;" +
                        "v.load();v.currentTime=p;if(!w)v.play();");
                    handler.postDelayed(this, 5000);
                }
            }
        };
        handler.postDelayed(reloadRunnable, 5000);
    }
    
    private void stop() {
        log("⏹️ Parado");
        downloading = false;
        handler.removeCallbacks(reloadRunnable);
        handler.removeCallbacksAndMessages(null);
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        if (torrentHandle != null && session != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
            torrentHandle = null;
        }
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
        if (session != null) session.stop();
        super.onDestroy();
    }
}