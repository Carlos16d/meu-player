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
        public void startDownload(String magnet) {
            new Thread(() -> {
                try {
                    byte[] data = magnet.getBytes("UTF-8");
                    File f = new File(savePath);
                    session.download(data, f);
                    
                    runOnUiThread(() -> 
                        Toast.makeText(MainActivity.this, "Baixando com UDP!", Toast.LENGTH_SHORT).show()
                    );
                } catch (Exception e) {
                    runOnUiThread(() -> 
                        Toast.makeText(MainActivity.this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show()
                    );
                }
            }).start();
        }
        
        @JavascriptInterface
        public String getProgress() {
            return "0";
        }
        
        @JavascriptInterface
        public String getPeers() {
            return "0";
        }
        
        @JavascriptInterface
        public String getSpeed() {
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
