package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private TextView statusText, debugText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    
    private String savePath;
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private long lastSeekTime = 0;
    private long videoStartTime = 0;
    private Set<Integer> downloadedPieces = new HashSet<>();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        webView = findViewById(R.id.webview);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        webView.post(() -> {
            int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.94);
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
        
        debug("=== TORRENT STREAM ===");
        
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
        Log.d("TS", msg);
        debugLog.append(line);
        handler.post(() -> { statusText.setText(msg); debugText.setText(debugLog.toString()); });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                while (!Thread.interrupted()) {
                    try { Socket client = server.accept(); new Thread(() -> handleHttp(client)).start(); } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(10000);
            InputStream inputStream = client.getInputStream();
            OutputStream out = client.getOutputStream();
            
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            int b;
            while ((b = inputStream.read()) != -1) {
                headerBuffer.write(b);
                if (headerBuffer.size() > 4) {
                    byte[] data = headerBuffer.toByteArray();
                    if (data[data.length-4] == '\r' && data[data.length-3] == '\n' &&
                        data[data.length-2] == '\r' && data[data.length-1] == '\n') break;
                }
            }
            
            String request = new String(headerBuffer.toByteArray());
            String[] lines = request.split("\r\n");
            String firstLine = lines.length > 0 ? lines[0] : "";
            
            if (!firstLine.contains("/video")) {
                out.write("HTTP/1.1 404\r\nContent-Length: 0\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long rangeStart = 0, rangeEnd = -1;
            boolean hasRange = false;
            for (String line : lines) {
                if (line.toLowerCase().startsWith("range: bytes=")) {
                    hasRange = true;
                    String v = line.substring(13).trim();
                    String[] p = v.split("-");
                    rangeStart = Long.parseLong(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) rangeEnd = Long.parseLong(p[1]);
                }
            }
            
            if (videoFile == null || !videoFile.exists()) {
                out.write("HTTP/1.1 404\r\nContent-Length: 0\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long fileSize = videoFile.length();
            
            if (!hasRange) {
                String resp = "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n" +
                    "Content-Length: " + fileSize + "\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
                out.write(resp.getBytes());
                byte[] data = new byte[1048576];
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                int read = raf.read(data);
                if (read > 0) out.write(data, 0, read);
                raf.close(); out.flush(); client.close();
                if (videoStartTime == 0) { videoStartTime = System.currentTimeMillis(); }
                return;
            }
            
            if (rangeEnd == -1 || rangeEnd >= fileSize) rangeEnd = fileSize - 1;
            long contentLength = rangeEnd - rangeStart + 1;
            
            if (hasRange && rangeStart > 0) {
                final long now = System.currentTimeMillis();
                boolean isAutoSeek = (videoStartTime > 0) && (now - videoStartTime) < 5000;
                
                if (!isAutoSeek && (now - lastSeekTime) > 3000) {
                    lastSeekTime = now;
                    try {
                        if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) {
                            TorrentInfo info = torrentHandle.torrentFile();
                            int pl = info.pieceLength(), np = info.numPieces();
                            int tp = (int)(rangeStart / pl);
                            int rs = Math.max(0, tp - 2), re = Math.min(tp + 7, np - 1);
                            
                            torrentHandle.setSequentialRange(rs, re);
                            for (int i = 0; i < np; i++) torrentHandle.piecePriority(i, org.libtorrent4j.Priority.IGNORE);
                            for (int i = rs; i <= re; i++) {
                                torrentHandle.piecePriority(i, org.libtorrent4j.Priority.TOP_PRIORITY);
                                torrentHandle.setPieceDeadline(i, 300);
                            }
                            
                            final int total = re - rs + 1;
                            debug("🔥 SEEK: " + (rangeStart/1048576) + "MB → " + total + " peças (" + rs + "-" + re + ")");
                            
                            final int ms = rs, me = re;
                            new Thread(() -> {
                                try { Thread.sleep(2000);
                                    int done = 0; StringBuilder sb = new StringBuilder();
                                    for (int i = ms; i <= me; i++) {
                                        if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.havePiece(i)) { done++; sb.append(" ✅").append(i); }
                                        else sb.append(" ❌").append(i);
                                    }
                                    debug("📊 " + done + "/" + total + " (" + ((done*100)/total) + "%)" + sb);
                                } catch (Exception e) {}
                            }).start();
                        }
                    } catch (Exception e) {}
                }
            }
            
            String mime = videoFile.getName().toLowerCase().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            String headers = "HTTP/1.1 206 Partial Content\r\nContent-Type: " + mime + "\r\n" +
                "Accept-Ranges: bytes\r\nContent-Range: bytes " + rangeStart + "-" + (rangeStart+contentLength-1) + "/" + fileSize + "\r\n" +
                "Content-Length: " + contentLength + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
            out.write(headers.getBytes());
            
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(rangeStart);
            byte[] buffer = new byte[65536];
            long sent = 0;
            while (sent < contentLength && downloading) {
                int toRead = (int)Math.min(buffer.length, contentLength - sent);
                int read = raf.read(buffer, 0, toRead);
                if (read <= 0) break;
                out.write(buffer, 0, read); out.flush(); sent += read;
            }
            raf.close(); out.flush(); client.close();
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        downloading = true; videoFile = null; torrentHandle = null; videoStartTime = 0;
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); });
        debug("⏳ Conectando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath); p.setFlags(torrent_flags_t.from_int(9)); p.setDownload_limit(3*1024*1024);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                session.swig().async_add_torrent(p); Thread.sleep(3000);
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0));
                
                int w = 0;
                while (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() == null && w < 60 && downloading) {
                    Thread.sleep(1000); w++;
                }
                
                if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) {
                    TorrentInfo info = torrentHandle.torrentFile();
                    int np = info.numPieces(), pl = info.pieceLength();
                    debug("📊 " + (info.totalSize()/1048576) + "MB, " + np + " peças de " + (pl/1024) + "KB");
                    
                    // 30 peças de metadados = ~30MB (áudio, legendas, índice)
                    int metaPieces = Math.min(30, np);
                    debug("📋 Metadados: " + metaPieces + " peças (~" + (metaPieces*pl/1048576) + "MB)");
                    
                    for (int i = 0; i < metaPieces; i++) {
                        try { torrentHandle.piecePriority(i, org.libtorrent4j.Priority.TOP_PRIORITY); torrentHandle.setPieceDeadline(i, 500); } catch (Exception e) {}
                    }
                    for (int i = metaPieces; i < np; i++) {
                        try { torrentHandle.piecePriority(i, org.libtorrent4j.Priority.IGNORE); } catch (Exception e) {}
                    }
                    
                    int complete = 0, wt = 0;
                    while (complete < metaPieces && wt < 120 && downloading) {
                        Thread.sleep(500); complete = 0; wt++;
                        for (int i = 0; i < metaPieces; i++) if (torrentHandle.havePiece(i)) complete++;
                        if (wt % 4 == 0) debug("   📋 " + complete + "/" + metaPieces + " (" + (wt/2) + "s)");
                    }
                    
                    if (complete >= metaPieces) {
                        debug("✅ Metadados OK! 🎵Áudio 📝Legendas 📊Índice (" + (metaPieces*pl/1048576) + "MB)");
                    }
                    
                    // Ativa o resto
                    for (int i = metaPieces; i < np; i++) {
                        try { torrentHandle.piecePriority(i, org.libtorrent4j.Priority.DEFAULT); } catch (Exception e) {}
                    }
                }
                
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 1048576) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; }
                        if ((hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                            ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3)) {
                            videoFile = f;
                            handler.post(() -> { btnWatch.setText("🎬 ASSISTIR"); btnWatch.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.GONE); });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e2) { debug("❌ " + e2.getMessage()); downloading = false; }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não encontrado"); return; }
        debug("▶️ " + videoFile.getName());
        handler.post(() -> { webView.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); });
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + "<style>body{margin:0;background:#000;}video{width:100%;height:100vh;display:block;}</style></head><body>"
            + "<video controls autoplay playsinline><source src='http://127.0.0.1:8080/video' type='video/mp4'></video></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }
    
    private void stop() {
        downloading = false; handler.removeCallbacksAndMessages(null);
        webView.loadUrl("about:blank"); webView.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); bufferBar.setVisibility(View.GONE);
        if (torrentHandle != null && session != null) {
            try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} torrentHandle = null;
        }
    }
    
    private File find(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) { File found = find(f); if (found != null) return found; }
            else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f;
        }
        return null;
    }
    
    @Override protected void onDestroy() { stop(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); super.onDestroy(); }
}