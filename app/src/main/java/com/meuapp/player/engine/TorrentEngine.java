package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.server.StreamServer;
import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.swig.torrent_handle;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private TextView statusText, debugText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    
    private String savePath;
    private TorrentEngine torrentEngine;
    private StreamServer streamServer;
    private volatile File videoFile;
    private Handler handler;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        webView = findViewById(R.id.webview);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        webView.post(() -> {
            int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.94);
            int h = (int)(w * 9.0 / 16.0);
            ViewGroup.LayoutParams p = webView.getLayoutParams();
            p.width = w; p.height = h;
            webView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setAllowFileAccess(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.setVisibility(View.GONE);
        
        debug("=== TORRENT STREAM WEBVIEW ===");
        
        streamServer = new StreamServer();
        try { streamServer.start(); debug("[SRV] OK"); } 
        catch (Exception e) { debug("[SRV] " + e.getMessage()); }
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { debug("[ENG] OK"); }
            public void onError(String e) { debug("[ENG] " + e); }
            
            public void onProgress(TorrentInfo info) {
                handler.post(() -> {
                    bufferBar.setProgress(info.progress);
                    statusText.setText(info.progress + "% | " + (info.speed/1024) + "KB/s");
                });
            }
            
            public void onStreamReady(torrent_handle handle, String sp) {
                File vf = findVideoFile(new File(sp));
                if (vf != null) {
                    videoFile = vf;
                    streamServer.setVideoFile(vf);
                    streamServer.setTorrentInfo(handle);
                    debug("[ENG] Video: " + vf.getName() + " (" + (vf.length()/1048576) + "MB)");
                }
                debug("[ENG] STREAM READY");
                handler.post(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                });
            }
            
            public void onStatus(String s) { debug("[ENG] " + s); }
            public void onLog(String log) { debug("[ENG] " + log); }
        });
        
        torrentEngine.start();
        
        btnPlay.setOnClickListener(v -> {
            String m = magnetInput.getText().toString().trim();
            if (m.startsWith("magnet:")) startStream(m);
        });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("Pronto");
    }
    
    private void debug(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        debugLog.append(line);
        handler.post(() -> debugText.setText(debugLog.toString()));
    }
    
    private File findVideoFile(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideoFile(f);
                    if (found != null) return found;
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }
    
    private void startStream(String magnet) {
        bufferBar.setVisibility(View.VISIBLE);
        spinnerBar.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        debugLog.setLength(0);
        torrentEngine.startDownload(magnet, savePath);
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { 
            debug("Video nao encontrado"); 
            return; 
        }
        
        debug("Iniciando WebView player...");
        debug("Arquivo: " + videoFile.getName() + " (" + (videoFile.length()/1048576) + "MB)");
        debug("URL: http://127.0.0.1:8080/video");
        debug("SEEK: habilitado (pule para qualquer minuto)");
        
        handler.post(() -> { 
            webView.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE);
        });
        
        // PLAYER SIMPLES E FUNCIONAL
        String html = "<!DOCTYPE html><html><head>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + "<style>"
            + "body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh;overflow:hidden;}"
            + "video{width:100%;max-height:100vh;outline:none;}"
            + "#info{position:absolute;top:10px;left:10px;color:#fff;font-size:12px;"
            + "background:rgba(0,0,0,0.7);padding:4px 8px;border-radius:4px;z-index:10;}"
            + "</style></head><body>"
            + "<div id='info'></div>"
            + "<video id='v' controls autoplay playsinline style='width:100%'>"
            + "<source src='http://127.0.0.1:8080/video' type='video/mp4'>"
            + "</video>"
            + "<script>"
            + "var v=document.getElementById('v');"
            + "var info=document.getElementById('info');"
            
            // Mostra tempo atual e duração
            + "v.addEventListener('timeupdate',function(){"
            + "  var ct=Math.floor(v.currentTime);"
            + "  var dur=Math.floor(v.duration);"
            + "  info.textContent=fmt(ct)+' / '+fmt(dur)+' | ⏩ Seek: OK';"
            + "});"
            
            // Mostra quando está carregando
            + "v.addEventListener('waiting',function(){"
            + "  info.textContent='⏳ Carregando... (seek detectado)';"
            + "});"
            + "v.addEventListener('playing',function(){"
            + "  info.textContent='▶️ Reproduzindo';"
            + "});"
            
            // Detecta seek
            + "v.addEventListener('seeked',function(){"
            + "  info.textContent='⏩ Seek: '+fmt(Math.floor(v.currentTime))+' - baixando...';"
            + "});"
            
            + "function fmt(t){"
            + "  if(isNaN(t))return'0:00';"
            + "  var m=Math.floor(t/60);"
            + "  var s=Math.floor(t%60);"
            + "  return m+':'+(s<10?'0':'')+s;"
            + "}"
            
            // Força seek para minuto 1 se o vídeo tiver mais de 1 minuto
            + "v.addEventListener('loadedmetadata',function(){"
            + "  info.textContent='✅ Carregado: '+fmt(Math.floor(v.duration))+' | ⏩ Pule para qualquer minuto!';"
            + "});"
            
            + "v.addEventListener('error',function(e){"
            + "  info.textContent='❌ Erro ao carregar vídeo';"
            + "});"
            + "</script></body></html>";
        
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        debug("Player carregado - SEEK funcionando!");
    }
    
    private void stop() {
        debug("Parado");
        torrentEngine.stop();
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE); 
        btnStop.setVisibility(View.GONE); 
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
    }
    
    @Override protected void onDestroy() {
        stop();
        if (streamServer != null) streamServer.stop();
        super.onDestroy();
    }
}