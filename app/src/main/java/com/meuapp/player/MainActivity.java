package com.meuapp.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.model.TorrentInfo;
import com.meuapp.player.server.StreamServer;

import org.libtorrent4j.swig.torrent_handle;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private SurfaceView videoSurface;
    private SurfaceHolder surfaceHolder;
    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;
    private TextView statusText, debugText, timeText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnTorrent, btnStop, btnWatch, btnSkip20;
    private LinearLayout playerControls, centerControls, audioMenu, subtitleMenu;
    private ScrollView audioScroll, subtitleScroll;
    private Button btnPlayPause, btnSeekBack, btnSeekForward, btnAudio, btnSubtitle;
    private SeekBar seekBar;
    private boolean isTracking = false;
    
    private String savePath;
    private TorrentEngine torrentEngine;
    private StreamServer streamServer;
    private volatile File videoFile;
    private Handler handler;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();
    private static final int PICK_TORRENT = 100;
    private Runnable timeUpdater;
    private boolean isPlaying = false;
    private boolean surfaceReady = false;
    private String pendingUrl = null;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoSurface = findViewById(R.id.video_surface);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        timeText = findViewById(R.id.time_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnTorrent = findViewById(R.id.btn_torrent);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        btnSkip20 = findViewById(R.id.btn_skip_20);
        playerControls = findViewById(R.id.player_controls);
        centerControls = findViewById(R.id.center_controls);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnSeekBack = findViewById(R.id.btn_seek_back);
        btnSeekForward = findViewById(R.id.btn_seek_forward);
        btnAudio = findViewById(R.id.btn_audio);
        btnSubtitle = findViewById(R.id.btn_subtitle);
        seekBar = findViewById(R.id.seek_bar);
        audioScroll = findViewById(R.id.audio_scroll);
        subtitleScroll = findViewById(R.id.subtitle_scroll);
        audioMenu = findViewById(R.id.audio_menu);
        subtitleMenu = findViewById(R.id.subtitle_menu);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        // StreamServer
        streamServer = new StreamServer();
        try { streamServer.start(); debug("🌐 Servidor HTTP iniciado"); } catch (IOException e) { debug("❌ " + e.getMessage()); }
        
        // TorrentEngine
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            @Override public void onReady() { debug("✅ Engine pronto"); }
            @Override public void onError(String error) { debug("❌ " + error); }
            @Override public void onProgress(TorrentInfo info) {
                handler.post(() -> bufferBar.setProgress(info.progress));
            }
            @Override public void onStreamReady(torrent_handle handle, String sp) {
                streamServer.setTorrentInfo(handle);
                debug("🎬 Streaming liberado!");
            }
            @Override public void onStatus(String status) { debug(status); }
            @Override public void onLog(String log) { debug(log); }
        });
        torrentEngine.start();
        
        // VLC
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=3000");
        options.add("--file-caching=2000");
        options.add("--avcodec-hw=any");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Opening: debug("[VLC] 🔄 Abrindo..."); break;
                case MediaPlayer.Event.Playing: isPlaying = true; handler.post(() -> { spinnerBar.setVisibility(View.GONE); btnPlayPause.setText("⏸"); handler.post(timeUpdater); }); debug("[VLC] ▶ Tocando!"); break;
                case MediaPlayer.Event.Paused: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.Stopped: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.Buffering: handler.post(() -> spinnerBar.setVisibility(View.VISIBLE)); debug("[VLC] 🔃 Buffering..."); break;
                case MediaPlayer.Event.EndReached: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.EncounteredError: debug("[VLC] ❌ Erro!"); break;
            }
        });
        
        timeUpdater = () -> {
            if (vlcPlayer != null && isPlaying) {
                long time = vlcPlayer.getTime(), length = vlcPlayer.getLength();
                if (time >= 0 && length > 0) {
                    timeText.setText(formatTime(time) + " / " + formatTime(length));
                    if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                }
            }
            handler.postDelayed(timeUpdater, 500);
        };
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { 
                surfaceHolder = h; 
                surfaceReady = true; 
                debug("✅ Superfície pronta!");
                if (pendingUrl != null) { 
                    String url = pendingUrl;
                    pendingUrl = null;
                    playWithVlc(url); 
                } 
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; surfaceHolder = null; }
        });
        
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer != null) { if (isPlaying) vlcPlayer.pause(); else vlcPlayer.play(); } });
        btnSeekBack.setOnClickListener(v -> seekRelative(-10000));
        btnSeekForward.setOnClickListener(v -> seekRelative(10000));
        btnSkip20.setOnClickListener(v -> { if (vlcPlayer != null && videoFile != null) vlcPlayer.setTime(20*60*1000); });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && vlcPlayer.getLength() > 0) vlcPlayer.setTime((long)(vlcPlayer.getLength() * p / 100.0)); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        debug("=== TORRENT STREAM ===");
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        debug("📱 Pronto");
    }
    
    private void seekRelative(long d) { if (vlcPlayer != null && vlcPlayer.getLength() > 0) vlcPlayer.setTime(Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + d))); }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        audioMenu.removeAllViews();
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
        int current = vlcPlayer.getAudioTrack();
        if (tracks != null) {
            debug("🎵 " + tracks.length + " áudios");
            for (MediaPlayer.TrackDescription t : tracks) if (t.id >= 0) {
                TextView tv = new TextView(this); tv.setText("🎵 " + t.name + (t.id == current ? " ✓" : ""));
                tv.setTextColor(t.id == current ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16,12,16,12);
                final int id = t.id; tv.setOnClickListener(v -> { vlcPlayer.setAudioTrack(id); audioScroll.setVisibility(View.GONE); }); audioMenu.addView(tv);
            }
        }
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (vlcPlayer == null) return;
        subtitleMenu.removeAllViews();
        TextView off = new TextView(this); off.setText("📝 Desligado" + (vlcPlayer.getSpuTrack() == -1 ? " ✓" : ""));
        off.setTextColor(vlcPlayer.getSpuTrack() == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); off.setTextSize(12); off.setPadding(16,12,16,12);
        off.setOnClickListener(v -> { vlcPlayer.setSpuTrack(-1); subtitleScroll.setVisibility(View.GONE); }); subtitleMenu.addView(off);
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
        if (tracks != null) {
            debug("📝 " + tracks.length + " legendas");
            for (MediaPlayer.TrackDescription t : tracks) if (t.id >= 0) {
                TextView tv = new TextView(this); tv.setText("📝 " + t.name + (t.id == vlcPlayer.getSpuTrack() ? " ✓" : ""));
                tv.setTextColor(t.id == vlcPlayer.getSpuTrack() ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16,12,16,12);
                final int id = t.id; tv.setOnClickListener(v -> { vlcPlayer.setSpuTrack(id); subtitleScroll.setVisibility(View.GONE); }); subtitleMenu.addView(tv);
            }
        }
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); audioScroll.setVisibility(View.GONE);
    }
    
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms/1000); return (s/60) + ":" + (s%60 < 10 ? "0" : "") + (s%60); }
    
    private void playWithVlc(String url) {
        if (!surfaceReady || surfaceHolder == null) { pendingUrl = url; debug("⏳ Aguardando superfície..."); return; }
        try {
            debug("[VLC] 🎬 " + url);
            vlcPlayer.getVLCVout().setVideoSurface(surfaceHolder.getSurface(), null);
            vlcPlayer.getVLCVout().setWindowSize(videoSurface.getWidth(), videoSurface.getHeight());
            vlcPlayer.getVLCVout().attachViews();
            Media m = new Media(libVLC, Uri.parse(url)); m.setHWDecoderEnabled(true, true);
            m.addOption(":network-caching=3000"); m.addOption(":file-caching=2000");
            m.addOption(":avcodec-hw=any");
            vlcPlayer.setMedia(m); m.release(); vlcPlayer.play();
            handler.post(() -> { playerControls.setVisibility(View.VISIBLE); centerControls.setVisibility(View.VISIBLE); btnSkip20.setVisibility(View.VISIBLE); });
            debug("[VLC] ▶ Play executado");
        } catch (Exception e) { debug("[VLC] ❌ " + e.getMessage()); }
    }
    
    @Override protected void onActivityResult(int r, int res, Intent data) {
        super.onActivityResult(r, res, data);
        if (r == PICK_TORRENT && res == RESULT_OK && data != null && data.getData() != null) try {
            InputStream is = getContentResolver().openInputStream(data.getData());
            File tf = new File(savePath, "torrent_file.torrent"); FileOutputStream fos = new FileOutputStream(tf);
            byte[] b = new byte[8192]; int l; while ((l = is.read(b)) > 0) fos.write(b, 0, l); fos.close(); is.close();
            startDownload(tf.getAbsolutePath());
        } catch (Exception e) { debug("❌ " + e.getMessage()); }
    }
    
    private void debug(String msg) { String line = "[" + sdf.format(new Date()) + "] " + msg + "\n"; Log.d("TS", msg); debugLog.append(line); handler.post(() -> { statusText.setText(msg); debugText.setText(debugLog.toString()); }); }
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:")) startDownload(m); }
    
    private void startDownload(String source) {
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE); });
        debug("⏳ Iniciando download...");
        new Thread(() -> {
            torrentEngine.startDownload(source, savePath);
            
            for (int i = 0; i < 120; i++) {
                File f = find(new File(savePath));
                if (f != null && f.length() > 5242880) {
                    videoFile = f;
                    streamServer.setVideoFile(f);
                    debug("📁 " + f.getName() + " (" + (f.length()/1048576) + "MB)");
                    handler.post(() -> { btnWatch.setText("🎬 ASSISTIR"); btnWatch.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.GONE); });
                    break;
                }
                try { Thread.sleep(1000); } catch (Exception e) {}
            }
        }).start();
    }
    
    private void watch() { 
        if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não encontrado"); return; } 
        debug("▶️ VLC: " + videoFile.getName() + " (" + (videoFile.length()/1048576) + "MB)"); 
        handler.post(() -> { 
            videoSurface.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE); 
            btnSkip20.setVisibility(View.VISIBLE); 
            spinnerBar.setVisibility(View.VISIBLE);
            if (surfaceReady && surfaceHolder != null) {
                playWithVlc("http://127.0.0.1:8080/video");
            } else {
                pendingUrl = "http://127.0.0.1:8080/video";
                debug("⏳ Aguardando superfície...");
            }
        }); 
    }
    
    private void stop() { 
        if (vlcPlayer != null) vlcPlayer.stop(); 
        videoSurface.setVisibility(View.GONE); playerControls.setVisibility(View.GONE); centerControls.setVisibility(View.GONE); 
        audioScroll.setVisibility(View.GONE); subtitleScroll.setVisibility(View.GONE); 
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE); 
        handler.removeCallbacks(timeUpdater); 
        torrentEngine.stop();
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File found = find(f); if (found != null) return found; } else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f; } return null; }
    
    @Override protected void onDestroy() { stop(); if (streamServer != null) streamServer.stop(); torrentEngine.destroy(); if (vlcPlayer != null) vlcPlayer.release(); if (libVLC != null) libVLC.release(); super.onDestroy(); }
}