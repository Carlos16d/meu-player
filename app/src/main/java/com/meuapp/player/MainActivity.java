package com.meuapp.player;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.server.StreamServer;
import com.meuapp.player.model.TorrentInfo;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    
    private PlayerView playerView;
    private SimpleExoPlayer exoPlayer;
    private TorrentEngine torrentEngine;
    private StreamServer streamServer;
    
    private TextView statusText, progressText, titleText;
    private ProgressBar bufferBar, spinnerBar;
    private View loadingOverlay;
    private EditText magnetInput;
    private Button btnStream, btnStop, btnWatch;
    
    private File videoFile;
    private String savePath;
    private StringBuilder logBuilder = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        requestPermissions();
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        progressText = findViewById(R.id.progress_text);
        titleText = findViewById(R.id.title_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        magnetInput = findViewById(R.id.magnet_input);
        btnStream = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        statusText.setMovementMethod(new ScrollingMovementMethod());
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        addLog("=== APP INICIADO ===");
        
        // ExoPlayer simples
        exoPlayer = new SimpleExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        playerView.setUseController(true);
        playerView.setKeepScreenOn(true);
        
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                String s;
                switch (state) {
                    case Player.STATE_IDLE: s = "IDLE"; break;
                    case Player.STATE_BUFFERING: s = "BUFFERING"; break;
                    case Player.STATE_READY: s = "READY"; break;
                    case Player.STATE_ENDED: s = "ENDED"; break;
                    default: s = "?"; break;
                }
                addLog("Player: " + s);
                loadingOverlay.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            }
            
            @Override
            public void onPlayerError(PlaybackException error) {
                addLog("ERRO: " + error.getErrorCodeName());
                addLog("  " + error.getMessage());
            }
        });
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { addLog("Engine OK"); }
            public void onError(String e) { addLog("Engine ERRO: " + e); }
            public void onProgress(TorrentInfo info) {
                runOnUiThread(() -> {
                    bufferBar.setProgress(info.progress);
                    progressText.setText(info.progress + "% " + (info.speed/1024) + "KB/s " + info.peers + "p");
                });
            }
            public void onStreamReady(File f) {
                videoFile = f;
                streamServer.setVideoFile(f);
                addLog("READY: " + (f.length()/1048576) + "MB");
                runOnUiThread(() -> {
                    loadingOverlay.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                });
            }
            public void onStatus(String s) { addLog(s); }
        });
        
        // Tenta iniciar servidor (mata anterior se necessário)
        startServer();
        
        torrentEngine.start();
        
        btnStream.setOnClickListener(v -> {
            String m = magnetInput.getText().toString().trim();
            if (m.startsWith("magnet:")) {
                addLog("Iniciando...");
                startStream(m);
            }
        });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
    }
    
    private void startServer() {
        if (streamServer != null) {
            streamServer.stop();
            try { Thread.sleep(500); } catch (Exception e) {}
        }
        
        streamServer = new StreamServer();
        
        try {
            streamServer.start();
            addLog("Servidor OK :8080");
        } catch (IOException e) {
            addLog("ERRO servidor: " + e.getMessage());
            // Tenta porta alternativa
            addLog("Porta 8080 ocupada - feche outras instâncias do app");
        }
    }
    
    private void addLog(String msg) {
        String time = sdf.format(new Date());
        String line = time + " " + msg + "\n";
        Log.d(TAG, msg);
        logBuilder.insert(0, line);
        if (logBuilder.length() > 8000) logBuilder.setLength(8000);
        runOnUiThread(() -> statusText.setText(logBuilder.toString()));
    }
    
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = {Manifest.permission.INTERNET, Manifest.permission.WRITE_EXTERNAL_STORAGE};
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, perms, 1);
                    break;
                }
            }
        }
    }
    
    private void startStream(String magnet) {
        bufferBar.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        torrentEngine.startDownload(magnet, savePath);
    }
    
    private void watch() {
        if (videoFile == null) {
            addLog("ERRO: videoFile null");
            return;
        }
        
        addLog("Assistindo: " + videoFile.getName() + " (" + videoFile.length()/1048576 + "MB)");
        addLog("URL: http://127.0.0.1:8080/video");
        
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        
        // Verifica se o servidor está respondendo
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) 
                new java.net.URL("http://127.0.0.1:8080/video").openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(3000);
            int code = conn.getResponseCode();
            addLog("Teste servidor: HTTP " + code + " Length:" + conn.getContentLength());
            conn.disconnect();
        } catch (Exception e) {
            addLog("ERRO teste servidor: " + e.getMessage());
        }
        
        Uri videoUri = Uri.parse("http://127.0.0.1:8080/video");
        
        DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true);
        
        ProgressiveMediaSource.Factory mediaSourceFactory = 
            new ProgressiveMediaSource.Factory(dataSourceFactory);
        
        MediaSource mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(videoUri));
        
        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
    }
    
    private void stop() {
        torrentEngine.stop();
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        playerView.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        addLog("Parado");
    }
    
    @Override
    protected void onDestroy() {
        addLog("onDestroy");
        if (torrentEngine != null) torrentEngine.destroy();
        if (streamServer != null) streamServer.stop();
        if (exoPlayer != null) exoPlayer.release();
        super.onDestroy();
    }
}