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
import org.proninyaroslav.libretorrent.core.stateparcel.BasicStateParcel;
import org.proninyaroslav.libretorrent.core.stateparcel.TorrentState;
import org.proninyaroslav.libretorrent.core.utils.TorrentUtils;

import java.io.File;
import java.text.DecimalFormat;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private TorrentEngine engine;
    private String currentInfoHash = null;
    private File downloadDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        downloadDir = new File(getExternalFilesDir(null), "downloads");
        downloadDir.mkdirs();
        
        engine = new TorrentEngine(this);
        engine.setDownloadPath(downloadDir.getAbsolutePath());
        
        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "NativeTorrent");
        webView.loadUrl("file:///android_asset/www/index.html");
    }
    
    public class Bridge {
        @JavascriptInterface
        public void download(String magnet) {
            runOnUiThread(() -> {
                try {
                    String hash = extractInfoHash(magnet);
                    currentInfoHash = hash;
                    engine.download(magnet);
                    Toast.makeText(MainActivity.this, "Baixando com UDP nativo!", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Erro: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        @JavascriptInterface
        public String getProgress() {
            if (currentInfoHash == null) return "0";
            try {
                BasicStateParcel state = engine.getState(currentInfoHash);
                if (state != null) return String.valueOf(state.progress);
                return "0";
            } catch (Exception e) {
                return "0";
            }
        }
        
        @JavascriptInterface
        public String getSpeed() {
            if (currentInfoHash == null) return "0 KB/s";
            try {
                BasicStateParcel state = engine.getState(currentInfoHash);
                if (state != null) {
                    long speed = state.downloadSpeed;
                    if (speed > 1048576) return (speed/1048576) + " MB/s";
                    if (speed > 1024) return (speed/1024) + " KB/s";
                    return speed + " B/s";
                }
                return "0 KB/s";
            } catch (Exception e) {
                return "0 KB/s";
            }
        }
        
        @JavascriptInterface
        public String getPeers() {
            if (currentInfoHash == null) return "0";
            try {
                BasicStateParcel state = engine.getState(currentInfoHash);
                return state != null ? String.valueOf(state.peers) : "0";
            } catch (Exception e) {
                return "0";
            }
        }
        
        @JavascriptInterface
        public String getFilePath() {
            if (currentInfoHash == null) return "";
            File[] files = downloadDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName().toLowerCase();
                    if (name.endsWith(".mp4") || name.endsWith(".mkv") || 
                        name.endsWith(".avi") || name.endsWith(".webm")) {
                        return "file://" + f.getAbsolutePath();
                    }
                }
            }
            return "";
        }
        
        @JavascriptInterface
        public void stop() {
            if (currentInfoHash != null) {
                engine.stopDownload(currentInfoHash);
                currentInfoHash = null;
            }
        }
        
        private String extractInfoHash(String magnet) {
            if (magnet.contains("urn:btih:")) {
                String[] parts = magnet.split("urn:btih:");
                if (parts.length > 1) {
                    String hash = parts[1].split("&")[0].split(":")[0];
                    return hash.toLowerCase();
                }
            }
            return magnet.hashCode() + "";
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.shutdown();
    }
}
