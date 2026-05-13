package com.seunome.meuapp;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.graphics.Bitmap;
import android.webkit.CookieManager;
import android.net.Uri;
import android.widget.VideoView;
import android.widget.RelativeLayout;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private TorrentBridge torrentBridge;
    private VideoView videoView;
    private RelativeLayout mainLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.hide();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        mainLayout = new RelativeLayout(this);
        
        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webSettings.setDatabaseEnabled(true);
        
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        videoView = new VideoView(this);
        videoView.setVisibility(View.GONE);
        RelativeLayout.LayoutParams videoParams = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, 
            RelativeLayout.LayoutParams.MATCH_PARENT);
        videoView.setLayoutParams(videoParams);

        webView.addJavascriptInterface(new TorrentBridgeInterface(), "TorrentBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }
            
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                view.postDelayed(() -> view.loadUrl("https://carlos16d.github.io/netflix-icons/"), 2000);
            }
            
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        mainLayout.addView(webView, new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT));
        mainLayout.addView(videoView, videoParams);
        
        setContentView(mainLayout);
        
        webView.loadUrl("https://carlos16d.github.io/netflix-icons/");
    }

    public class TorrentBridgeInterface {
        
        @JavascriptInterface
        public void playTorrent(String magnetUrl) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "🚀 Iniciando torrent nativo (libtorrent C++)...", Toast.LENGTH_SHORT).show();
                
                if (torrentBridge != null) {
                    torrentBridge.destroy();
                }
                
                torrentBridge = new TorrentBridge(MainActivity.this, new TorrentBridge.TorrentListener() {
                    @Override
                    public void onProgress(float progress, int downloadSpeed, int peers) {
                        runOnUiThread(() -> {
                            webView.evaluateJavascript(
                                "if(typeof updateTorrentProgress === 'function') updateTorrentProgress(" + progress + "," + downloadSpeed + "," + peers + ")", 
                                null);
                        });
                    }

                    @Override
                    public void onReady(String videoPath) {
                        runOnUiThread(() -> {
                            webView.setVisibility(View.GONE);
                            videoView.setVisibility(View.VISIBLE);
                            videoView.setVideoURI(Uri.parse("file://" + videoPath));
                            videoView.setOnPreparedListener(mp -> videoView.start());
                            videoView.setOnCompletionListener(mp -> {
                                webView.setVisibility(View.VISIBLE);
                                videoView.setVisibility(View.GONE);
                            });
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onStatus(String status) {
                        runOnUiThread(() -> {
                            webView.evaluateJavascript(
                                "if(typeof appendPlayerLog === 'function') appendPlayerLog('" + status.replace("'", "\\'") + "')", 
                                null);
                        });
                    }
                });
                
                torrentBridge.addMagnet(magnetUrl);
            });
        }
        
        @JavascriptInterface
        public void stopTorrent() {
            runOnUiThread(() -> {
                if (torrentBridge != null) {
                    torrentBridge.destroy();
                    torrentBridge = null;
                }
                videoView.stopPlayback();
                videoView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            });
        }
        
        @JavascriptInterface
        public void pauseTorrent() {
            if (torrentBridge != null) torrentBridge.pause();
        }
        
        @JavascriptInterface
        public void resumeTorrent() {
            if (torrentBridge != null) torrentBridge.resume();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (torrentBridge != null) {
            torrentBridge.destroy();
        }
    }
}
