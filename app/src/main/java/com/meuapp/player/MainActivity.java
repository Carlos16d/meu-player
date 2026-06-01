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
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.Priority;
import org.libtorrent4j.swig.libtorrent;
import org.libtorrent4j.swig.settings_pack;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.torrent_handle;
import org.libtorrent4j.swig.torrent_status;
import org.libtorrent4j.swig.torrent_info;
import org.libtorrent4j.swig.string_view;
import org.libtorrent4j.swig.entry;
import org.libtorrent4j.swig.byte_span;
import org.libtorrent4j.swig.error_code;

import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
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
                // Configurações UDP/DHT
                settings_pack sp = new settings_pack();
                sp.set_bool(settings_pack.bool_types.enable_dht, true);
                sp.set_bool(settings_pack.bool_types.enable_lsd, true);
                sp.set_bool(settings_pack.bool_types.enable_upnp, true);
                sp.set_bool(settings_pack.bool_types.enable_natpmp, true);
                
                session = new SessionManager(sp);
                session.start();
                
                // DHT routers
                session.swig().add_dht_router("router.bittorrent.com", 6881);
                session.swig().add_dht_router("dht.transmissionbt.com", 6881);
                session.swig().add_dht_router("dht.libtorrent.org", 25401);
                
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
                    add_torrent_params params = session.swig().parse_magnet_uri(magnet);
                    params.set_save_path(savePath);
                    
                    // Download sequencial
                    int flags = params.get_flags();
                    flags |= add_torrent_params.flags_t.flag_sequential_download;
                    flags |= add_torrent_params.flags_t.flag_auto_managed;
                    params.set_flags(flags);
                    
                    session.swig().async_add_torrent(params);
                    
                    Thread.sleep(5000);
                    
                    // Pega o torrent adicionado
                    long[] handles = session.swig().get_torrents();
                    if (handles.length > 0) {
                        torrentHandle = new torrent_handle(handles[0]);
                        startWatching();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
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
            return findVideo(dir);
        }
        
        private String findVideo(File dir) {
            File[] files = dir.listFiles();
            if (files == null) return "";
            
            for (File f : files) {
                if (f.isDirectory()) {
                    String found = findVideo(f);
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
