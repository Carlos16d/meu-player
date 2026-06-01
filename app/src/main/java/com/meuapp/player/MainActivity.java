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
    private Bridge bridge = new Bridge();
    private long sessionPtr = 0;
    private long torrentPtr = 0;
    private String savePath;
    private boolean downloading = false;

    static {
        System.loadLibrary("torrent4j");
    }

    private native long nativeCreateSession(String listenAddr, int portStart, int portEnd);
    private native long nativeAddMagnet(long sessionPtr, String magnet, String savePath);
    private native void nativeRemoveTorrent(long sessionPtr, long torrentPtr);
    private native float nativeGetProgress(long torrentPtr);
    private native int nativeGetPeers(long torrentPtr);
    private native long nativeGetDownloadSpeed(long torrentPtr);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        new Thread(() -> {
            try {
                sessionPtr = nativeCreateSession("0.0.0.0", 6881, 6889);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        
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
        webView.addJavascriptInterface(bridge, "App");
        webView.loadUrl("file:///android_asset/www/index.html");
    }
    
    public class Bridge {
        @JavascriptInterface
        public void startDownload(String magnet) {
            if (downloading) return;
            downloading = true;
            
            new Thread(() -> {
                try {
                    torrentPtr = nativeAddMagnet(sessionPtr, magnet, savePath);
                    startWatching();
                } catch (Exception e) {
                    downloading = false;
                }
            }).start();
        }
        
        @JavascriptInterface
        public String getProgress() {
            if (torrentPtr != 0) {
                return String.valueOf((int)(nativeGetProgress(torrentPtr) * 100));
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getPeers() {
            if (torrentPtr != 0) {
                return String.valueOf(nativeGetPeers(torrentPtr));
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getSpeed() {
            if (torrentPtr != 0) {
                long speed = nativeGetDownloadSpeed(torrentPtr);
                if (speed > 1048576) return (speed / 1048576.0) + " MB/s";
                if (speed > 1024) return (speed / 1024.0) + " KB/s";
                return speed + " B/s";
            }
            return "0 KB/s";
        }
        
        @JavascriptInterface
        public String checkVideo() {
            File dir = new File(savePath);
            if (dir.exists()) {
                File[] videos = dir.listFiles(f -> {
                    String n = f.getName().toLowerCase();
                    return (n.endsWith(".mp4") || n.endsWith(".mkv") || 
                            n.endsWith(".avi") || n.endsWith(".webm"));
                });
                if (videos != null && videos.length > 0) {
                    Arrays.sort(videos, (a, b) -> 
                        Long.compare(b.lastModified(), a.lastModified()));
                    String path = "file://" + videos[0].getAbsolutePath();
                    if (!path.equals(lastVideo)) {
                        lastVideo = path;
                        return path;
                    }
                }
            }
            return "";
        }
        
        @JavascriptInterface
        public void stop() {
            if (torrentPtr != 0) {
                nativeRemoveTorrent(sessionPtr, torrentPtr);
                torrentPtr = 0;
                downloading = false;
            }
        }
    }
    
    private void startWatching() {
        watcher = new Runnable() {
            @Override
            public void run() {
                String video = bridge.checkVideo();
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
        if (torrentPtr != 0) nativeRemoveTorrent(sessionPtr, torrentPtr);
        if (watcher != null) handler.removeCallbacks(watcher);
    }
}
