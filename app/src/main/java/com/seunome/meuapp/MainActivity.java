package com.seuapp;

import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private StreamServer streamServer;
    private TorrentServer torrentServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Inicia servidores
        streamServer = new StreamServer(8080);
        torrentServer = new TorrentServer(this, streamServer);
        
        try {
            streamServer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Configura WebView
        webView = findViewById(R.id.webview);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setMixedContentMode(
            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        );
        
        // Bridge JavaScript ↔ Java
        webView.addJavascriptInterface(new TorrentBridge(), "AndroidTorrent");
        
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        
        // Carrega seu HTML5
        webView.loadUrl("file:///android_asset/www/index.html");
    }
    
    public class TorrentBridge {
        @android.webkit.JavascriptInterface
        public void playTorrent(String magnetURI) {
            torrentServer.playTorrent(magnetURI);
        }
        
        @android.webkit.JavascriptInterface
        public void playTorrentWithFileIndex(String magnetURI, int fileIndex) {
            torrentServer.playTorrent(magnetURI, fileIndex);
        }
        
        @android.webkit.JavascriptInterface
        public String getProgress() {
            return String.valueOf(torrentServer.getProgress());
        }
        
        @android.webkit.JavascriptInterface
        public String getStreamUrl() {
            return "http://127.0.0.1:8080/stream";
        }
        
        @android.webkit.JavascriptInterface
        public String getPeerCount() {
            return String.valueOf(torrentServer.getPeerCount());
        }
        
        @android.webkit.JavascriptInterface
        public String getDownloadSpeed() {
            return torrentServer.getDownloadSpeed();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        torrentServer.stop();
        streamServer.stop();
    }
}
