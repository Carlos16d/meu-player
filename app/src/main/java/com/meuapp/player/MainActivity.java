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

import java.io.File;
import java.lang.reflect.Method;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private String savePath;
    private SessionManager session;

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
        public String getMethods() {
            StringBuilder sb = new StringBuilder();
            try {
                Method[] methods = SessionManager.class.getDeclaredMethods();
                sb.append("SessionManager methods:\n");
                for (Method m : methods) {
                    sb.append(m.getName()).append("\n");
                }
                
                sb.append("\n\nadd_torrent_params setters:\n");
                add_torrent_params p = new add_torrent_params();
                Method[] pm = add_torrent_params.class.getDeclaredMethods();
                for (Method m : pm) {
                    if (m.getName().startsWith("set")) {
                        sb.append(m.getName()).append("\n");
                    }
                }
                
                sb.append("\n\nlibtorrent static methods:\n");
                Method[] lm = libtorrent.class.getDeclaredMethods();
                for (Method m : lm) {
                    if (m.getName().contains("magnet") || m.getName().contains("parse")) {
                        sb.append(">>> ").append(m.getName()).append("\n");
                    }
                }
            } catch (Exception e) {
                sb.append("Erro: ").append(e.getMessage());
            }
            return sb.toString();
        }
        
        @JavascriptInterface
        public void startDownload(String magnet) {
            Toast.makeText(MainActivity.this, "Veja os métodos", Toast.LENGTH_SHORT).show();
        }
        
        @JavascriptInterface
        public String checkVideo() { return ""; }
        @JavascriptInterface
        public String getProgress() { return "0"; }
        @JavascriptInterface
        public String getPeers() { return "0"; }
        @JavascriptInterface
        public String getSpeed() { return "0 B/s"; }
    }
}
