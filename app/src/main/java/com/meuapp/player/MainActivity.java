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

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private boolean downloading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        try {
            session = new SessionManager();
            session.start();
            Toast.makeText(this, "UDP rodando!", Toast.LENGTH_SHORT).show();
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
                    trackers.add("udp://open.stealth.si:80/announce");
                    trackers.add("udp://tracker.torrent.eu.org:451/announce");
                    trackers.add("udp://explodie.org:6969/announce");
                    p.setTrackers(trackers);
                    
                    // Flags corretas para download sequencial
                    // 1 = auto_managed, 8 = sequential_download
                    p.setFlags(torrent_flags_t.from_int(9));
                    
                    // Limita velocidade de download para 2MB/s (não sugar toda internet)
                    p.setDownload_limit(2 * 1024 * 1024);
                    
                    // Limita conexões
                    p.setMax_connections(50);
                    p.setMax_uploads(5);
                    
                    session.swig().async_add_torrent(p);
                    
                    Thread.sleep(4000);
                    
                    torrent_handle_vector handles = session.swig().get_torrents();
                    if (handles.size() > 0) {
                        torrent = handles.get(0);
                        
                        // Prioriza as primeiras peças (streaming)
                        torrent.set_sequential_download(true);
                        
                        // Prioridade alta para as primeiras 100 peças
                        int numPieces = torrent.get_torrent_info().num_pieces();
                        int firstPieces = Math.min(100, numPieces);
                        int[] priorities = new int[numPieces];
                        for (int i = 0; i < numPieces; i++) {
                            priorities[i] = (i < firstPieces) ? 7 : 4;
                        }
                        torrent.prioritize_pieces(new int_vector(priorities));
                    }
                    
                    runOnUiThread(() -> 
                        Toast.makeText(MainActivity.this, "Baixando com UDP! (Streaming)", Toast.LENGTH_SHORT).show()
                    );
                } catch (Exception e) {
                    downloading = false;
                    runOnUiThread(() -> 
                        Toast.makeText(MainActivity.this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show()
                    );
                }
            }).start();
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
        
        @JavascriptInterface
        public String checkVideo() {
            return findVideoInDir(new File(savePath));
        }
        
        private String findVideoInDir(File dir) {
            File[] files = dir.listFiles();
            if (files == null) return "";
            for (File f : files) {
                if (f.isDirectory()) {
                    String found = findVideoInDir(f);
                    if (!found.isEmpty()) return found;
                } else {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".mp4") || n.endsWith(".mkv") || 
                        n.endsWith(".avi") || n.endsWith(".webm")) {
                        return "file://" + f.getAbsolutePath();
                    }
                }
            }
            return "";
        }
    }
}
