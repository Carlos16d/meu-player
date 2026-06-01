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

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.libtorrent;
import org.libtorrent4j.swig.settings_pack;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.torrent_handle;
import org.libtorrent4j.swig.torrent_info;
import org.libtorrent4j.swig.torrent_status;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.string_vector;
import org.libtorrent4j.swig.byte_vector;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Bridge bridge = new Bridge();
    private SessionManager session;
    private torrent_handle torrentHandle;
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
                settings_pack sp = new settings_pack();
                sp.set_bool(settings_pack.bool_types.enable_dht.swigValue(), true);
                sp.set_bool(settings_pack.bool_types.enable_lsd.swigValue(), true);
                sp.set_bool(settings_pack.bool_types.enable_upnp.swigValue(), true);
                sp.set_bool(settings_pack.bool_types.enable_natpmp.swigValue(), true);
                sp.set_int(settings_pack.int_types.alert_mask.swigValue(), 
                    org.libtorrent4j.swig.alert.category_t.all_categories.swigValue());
                
                session = new SessionManager(sp);
                session.start();
                
                // Adiciona DHT routers
                session.addDhtNode("router.bittorrent.com", 6881);
                session.addDhtNode("dht.transmissionbt.com", 6881);
                session.addDhtNode("dht.libtorrent.org", 25401);
                
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
                    add_torrent_params params = new add_torrent_params();
                    params.set_url(magnet);
                    params.set_save_path(savePath);
                    params.set_flags(add_torrent_params.flags_t.flag_sequential_download.swigValue() |
                                    add_torrent_params.flags_t.flag_auto_managed.swigValue());
                    
                    session.swig().async_add_torrent(params);
                    
                    Thread.sleep(4000);
                    
                    torrent_handle[] handles = session.swig().get_torrents().to_array();
                    if (handles.length > 0) {
                        torrentHandle = handles[0];
                        startWatching();
                    }
                } catch (Exception e) {
                    downloading = false;
                }
            });
        }
        
        @JavascriptInterface
        public String getProgress() {
            if (torrentHandle != null && torrentHandle.is_valid()) {
                torrent_status status = torrentHandle.status();
                return String.valueOf((int)(status.get_progress() * 100));
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getPeers() {
            if (torrentHandle != null && torrentHandle.is_valid()) {
                return String.valueOf(torrentHandle.status().get_num_peers());
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getSpeed() {
            if (torrentHandle != null && torrentHandle.is_valid()) {
                long speed = torrentHandle.status().get_download_rate();
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
                session.swig().remove_torrent(torrentHandle);
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
