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
import java.net.*;
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
        
        // Listener do ExoPlayer para debug
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
                log("❌ ExoPlayer ERRO: " + error.getErrorCodeName() + " - " + error.getMessage());
            }
        });
        
        log("══════ APP INICIADO ══════");
        log("📁 Pasta: " + savePath);
        log("📱 Android: " + android.os.Build.VERSION.SDK_INT);
        
        new Thread(() -> {
            try {
                log("🔄 Iniciando libtorrent...");
                session = new SessionManager();
                session.start();
                log("✅ Sessão torrent OK");
                log("🌐 DHT: " + (session.swig().is_dht_running() ? "ATIVO" : "OFF"));
            } catch (Exception e) {
                log("❌ Sessão: " + e.getMessage());
            }
        }).start();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
    }
    
    private void log(String msg) {
        String timestamp = sdf.format(new Date());
        String line = "[" + timestamp + "] " + msg;
        fullLog.append(line).append("\n");
        handler.post(() -> {
            logText.setText(fullLog.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
        android.util.Log.d("TorrentDebug", msg);
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:")) {
            log("⚠️ Magnet inválido!");
            return;
        }
        if (downloading) {
            log("⚠️ Já está baixando!");
            return;
        }
        
        downloading = true;
        videoFile = null;
        btnWatch.setVisibility(View.GONE);
        
        controlPanel.setVisibility(View.GONE);
        statsRow.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        logScroll.setVisibility(View.VISIBLE);
        
        log("══════ INICIANDO DOWNLOAD ══════");
        log("📡 Magnet: " + magnet.substring(0, Math.min(80, magnet.length())) + "...");
        
        new Thread(() -> {
            try {
                log("🔍 Parseando magnet...");
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(0);
                p.setMax_connections(200);
                
                log("📊 Flags: 9 (sequential + auto)");
                log("📊 Download limit: ILIMITADO");
                log("📊 Max connections: 200");
                
                byte_vector pr = new byte_vector();
                pr.add((byte)7);
                p.set_file_priorities(pr);
                log("📊 Prioridade arquivo: 7 (máxima)");
                
                log("📤 Enviando para sessão...");
                session.swig().async_add_torrent(p);
                log("✅ Magnet enviado!");
                
                log("⏳ Aguardando metadados (5s)...");
                Thread.sleep(5000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                log("📊 Torrents na sessão: " + h.size());
                
                if (h.size() > 0) {
                    torrent = h.get(0);
                    torrent_status ts = torrent.status();
                    log("✅ Torrent obtido!");
                    log("📊 Progresso: " + (int)(ts.getProgress()*100) + "%");
                    log("📊 Peers: " + ts.getNum_peers());
                    log("📊 Download rate: " + ts.getDownload_rate());
                    log("📊 Estado: " + ts.getState());
                    
                    // Lista arquivos
                    torrent_info ti = torrent.torrent_file();
                    if (ti != null) {
                        log("📁 Total arquivos: " + ti.num_files());
                        for (int i = 0; i < Math.min(5, ti.num_files()); i++) {
                            log("   📄 " + ti.files().file_name(i) + " (" + (ti.files().file_size(i)/1048576) + "MB)");
                        }
                    } else {
                        log("⚠️ Metadados ainda não carregados");
                    }
                } else {
                    log("❌ Nenhum torrent encontrado!");
                }
                
                // Procura arquivo
                log("🔍 Procurando arquivo de vídeo...");
                for (int i = 0; i < 60; i++) {
                    if (videoFile == null) {
                        videoFile = findVideo(new File(savePath));
                    }
                    if (videoFile != null && videoFile.exists()) {
                        long size = videoFile.length();
                        log("📁 Encontrado: " + videoFile.getName() + " (" + (size/1024) + "KB)");
                        if (size > 100000) {
                            log("✅ Arquivo pronto para reprodução!");
                            handler.post(() -> {
                                btnWatch.setVisibility(View.VISIBLE);
                                btnWatch.setText("🎬 ASSISTIR (" + (size/1048576) + "MB)");
                            });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
                
                if (videoFile == null || !videoFile.exists()) {
                    log("⚠️ Arquivo não encontrado após 60s");
                }
                
            } catch (Exception e) {
                downloading = false;
                log("❌ ERRO: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
        
        statsUpdater = new Runnable() {
            @Override
            public void run() {
                if (torrent != null && torrent.is_valid()) {
                    torrent_status ts = torrent.status();
                    int prog = (int)(ts.getProgress() * 100);
                    long speed = ts.getDownload_rate();
                    int peers = ts.getNum_peers();
                    String spd = speed > 1048576 ? String.format("%.1f MB/s", speed/1048576.0) :
                        speed > 1024 ? String.format("%.1f KB/s", speed/1024.0) : speed + " B/s";
                    
                    statProgress.setText(prog + "%");
                    statSpeed.setText(spd);
                    statPeers.setText(String.valueOf(peers));
                    bufferBar.setProgress(prog);
                    
                    if (prog > 0 && prog % 10 == 0) {
                        log("📥 " + prog + "% | " + spd + " | " + peers + " peers | Estado: " + ts.getState());
                    }
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(statsUpdater);
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) {
            log("❌ Arquivo não encontrado!");
            return;
        }
        
        log("══════ INICIANDO REPRODUÇÃO ══════");
        log("🎬 Arquivo: " + videoFile.getAbsolutePath());
        log("📊 Tamanho: " + (videoFile.length()/1048576) + "MB");
        log("📊 Existe: " + videoFile.exists());
        log("📊 Pode ler: " + videoFile.canRead());
        
        playerView.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        
        String uri = "file://" + videoFile.getAbsolutePath();
        log("🔗 URI: " + uri);
        
        MediaItem item = MediaItem.fromUri(uri);
        player.setMediaItem(item);
        player.prepare();
        player.play();
        log("▶️ Play chamado");
    }
    
    private void stop() {
        log("══════ PARANDO ══════");
        downloading = false;
        player.stop();
        player.clearMediaItems();
        handler.removeCallbacks(statsUpdater);
        playerView.setVisibility(View.GONE);
        controlPanel.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        logScroll.setVisibility(View.GONE);
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
        handler.removeCallbacks(statsUpdater);
        if (player != null) player.release();
        if (session != null) session.stop();
    }
}
