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

import java.io.File;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable watcher;
    private String lastVideo = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
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
        public void openMagnet(String magnet) {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                intent.setData(android.net.Uri.parse(magnet));
                startActivity(intent);
                startWatching();
                Toast.makeText(MainActivity.this, "Baixando... Aguarde!", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Instale um app de torrent!", Toast.LENGTH_LONG).show();
            }
        }
        
        @JavascriptInterface
        public String checkVideo() {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File[] videos = downloads.listFiles(f -> {
                String n = f.getName().toLowerCase();
                return (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".webm"));
            });
            
            if (videos != null && videos.length > 0) {
                Arrays.sort(videos, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                String path = "file://" + videos[0].getAbsolutePath();
                if (!path.equals(lastVideo)) {
                    lastVideo = path;
                    return path;
                }
            }
            return "";
        }
    }
    
    private void startWatching() {
        watcher = new Runnable() {
            @Override
            public void run() {
                String video = ((Bridge)webView.getJavascriptInterface()).checkVideo();
                if (!video.isEmpty()) {
                    webView.evaluateJavascript("playVideo('" + video + "')", null);
                }
                handler.postDelayed(this, 3000);
            }
        };
        handler.post(watcher);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (watcher != null) handler.removeCallbacks(watcher);
    }
}
