package com.seuapp;

import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private StreamServer streamServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Inicia servidor de streaming
        streamServer = new StreamServer(8080);
        try {
            streamServer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
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
        
        // Suporte a WebRTC (necessário para WebTorrent)
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        
        // Bridge para comunicação
        webView.addJavascriptInterface(new TorrentBridge(), "AndroidTorrent");
        
        // Carrega o HTML5
        webView.loadUrl("file:///android_asset/www/index.html");
    }
    
    public class TorrentBridge {
        @android.webkit.JavascriptInterface
        public String getStreamUrl() {
            return "http://127.0.0.1:8080/stream";
        }
        
        @android.webkit.JavascriptInterface
        public String getProgress() {
            return String.valueOf(streamServer.getProgress());
        }
        
        @android.webkit.JavascriptInterface
        public String getPeerCount() {
            return String.valueOf(streamServer.getPeerCount());
        }
        
        @android.webkit.JavascriptInterface
        public String getDownloadSpeed() {
            return streamServer.getDownloadSpeed();
        }
        
        @android.webkit.JavascriptInterface
        public void updateTorrentData(String progress, String peers, String speed) {
            streamServer.updateStats(progress, peers, speed);
        }
        
        @android.webkit.JavascriptInterface
        public void setVideoData(String mimeType, long contentLength) {
            streamServer.setVideoData(mimeType, contentLength);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        streamServer.stop();
    }
}
