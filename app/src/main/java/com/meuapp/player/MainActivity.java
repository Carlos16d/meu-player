package com.meuapp.player;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
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
        
        btnStream.setText("▶️ STREAM");
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        Log.d(TAG, "Save path: " + savePath);
        
        exoPlayer = new SimpleExoPlayer.Builder(this).build();
        playerManager = new ExoPlayerManager(playerView, exoPlayer);
        
        // Listener para tracks de áudio/legendas
        playerManager.setPlayerListener(new ExoPlayerManager.PlayerListener() {
            public void onTracksAvailable(int audio, int subs) {
                Log.d(TAG, "Tracks disponíveis - Áudio: " + audio + ", Legendas: " + subs);
                String msg = "▶️ Reproduzindo";
                if (audio > 1) msg += " | 🎵 " + audio + " áudios";
                if (subs > 0) msg += " | 📝 " + subs + " legendas";
                setStatus(msg);
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
            public void onBuffering(boolean b) {
                Log.d(TAG, "Buffering: " + b);
                spinnerBar.setVisibility(b ? View.VISIBLE : View.GONE);
                loadingOverlay.setVisibility(b ? View.VISIBLE : View.GONE);
            }
            public void onError(String e) {
                Log.e(TAG, "Erro player: " + e);
                setStatus("Erro: " + e);
            }
        });
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { 
                Log.d(TAG, "Engine pronto");
                setStatus("Pronto!"); 
            }
            public void onError(String e) { 
                Log.e(TAG, "Engine error: " + e);
                setStatus("Erro: " + e); 
            }
            public void onProgress(TorrentInfo info) {
                runOnUiThread(() -> {
                    bufferBar.setProgress(info.progress);
                    progressText.setText(info.progress + "% | " + info.peers + " peers | " + (info.speed/1024) + "KB/s");
                });
            }
            public void onStreamReady(File f) {
                Log.d(TAG, "Stream ready: " + f.getName() + " (" + (f.length()/1048576) + "MB)");
                videoFile = f;
                streamServer.setVideoFile(f);
                runOnUiThread(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    loadingOverlay.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                    titleText.setText("🎬 Clique ASSISTIR");
                    setStatus("Streaming pronto!");
                });
            }
            public void onStatus(String s) { setStatus(s); }
        });
        
        streamServer = new StreamServer();
        
        try {
            Log.d(TAG, "Iniciando servidor e engine...");
            streamServer.start();
            torrentEngine.start();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar", e);
            setStatus("Erro: " + e.getMessage());
        }
        
        btnStream.setOnClickListener(v -> {
            String m = magnetInput.getText().toString().trim();
            Log.d(TAG, "Botão STREAM clicado. Magnet: " + m.substring(0, Math.min(60, m.length())));
            if (m.startsWith("magnet:")) startStream(m);
        });
        
        btnStop.setOnClickListener(v -> {
            Log.d(TAG, "Botão PARAR clicado");
            stop();
        });
        
        btnWatch.setOnClickListener(v -> {
            Log.d(TAG, "Botão ASSISTIR clicado");
            watch();
        });
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
        Log.d(TAG, "Iniciando stream...");
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
        Log.d(TAG, "Assistindo vídeo: " + (videoFile != null ? videoFile.getName() : "null"));
        glassPanel.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        titleText.setText("▶️ Reproduzindo");
        playerManager.play("http://127.0.0.1:8080/video");
    }
    
    private void stop() {
        Log.d(TAG, "Parando...");
        torrentEngine.stop();
        playerManager.stop();
        playerView.setVisibility(View.GONE);
        glassPanel.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        titleText.setText("🎬 Torrent Stream");
        progressText.setText("Pronto");
    }
    
    private void setStatus(String s) {
        Log.d(TAG, "Status: " + s);
        runOnUiThread(() -> statusText.setText(s));
    }
    
    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        if (torrentEngine != null) torrentEngine.destroy();
        if (streamServer != null) streamServer.stop();
        if (playerManager != null) playerManager.release();
        super.onDestroy();
    }
}