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

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.server.StreamServer;
import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.swig.torrent_handle;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.*;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    
    private VLCVideoLayout videoLayout;
    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;
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
    private boolean isPlayerActive = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        requestPermissions();
        
        videoLayout = findViewById(R.id.video_surface);
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
        
        addLog("=== TORRENT STREAM VLC FINAL ===");
        addLog("Limite: 2MB/s | Buffer: desligado");
        
        // VLC
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=5000");
        options.add("--file-caching=3000");
        options.add("--http-reconnect");
        options.add("--clock-synchro=0");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        vlcPlayer.attachViews(videoLayout, null, false, false);
        
        vlcPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        addLog("[VLC] ▶ Playing");
                        isPlayerActive = true;
                        runOnUiThread(() -> loadingOverlay.setVisibility(View.GONE));
                        break;
                    case MediaPlayer.Event.Buffering:
                        float pct = event.getBuffering();
                        addLog("[VLC] 🔄 Buffering " + pct + "%");
                        // SÓ mostra buffering quando o player estiver ativo
                        if (isPlayerActive && pct < 100) {
                            runOnUiThread(() -> loadingOverlay.setVisibility(View.VISIBLE));
                        }
                        break;
                    case MediaPlayer.Event.Stopped:
                        addLog("[VLC] ⏹ Stopped");
                        isPlayerActive = false;
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        addLog("[VLC] ❌ ERRO!");
                        isPlayerActive = false;
                        break;
                }
            }
        });
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { addLog("[ENG] ✅ Pronto"); }
            public void onError(String e) { addLog("[ENG] ❌ " + e); }
            
            public void onProgress(TorrentInfo info) {
                runOnUiThread(() -> {
                    bufferBar.setProgress(info.progress);
                    progressText.setText(info.progress + "% | " + (info.speed/1024) + "KB/s | " + info.peers + " peers");
                });
            }
            
            public void onStreamReady(torrent_handle handle, String sp) {
                streamServer.setSavePath(sp);
                streamServer.setTorrent(handle);
                addLog("[ENG] ✅ STREAM READY");
                runOnUiThread(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    loadingOverlay.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                    titleText.setText("🎬 Pronto! Clique ASSISTIR");
                });
            }
            
            public void onStatus(String s) { addLog("[ENG] " + s); }
            public void onLog(String log) { addLog("[ENG] " + log); }
        });
        
        streamServer = new StreamServer();
        try { streamServer.start(); addLog("[SRV] HTTP:8080 OK"); }
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
        String line = sdf.format(new Date()) + " " + msg + "\n";
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
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        videoLayout.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE); // SEM buffering durante preparação
        titleText.setText("⬇️ Preparando stream...");
        isPlayerActive = false;
        torrentEngine.startDownload(magnet, savePath);
    }
    
    private void watch() {
        btnWatch.setVisibility(View.GONE);
        videoLayout.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.GONE); // SEM buffering inicial
        titleText.setText("▶️ Reproduzindo");
        isPlayerActive = false;
        
        String url = "http://127.0.0.1:8080/video";
        addLog("[VLC] Playing: " + url);
        
        Media media = new Media(libVLC, Uri.parse(url));
        media.setHWDecoderEnabled(true, true);
        media.addOption(":network-caching=5000");
        media.addOption(":file-caching=3000");
        media.addOption(":http-reconnect");
        
        vlcPlayer.setMedia(media);
        media.release();
        vlcPlayer.play();
    }
    
    private void stop() {
        torrentEngine.stop();
        if (vlcPlayer != null) vlcPlayer.stop();
        videoLayout.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        titleText.setText("🎬 Torrent Stream");
        progressText.setText("Pronto");
        isPlayerActive = false;
        addLog("⏹ Parado");
    }
    
    @Override
    protected void onDestroy() {
        if (torrentEngine != null) torrentEngine.destroy();
        if (streamServer != null) streamServer.stop();
        if (vlcPlayer != null) { vlcPlayer.release(); vlcPlayer = null; }
        if (libVLC != null) { libVLC.release(); libVLC = null; }
        super.onDestroy();
    }
}