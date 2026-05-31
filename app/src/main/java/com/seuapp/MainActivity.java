package com.seuapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.PermissionRequest;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private WebView webView;
    private StreamServer streamServer;
    private TorrentEngine torrentEngine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Solicita permissões
        requestPermissions();
        
        // Inicia o motor torrent nativo (UDP real)
        torrentEngine = new TorrentEngine(this);
        torrentEngine.start();
        
        // Inicia servidor de streaming HTTP
        streamServer = new StreamServer(8080, torrentEngine);
        try {
            streamServer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Configura WebView
        setupWebView();
        
        // Carrega o HTML5
        webView.loadUrl("file:///android_asset/www/index.html");
    }
    
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] permissions = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            
            boolean allGranted = true;
            for (String perm : permissions) {
                if (ContextCompat.checkSelfPermission(this, perm) 
                    != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                ActivityCompat.requestPermissions(this, permissions, 
                    PERMISSION_REQUEST_CODE);
            }
        }
    }
    
    private void setupWebView() {
        webView = findViewById(R.id.webview);
        
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    request.grant(request.getResources());
                }
            }
        });
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, 
                android.webkit.WebResourceRequest request) {
                return false;
            }
        });
        
        // Bridge JavaScript ↔ Java
        webView.addJavascriptInterface(new TorrentBridge(), "AndroidTorrent");
    }
    
    public class TorrentBridge {
        @android.webkit.JavascriptInterface
        public void addTorrent(String magnetURI) {
            runOnUiThread(() -> {
                torrentEngine.addMagnet(magnetURI);
                Toast.makeText(MainActivity.this, 
                    "Torrent adicionado! Conectando UDP...", 
                    Toast.LENGTH_SHORT).show();
            });
        }
        
        @android.webkit.JavascriptInterface
        public String getStreamUrl() {
            return "http://127.0.0.1:8080/stream";
        }
        
        @android.webkit.JavascriptInterface
        public String getProgress() {
            return String.valueOf(torrentEngine.getProgress());
        }
        
        @android.webkit.JavascriptInterface
        public String getPeers() {
            return String.valueOf(torrentEngine.getPeers());
        }
        
        @android.webkit.JavascriptInterface
        public String getDownloadSpeed() {
            long speed = torrentEngine.getDownloadSpeed();
            if (speed > 1048576)
                return String.format("%.1f MB/s", speed / 1048576.0);
            else if (speed > 1024)
                return String.format("%.1f KB/s", speed / 1024.0);
            else
                return speed + " B/s";
        }
        
        @android.webkit.JavascriptInterface
        public void stopTorrent() {
            torrentEngine.stop();
        }
        
        @android.webkit.JavascriptInterface
        public void pauseTorrent() {
            torrentEngine.pause();
        }
        
        @android.webkit.JavascriptInterface
        public void resumeTorrent() {
            torrentEngine.resume();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (streamServer != null) streamServer.stop();
        if (torrentEngine != null) torrentEngine.shutdown();
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, 
        @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, 
                    "Permissões necessárias para salvar arquivos", 
                    Toast.LENGTH_LONG).show();
            }
        }
    }
}
