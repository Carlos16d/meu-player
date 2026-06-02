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
import androidx.core.content.FileProvider;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;

public class MainActivity extends AppCompatActivity {
    private LinearLayout loadingOverlay, controlPanel, statsRow;
    private TextView loadingTitle, loadingProgress, loadingSpeed, loadingPeers, loadingStatus;
    private TextView statProgress, statSpeed, statPeers;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnOpenVideo;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private boolean downloading = false;
    private File videoFile = null;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statsUpdater;
    private Runnable fileWatcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        loadingOverlay = findViewById(R.id.loading_overlay);
        controlPanel = findViewById(R.id.control_panel);
        statsRow = findViewById(R.id.stats_row);
        loadingTitle = findViewById(R.id.loading_title);
        loadingProgress = findViewById(R.id.loading_progress);
        loadingSpeed = findViewById(R.id.loading_speed);
        loadingPeers = findViewById(R.id.loading_peers);
        loadingStatus = findViewById(R.id.loading_status);
        statProgress = findViewById(R.id.stat_progress);
        statSpeed = findViewById(R.id.stat_speed);
        statPeers = findViewById(R.id.stat_peers);
        bufferBar = findViewById(R.id.buffer_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnOpenVideo = findViewById(R.id.btn_open_video);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                showLog("✅ UDP iniciado");
            } catch (Exception e) {
                showLog("❌ Erro: " + e.getMessage());
            }
        }).start();
        
        btnPlay.setOnClickListener(v -> startStream());
        btnStop.setOnClickListener(v -> stopStream());
        btnOpenVideo.setOnClickListener(v -> openVideo());
        
        showLog("📱 App pronto");
    }
    
    private void showLog(String msg) {
        handler.post(() -> {
            if (loadingStatus != null) loadingStatus.setText(msg);
        });
    }
    
    private void openVideo() {
        if (videoFile != null && videoFile.exists()) {
            try {
                Uri uri = FileProvider.getUriForFile(this, 
                    "com.meuapp.player.fileprovider", videoFile);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "video/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
                showLog("▶️ Abrindo no player externo...");
            } catch (Exception e) {
                // Fallback: tenta abrir direto
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.fromFile(videoFile), "video/*");
                startActivity(intent);
            }
        }
    }
    
    private void startStream() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:")) {
            Toast.makeText(this, "Cole um magnet link!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (downloading) return;
        downloading = true;
        videoFile = null;
        
        loadingOverlay.setVisibility(View.VISIBLE);
        controlPanel.setVisibility(View.GONE);
        statsRow.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnOpenVideo.setVisibility(View.GONE);
        
        showLog("⏳ Iniciando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                
                string_vector trackers = new string_vector();
                trackers.add("udp://tracker.opentrackr.org:1337/announce");
                trackers.add("udp://tracker.openbittorrent.com:6969/announce");
                trackers.add("udp://open.stealth.si:80/announce");
                trackers.add("udp://tracker.torrent.eu.org:451/announce");
                trackers.add("udp://explodie.org:6969/announce");
                p.setTrackers(trackers);
                
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(3 * 1024 * 1024);
                p.setMax_connections(200);
                
                byte_vector priorities = new byte_vector();
                priorities.add((byte)7);
                p.set_file_priorities(priorities);
                
                session.swig().async_add_torrent(p);
                
                showLog("📡 Aguardando metadados...");
                Thread.sleep(4000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() > 0) {
                    torrent = handles.get(0);
                    showLog("✅ Conectado! " + torrent.status().getNum_peers() + " peers");
                }
                
                handler.post(() -> startFileWatcher());
                
            } catch (Exception e) {
                downloading = false;
                showLog("❌ Erro: " + e.getMessage());
            }
        }).start();
        
        statsUpdater = new Runnable() {
            @Override
            public void run() {
                if (torrent != null && torrent.is_valid()) {
                    int prog = (int)(torrent.status().getProgress() * 100);
                    long speed = torrent.status().getDownload_rate();
                    int peers = torrent.status().getNum_peers();
                    
                    String speedStr = speed > 1048576 ? 
                        String.format("%.1f MB/s", speed / 1048576.0) :
                        speed > 1024 ? String.format("%.1f KB/s", speed / 1024.0) :
                        speed + " B/s";
                    
                    statProgress.setText(prog + "%");
                    statSpeed.setText(speedStr);
                    statPeers.setText(String.valueOf(peers));
                    loadingProgress.setText(prog + "%");
                    loadingSpeed.setText(speedStr);
                    loadingPeers.setText(peers + " peers");
                    bufferBar.setProgress(prog);
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(statsUpdater);
    }
    
    private void startFileWatcher() {
        fileWatcher = new Runnable() {
            @Override
            public void run() {
                if (!downloading) return;
                
                if (videoFile == null) {
                    videoFile = findVideoFile(new File(savePath));
                }
                
                if (videoFile != null && videoFile.exists()) {
                    long size = videoFile.length();
                    showLog("📁 " + videoFile.getName() + " (" + (size/1024) + "KB)");
                    
                    if (size > 50000) {
                        handler.post(() -> {
                            loadingOverlay.setVisibility(View.GONE);
                            btnOpenVideo.setVisibility(View.VISIBLE);
                            showLog("✅ Pronto! Clique para assistir");
                        });
                        return;
                    }
                }
                
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(fileWatcher);
    }
    
    private void stopStream() {
        downloading = false;
        handler.removeCallbacks(statsUpdater);
        handler.removeCallbacks(fileWatcher);
        loadingOverlay.setVisibility(View.GONE);
        controlPanel.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnOpenVideo.setVisibility(View.GONE);
        showLog("⏹️ Parado");
    }
    
    private File findVideoFile(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideoFile(f);
                    if (found != null) return found;
                } else {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".webm")) {
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
        handler.removeCallbacks(statsUpdater);
        handler.removeCallbacks(fileWatcher);
        if (session != null) session.stop();
    }
}
