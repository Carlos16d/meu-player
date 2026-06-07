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
import com.google.android.exoplayer2.util.*;
import com.google.android.exoplayer2.audio.*;
import com.google.android.exoplayer2.video.*;

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
        
        addLog("╔══════════════════════════════════════╗");
        addLog("║   TORRENT STREAM v12 - LOGS FULL    ║");
        addLog("╚══════════════════════════════════════╝");
        addLog("Android: " + Build.VERSION.SDK_INT + " | " + Build.MANUFACTURER + " | " + Build.MODEL);
        addLog("SavePath: " + savePath);
        addLog("SavePath existe: " + new File(savePath).exists());
        
        // ========== EXOPLAYER COM LOGS ==========
        addLog("[EXO] Criando ExoPlayer...");
        
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        
        exoPlayer = new SimpleExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(new DefaultTrackSelector(this))
            .build();
        
        addLog("[EXO] ExoPlayer criado: " + exoPlayer);
        
        playerView.setPlayer(exoPlayer);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(0);
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
                    default: s = "STATE_" + state; break;
                }
                addLog("[EXO] State: " + s + " (raw=" + state + ")");
                
                runOnUiThread(() -> {
                    loadingOverlay.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                });
                
                if (state == Player.STATE_READY) {
                    addLog("[EXO] READY! Video pronto para reproduzir");
                    addLog("[EXO] Duration: " + exoPlayer.getDuration() + "ms");
                    addLog("[EXO] CurrentPosition: " + exoPlayer.getCurrentPosition() + "ms");
                    addLog("[EXO] VideoFormat: " + exoPlayer.getVideoFormat());
                    addLog("[EXO] AudioFormat: " + exoPlayer.getAudioFormat());
                    addLog("[EXO] PlayWhenReady: " + exoPlayer.getPlayWhenReady());
                }
            }
            
            @Override
            public void onPlayerError(PlaybackException error) {
                addLog("[EXO] ❌ ERRO: " + error.getErrorCodeName());
                addLog("[EXO]   Message: " + error.getMessage());
                addLog("[EXO]   ErrorCode: " + error.errorCode);
                addLog("[EXO]   Timestamp: " + error.timestampMs);
                
                if (error.getCause() != null) {
                    addLog("[EXO]   Cause: " + error.getCause().getClass().getName() + " - " + error.getCause().getMessage());
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    error.getCause().printStackTrace(pw);
                    String[] lines = sw.toString().split("\n");
                    for (int i = 0; i < Math.min(10, lines.length); i++) {
                        addLog("[EXO]     " + lines[i].trim());
                    }
                }
            }
            
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                addLog("[EXO] isPlaying: " + isPlaying);
            }
            
            @Override
            public void onTracksChanged(Tracks tracks) {
                addLog("[EXO] Tracks changed: " + tracks.getGroups().size() + " grupos");
                for (Tracks.Group g : tracks.getGroups()) {
                    String type = "?";
                    if (g.getMediaTrackGroup().type == C.TRACK_TYPE_VIDEO) type = "VIDEO";
                    else if (g.getMediaTrackGroup().type == C.TRACK_TYPE_AUDIO) type = "AUDIO";
                    else if (g.getMediaTrackGroup().type == C.TRACK_TYPE_TEXT) type = "TEXT";
                    
                    addLog("[EXO]   " + type + ": " + g.length + " tracks, supported=" + g.isSupported());
                    
                    for (int i = 0; i < g.length; i++) {
                        Format f = g.getTrackFormat(i);
                        addLog("[EXO]     [" + i + "] " + f.sampleMimeType + 
                               " codecs=" + f.codecs + 
                               " lang=" + f.language + 
                               " res=" + f.width + "x" + f.height +
                               " bitrate=" + f.bitrate);
                    }
                }
            }
            
            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                addLog("[EXO] PlayWhenReady: " + playWhenReady + " reason=" + reason);
            }
        });
        
        // ========== TORRENT ENGINE ==========
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { addLog("[ENG] Pronto"); }
            public void onError(String e) { addLog("[ENG] ERRO: " + e); }
            
            public void onProgress(TorrentInfo info) {
                runOnUiThread(() -> {
                    bufferBar.setProgress(info.progress);
                    progressText.setText(info.progress + "% " + (info.speed/1024) + "KB/s " + info.peers + "p " + (info.downloaded/1048576) + "MB");
                });
            }
            
            public void onStreamReady(torrent_handle handle) {
                addLog("[ENG] Stream Ready - configurando servidor...");
                streamServer.setSavePath(savePath);
                streamServer.setTorrent(handle);
                addLog("[ENG] Servidor configurado");
                runOnUiThread(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    loadingOverlay.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                    titleText.setText("Pronto!");
                });
            }
            
            public void onStatus(String s) { addLog("[ENG] " + s); }
            public void onLog(String log) { addLog("[ENG] " + log); }
        });
        
        // ========== STREAM SERVER ==========
        streamServer = new StreamServer();
        try {
            streamServer.start();
            addLog("[SRV] Servidor HTTP:8080 iniciado");
            addLog("[SRV] isAlive: " + streamServer.isAlive());
        } catch (Exception e) {
            addLog("[SRV] ERRO ao iniciar: " + e.getMessage());
        }
        
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
        String time = sdf.format(new Date());
        String line = String.format("#%04d %s %s\n", logCount, time, msg);
        Log.d(TAG, msg);
        logBuilder.insert(0, line);
        if (logBuilder.length() > 20000) logBuilder.setLength(20000);
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
        addLog("[UI] startStream: " + magnet.substring(0, Math.min(60, magnet.length())) + "...");
        bufferBar.setVisibility(View.VISIBLE);
        spinnerBar.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        titleText.setText("Preparando...");
        torrentEngine.startDownload(magnet, savePath);
    }
    
    private void watch() {
        addLog("[UI] ========== WATCH ==========");
        addLog("[UI] Server stats: " + streamServer.getStats());
        addLog("[UI] isAlive: " + streamServer.isAlive());
        
        String url = "http://127.0.0.1:8080/video";
        
        // Teste HTTP detalhado
        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) 
                    new java.net.URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Range", "bytes=0-1023");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.connect();
                
                int code = conn.getResponseCode();
                String type = conn.getContentType();
                long len = conn.getContentLength();
                String range = conn.getHeaderField("Content-Range");
                
                addLog("[UI] Teste HTTP: " + code + " Type:" + type + " Len:" + len);
                addLog("[UI] Content-Range: " + range);
                
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[1024];
                int read = is.read(buf);
                is.close();
                addLog("[UI] Bytes lidos: " + read);
                if (read > 4) {
                    addLog("[UI] Magic: " + String.format("%02X %02X %02X %02X", 
                        buf[0] & 0xFF, buf[1] & 0xFF, buf[2] & 0xFF, buf[3] & 0xFF));
                }
                conn.disconnect();
            } catch (Exception e) {
                addLog("[UI] Teste HTTP ERRO: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }).start();
        
        addLog("[UI] URL: " + url);
        
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        titleText.setText("Reproduzindo");
        
        Uri videoUri = Uri.parse(url);
        DataSource.Factory factory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000).setReadTimeoutMs(60000).setAllowCrossProtocolRedirects(true);
        ProgressiveMediaSource.Factory mediaFactory = new ProgressiveMediaSource.Factory(factory);
        MediaSource source = mediaFactory.createMediaSource(MediaItem.fromUri(videoUri));
        
        addLog("[UI] Preparando player...");
        exoPlayer.setMediaSource(source);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
        addLog("[UI] Player iniciado");
    }
    
    private void stop() {
        addLog("[UI] ========== STOP ==========");
        torrentEngine.stop();
        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.clearMediaItems(); }
        playerView.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        titleText.setText("Torrent Stream");
        progressText.setText("Pronto");
    }
    
    @Override
    protected void onDestroy() {
        addLog("[UI] ========== DESTROY ==========");
        if (torrentEngine != null) torrentEngine.destroy();
        if (streamServer != null) streamServer.stop();
        if (exoPlayer != null) exoPlayer.release();
        super.onDestroy();
    }
}