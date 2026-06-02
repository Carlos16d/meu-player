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
    private File videoFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        try {
            // Configura sessão com DHT ativado
            settings_pack sp = new settings_pack();
            sp.set_bool(settings_pack.bool_types.enable_dht, true);
            sp.set_bool(settings_pack.bool_types.enable_lsd, true);
            sp.set_bool(settings_pack.bool_types.enable_upnp, true);
            sp.set_bool(settings_pack.bool_types.enable_natpmp, true);
            
            session = new SessionManager(sp);
            session.start();
            
            // Adiciona DHT routers para encontrar mais peers
            session.swig().add_dht_router("router.bittorrent.com", 6881);
            session.swig().add_dht_router("dht.transmissionbt.com", 6881);
            session.swig().add_dht_router("dht.libtorrent.org", 25401);
            session.swig().add_dht_router("router.utorrent.com", 6881);
            session.swig().add_dht_router("dht.aelitis.com", 6881);
            
            streamServer = new StreamServer(8080);
            streamServer.start();
            
            Toast.makeText(this, "UDP + DHT + Streaming OK!", Toast.LENGTH_SHORT).show();
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
        private static final int MAX_CHUNK = 512 * 1024;
        
        public StreamServer(int port) {
            super(port);
        }
        
        @Override
        public Response serve(IHTTPSession ses) {
            if ("/video".equals(ses.getUri())) {
                return serveVideo(ses);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        
        private Response serveVideo(IHTTPSession ses) {
            try {
                if (videoFile == null || !videoFile.exists()) {
                    videoFile = findVideoFile(new File(savePath));
                }
                
                if (videoFile == null) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Arquivo nao encontrado");
                }
                
                long fileLength = videoFile.length();
                if (fileLength == 0) {
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Arquivo vazio");
                }
                
                Map<String, String> headers = ses.getHeaders();
                String rangeHeader = headers.get("range");
                
                long start = 0;
                long end = Math.min(MAX_CHUNK - 1, fileLength - 1);
                
                if (rangeHeader != null) {
                    String range = rangeHeader.replace("bytes=", "");
                    String[] parts = range.split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    } else {
                        end = Math.min(start + MAX_CHUNK - 1, fileLength - 1);
                    }
                }
                
                if (end - start + 1 > MAX_CHUNK) {
                    end = start + MAX_CHUNK - 1;
                }
                
                if (start >= fileLength) {
                    return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, 
                        "text/plain", "Range not satisfiable");
                }
                if (end >= fileLength) {
                    end = fileLength - 1;
                }
                
                int length = (int)(end - start + 1);
                byte[] data = new byte[length];
                
                try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
                    raf.seek(start);
                    int totalRead = 0;
                    while (totalRead < length) {
                        int read = raf.read(data, totalRead, length - totalRead);
                        if (read == -1) break;
                        totalRead += read;
                    }
                    
                    if (totalRead == 0) {
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, 
                            "text/plain", "Parte ainda nao baixada");
                    }
                    
                    if (totalRead < length) {
                        byte[] trimmed = new byte[totalRead];
                        System.arraycopy(data, 0, trimmed, 0, totalRead);
                        data = trimmed;
                        length = totalRead;
                    }
                }
                
                Response resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT,
                    "video/mp4", new ByteArrayInputStream(data), length);
                resp.addHeader("Content-Range", "bytes " + start + "-" + (start + length - 1) + "/" + fileLength);
                resp.addHeader("Accept-Ranges", "bytes");
                resp.addHeader("Access-Control-Allow-Origin", "*");
                resp.addHeader("Content-Type", "video/mp4");
                return resp;
                
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, 
                    "text/plain", e.getMessage());
            }
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
    
    public class Bridge {
        @JavascriptInterface
        public void startDownload(String magnet) {
            if (downloading) return;
            downloading = true;
            
            new Thread(() -> {
                try {
                    add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                    p.setSave_path(savePath);
                    
                    // MUITOS trackers UDP públicos para maximizar peers
                    string_vector trackers = new string_vector();
                    trackers.add("udp://tracker.opentrackr.org:1337/announce");
                    trackers.add("udp://tracker.openbittorrent.com:6969/announce");
                    trackers.add("udp://open.stealth.si:80/announce");
                    trackers.add("udp://tracker.torrent.eu.org:451/announce");
                    trackers.add("udp://explodie.org:6969/announce");
                    trackers.add("udp://tracker.moeking.me:6969/announce");
                    trackers.add("udp://tracker.cyberia.is:6969/announce");
                    trackers.add("udp://tracker.coppersurfer.tk:6969/announce");
                    trackers.add("udp://tracker.leechers-paradise.org:6969/announce");
                    trackers.add("udp://tracker.internetwarriors.net:1337/announce");
                    trackers.add("udp://9.rarbg.to:2710/announce");
                    trackers.add("udp://tracker.dler.org:6969/announce");
                    // Trackers HTTP também
                    trackers.add("http://tracker.opentrackr.org:1337/announce");
                    trackers.add("http://tracker.openbittorrent.com:80/announce");
                    p.setTrackers(trackers);
                    
                    p.setFlags(torrent_flags_t.from_int(9));
                    p.setDownload_limit(3 * 1024 * 1024); // 3 MB/s
                    p.setMax_connections(200);
                    p.setMax_uploads(10);
                    
                    byte_vector priorities = new byte_vector();
                    priorities.add((byte)7);
                    p.set_file_priorities(priorities);
                    
                    session.swig().async_add_torrent(p);
                    
                    Thread.sleep(4000);
                    
                    torrent_handle_vector handles = session.swig().get_torrents();
                    if (handles.size() > 0) {
                        torrent = handles.get(0);
                    }
                    
                    runOnUiThread(() -> 
                        Toast.makeText(MainActivity.this, "Buscando peers UDP/DHT...", Toast.LENGTH_SHORT).show()
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
