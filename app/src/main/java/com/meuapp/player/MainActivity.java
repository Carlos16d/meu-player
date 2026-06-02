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
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private boolean downloading = false;
    private StreamServer streamServer;
    private long fileSize = 0;
    private long pieceLength = 0;
    private int numPieces = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        try {
            session = new SessionManager();
            session.start();
            
            streamServer = new StreamServer(8080);
            streamServer.start();
            
            Toast.makeText(this, "UDP + Streaming OK!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        
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
    
    class StreamServer extends NanoHTTPD {
        public StreamServer(int port) {
            super(port);
        }
        
        @Override
        public Response serve(IHTTPSession ses) {
            String uri = ses.getUri();
            if ("/video".equals(uri) && torrent != null && torrent.is_valid()) {
                return serveVideo(ses);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        
        private Response serveVideo(IHTTPSession ses) {
            try {
                if (fileSize == 0) {
                    torrent_status st = torrent.status();
                    fileSize = st.getTotal_wanted();
                    if (fileSize <= 0) fileSize = st.getAll_time_download();
                }
                
                Map<String, String> headers = ses.getHeaders();
                String rangeHeader = headers.get("range");
                
                long start = 0;
                long end = Math.min(1024 * 1024, fileSize - 1);
                
                if (rangeHeader != null) {
                    String[] parts = rangeHeader.replace("bytes=", "").split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    } else {
                        end = Math.min(start + 1024 * 1024, fileSize - 1);
                    }
                }
                
                File videoFile = findVideoFile(new File(savePath));
                if (videoFile == null || !videoFile.exists()) {
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Aguardando download...");
                }
                
                int len = (int)(end - start + 1);
                byte[] data = new byte[len];
                
                try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
                    raf.seek(start);
                    int read = raf.read(data);
                    if (read < len) {
                        byte[] trimmed = new byte[read];
                        System.arraycopy(data, 0, trimmed, 0, read);
                        data = trimmed;
                        len = read;
                    }
                } catch (Exception e) {
                    // Arquivo ainda não tem essa parte
                    return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Parte ainda não baixada");
                }
                
                Response resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT,
                    "video/mp4", new ByteArrayInputStream(data), len);
                resp.addHeader("Content-Range", "bytes " + start + "-" + (start + len - 1) + "/" + fileSize);
                resp.addHeader("Accept-Ranges", "bytes");
                resp.addHeader("Access-Control-Allow-Origin", "*");
                return resp;
                
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
            }
        }
        
        private File findVideoFile(File dir) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        File found = findVideoFile(f);
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
    }
    
    public class Bridge {
        @JavascriptInterface
        public void startDownload(String magnet) {
            if (downloading) return;
            downloading = true;
            
            new Thread(() -> {
                try {
                    add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                    p.setSave_path(savePath);
                    
                    string_vector trackers = new string_vector();
                    trackers.add("udp://tracker.opentrackr.org:1337/announce");
                    trackers.add("udp://tracker.openbittorrent.com:6969/announce");
                    p.setTrackers(trackers);
                    
                    p.setFlags(torrent_flags_t.from_int(9));
                    p.setDownload_limit(0);
                    
                    byte_vector priorities = new byte_vector();
                    priorities.add((byte)7);
                    p.set_file_priorities(priorities);
                    
                    session.swig().async_add_torrent(p);
                    
                    Thread.sleep(5000);
                    
                    torrent_handle_vector handles = session.swig().get_torrents();
                    if (handles.size() > 0) {
                        torrent = handles.get(0);
                        
                        torrent_status st = torrent.status();
                        fileSize = st.getTotal_wanted();
                        
                        byte_vector piecePriorities = new byte_vector();
                        for (int i = 0; i < 200; i++) {
                            byte priority = (i < 20) ? (byte)7 : (i < 50) ? (byte)6 : (i < 100) ? (byte)5 : (byte)4;
                            piecePriorities.add(priority);
                        }
                        torrent.prioritize_pieces_ex(piecePriorities);
                    }
                    
                    runOnUiThread(() -> 
                        Toast.makeText(MainActivity.this, "Streaming UDP ativado!", Toast.LENGTH_SHORT).show()
                    );
                } catch (Exception e) {
                    downloading = false;
                }
            }).start();
        }
        
        @JavascriptInterface
        public String getStreamUrl() {
            return "http://127.0.0.1:8080/video";
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
}
