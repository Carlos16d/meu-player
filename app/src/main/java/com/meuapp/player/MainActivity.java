package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer player;
    private LinearLayout controlPanel, statsRow;
    private TextView logText, statProgress, statSpeed, statPeers;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    private ScrollView logScroll;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private boolean downloading = false;
    private File videoFile = null;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statsUpdater;
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        controlPanel = findViewById(R.id.control_panel);
        statsRow = findViewById(R.id.stats_row);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        statProgress = findViewById(R.id.stat_progress);
        statSpeed = findViewById(R.id.stat_speed);
        statPeers = findViewById(R.id.stat_peers);
        bufferBar = findViewById(R.id.buffer_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setVisibility(View.GONE);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                String stateStr = state == Player.STATE_IDLE ? "IDLE" :
                    state == Player.STATE_BUFFERING ? "BUFFERING" :
                    state == Player.STATE_READY ? "READY" :
                    state == Player.STATE_ENDED ? "ENDED" : "?";
                log("🎬 ExoPlayer: " + stateStr);
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                log("❌ ExoPlayer ERRO: " + error.getErrorCodeName());
            }
        });
        
        log("══════ APP INICIADO ══════");
        
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
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        fullLog.append(line);
        handler.post(() -> {
            logText.setText(fullLog.toString());
            logScroll.fullScroll(View.FOCUS_DOWN);
        });
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        
        // Mostra stats e esconde controles
        handler.post(() -> {
            controlPanel.setVisibility(View.GONE);
            statsRow.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
            logScroll.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            statProgress.setText("0%");
            statSpeed.setText("0 B/s");
            statPeers.setText("0");
            bufferBar.setProgress(0);
        });
        
        log("══════ DOWNLOAD ══════");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(0);
                
                byte_vector pr = new byte_vector();
                pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                log("✅ Magnet enviado");
                
                Thread.sleep(5000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrent = h.get(0);
                    log("✅ Torrent OK - " + torrent.status().getNum_peers() + " peers");
                }
                
                // Procura arquivo (loop infinito até achar)
                while (downloading && videoFile == null) {
                    handler.post(() -> {
                        File f = findVideo(new File(savePath));
                        if (f != null && f.exists() && f.length() > 100000) {
                            videoFile = f;
                            long mb = f.length() / 1048576;
                            log("📁 " + f.getName() + " (" + mb + "MB)");
                            btnWatch.setText("🎬 ASSISTIR (" + mb + "MB)");
                            btnWatch.setVisibility(View.VISIBLE);
                        }
                    });
                    Thread.sleep(2000);
                }
                
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
        
        // Stats updater (roda sempre)
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (downloading && torrent != null && torrent.is_valid()) {
                    torrent_status ts = torrent.status();
                    int prog = (int)(ts.getProgress() * 100);
                    long speed = ts.getDownload_rate();
                    int peers = ts.getNum_peers();
                    
                    statProgress.setText(prog + "%");
                    statPeers.setText(String.valueOf(peers));
                    bufferBar.setProgress(prog);
                    
                    if (speed > 1048576)
                        statSpeed.setText(String.format("%.1f MB/s", speed / 1048576.0));
                    else if (speed > 1024)
                        statSpeed.setText(String.format("%.1f KB/s", speed / 1024.0));
                    else
                        statSpeed.setText(speed + " B/s");
                }
                if (downloading) handler.postDelayed(this, 500);
            }
        });
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) {
            log("❌ Arquivo não encontrado");
            return;
        }
        
        log("▶️ Reproduzindo " + videoFile.getName());
        
        handler.post(() -> {
            playerView.setVisibility(View.VISIBLE);
            statsRow.setVisibility(View.GONE);
            btnWatch.setVisibility(View.GONE);
            bufferBar.setVisibility(View.GONE);
            logScroll.setVisibility(View.GONE);
        });
        
        player.setMediaItem(MediaItem.fromUri("file://" + videoFile.getAbsolutePath()));
        player.prepare();
        player.play();
    }
    
    private void stop() {
        downloading = false;
        player.stop();
        player.clearMediaItems();
        handler.removeCallbacksAndMessages(null);
        
        handler.post(() -> {
            playerView.setVisibility(View.GONE);
            controlPanel.setVisibility(View.VISIBLE);
            statsRow.setVisibility(View.GONE);
            btnStop.setVisibility(View.GONE);
            btnWatch.setVisibility(View.GONE);
            bufferBar.setVisibility(View.GONE);
            logScroll.setVisibility(View.GONE);
        });
        
        log("⏹️ Parado");
    }
    
    private File findVideo(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideo(f);
                    if (found != null) return found;
                } else {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".mp4") || n.endsWith(".mkv") || 
                        n.endsWith(".avi") || n.endsWith(".webm")) {
                        return f;
                    }
                }
            }
        }
        return null;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        if (session != null) session.stop();
    }
}
