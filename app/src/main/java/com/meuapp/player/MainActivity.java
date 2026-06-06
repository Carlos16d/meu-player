package com.meuapp.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.ui.PlayerView;

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.server.StreamServer;
import com.meuapp.player.player.ExoPlayerManager;
import com.meuapp.player.cache.CacheManager;
import com.meuapp.player.model.TorrentInfo;
import com.meuapp.player.utils.FileUtils;
import com.meuapp.player.utils.LogUtils;

import java.io.*;
import java.text.DecimalFormat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    
    // Views
    private PlayerView playerView;
    private TextView statusText, progressText, titleText;
    private ProgressBar bufferBar, spinnerBar;
    private View loadingOverlay, glassPanel;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch, btnTorrentFile;
    
    // Managers
    private SimpleExoPlayer exoPlayer;
    private ExoPlayerManager playerManager;
    private TorrentEngine torrentEngine;
    private StreamServer streamServer;
    private CacheManager cacheManager;
    
    // Estado
    private File videoFile;
    private String savePath;
    private DecimalFormat df = new DecimalFormat("#.##");
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        LogUtils.d(TAG, "onCreate iniciado");
        
        // Inicializa views
        initViews();
        
        // Configura paths
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        // Inicializa managers
        initManagers();
        
        // Configura listeners
        setupListeners();
        
        // Animação do título
        AlphaAnimation glow = new AlphaAnimation(0.6f, 1.0f);
        glow.setDuration(2000);
        glow.setRepeatMode(Animation.REVERSE);
        glow.setRepeatCount(Animation.INFINITE);
        titleText.startAnimation(glow);
        
        LogUtils.d(TAG, "onCreate finalizado");
    }
    
    private void initViews() {
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
        btnTorrentFile = findViewById(R.id.btn_torrent_file);
    }
    
    private void initManagers() {
        // ExoPlayer
        exoPlayer = new SimpleExoPlayer.Builder(this).build();
        playerManager = new ExoPlayerManager(playerView, exoPlayer);
        
        // Cache
        cacheManager = new CacheManager(this);
        
        // Torrent Engine
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            @Override
            public void onReady() {
                setStatus("✅ Pronto para streaming!");
                LogUtils.d(TAG, "Engine pronta");
            }
            
            @Override
            public void onError(String error) {
                setStatus("❌ " + error);
                LogUtils.e(TAG, "Engine error: " + error);
            }
            
            @Override
            public void onProgress(TorrentInfo info) {
                runOnUiThread(() -> {
                    bufferBar.setProgress(info.progress);
                    progressText.setText(
                        info.getDownloadedMB() + " | " + 
                        info.getSpeedKB() + " | " + 
                        info.getPeersInfo()
                    );
                });
            }
            
            @Override
            public void onStreamReady(File videoFile) {
                MainActivity.this.videoFile = videoFile;
                streamServer.setVideoFile(videoFile);
                
                runOnUiThread(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    loadingOverlay.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                    btnWatch.setAlpha(0f);
                    btnWatch.animate().alpha(1f).setDuration(500);
                    titleText.setText("🎬 Pronto para assistir!");
                    setStatus("✅ " + FileUtils.formatFileSize(videoFile.length()) + " baixados");
                });
            }
            
            @Override
            public void onStatus(String status) {
                setStatus(status);
            }
        });
        
        // Stream Server
        streamServer = new StreamServer();
    }
    
    private void setupListeners() {
        btnPlay.setOnClickListener(v -> startMagnet());
        btnTorrentFile.setOnClickListener(v -> openTorrentFile());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        // Player listener
        playerManager.setPlayerListener(new ExoPlayerManager.PlayerListener() {
            @Override
            public void onPlayerReady() {
                spinnerBar.setVisibility(View.GONE);
                loadingOverlay.setVisibility(View.GONE);
                setStatus("▶️ Reproduzindo...");
            }
            
            @Override
            public void onPlayerBuffering() {
                spinnerBar.setVisibility(View.VISIBLE);
                loadingOverlay.setVisibility(View.VISIBLE);
                setStatus("🔄 Buffering...");
            }
            
            @Override
            public void onPlayerError(String error) {
                setStatus("❌ Erro no player: " + error);
            }
            
            @Override
            public void onTracksAvailable(int audioTracks, int subtitleTracks) {
                String msg = "▶️ Reproduzindo";
                if (audioTracks > 1) msg += " [🎵 " + audioTracks + " áudios]";
                if (subtitleTracks > 0) msg += " [📝 " + subtitleTracks + " legendas]";
                setStatus(msg);
            }
        });
    }
    
    private void startMagnet() {
        String magnet = magnetInput.getText().toString().trim();
        if (magnet.startsWith("magnet:")) {
            startDownload(magnet);
        } else {
            Toast.makeText(this, "Cole um magnet link válido", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void openTorrentFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-bittorrent");
        startActivityForResult(intent, 100);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            try {
                Uri uri = data.getData();
                InputStream is = getContentResolver().openInputStream(uri);
                File torrentFile = new File(savePath, "temp.torrent");
                FileOutputStream fos = new FileOutputStream(torrentFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
                fos.close();
                is.close();
                
                setStatus("📁 Arquivo .torrent carregado!");
                startDownload(torrentFile.getAbsolutePath());
            } catch (Exception e) {
                setStatus("❌ Erro ao ler arquivo");
                LogUtils.e(TAG, "Erro ao abrir torrent", e);
            }
        }
    }
    
    private void startDownload(String source) {
        // Inicia engine se necessário
        if (!torrentEngine.isReady()) {
            torrentEngine.start(savePath);
            streamServer.start();
        }
        
        // Mostra UI de download
        glassPanel.setVisibility(View.VISIBLE);
        bufferBar.setVisibility(View.VISIBLE);
        spinnerBar.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        
        titleText.setText("⬇️ Baixando...");
        bufferBar.setProgress(0);
        progressText.setText("Conectando...");
        
        // Inicia download
        torrentEngine.startDownload(source, savePath);
        setStatus("🔍 Buscando peers...");
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) {
            setStatus("❌ Arquivo não encontrado");
            return;
        }
        
        // Esconde UI de download
        glassPanel.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        
        // Mostra player
        playerView.setVisibility(View.VISIBLE);
        titleText.setText("▶️ Reproduzindo");
        
        // Inicia reprodução
        playerManager.play("http://127.0.0.1:8080/video");
    }
    
    private void stop() {
        // Para engine
        torrentEngine.stop();
        
        // Para player
        playerManager.stop();
        
        // Reseta UI
        playerView.setVisibility(View.GONE);
        glassPanel.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        
        titleText.setText("🎬 Torrent Streaming");
        progressText.setText("Pronto para começar");
        setStatus("⏹️ Parado");
        
        videoFile = null;
    }
    
    private void setStatus(String msg) {
        runOnUiThread(() -> statusText.setText(msg));
    }
    
    @Override
    protected void onDestroy() {
        LogUtils.d(TAG, "onDestroy");
        
        if (torrentEngine != null) torrentEngine.destroy();
        if (streamServer != null) streamServer.stop();
        if (playerManager != null) playerManager.release();
        if (cacheManager != null) cacheManager.saveCacheIndex();
        
        super.onDestroy();
    }
}