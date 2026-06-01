package com.seuapp;

import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.proninyaroslav.libretorrent.core.TorrentEngine;
import org.proninyaroslav.libretorrent.core.model.TorrentEngineCallback;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private TorrentEngine engine;
    private String currentMagnet = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Inicializa o motor torrent NATIVO (UDP REAL)
        engine = TorrentEngine.getInstance(this);
        
        File savePath = new File(getExternalFilesDir(null), "torrents");
        savePath.mkdirs();
        
        engine.setDownloadPath(savePath.getAbsolutePath());
        
        // Configura WebView
        webView = findViewById(R.id.webview);
        
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        
        // Bridge para o HTML5 controlar o torrent nativo
        webView.addJavascriptInterface(new TorrentBridge(), "AndroidTorrent");
        
        webView.loadUrl("file:///android_asset/www/index.html");
    }
    
    public class TorrentBridge {
        @JavascriptInterface
        public void addTorrent(String magnet) {
            currentMagnet = magnet;
            runOnUiThread(() -> {
                engine.startDownload(magnet);
                Toast.makeText(MainActivity.this, 
                    "Torrent iniciado com UDP!", Toast.LENGTH_SHORT).show();
            });
        }
        
        @JavascriptInterface
        public String getProgress() {
            if (currentMagnet != null) {
                float progress = engine.getProgress(currentMagnet);
                return String.valueOf((int)(progress * 100));
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getPeers() {
            if (currentMagnet != null) {
                return String.valueOf(engine.getPeers(currentMagnet));
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getDownloadSpeed() {
            if (currentMagnet != null) {
                long speed = engine.getDownloadSpeed(currentMagnet);
                if (speed > 1048576)
                    return String.format("%.1f MB/s", speed / 1048576.0);
                else if (speed > 1024)
                    return String.format("%.1f KB/s", speed / 1024.0);
                else
                    return speed + " B/s";
            }
            return "0 KB/s";
        }
        
        @JavascriptInterface
        public String getStreamUrl() {
            // LibreTorrent salva o arquivo, retorna o caminho
            if (currentMagnet != null) {
                File dir = new File(getExternalFilesDir(null), "torrents");
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".mp4") || name.endsWith(".mkv") || 
                            name.endsWith(".avi") || name.endsWith(".webm")) {
                            return "file://" + f.getAbsolutePath();
                        }
                    }
                }
            }
            return "";
        }
        
        @JavascriptInterface
        public void stopTorrent() {
            if (currentMagnet != null) {
                engine.stopDownload(currentMagnet);
                currentMagnet = null;
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (engine != null) {
            engine.shutdown();
        }
    }
}
