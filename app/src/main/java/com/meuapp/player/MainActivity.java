package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private TextView statusText, debugText;
    private ProgressBar bufferBar;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        webView = findViewById(R.id.webview);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        debugScroll = findViewById(R.id.debug_scroll);
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
            try { session = new SessionManager(); session.start(); debug("✅ OK"); } 
            catch (Exception e) { debug("❌ " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("📱 Pronto");
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
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                while (!Thread.interrupted()) {
                    try { Socket c = server.accept(); new Thread(() -> handleHttp(c)).start(); } catch (IOException e) {}
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
        
        // 🗑️ FORÇA BRUTA DE DELEÇÃO
        debug("🗑️ Limpando pasta...");
        File dir = new File(savePath);
        
        // Método 1: deleta recursivo
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursive(f);
                }
            }
        }
        
        // Verifica se deletou
        dir = new File(savePath);
        File[] remaining = dir.exists() ? dir.listFiles() : null;
        if (remaining != null && remaining.length > 0) {
            debug("⚠️ Ainda restam " + remaining.length + " arquivos:");
            for (File f : remaining) {
                debug("   → " + f.getName() + " (" + (f.length()/1048576) + "MB)");
                f.delete();
            }
        }
        
        // Recria pasta
        new File(savePath).mkdirs();
        
        // Verifica final
        dir = new File(savePath);
        remaining = dir.exists() ? dir.listFiles() : null;
        if (remaining == null || remaining.length == 0) {
            debug("✅ Pasta LIMPA! Iniciando download novo...");
        } else {
            debug("❌ NÃO FOI POSSÍVEL LIMPAR! " + remaining.length + " arquivos restantes");
        }
        
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        debugLog.setLength(0);
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        debug("╔══════════════════════════╗");
        debug("║   TESTE: Minuto 8        ║");
        debug("╚══════════════════════════╝");
        debug("🎯 Baixando APENAS região 210MB-220MB");
        
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
                    debug("📊 " + torrentHandle.status().getNum_peers() + " peers");
                    
                    int pieceLen = 262144;
                    int startP = (int)(210L * 1048576 / pieceLen);
                    int endP = startP + 40;
                    
                    for (int j = startP; j <= Math.min(endP, 9999); j++) {
                        try { torrentHandle.set_piece_deadline(j, 10); } catch (Exception ex) {}
                    }
                    debug("🎯 Peças " + startP + "-" + endP + " priorizadas");
                }
                
                debug("🔍 Aguardando 10MB em 210MB...");
                for (int i = 0; i < 300 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null) {
                        long flen = f.length();
                        
                        if (flen > 215000000L) {
                            byte[] check = new byte[1024];
                            try {
                                RandomAccessFile raf = new RandomAccessFile(f, "r");
                                raf.seek(215000000L);
                                raf.read(check);
                                raf.close();
                                
                                int nz = 0;
                                for (byte b : check) if (b != 0) nz++;
                                
                                debug("   [" + (i+1) + "s] " + (flen/1048576) + "MB | 210MB: " + nz + "/1024 bytes reais");
                                
                                if (nz > 500) {
                                    videoFile = f;
                                    debug("✅ DADOS REAIS ENCONTRADOS em 210MB!");
                                    handler.post(() -> {
                                        btnWatch.setText("🎬 TESTAR MIN 8");
                                        btnWatch.setVisibility(View.VISIBLE);
                                    });
                                    break;
                                }
                            } catch (Exception e2) {}
                        } else {
                            if (i % 5 == 0) {
                                debug("   [" + (i+1) + "s] " + (flen/1048576) + "MB (ainda não chegou em 215MB)");
                            }
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e2) { debug("❌ " + e2.getMessage()); }
        }).start();
    }
    
    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return f.delete();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não encontrado"); return; }
        debug("▶️ Tentando reproduzir minuto 8...");
        
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
            "v.addEventListener('loadedmetadata',function(){" +
            "  v.currentTime=480;v.play();" +
            "  document.title='▶️ Min 8'" +
            "});" +
            "</script></body></html>";
        
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }
    
    private void stop() {
        debug("⏹️ Parado");
        downloading = false;
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
        if (serverThread != null) serverThread.interrupt();
        if (session != null) session.stop();
        super.onDestroy();
    }
}