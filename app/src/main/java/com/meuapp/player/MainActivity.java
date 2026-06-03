package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView statusText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    
    private String savePath;
    private SessionManager session;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private long lastPosition = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        // Player menor - 92% da largura
        playerView.post(() -> {
            int width = (int)(getResources().getDisplayMetrics().widthPixels * 0.92);
            int height = (int)(width * 9.0 / 16.0);
            ViewGroup.LayoutParams params = playerView.getLayoutParams();
            params.width = width;
            params.height = height;
            playerView.setLayoutParams(params);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setVisibility(View.GONE);
        
        // Listener do player com tela de carregamento + manter posição
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    log("▶️ Reproduzindo");
                    loadingOverlay.setVisibility(View.GONE);
                    spinnerBar.setVisibility(View.GONE);
                    // Restaura posição se necessário
                    if (lastPosition > 0 && player.getCurrentPosition() == 0) {
                        player.seekTo(lastPosition);
                        lastPosition = 0;
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    log("⏳ Carregando...");
                    loadingOverlay.setVisibility(View.VISIBLE);
                    spinnerBar.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_ENDED) {
                    log("✅ Finalizado");
                    loadingOverlay.setVisibility(View.GONE);
                    spinnerBar.setVisibility(View.GONE);
                    lastPosition = 0;
                }
            }
            
            @Override
            public void onPlayerError(PlaybackException error) {
                log("⏳ Aguardando mais dados...");
                loadingOverlay.setVisibility(View.VISIBLE);
                spinnerBar.setVisibility(View.VISIBLE);
                
                // Salva posição atual
                lastPosition = player.getCurrentPosition();
                if (lastPosition < 0) lastPosition = 0;
                
                // Tenta de novo mantendo a posição
                handler.postDelayed(() -> {
                    if (downloading && player != null && videoFile != null) {
                        player.setMediaItem(MediaItem.fromUri("file://" + videoFile.getAbsolutePath()));
                        player.prepare();
                        player.play();
                    }
                }, 3000);
            }
        });
        
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                log("✅ Sessão OK");
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        log("📱 Pronto");
    }
    
    private void log(String msg) {
        handler.post(() -> statusText.setText(msg));
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        lastPosition = 0;
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        log("⏳ Baixando (max 3 MB/s)...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(3 * 1024 * 1024);
                p.setMax_connections(50);
                p.setMax_uploads(5);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                
                // Aguarda header válido e inicia automaticamente
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 65536) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e) { continue; }
                        
                        boolean valid = (hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                                       ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3);
                        
                        if (valid) {
                            videoFile = f;
                            long mb = f.length() / 1048576;
                            log("▶️ Iniciando player... (" + mb + "MB)");
                            
                            // Inicia o player automaticamente
                            handler.post(() -> {
                                playerView.setVisibility(View.VISIBLE);
                                loadingOverlay.setVisibility(View.VISIBLE);
                                spinnerBar.setVisibility(View.VISIBLE);
                                player.setMediaItem(MediaItem.fromUri("file://" + videoFile.getAbsolutePath()));
                                player.prepare();
                                player.play();
                                btnWatch.setVisibility(View.GONE);
                            });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
                
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) {
            log("❌ Arquivo não encontrado");
            return;
        }
        
        log("▶️ " + videoFile.getName());
        lastPosition = 0;
        
        handler.post(() -> {
            playerView.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
            loadingOverlay.setVisibility(View.VISIBLE);
            spinnerBar.setVisibility(View.VISIBLE);
        });
        
        player.setMediaItem(MediaItem.fromUri("file://" + videoFile.getAbsolutePath()));
        player.prepare();
        player.play();
    }
    
    private void stop() {
        downloading = false;
        lastPosition = 0;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.clearMediaItems(); }
        playerView.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
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
        if (player != null) player.release();
        if (session != null) session.stop();
        super.onDestroy();
    }
}
