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

import org.libtorrent4j.swig.torrent_handle;

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
        
        addLog("╔══════════════════════════════╗");
        addLog("║   TORRENT STREAM v10         ║");
        addLog("║   SEEK DISPONÍVEL            ║");
        addLog("╚══════════════════════════════╝");
        addLog("Save: " + savePath);
        
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
                addLog("ERRO PLAYER: " + error.getErrorCodeName());
                addLog("  " + error.getMessage());
            }
            
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    addLog("▶ Reproduzindo");
                }
            }
        });
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { addLog("✅ Engine pronto"); }
            public void onError(String e) { addLog("❌ Engine: " + e); }
            
            public void onProgress(TorrentInfo info) {
                runOnUiThread(() -> {
                    bufferBar.setProgress(info.progress);
                    progressText.setText(info.progress + "% | " + (info.speed/1024) + "KB/s | " + info.peers + " peers");
                });
            }
            
            public void onStreamReady(torrent_handle handle) {
                streamServer.setSavePath(savePath);
                streamServer.setTorrent(handle);
                addLog("✅ STREAM READY - Seek disponível!");
                runOnUiThread(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    loadingOverlay.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                    titleText.setText("🎬 Pronto! Clique ASSISTIR");
                });
            }
            
            public void onStatus(String s) { addLog("📡 " + s); }
            public void onLog(String log) { addLog("🔧 " + log); }
        });
        
        streamServer = new StreamServer();
        try {
            streamServer.start();
            addLog("✅ Servidor HTTP:8080 OK");
        } catch (Exception e) {
            addLog("❌ Servidor: " + e.getMessage());
        }
        
        torrentEngine.start();
        
        btnStream.setOnClickListener(v -> {
            String m = magnetInput.getText().toString().trim();
            if (m.startsWith("magnet:")) {
                addLog("🔗 Iniciando stream...");
                startStream(m);
            }
        });
        
        btnStop.setOnClickListener(v -> {
            addLog("⏹ Parando...");
            stop();
        });
        
        btnWatch.setOnClickListener(v -> {
            addLog("▶ Assistindo... " + streamServer.getStats());
            watch();
        });
    }
    
    private void addLog(String msg) {
        String time = sdf.format(new Date());
        String line = time + " " + msg + "\n";
        Log.d(TAG, msg);
        logBuilder.insert(0, line);
        if (logBuilder.length() > 12000) logBuilder.setLength(12000);
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
        spinnerBar.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        titleText.setText("⬇️ Preparando stream...");
        torrentEngine.startDownload(magnet, savePath);
    }
    
    private void watch() {
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        titleText.setText("▶️ Reproduzindo");
        
        String url = "http://127.0.0.1:8080/video";
        addLog("▶ URL: " + url);
        
        Uri videoUri = Uri.parse(url);
        
        DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(60000)
            .setAllowCrossProtocolRedirects(true);
        
        ProgressiveMediaSource.Factory mediaSourceFactory = 
            new ProgressiveMediaSource.Factory(dataSourceFactory);
        
        MediaSource mediaSource = mediaSourceFactory.createMediaSource(
            MediaItem.fromUri(videoUri));
        
        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
        
        addLog("   Player iniciado");
    }
    
    private void stop() {
        torrentEngine.stop();
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        playerView.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        titleText.setText("🎬 Torrent Stream");
        progressText.setText("Pronto");
        addLog("⏹ Parado");
    }
    
    @Override
    protected void onDestroy() {
        addLog("💀 onDestroy");
        if (torrentEngine != null) torrentEngine.destroy();
        if (streamServer != null) streamServer.stop();
        if (exoPlayer != null) exoPlayer.release();
        super.onDestroy();
    }
}