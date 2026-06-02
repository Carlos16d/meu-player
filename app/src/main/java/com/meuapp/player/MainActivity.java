package com.meuapp.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;

public class MainActivity extends AppCompatActivity {
    private VideoView videoView;
    private LinearLayout controlPanel, statsRow;
    private TextView logText, statProgress, statSpeed, statPeers;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop;
    private ScrollView logScroll;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private volatile boolean downloading = false;
    private volatile File videoFile = null;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder fullLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoView = findViewById(R.id.video_view);
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
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        log("═══ APP INICIADO ═══");
        
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                log("✅ Sessão torrent OK");
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        
        videoView.setOnPreparedListener(mp -> {
            log("✅ Reproduzindo!");
            handler.post(() -> {
                logScroll.setVisibility(View.GONE);
                statsRow.setVisibility(View.VISIBLE);
                btnStop.setVisibility(View.VISIBLE);
                bufferBar.setVisibility(View.VISIBLE);
            });
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            log("❌ Erro VideoView: " + what);
            return true;
        });
        videoView.setOnCompletionListener(mp -> log("🏁 Fim"));
    }
    
    private void log(String msg) {
        fullLog.append(msg).append("\n");
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
        
        handler.post(() -> {
            videoView.setVisibility(View.VISIBLE);
            controlPanel.setVisibility(View.GONE);
            logScroll.setVisibility(View.VISIBLE);
        });
        
        log("═══ INICIANDO ═══");
        
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
                
                Thread.sleep(3000);
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrent = h.get(0);
                
                // Espera arquivo aparecer e tenta reproduzir
                while (downloading && videoFile == null) {
                    File f = find(new File(savePath));
                    if (f != null && f.exists() && f.length() > 50000) {
                        videoFile = f;
                        log("📁 " + f.getName() + " (" + (f.length()/1024) + "KB)");
                        
                        handler.post(() -> {
                            // Lê direto do arquivo (sem servidor HTTP)
                            videoView.setVideoPath(videoFile.getAbsolutePath());
                            videoView.start();
                            log("▶️ Player iniciado");
                        });
                        break;
                    }
                    Thread.sleep(2000);
                }
                
            } catch (Exception e) {
                log("❌ " + e.getMessage());
            }
        }).start();
        
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (downloading && torrent != null && torrent.is_valid()) {
                    torrent_status ts = torrent.status();
                    statProgress.setText((int)(ts.getProgress() * 100) + "%");
                    long speed = ts.getDownload_rate();
                    statSpeed.setText(speed > 1048576 ? String.format("%.1f MB/s", speed/1048576.0) :
                        speed > 1024 ? String.format("%.1f KB/s", speed/1024.0) : speed + " B/s");
                    statPeers.setText(String.valueOf(ts.getNum_peers()));
                    bufferBar.setProgress((int)(ts.getProgress() * 100));
                }
                if (downloading) handler.postDelayed(this, 500);
            }
        });
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE);
        controlPanel.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        logScroll.setVisibility(View.GONE);
        log("⏹️ Parado");
    }
    
    private File find(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = find(f);
                    if (found != null) return found;
                } else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || 
                           f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) {
                    return f;
                }
            }
        }
        return null;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloading = false;
        if (session != null) session.stop();
    }
}
