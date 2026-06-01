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

import org.libtorrent4j.AlertListener;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.AddTorrentAlert;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.TorrentFinishedAlert;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Bridge bridge = new Bridge();
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private String savePath;
    private boolean downloading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                session = new SessionManager();
                session.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        
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
            if (downloading || session == null) return;
            downloading = true;
            
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    byte[] data = magnet.getBytes("UTF-8");
                    session.download(data, new File(savePath));
                    
                    Thread.sleep(3000);
                    
                    TorrentHandle[] handles = session.swig().get_torrents();
                    if (handles.length > 0) {
                        torrentHandle = handles[0];
                        torrentHandle.setSequentialDownload(true);
                        
                        startWatching();
                    }
                } catch (Exception e) {
                    downloading = false;
                }
            });
        }
        
        @JavascriptInterface
        public String getProgress() {
            if (torrentHandle != null && torrentHandle.isValid()) {
                TorrentStatus status = torrentHandle.status();
                return String.valueOf((int)(status.progress() * 100));
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getPeers() {
            if (torrentHandle != null && torrentHandle.isValid()) {
                return String.valueOf(torrentHandle.status().numPeers());
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getSpeed() {
            if (torrentHandle != null && torrentHandle.isValid()) {
                long speed = torrentHandle.status().downloadRate();
                if (speed > 1048576) return String.format("%.1f MB/s", speed / 1048576.0);
                if (speed > 1024) return String.format("%.1f KB/s", speed / 1024.0);
                return speed + " B/s";
            }
            return "0 KB/s";
        }
        
        @JavascriptInterface
        public String checkVideo() {
            File dir = new File(savePath);
            File[] videos = dir.listFiles(f -> {
                String n = f.getName().toLowerCase();
                return n.endsWith(".mp4") || n.endsWith(".mkv") || 
                       n.endsWith(".avi") || n.endsWith(".webm");
            });
            
            if (videos != null && videos.length > 0) {
                Arrays.sort(videos, (a, b) -> 
                    Long.compare(b.lastModified(), a.lastModified()));
                return "file://" + videos[0].getAbsolutePath();
            }
            
            // Procura em subpastas
            File[] dirs = dir.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File d : dirs) {
                    File[] subVideos = d.listFiles(f -> {
                        String n = f.getName().toLowerCase();
                        return n.endsWith(".mp4") || n.endsWith(".mkv") || 
                               n.endsWith(".avi") || n.endsWith(".webm");
                    });
                    if (subVideos != null && subVideos.length > 0) {
                        return "file://" + subVideos[0].getAbsolutePath();
                    }
                }
            }
            return "";
        }
        
        @JavascriptInterface
        public void stop() {
            if (torrentHandle != null) {
                session.remove(torrentHandle);
                torrentHandle = null;
                downloading = false;
            }
        }
    }
    
    private void startWatching() {
        new Thread(() -> {
            while (downloading) {
                try {
                    Thread.sleep(3000);
                    String video = bridge.checkVideo();
                    if (!video.isEmpty()) {
                        final String path = video;
                        handler.post(() -> {
                            webView.evaluateJavascript("playVideo('" + path + "')", null);
                        });
                    }
                } catch (Exception e) {}
            }
        }).start();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) session.stop();
    }
}
