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
import com.google.android.exoplayer2.trackselection.*;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.upstream.cache.*;
import com.google.android.exoplayer2.util.*;

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
    private int logCount = 0;
    
    // Cache para o ExoPlayer
    private Cache cache;
    private CacheDataSource.Factory cacheDataSourceFactory;
    
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
        
        addLog("=== TORRENT STREAM v13 ===");
        addLog("Android: " + Build.VERSION.SDK_INT + " | " + Build.MANUFACTURER + " " + Build.MODEL);
        
        // Cache do ExoPlayer (2MB)
        File cacheDir = new File(getCacheDir(), "exo-cache");
        cache = new SimpleCache(cacheDir, new NoOpCacheEvictor());
        cacheDataSourceFactory = new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(60000));
        
        addLog("Cache ExoPlayer: " + cacheDir.getAbsolutePath());
        
        // ExoPlayer
        exoPlayer = new SimpleExoPlayer.Builder(this)
            .setTrackSelector(new DefaultTrackSelector(this))
            .build();
        
        playerView.setPlayer(exoPlayer);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(0);
        playerView.setKeepScreenOn(true);
        
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                String s = state == Player.STATE_BUFFERING ? "BUFFERING" : 
                          state == Player.STATE_READY ? "READY" : 
                          state == Player.STATE_ENDED ? "ENDED" : "IDLE";
                addLog("[EXO] " + s);
                loadingOverlay.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            }
            
            @Override
            public void onPlayerError(PlaybackException error) {
                addLog("[EXO] ERRO: " + error.getErrorCodeName() + " - " + error.getMessage());
            }
        });
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { addLog("[ENG] OK"); }
            public void onError(String e) { addLog("[ENG] ERRO: " + e); }
            public void onProgress(TorrentInfo info) {
                runOnUiThread(() -> {
                    bufferBar.setProgress(info.progress);
                    progressText.setText(info.progress + "% " + (info.speed/1024) + "KB/s");
                });
            }
            public void onStreamReady(torrent_handle handle) {
                streamServer.setSavePath(savePath);
                streamServer.setTorrent(handle);
                addLog("[ENG] READY");
                runOnUiThread(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    loadingOverlay.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                });
            }
            public void onStatus(String s) { addLog("[ENG] " + s); }
            public void onLog(String log) { addLog("[ENG] " + log); }
        });
        
        streamServer = new StreamServer();
        try { streamServer.start(); addLog("[SRV] OK"); } 
        catch (Exception e) { addLog("[SRV] ERRO: " + e.getMessage()); }
        
        torrentEngine.start();
        
        btnStream.setOnClickListener(v -> {
            String m = magnetInput.getText().toString().trim();
            if (m.startsWith("magnet:")) startStream(m);
        });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
    }
    
    private void addLog(String msg) {
        logCount++;
        String line = String.format("#%04d %s %s\n", logCount, sdf.format(new Date()), msg);
        Log.d(TAG, msg);
        logBuilder.insert(0, line);
        if (logBuilder.length() > 15000) logBuilder.setLength(15000);
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
        torrentEngine.startDownload(magnet, savePath);
    }
    
    private void watch() {
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        
        String url = "http://127.0.0.1:8080/video";
        addLog("[UI] Playing: " + url);
        
        Uri videoUri = Uri.parse(url);
        
        // Usa CacheDataSource para buffer local
        ProgressiveMediaSource.Factory mediaFactory = 
            new ProgressiveMediaSource.Factory(cacheDataSourceFactory);
        
        MediaSource source = mediaFactory.createMediaSource(MediaItem.fromUri(videoUri));
        
        exoPlayer.setMediaSource(source);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
    }
    
    private void stop() {
        torrentEngine.stop();
        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.clearMediaItems(); }
        playerView.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
    }
    
    @Override
    protected void onDestroy() {
        if (torrentEngine != null) torrentEngine.destroy();
        if (streamServer != null) streamServer.stop();
        if (exoPlayer != null) exoPlayer.release();
        if (cache != null) cache.release();
        super.onDestroy();
    }
}