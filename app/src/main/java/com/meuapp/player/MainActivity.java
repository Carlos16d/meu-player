package com.meuapp.player;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private VideoView videoView;
    private TextView statusText, progressText, speedText;
    private ProgressBar bufferBar;
    private FrameLayout loadingOverlay;
    private LinearLayout controlPanel;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    private ImageView appIcon;
    
    private String savePath;
    private SessionManager session;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private Runnable statsUpdater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoView = findViewById(R.id.video_view);
        statusText = findViewById(R.id.status_text);
        progressText = findViewById(R.id.progress_text);
        speedText = findViewById(R.id.speed_text);
        bufferBar = findViewById(R.id.buffer_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        controlPanel = findViewById(R.id.control_panel);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        appIcon = findViewById(R.id.app_icon);
        
        // Animação no ícone
        AlphaAnimation glow = new AlphaAnimation(0.5f, 1.0f);
        glow.setDuration(1500);
        glow.setRepeatMode(Animation.REVERSE);
        glow.setRepeatCount(Animation.INFINITE);
        appIcon.startAnimation(glow);
        
        videoView.post(() -> {
            int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.94);
            int h = (int)(w * 9.0 / 16.0);
            ViewGroup.LayoutParams p = videoView.getLayoutParams();
            p.width = w;
            p.height = h;
            videoView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        // Controles do VideoView
        MediaController mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);
        
        videoView.setOnPreparedListener(mp -> {
            loadingOverlay.setVisibility(View.GONE);
            log("▶️ Reproduzindo");
        });
        
        videoView.setOnErrorListener((mp, what, extra) -> {
            loadingOverlay.setVisibility(View.VISIBLE);
            handler.postDelayed(() -> {
                if (downloading && videoFile != null && videoFile.exists()) {
                    videoView.setVideoPath(videoFile.getAbsolutePath());
                    videoView.start();
                }
            }, 2000);
            return true;
        });
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); log("✅ Conectado à rede P2P"); } 
            catch (Exception e) { log("❌ Erro: " + e.getMessage()); }
        }).start();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        log("📱 Aguardando magnet link...");
    }
    
    private void log(String msg) {
        handler.post(() -> statusText.setText(msg));
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        
        // Animação de transição
        controlPanel.animate().translationY(controlPanel.getHeight()).alpha(0f).setDuration(400);
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
            progressText.setVisibility(View.VISIBLE);
            speedText.setVisibility(View.VISIBLE);
        });
        
        log("⏳ Conectando a peers...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(0));
                p.setDownload_limit(2 * 1024 * 1024);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(2000);
                
                log("📡 Buscando arquivo...");
                
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 5242880) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; }
                        if ((hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                            ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3)) {
                            videoFile = f;
                            long mb = f.length() / 1048576;
                            
                            handler.post(() -> {
                                btnWatch.setText("🎬 ASSISTIR (" + mb + "MB)");
                                btnWatch.setVisibility(View.VISIBLE);
                                btnWatch.setAlpha(0f);
                                btnWatch.animate().alpha(1f).setDuration(500);
                                log("✅ Pronto! " + f.getName());
                            });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e2) { log("❌ " + e2.getMessage()); }
        }).start();
        
        // Stats updater
        statsUpdater = new Runnable() {
            @Override public void run() {
                if (downloading && videoFile != null) {
                    long size = videoFile.length();
                    progressText.setText("📦 " + (size / 1048576) + " MB baixados");
                    speedText.setText("⚡ 2 MB/s máx.");
                    bufferBar.setProgress((int)((size * 100) / (videoFile.length() + 1)));
                }
                if (downloading) handler.postDelayed(this, 1000);
            }
        };
        handler.post(statsUpdater);
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { log("❌ Arquivo não encontrado"); return; }
        log("▶️ " + videoFile.getName());
        
        handler.post(() -> { 
            videoView.setVisibility(View.VISIBLE);
            videoView.setAlpha(0f);
            videoView.animate().alpha(1f).setDuration(500);
            btnWatch.setVisibility(View.GONE);
            controlPanel.animate().translationY(controlPanel.getHeight()).alpha(0f).setDuration(400);
            loadingOverlay.setVisibility(View.VISIBLE);
        });
        
        videoView.setVideoPath(videoFile.getAbsolutePath());
        videoView.start();
        videoView.requestFocus();
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacks(statsUpdater);
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        speedText.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        
        // Mostra painel de controle de volta
        controlPanel.setAlpha(0f);
        controlPanel.setVisibility(View.VISIBLE);
        controlPanel.animate().translationY(0).alpha(1f).setDuration(400);
        
        log("⏹️ Parado");
    }
    
    private File find(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) { File found = find(f); if (found != null) return found; }
            else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || 
                      f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f;
        }
        return null;
    }
    
    @Override protected void onDestroy() {
        stop();
        if (session != null) session.stop();
        super.onDestroy();
    }
}