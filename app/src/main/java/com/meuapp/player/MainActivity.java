package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
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
    private volatile boolean downloading = false;
    private volatile File videoFile = null;
    private Thread serverThread;

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
        
        startServer();
        
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
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                
                while (!Thread.interrupted()) {
                    try {
                        Socket client = server.accept();
                        new Thread(() -> handle(client)).start();
                    } catch (IOException e) {
                        if (!server.isClosed()) e.printStackTrace();
                    }
                }
                server.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handle(Socket client) {
        try {
            client.setSoTimeout(10000);
            OutputStream out = client.getOutputStream();
            InputStream in = client.getInputStream();
            
            // Lê request
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') sb.append((char)c);
            String request = sb.toString();
            
            // Lê headers
            String range = null;
            sb.setLength(0);
            boolean headerDone = false;
            while (!headerDone && (c = in.read()) != -1) {
                if (c == '\r') continue;
                if (c == '\n') {
                    String line = sb.toString().trim();
                    if (line.isEmpty()) headerDone = true;
                    else if (line.toLowerCase().startsWith("range:")) range = line.substring(6).trim();
                    sb.setLength(0);
                } else {
                    sb.append((char)c);
                }
            }
            
            if (!request.contains("/video")) {
                write(out, "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
                client.close();
                return;
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 8192) {
                write(out, "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 1\r\nConnection: close\r\n\r\n");
                client.close();
                return;
            }
            
            long fileLen = vf.length();
            long start = 0, end = fileLen - 1;
            
            if (range != null) {
                try {
                    String r = range.replace("bytes=", "");
                    String[] parts = r.split("-");
                    start = Long.parseLong(parts[0]);
                    end = (parts.length > 1 && !parts[1].isEmpty()) ? 
                        Long.parseLong(parts[1]) : fileLen - 1;
                } catch (NumberFormatException e) {}
            }
            
            // Verifica se o pedaço solicitado já existe no arquivo
            if (start >= fileLen) {
                write(out, "HTTP/1.1 416 Range Not Satisfiable\r\nConnection: close\r\n\r\n");
                client.close();
                return;
            }
            
            // Se pediu além do que existe, limita ao que tem
            if (end >= fileLen) end = fileLen - 1;
            
            // Máximo 256KB por resposta
            if (end - start > 262143) end = start + 262143;
            
            int len = (int)(end - start + 1);
            if (len <= 0 || len > 262144) {
                write(out, "HTTP/1.1 416 Range Not Satisfiable\r\nConnection: close\r\n\r\n");
                client.close();
                return;
            }
            
            byte[] buf = new byte[len];
            int total = 0;
            
            try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
                raf.seek(start);
                while (total < len) {
                    int r = raf.read(buf, total, len - total);
                    if (r == -1) break;
                    total += r;
                }
            } catch (IOException e) {
                // Erro ao ler - retorna o que conseguiu
            }
            
            if (total == 0) {
                write(out, "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 1\r\nConnection: close\r\n\r\n");
                client.close();
                return;
            }
            
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            
            StringBuilder headers = new StringBuilder();
            headers.append("HTTP/1.1 206 Partial Content\r\n");
            headers.append("Content-Type: ").append(mime).append("\r\n");
            headers.append("Content-Range: bytes ").append(start).append("-").append(start + total - 1).append("/").append(fileLen).append("\r\n");
            headers.append("Content-Length: ").append(total).append("\r\n");
            headers.append("Accept-Ranges: bytes\r\n");
            headers.append("Connection: close\r\n");
            headers.append("Access-Control-Allow-Origin: *\r\n");
            headers.append("\r\n");
            
            out.write(headers.toString().getBytes());
            out.write(buf, 0, total);
            out.flush();
            client.close();
            
        } catch (Exception e) {
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private void write(OutputStream out, String s) {
        try {
            out.write(s.getBytes());
            out.flush();
        } catch (IOException e) {}
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
            if (videoFile == null) videoFile = findVideo(new File(savePath));
            if (videoFile != null && videoFile.exists() && videoFile.length() > 8192) {
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
        downloading = false;
        if (serverThread != null) serverThread.interrupt();
        if (session != null) session.stop();
    }
}
