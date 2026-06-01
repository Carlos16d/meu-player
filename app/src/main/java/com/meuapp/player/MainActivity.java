package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.swig.libtorrent;
import org.libtorrent4j.swig.settings_pack;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.torrent_handle;
import org.libtorrent4j.swig.torrent_status;
import org.libtorrent4j.swig.session;

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
            Toast.makeText(this, "UDP pronto!", Toast.LENGTH_SHORT).show();
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
                    session swig = session.swig();
                    
                    // Configura o magnet
                    add_torrent_params params = swig.parse_magnet_uri(magnet);
                    params.set_save_path(savePath);
                    
                    // Adiciona o torrent
                    swig.async_add_torrent(params);
                    
                    Thread.sleep(3000);
                    
                    // Pega o torrent adicionado
                    torrent_handle[] handles = swig.get_torrents().to_array();
                    if (handles.length > 0) {
                        torrent = handles[0];
                        
                        runOnUiThread(() -> 
                            Toast.makeText(MainActivity.this, "Baixando!", Toast.LENGTH_SHORT).show()
                        );
                    }
                } catch (Exception e) {
                    downloading = false;
                }
            }).start();
        }
        
        @JavascriptInterface
        public String getProgress() {
            if (torrent != null && torrent.is_valid()) {
                torrent_status status = torrent.status();
                return String.valueOf((int)(status.get_progress() * 100));
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getPeers() {
            if (torrent != null && torrent.is_valid()) {
                return String.valueOf(torrent.status().get_num_peers());
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getSpeed() {
            if (torrent != null && torrent.is_valid()) {
                long speed = torrent.status().get_download_rate();
                if (speed > 1048576) return (speed / 1048576) + " MB/s";
                if (speed > 1024) return (speed / 1024) + " KB/s";
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
