package com.meuapp.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.server.StreamServer;
import com.meuapp.player.server.TorrentDataSource;
import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.swig.torrent_handle;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer exoPlayer;
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
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnTorrent = findViewById(R.id.btn_torrent);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        playerView.setUseController(true);
        playerView.setKeepScreenOn(true);
        
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                String s = state == Player.STATE_BUFFERING ? "BUFFERING" : 
                          state == Player.STATE_READY ? "READY" : 
                          state == Player.STATE_ENDED ? "ENDED" : "IDLE";
                debug("[EXO] " + s);
                handler.post(() -> spinnerBar.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE));
                if (state == Player.STATE_READY) {
                    debug("[EXO] ✅ READY! Duration: " + exoPlayer.getDuration() + "ms");
                }
            }
            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                debug("[EXO] ❌ " + error.getErrorCodeName() + ": " + error.getMessage());
                debug("[EXO]   Code: " + error.errorCode);
            }
        });
        
        debug("=== TORRENT STREAM ===");
        
        streamServer = new StreamServer();
        try { streamServer.start(); debug("[SRV] ✅ HTTP:8080"); } 
        catch (Exception e) { debug("[SRV] ❌ " + e.getMessage()); }
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { debug("[ENG] ✅"); }
            public void onError(String e) { debug("[ENG] ❌ " + e); }
            public void onProgress(TorrentInfo info) {
                handler.post(() -> { bufferBar.setProgress(info.progress); statusText.setText(info.progress + "% | " + (info.speed/1024) + "KB/s"); });
            }
            public void onStreamReady(torrent_handle handle, String sp) {
                File vf = findVideoFile(new File(sp));
                if (vf != null) { 
                    videoFile = vf; 
                    streamServer.setVideoFile(vf); 
                    streamServer.setTorrentInfo(handle);
                    debug("[ENG] 📁 " + vf.getName() + " (" + (vf.length()/1048576) + "MB)"); 
                }
                debug("[ENG] ✅ STREAM READY");
                handler.post(() -> { spinnerBar.setVisibility(View.GONE); btnWatch.setVisibility(View.VISIBLE); });
            }
            public void onStatus(String s) { debug("[ENG] " + s); }
            public void onLog(String log) { debug("[ENG] " + log); }
        });
        torrentEngine.start();
        
        btnPlay.setOnClickListener(v -> { String m = magnetInput.getText().toString().trim(); if (!m.isEmpty()) startStream(m); });
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT_FILE); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        debug("📱 Pronto");
    }
    
    @Override protected void onActivityResult(int r, int res, Intent data) {
        super.onActivityResult(r, res, data);
        if (r == PICK_TORRENT_FILE && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) try {
                InputStream is = getContentResolver().openInputStream(uri);
                File tf = new File(savePath, "torrent_file.torrent");
                FileOutputStream fos = new FileOutputStream(tf); byte[] b = new byte[8192]; int l; while ((l = is.read(b)) > 0) fos.write(b, 0, l);
                fos.close(); is.close(); startStream(tf.getAbsolutePath());
            } catch (Exception e) { debug("❌ " + e.getMessage()); }
        }
    }
    
    private void debug(String msg) { String line = "[" + sdf.format(new Date()) + "] " + msg + "\n"; debugLog.append(line); handler.post(() -> debugText.setText(debugLog.toString())); }
    
    private File findVideoFile(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File found = findVideoFile(f); if (found != null) return found; } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) return f; }
        return null;
    }
    
    private void startStream(String source) { bufferBar.setVisibility(View.VISIBLE); spinnerBar.setVisibility(View.VISIBLE); btnStop.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); playerView.setVisibility(View.GONE); debugLog.setLength(0); torrentEngine.startDownload(source, savePath); }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { debug("❌ Video nao encontrado"); return; }
        
        debug("══════════════════════════════════");
        debug("▶️ INICIANDO EXOPLAYER");
        debug("   Arquivo: " + videoFile.getAbsolutePath());
        debug("   Tamanho: " + videoFile.length() + " bytes (" + (videoFile.length()/1048576) + "MB)");
        debug("   Existe: " + videoFile.exists());
        debug("   Pode ler: " + videoFile.canRead());
        debug("══════════════════════════════════");
        
        handler.post(() -> {
            playerView.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE);
            spinnerBar.setVisibility(View.VISIBLE);
            
            // Cria NOVA instância do TorrentDataSource
            TorrentDataSource dataSource = new TorrentDataSource();
            dataSource.setVideoFile(videoFile);
            
            Uri videoUri = Uri.parse("http://127.0.0.1:8080/video");
            MediaItem mediaItem = MediaItem.fromUri(videoUri);
            
            ProgressiveMediaSource.Factory mediaSourceFactory = 
                new ProgressiveMediaSource.Factory(() -> dataSource);
            
            exoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem));
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);
            
            debug("✅ prepare() chamado com TorrentDataSource");
        });
    }
    
    private void stop() { 
        debug("⏹ Parado");
        torrentEngine.stop(); 
        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.clearMediaItems(); } 
        playerView.setVisibility(View.GONE); 
        btnStop.setVisibility(View.GONE); 
        btnWatch.setVisibility(View.GONE); 
        bufferBar.setVisibility(View.GONE); 
        spinnerBar.setVisibility(View.GONE); 
    }
    
    @Override protected void onDestroy() { 
        stop(); 
        if (streamServer != null) streamServer.stop(); 
        if (exoPlayer != null) exoPlayer.release(); 
        super.onDestroy(); 
    }
}