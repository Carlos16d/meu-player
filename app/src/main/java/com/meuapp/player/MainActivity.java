package com.meuapp.player;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.ui.PlayerView;

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.server.StreamServer;
import com.meuapp.player.player.ExoPlayerManager;
import com.meuapp.player.model.TorrentInfo;

import java.io.*;

public class MainActivity extends AppCompatActivity {
    
    private PlayerView playerView;
    private SimpleExoPlayer exoPlayer;
    private ExoPlayerManager playerManager;
    private TorrentEngine torrentEngine;
    private StreamServer streamServer;
    
    private TextView statusText, progressText, titleText;
    private ProgressBar bufferBar, spinnerBar;
    private View loadingOverlay, glassPanel;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    
    private File videoFile;
    private String savePath;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Solicita permissões
        requestPermissions();
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        progressText = findViewById(R.id.progress_text);
        titleText = findViewById(R.id.title_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        glassPanel = findViewById(R.id.glass_panel);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        exoPlayer = new SimpleExoPlayer.Builder(this).build();
        playerManager = new ExoPlayerManager(playerView, exoPlayer);
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { setStatus("Pronto!"); }
            public void onError(String e) { setStatus("Erro: " + e); }
            
            public void onProgress(TorrentInfo info) {
                runOnUiThread(() -> {
                    bufferBar.setProgress(info.progress);
                    progressText.setText("Baixando... " + info.progress + "%");
                });
            }
            
            public void onStreamReady(File f) {
                videoFile = f;
                streamServer.setVideoFile(f);
                runOnUiThread(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    loadingOverlay.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                    titleText.setText("Pronto para assistir!");
                    setStatus("Video pronto!");
                });
            }
            
            public void onStatus(String s) { setStatus(s); }
        });
        
        streamServer = new StreamServer();
        
        try {
            streamServer.start();
            torrentEngine.start();
        } catch (Exception e) {
            setStatus("Erro: " + e.getMessage());
        }
        
        btnPlay.setOnClickListener(v -> {
            String m = magnetInput.getText().toString().trim();
            if (m.startsWith("magnet:")) startDownload(m);
        });
        
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
    }
    
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] permissions = {
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            
            boolean allGranted = true;
            for (String perm : permissions) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                ActivityCompat.requestPermissions(this, permissions, 1);
            }
        }
    }
    
    private void startDownload(String magnet) {
        glassPanel.setVisibility(View.VISIBLE);
        bufferBar.setVisibility(View.VISIBLE);
        spinnerBar.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        torrentEngine.startDownload(magnet, savePath);
    }
    
    private void watch() {
        glassPanel.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        playerManager.play("http://127.0.0.1:8080/video");
    }
    
    private void stop() {
        torrentEngine.stop();
        playerManager.stop();
        playerView.setVisibility(View.GONE);
        glassPanel.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
    }
    
    private void setStatus(String s) {
        runOnUiThread(() -> statusText.setText(s));
    }
    
    @Override
    protected void onDestroy() {
        if (torrentEngine != null) torrentEngine.destroy();
        if (streamServer != null) streamServer.stop();
        if (playerManager != null) playerManager.release();
        super.onDestroy();
    }
}