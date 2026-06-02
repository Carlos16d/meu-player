package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private boolean downloading = false;
    private File videoFile = null;
    private HttpServer httpServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        try {
            session = new SessionManager();
            session.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Inicia servidor HTTP
        httpServer = new HttpServer(8080);
        httpServer.start();
        
        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "App");
        webView.loadUrl("file:///android_asset/www/index.html");
    }
    
    // Servidor HTTP ultra simples
    class HttpServer {
        private int port;
        private ServerSocket serverSocket;
        private volatile boolean running;
        
        HttpServer(int port) { this.port = port; }
        
        void start() {
            running = true;
            new Thread(() -> {
                try {
                    serverSocket = new ServerSocket(port, 5);
                    serverSocket.setReuseAddress(true);
                    while (running) {
                        try {
                            Socket client = serverSocket.accept();
                            handleClient(client);
                        } catch (IOException e) {
                            if (running) e.printStackTrace();
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();
        }
        
        void stop() {
            running = false;
            try { serverSocket.close(); } catch (IOException e) {}
        }
        
        private void handleClient(Socket client) {
            try {
                client.setSoTimeout(5000);
                OutputStream out = client.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                
                String line = in.readLine();
                if (line == null || !line.startsWith("GET /video")) {
                    sendError(out, 404);
                    client.close();
                    return;
                }
                
                // Lê headers
                String rangeStr = null;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("range:")) {
                        rangeStr = line.substring(6).trim();
                    }
                }
                
                // Verifica arquivo
                File vf = videoFile;
                if (vf == null || !vf.exists() || vf.length() < 4096) {
                    sendError(out, 503);
                    client.close();
                    return;
                }
                
                long fileLen = vf.length();
                long start = 0;
                long end = Math.min(65535, fileLen - 1);
                
                if (rangeStr != null) {
                    String r = rangeStr.replace("bytes=", "");
                    String[] parts = r.split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Math.min(Long.parseLong(parts[1]), start + 65535);
                    } else {
                        end = Math.min(start + 65535, fileLen - 1);
                    }
                }
                
                if (start >= fileLen || end >= fileLen) {
                    sendError(out, 416);
                    client.close();
                    return;
                }
                
                int len = (int)(end - start + 1);
                if (len > 65536) len = 65536;
                
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
                
                if (total == 0) {
                    sendError(out, 503);
                    client.close();
                    return;
                }
                
                String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
                
                out.write("HTTP/1.1 206 Partial Content\r\n".getBytes());
                out.write(("Content-Type: " + mime + "\r\n").getBytes());
                out.write(("Content-Range: bytes " + start + "-" + (start + total - 1) + "/" + fileLen + "\r\n").getBytes());
                out.write(("Content-Length: " + total + "\r\n").getBytes());
                out.write("Accept-Ranges: bytes\r\n".getBytes());
                out.write("Connection: close\r\n".getBytes());
                out.write("Access-Control-Allow-Origin: *\r\n".getBytes());
                out.write("\r\n".getBytes());
                out.write(buf, 0, total);
                out.flush();
                client.close();
                
            } catch (Exception e) {
                try { client.close(); } catch (IOException ex) {}
            }
        }
        
        private void sendError(OutputStream out, int code) {
            try {
                out.write(("HTTP/1.1 " + code + " Error\r\nConnection: close\r\n\r\n").getBytes());
                out.flush();
            } catch (IOException e) {}
        }
    }
    
    public class Bridge {
        @JavascriptInterface
        public void startDownload(String magnet) {
            if (downloading) return;
            downloading = true;
            videoFile = null;
            
            new Thread(() -> {
                try {
                    add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                    p.setSave_path(savePath);
                    p.setFlags(torrent_flags_t.from_int(9));
                    p.setDownload_limit(0);
                    p.setMax_connections(200);
                    
                    byte_vector pr = new byte_vector();
                    pr.add((byte)7);
                    p.set_file_priorities(pr);
                    
                    session.swig().async_add_torrent(p);
                    
                    Thread.sleep(3000);
                    
                    torrent_handle_vector h = session.swig().get_torrents();
                    if (h.size() > 0) torrent = h.get(0);
                    
                } catch (Exception e) {
                    downloading = false;
                }
            }).start();
        }
        
        @JavascriptInterface
        public String getStreamUrl() {
            // Verifica se o arquivo já existe
            if (videoFile == null) {
                videoFile = findVideo(new File(savePath));
            }
            if (videoFile != null && videoFile.exists() && videoFile.length() > 10000) {
                return "http://127.0.0.1:8080/video";
            }
            return "";
        }
        
        @JavascriptInterface
        public String getProgress() {
            if (torrent != null && torrent.is_valid()) {
                return String.valueOf((int)(torrent.status().getProgress() * 100));
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getPeers() {
            if (torrent != null && torrent.is_valid()) {
                return String.valueOf(torrent.status().getNum_peers());
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getSpeed() {
            if (torrent != null && torrent.is_valid()) {
                long speed = torrent.status().getDownload_rate();
                if (speed > 1048576) return String.format("%.1f MB/s", speed / 1048576.0);
                if (speed > 1024) return String.format("%.1f KB/s", speed / 1024.0);
                return speed + " B/s";
            }
            return "0 B/s";
        }
    }
    
    private File findVideo(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideo(f);
                    if (found != null) return found;
                } else {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".webm")) {
                        return f;
                    }
                }
            }
        }
        return null;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (httpServer != null) httpServer.stop();
        if (session != null) session.stop();
    }
}
