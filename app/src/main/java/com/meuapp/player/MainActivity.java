package com.meuapp.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.trackselection.*;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.util.*;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "TorrentStream";
    
    private PlayerView playerView;
    private SimpleExoPlayer player;
    private TextView statusText, progressText, titleText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private LinearLayout glassPanel;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private volatile boolean sessionReady = false;
    
    private long lastDownloaded = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
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
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        initializePlayer();
        
        AlphaAnimation glow = new AlphaAnimation(0.6f, 1.0f);
        glow.setDuration(2000);
        glow.setRepeatMode(Animation.REVERSE);
        glow.setRepeatCount(Animation.INFINITE);
        titleText.startAnimation(glow);
        
        initializeSession();
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        log("Pronto para streaming");
    }
    
    private void initializePlayer() {
        player = new SimpleExoPlayer.Builder(this)
            .setTrackSelector(new DefaultTrackSelector(this))
            .build();
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setKeepScreenOn(true);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                boolean hasAudioTracks = false;
                boolean hasSubtitleTracks = false;
                
                for (Tracks.Group group : tracks.getGroups()) {
                    if (group.getMediaTrackGroup().type == C.TRACK_TYPE_AUDIO) {
                        hasAudioTracks = true;
                    }
                    if (group.getMediaTrackGroup().type == C.TRACK_TYPE_TEXT) {
                        hasSubtitleTracks = true;
                    }
                }
                
                String msg = "▶️ Reproduzindo";
                if (hasAudioTracks) msg += " [🎵 Multi-áudio]";
                if (hasSubtitleTracks) msg += " [📝 Legendas]";
                
                final String finalMsg = msg;
                handler.post(() -> log(finalMsg));
            }
            
            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        handler.post(() -> {
                            spinnerBar.setVisibility(View.VISIBLE);
                            loadingOverlay.setVisibility(View.VISIBLE);
                        });
                        break;
                    case Player.STATE_READY:
                        handler.post(() -> {
                            spinnerBar.setVisibility(View.GONE);
                            loadingOverlay.setVisibility(View.GONE);
                        });
                        break;
                }
            }
        });
    }
    
    private void initializeSession() {
        new Thread(() -> {
            try {
                log("🔄 Inicializando rede P2P...");
                
                String version = libtorrent.version();
                Log.d(TAG, "libtorrent version: " + version);
                
                session = new SessionManager();
                Thread.sleep(2000);
                
                session_handle sh = session.swig();
                if (sh != null) {
                    settings_pack sp = new settings_pack();
                    
                    // Configurações que FUNCIONAM nesta versão
                    sp.set_int(settings_pack.int_types.connections_limit.swigValue(), 50);
                    sp.set_int(settings_pack.int_types.unchoke_slots_limit.swigValue(), 10);
                    sp.set_int(settings_pack.int_types.active_downloads.swigValue(), 3);
                    sp.set_int(settings_pack.int_types.active_seeds.swigValue(), 5);
                    sp.set_int(settings_pack.int_types.active_limit.swigValue(), 20);
                    sp.set_int(settings_pack.int_types.request_timeout.swigValue(), 3);
                    sp.set_int(settings_pack.int_types.peer_timeout.swigValue(), 30);
                    sp.set_int(settings_pack.int_types.max_out_request_queue.swigValue(), 5000);
                    
                    sp.set_bool(settings_pack.bool_types.strict_end_game_mode.swigValue(), true);
                    sp.set_bool(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true);
                    sp.set_bool(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true);
                    
                    sh.apply_settings(sp);
                    
                    sessionReady = true;
                    log("✅ Rede P2P pronta!");
                }
            } catch (Exception e) { 
                Log.e(TAG, "Erro sessão", e);
                log("❌ Erro: " + e.getMessage());
            }
        }).start();
    }
    
    private void log(String msg) {
        handler.post(() -> statusText.setText(msg));
    }
    
    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }
    
    private void startServer() {
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 50);
                server.setReuseAddress(true);
                Log.d(TAG, "Servidor HTTP na porta 8080");
                
                while (!Thread.interrupted()) {
                    try { 
                        Socket c = server.accept();
                        c.setSoTimeout(5000);
                        new Thread(() -> handleHttp(c)).start(); 
                    } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {
                Log.e(TAG, "Erro servidor", e);
            }
        }).start();
    }
    
    private void handleHttp(Socket c) {
        try {
            c.setSoTimeout(3000);
            OutputStream o = c.getOutputStream();
            BufferedReader i = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String r = i.readLine();
            
            if (r == null || !r.contains("/video")) { 
                o.write("HTTP/1.1 404\r\n\r\n".getBytes()); 
                o.flush(); 
                c.close(); 
                return; 
            }
            
            String corsHeaders = "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Range\r\n\r\n";
            
            if (r.startsWith("OPTIONS")) {
                o.write(("HTTP/1.1 200 OK\r\n" + corsHeaders + "\r\n").getBytes());
                o.flush();
                c.close();
                return;
            }
            
            long start = 0, end = -1;
            String l;
            while ((l = i.readLine()) != null && !l.isEmpty()) {
                if (l.toLowerCase().startsWith("range:")) {
                    String x = l.substring(6).trim().replace("bytes=", "");
                    String[] p = x.split("-");
                    start = Long.parseLong(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) end = Long.parseLong(p[1]);
                }
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 4096) {
                o.write(("HTTP/1.1 503\r\n" + corsHeaders + "Retry-After: 1\r\n\r\n").getBytes()); 
                o.flush(); 
                c.close(); 
                return;
            }
            
            long len = vf.length();
            if (end == -1 || end >= len) end = len - 1;
            
            String mime = "video/mp4";
            String name = vf.getName().toLowerCase();
            if (name.endsWith(".mkv")) mime = "video/x-matroska";
            else if (name.endsWith(".webm")) mime = "video/webm";
            
            int chunkSize = Math.min((int)(end - start + 1), 2097152);
            
            byte[] b = new byte[chunkSize];
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(start);
            int t = raf.read(b);
            raf.close();
            
            int retries = 0;
            while (t < 8192 && retries < 15 && downloading) {
                Thread.sleep(300);
                if (!vf.exists() || vf.length() <= start + t) continue;
                raf = new RandomAccessFile(vf, "r");
                raf.seek(start);
                t = raf.read(b);
                raf.close();
                retries++;
            }
            
            if (t <= 1024) { 
                o.write(("HTTP/1.1 503\r\n" + corsHeaders + "Retry-After: 1\r\n\r\n").getBytes()); 
                o.flush(); 
                c.close(); 
                return; 
            }
            
            String resp = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "Content-Range: bytes " + start + "-" + (start+t-1) + "/" + len + "\r\n" +
                "Content-Length: " + t + "\r\n" +
                "Accept-Ranges: bytes\r\n" +
                corsHeaders + "\r\n";
            
            o.write(resp.getBytes()); 
            o.write(b, 0, t); 
            o.flush(); 
            c.close();
            
        } catch (Exception ex) { 
            try { c.close(); } catch (IOException ex2) {}
        }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        
        if (!magnet.startsWith("magnet:") || downloading) return;
        if (!sessionReady || session == null) {
            log("❌ Aguardando rede P2P...");
            return;
        }
        
        File dir = new File(savePath);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) deleteRecursive(f);
            }
        }
        new File(savePath).mkdirs();
        
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        lastDownloaded = 0;
        
        handler.post(() -> {
            glassPanel.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            spinnerBar.setVisibility(View.VISIBLE);
            loadingOverlay.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
            playerView.setVisibility(View.GONE);
            titleText.setText("⬇️ Conectando...");
            bufferBar.setProgress(0);
        });
        
        log("🔍 Buscando peers...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setDownload_limit(0);
                p.setUpload_limit(0);
                
                byte_vector pr = new byte_vector();
                pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrentHandle = h.get(0);
                    log("✅ Torrent adicionado");
                    
                    // Aguarda arquivo aparecer
                    while (downloading) {
                        File f = findVideoFile(new File(savePath));
                        
                        if (f != null && f.length() > 10485760 && isValidVideoFile(f)) {
                            videoFile = f;
                            long downloadedMB = f.length() / 1048576;
                            
                            handler.post(() -> {
                                bufferBar.setProgress(Math.min((int)((f.length() * 100) / 276134947L), 100));
                                progressText.setText(downloadedMB + " MB baixados");
                                
                                if (btnWatch.getVisibility() != View.VISIBLE) {
                                    spinnerBar.setVisibility(View.GONE);
                                    loadingOverlay.setVisibility(View.GONE);
                                    btnWatch.setVisibility(View.VISIBLE);
                                    btnWatch.setAlpha(0f);
                                    btnWatch.animate().alpha(1f).setDuration(500);
                                    titleText.setText("🎬 Pronto para streaming!");
                                    log("✅ " + downloadedMB + "MB disponível!");
                                }
                            });
                            break;
                        }
                        Thread.sleep(1000);
                    }
                }
            } catch (Exception e2) {
                Log.e(TAG, "Erro download", e2);
                log("❌ " + e2.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private File findVideoFile(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) { 
                    File found = findVideoFile(f); 
                    if (found != null) return found; 
                } else if (f.getName().toLowerCase().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }
    
    private boolean isValidVideoFile(File f) {
        if (f == null || !f.exists() || f.length() < 4096) return false;
        try {
            byte[] header = new byte[8];
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            raf.read(header);
            raf.close();
            
            return (header[4]=='f' && header[5]=='t' && header[6]=='y' && header[7]=='p') ||
                   ((header[0]&0xFF)==0x1A && header[1]==0x45 && header[2]==(byte)0xDF && header[3]==(byte)0xA3) ||
                   (header[0]=='R' && header[1]=='I' && header[2]=='F' && header[3]=='F') ||
                   (header[0]==0x00 && header[1]==0x00 && header[2]==0x00 && header[3]=='m');
        } catch (Exception e) {
            return false;
        }
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { 
            log("❌ Arquivo não encontrado"); 
            return; 
        }
        
        handler.post(() -> { 
            glassPanel.setVisibility(View.GONE);
            btnWatch.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);
            titleText.setText("▶️ Reproduzindo...");
        });
        
        Uri videoUri = Uri.parse("http://127.0.0.1:8080/video");
        
        DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(10000);
        
        ProgressiveMediaSource.Factory mediaSourceFactory = 
            new ProgressiveMediaSource.Factory(dataSourceFactory);
        
        MediaSource mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(videoUri));
        
        player.setMediaSource(mediaSource);
        player.prepare();
        player.setPlayWhenReady(true);
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        
        playerView.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE); 
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); 
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        glassPanel.setVisibility(View.GONE);
        
        titleText.setText("🎬 Torrent Streaming");
        progressText.setText("Pronto para começar");
        log("⏹️ Parado");
        
        if (torrentHandle != null && session != null && session.swig() != null) {
            try { 
                session.swig().remove_torrent(torrentHandle); 
            } catch (Exception e) {}
            torrentHandle = null;
        }
    }
    
    @Override 
    protected void onDestroy() {
        stop();
        if (player != null) {
            player.release();
            player = null;
        }
        if (session != null) {
            try { session.stop(); } catch (Exception e) {}
        }
        super.onDestroy();
    }
}