package com.meuapp.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.ui.PlayerView;

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.server.StreamServer;
import com.meuapp.player.player.ExoPlayerManager;

import java.io.*;
import java.text.DecimalFormat;

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
    private Button btnPlay, btnStop, btnWatch, btnTorrentFile;
    
    private File videoFile;
    private String savePath;
    private DecimalFormat df = new DecimalFormat("#.##");
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Inicializa views
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
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        
        // ExoPlayer
        exoPlayer = new SimpleExoPlayer.Builder(this).build();
        playerManager = new ExoPlayerManager(playerView, exoPlayer);
        
        // Torrent Engine
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineListener() {
            @Override public void onEngineReady() { setStatus("✅ Pronto!"); }
            @Override public void onEngineError(String error) { setStatus("❌ " + error); }
            @Override public void onMetadata(String name, long size) {}
            
            @Override
            public void onProgress(long downloaded, long total, int speed, int peers) {
                int pct = total > 0 ? (int)(downloaded * 100 / total) : 0;
                bufferBar.setProgress(pct);
                progressText.setText(df.format(downloaded / 1048576.0) + " MB | " + 
                    (speed / 1024) + " KB/s | " + peers + " peers");
            }
            
            @Override
            public void onStreamReady(File videoFile) {
                MainActivity.this.videoFile = videoFile;
                spinnerBar.setVisibility(View.GONE);
                loadingOverlay.setVisibility(View.GONE);
                btnWatch.setVisibility(View.VISIBLE);
                btnWatch.setAlpha(0f);
                btnWatch.animate().alpha(1f).setDuration(500);
                titleText.setText("🎬 Pronto para streaming!");
            }
            
            @Override public void onStatus(String status) { setStatus(status); }
        });
        
        // Stream Server
        streamServer = new StreamServer();
        streamServer.setVideoProvider(new StreamServer.VideoProvider() {
            @Override public File getVideoFile() { return videoFile; }
            
            @Override
            public byte[] readChunk(long offset, int size) throws IOException {
                byte[] data = new byte[size];
                try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
                    raf.seek(offset);
                    int read = raf.read(data);
                    if (read > 0) {
                        byte[] result = new byte[read];
                        System.arraycopy(data, 0, result, 0, read);
                        return result;
                    }
                }
                return new byte[0];
            }
            
            @Override public long getFileSize() { return videoFile != null ? videoFile.length() : 0; }
            
            @Override
            public String getMimeType() {
                if (videoFile == null) return "video/mp4";
                String name = videoFile.getName().toLowerCase();
                if (name.endsWith(".mkv")) return "video/x-matroska";
                if (name.endsWith(".webm")) return "video/webm";
                return "video/mp4";
            }
        });
        
        // Inicia
        torrentEngine.init(savePath);
        streamServer.start();
        
        // Botões
        btnPlay.setOnClickListener(v -> startMagnet());
        btnTorrentFile.setOnClickListener(v -> openTorrentFile());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        setStatus("Aguardando magnet ou .torrent...");
    }
    
    private void startMagnet() {
        String magnet = magnetInput.getText().toString().trim();
        if (magnet.startsWith("magnet:")) {
            startDownload(magnet);
        }
    }
    
    private void openTorrentFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, 100);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            try {
                InputStream is = getContentResolver().openInputStream(data.getData());
                File torrentFile = new File(savePath, "temp.torrent");
                FileOutputStream fos = new FileOutputStream(torrentFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                is.close();
                startDownload(torrentFile.getAbsolutePath());
            } catch (Exception e) {
                setStatus("❌ Erro ao ler arquivo");
            }
        }
    }
    
    private void startDownload(String source) {
        glassPanel.setVisibility(View.VISIBLE);
        bufferBar.setVisibility(View.VISIBLE);
        spinnerBar.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        titleText.setText("⬇️ Baixando...");
        
        torrentEngine.startDownload(source);
    }
    
    private void watch() {
        if (videoFile == null) return;
        
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
        glassPanel.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        
        titleText.setText("🎬 Torrent Streaming");
        progressText.setText("Pronto");
        setStatus("⏹️ Parado");
    }
    
    private void setStatus(String msg) {
        runOnUiThread(() -> statusText.setText(msg));
    }
    
    @Override
    protected void onDestroy() {
        torrentEngine.destroy();
        streamServer.stop();
        playerManager.release();
        super.onDestroy();
    }
}
