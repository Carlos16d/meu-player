package com.meuapp.player;

import android.content.Intent;
import android.net.Uri;
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

import org.libtorrent4j.swig.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private TextView statusText, debugText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnTorrent, btnStop, btnWatch;
    
    private String savePath;
    private TorrentEngine torrentEngine;
    private StreamServer streamServer;
    private volatile File videoFile;
    private Handler handler;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();
    private static final int PICK_TORRENT_FILE = 100;

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
        btnTorrent = findViewById(R.id.btn_torrent);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        webView.post(() -> {
            try {
                int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.94);
                int h = (int)(w * 9.0 / 16.0);
                ViewGroup.LayoutParams p = webView.getLayoutParams();
                if (p != null) { p.width = w; p.height = h; webView.setLayoutParams(p); }
            } catch (Exception e) {}
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        try {
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
            webView.getSettings().setAllowFileAccess(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.setWebChromeClient(new WebChromeClient());
            webView.setWebViewClient(new WebViewClient());
        } catch (Exception e) {}
        webView.setVisibility(View.GONE);
        
        debug("=== TORRENT STREAM ===");
        debug("Suporte: Magnet + .Torrent");
        
        streamServer = new StreamServer();
        try { streamServer.start(); debug("[SRV] OK"); } 
        catch (Exception e) { debug("[SRV] " + e.getMessage()); }
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { debug("[ENG] OK"); }
            public void onError(String e) { debug("[ENG] " + e); }
            
            public void onProgress(TorrentInfo info) {
                handler.post(() -> {
                    try {
                        bufferBar.setProgress(info.progress);
                        statusText.setText(info.progress + "% | " + (info.speed/1024) + "KB/s");
                    } catch (Exception e) {}
                });
            }
            
            public void onStreamReady(torrent_handle handle, String sp) {
                try {
                    File vf = findVideoFile(new File(sp));
                    if (vf != null) {
                        videoFile = vf;
                        streamServer.setVideoFile(vf);
                        streamServer.setTorrentInfo(handle);
                        debug("[ENG] Video: " + vf.getName() + " (" + (vf.length()/1048576) + "MB)");
                    }
                    debug("[ENG] STREAM READY");
                    handler.post(() -> {
                        try {
                            spinnerBar.setVisibility(View.GONE);
                            btnWatch.setVisibility(View.VISIBLE);
                        } catch (Exception e) {}
                    });
                } catch (Exception e) {
                    debug("[ENG] ERRO: " + e.getMessage());
                }
            }
            
            public void onStatus(String s) { debug("[ENG] " + s); }
            public void onLog(String log) { debug("[ENG] " + log); }
        });
        
        torrentEngine.start();
        
        btnPlay.setOnClickListener(v -> {
            String m = magnetInput.getText().toString().trim();
            if (m.startsWith("magnet:")) {
                debug("Iniciando magnet...");
                startStream(m);
            } else if (m.startsWith("/") || m.startsWith("content://")) {
                debug("Iniciando arquivo...");
                startStream(m);
            }
        });
        
        btnTorrent.setOnClickListener(v -> {
            debug("Abrindo seletor de arquivo .torrent...");
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_TORRENT_FILE);
        });
        
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("Pronto");
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == PICK_TORRENT_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                debug("Arquivo selecionado: " + uri.toString());
                
                // Copia o arquivo .torrent para a pasta do app
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    File torrentFile = new File(savePath, "torrent_file.torrent");
                    FileOutputStream fos = new FileOutputStream(torrentFile);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                    is.close();
                    
                    debug("Arquivo .torrent copiado: " + torrentFile.getAbsolutePath());
                    debug("Tamanho: " + torrentFile.length() + " bytes");
                    
                    // Inicia o download do arquivo .torrent
                    startStream(torrentFile.getAbsolutePath());
                    
                } catch (Exception e) {
                    debug("ERRO ao ler arquivo: " + e.getMessage());
                }
            }
        }
    }
    
    private void debug(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        debugLog.append(line);
        handler.post(() -> {
            try { debugText.setText(debugLog.toString()); } catch (Exception e) {}
        });
    }
    
    private File findVideoFile(File dir) {
        try {
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
        } catch (Exception e) {}
        return null;
    }
    
    private void startStream(String source) {
        bufferBar.setVisibility(View.VISIBLE);
        spinnerBar.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        debugLog.setLength(0);
        torrentEngine.startDownload(source, savePath);
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { 
            debug("Video nao encontrado"); 
            return; 
        }
        
        debug("Iniciando player...");
        debug("Arquivo: " + videoFile.getName());
        debug("Tamanho: " + (videoFile.length()/1048576) + "MB");
        
        handler.post(() -> { 
            try {
                webView.setVisibility(View.VISIBLE); 
                btnWatch.setVisibility(View.GONE);
                
                String html = "<!DOCTYPE html><html><head>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
                    + "<style>body{margin:0;background:#000;}"
                    + "video{width:100%;height:100vh;display:block;}</style></head><body>"
                    + "<video controls autoplay playsinline>"
                    + "<source src='http://127.0.0.1:8080/video' type='video/mp4'>"
                    + "</video></body></html>";
                
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                debug("Player carregado");
            } catch (Exception e) {
                debug("ERRO ao carregar player: " + e.getMessage());
            }
        });
    }
    
    private void stop() {
        debug("Parado");
        torrentEngine.stop();
        try {
            webView.loadUrl("about:blank");
            webView.setVisibility(View.GONE);
        } catch (Exception e) {}
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