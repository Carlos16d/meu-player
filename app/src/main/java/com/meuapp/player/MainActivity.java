package com.meuapp.player;

import android.Manifest;
import android.content.pm.PackageManager;
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
import com.google.android.exoplayer2.ui.PlayerView;

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.server.StreamServer;
import com.meuapp.player.player.ExoPlayerManager;
import com.meuapp.player.model.TorrentInfo;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    
    private PlayerView playerView;
    private SimpleExoPlayer exoPlayer;
    private ExoPlayerManager playerManager;
    private TorrentEngine torrentEngine;
    private StreamServer streamServer;
    
    private TextView statusText, progressText, titleText;
    private ProgressBar bufferBar, spinnerBar;
    private View loadingOverlay, glassPanel;
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
        glassPanel = findViewById(R.id.glass_panel);
        magnetInput = findViewById(R.id.magnet_input);
        btnStream = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        // Configura Scroll nos logs
        statusText.setMovementMethod(new ScrollingMovementMethod());
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        addLog("App iniciado");
        addLog("Save path: " + savePath);
        
        exoPlayer = new SimpleExoPlayer.Builder(this).build();
        playerManager = new ExoPlayerManager(playerView, exoPlayer);
        
        playerManager.setPlayerListener(new ExoPlayerManager.PlayerListener() {
            public void onTracksAvailable(int audio, int subs) {
                addLog("TRACKS: " + audio + " áudios, " + subs + " legendas");
                String msg = "▶️ Reproduzindo";
                if (audio > 1) msg += " | 🎵 " + audio + " áudios";
                if (subs > 0) msg += " | 📝 " + subs + " legendas";
                titleText.setText(msg);
            }
            public void onBuffering(boolean b) {
                addLog("Player buffering: " + b);
                spinnerBar.setVisibility(b ? View.VISIBLE : View.GONE);
                loadingOverlay.setVisibility(b ? View.VISIBLE : View.GONE);
            }
            public void onError(String e) {
                addLog("ERRO PLAYER: " + e);
            }
        });
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { addLog("Engine pronto"); }
            public void onError(String e) { addLog("ERRO Engine: " + e); }
            public void onProgress(TorrentInfo info) {
                runOnUiThread(() -> {
                    bufferBar.setProgress(info.progress);
                    progressText.setText(info.progress + "% | " + info.peers + " peers | " + (info.speed/1024) + "KB/s | " + (info.downloaded/1048576) + "MB");
                });
            }
            public void onStreamReady(File f) {
                videoFile = f;
                streamServer.setVideoFile(f);
                addLog("STREAM READY: " + f.getName() + " (" + (f.length()/1048576) + "MB)");
                runOnUiThread(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    loadingOverlay.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                    titleText.setText("🎬 Clique ASSISTIR");
                });
            }
            public void onStatus(String s) { 
                addLog("Status: " + s);
            }
        });
        
        streamServer = new StreamServer();
        
        try {
            streamServer.start();
            torrentEngine.start();
            addLog("Servidor e engine iniciados");
        } catch (Exception e) {
            addLog("ERRO ao iniciar: " + e.getMessage());
        }
        
        btnStream.setOnClickListener(v -> {
            String m = magnetInput.getText().toString().trim();
            if (m.startsWith("magnet:")) {
                addLog("Iniciando stream: " + m.substring(0, Math.min(50, m.length())));
                startStream(m);
            }
        });
        btnStop.setOnClickListener(v -> {
            addLog("Parando...");
            stop();
        });
        btnWatch.setOnClickListener(v -> {
            addLog("Assistindo: " + (videoFile != null ? videoFile.getName() : "null"));
            watch();
        });
    }
    
    private void addLog(String msg) {
        String time = sdf.format(new Date());
        String line = time + " " + msg + "\n";
        Log.d(TAG, msg);
        logBuilder.insert(0, line);
        if (logBuilder.length() > 5000) {
            logBuilder.setLength(5000);
        }
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
        glassPanel.setVisibility(View.VISIBLE);
        bufferBar.setVisibility(View.VISIBLE);
        spinnerBar.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        titleText.setText("⬇️ Preparando stream...");
        torrentEngine.startDownload(magnet, savePath);
    }
    
    private void watch() {
        addLog("Iniciando player...");
        glassPanel.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        titleText.setText("▶️ Reproduzindo");
        playerManager.play("http://127.0.0.1:8080/video");
    }
    
    private void stop() {
        torrentEngine.stop();
        playerManager.stop();
        playerView.setVisibility(View.GONE);
        glassPanel.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        titleText.setText("🎬 Torrent Stream");
        progressText.setText("Pronto");
    }
    
    @Override
    protected void onDestroy() {
        if (torrentEngine != null) torrentEngine.destroy();
        if (streamServer != null) streamServer.stop();
        if (playerManager != null) playerManager.release();
        super.onDestroy();
    }
}