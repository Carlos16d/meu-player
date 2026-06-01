package com.seuapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.proninyaroslav.libretorrent.core.TorrentEngine;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_CODE = 100;
    private WebView webView;
    private TorrentEngine engine;
    private String currentMagnet = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        requestPermissions();
        
        engine = TorrentEngine.getInstance(this);
        File savePath = new File(getExternalFilesDir(null), "torrents");
        savePath.mkdirs();
        engine.setDownloadPath(savePath.getAbsolutePath());
        
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
        webView.addJavascriptInterface(new TorrentBridge(), "AndroidTorrent");
        webView.loadUrl("file:///android_asset/www/index.html");
    }
    
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            boolean allGranted = true;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                ActivityCompat.requestPermissions(this, perms, PERMISSION_CODE);
            }
        }
    }
    
    public class TorrentBridge {
        @JavascriptInterface
        public void addTorrent(String magnet) {
            currentMagnet = magnet;
            runOnUiThread(() -> {
                engine.startDownload(magnet);
                Toast.makeText(MainActivity.this, "UDP ativado! Baixando...", Toast.LENGTH_SHORT).show();
            });
        }
        
        @JavascriptInterface
        public String getProgress() {
            if (currentMagnet != null) {
                return String.valueOf((int)(engine.getProgress(currentMagnet) * 100));
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
                if (speed > 1048576) return String.format("%.1f MB/s", speed / 1048576.0);
                else if (speed > 1024) return String.format("%.1f KB/s", speed / 1024.0);
                else return speed + " B/s";
            }
            return "0 KB/s";
        }
        
        @JavascriptInterface
        public String getStreamUrl() {
            if (currentMagnet != null) {
                File dir = new File(getExternalFilesDir(null), "torrents");
                if (dir.exists()) {
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.shutdown();
    }
}
