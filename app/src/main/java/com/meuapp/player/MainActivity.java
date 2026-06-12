package com.meuapp.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.meuapp.player.buffer.SmartBuffer;
import com.meuapp.player.model.StreamInfo;
import com.meuapp.player.player.VlcPlayerManager;
import com.meuapp.player.server.HttpStreamServer;
import com.meuapp.player.torrent.TorrentSession;
import com.meuapp.player.torrent.TorrentStreamer;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private SurfaceView videoSurface;
    private TextView statusText, debugText, timeText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnTorrent, btnStop, btnWatch, btnSkip20;
    private LinearLayout playerControls, centerControls, audioMenu, subtitleMenu;
    private ScrollView audioScroll, subtitleScroll;
    private Button btnPlayPause, btnSeekBack, btnSeekForward, btnAudio, btnSubtitle;
    private SeekBar seekBar;
    private boolean isTracking = false;
    
    private StreamInfo streamInfo;
    private TorrentSession torrentSession;
    private TorrentStreamer torrentStreamer;
    private HttpStreamServer httpServer;
    private VlcPlayerManager vlcPlayer;
    private SmartBuffer smartBuffer;
    
    private Handler handler;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();
    private static final int PICK_TORRENT = 100;
    private String savePath;
    private boolean videoPlaying = false;
    private Runnable timeUpdater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        streamInfo = new StreamInfo();
        
        torrentSession = new TorrentSession(streamInfo, new TorrentSession.SessionCallback() {
            @Override public void onMetadataReady() { handler.post(() -> torrentStreamer.preload()); }
            @Override public void onError(String error) { debug(error); }
            @Override public void onLog(String msg) { debug(msg); }
        });
        
        torrentStreamer = new TorrentStreamer(torrentSession, streamInfo, savePath, new TorrentStreamer.StreamerCallback() {
            @Override public void onReady() {
                handler.post(() -> {
                    btnWatch.setText("🎬 ASSISTIR");
                    btnWatch.setVisibility(View.VISIBLE);
                    bufferBar.setVisibility(View.GONE);
                });
            }
            @Override public void onProgress(String msg) { handler.post(() -> statusText.setText(msg)); }
            @Override public void onLog(String msg) { debug(msg); }
        });
        
        smartBuffer = new SmartBuffer(torrentStreamer, streamInfo);
        httpServer = new HttpStreamServer(8080, streamInfo);
        
        vlcPlayer = new VlcPlayerManager(this, streamInfo, new VlcPlayerManager.PlayerCallback() {
            @Override public void onPlaying() {
                handler.post(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    btnPlayPause.setText("⏸");
                    playerControls.setVisibility(View.VISIBLE);
                    centerControls.setVisibility(View.VISIBLE);
                    btnSkip20.setVisibility(View.VISIBLE);
                    videoPlaying = true;
                    smartBuffer.enable();
                });
            }
            @Override public void onPaused() { handler.post(() -> btnPlayPause.setText("▶")); }
            @Override public void onStopped() { handler.post(() -> btnPlayPause.setText("▶")); }
            @Override public void onBuffering() { handler.post(() -> spinnerBar.setVisibility(View.VISIBLE)); }
            @Override public void onTimeChanged(long time, long length) {
                if (length > 0 && !torrentStreamer.isPreloading()) {
                    streamInfo.videoDurationMs = length;
                    if (!streamInfo.minuteAppeared) {
                        streamInfo.minuteAppeared = true;
                        torrentSession.disableSequential();
                        torrentStreamer.disableSequential();
                        smartBuffer.enable();
                        debug("🔓 Sequential OFF - minutagem OK");
                    }
                    handler.post(() -> {
                        timeText.setText(formatTime(time) + " / " + formatTime(length));
                        if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                    });
                    smartBuffer.checkAndBuffer(time);
                }
            }
        });
        
        timeUpdater = () -> {
            if (vlcPlayer != null && vlcPlayer.isPlaying() && !torrentStreamer.isPreloading()) {
                long time = vlcPlayer.getTime();
                long length = vlcPlayer.getLength();
                if (length > 0) {
                    streamInfo.videoDurationMs = length;
                    if (!streamInfo.minuteAppeared) {
                        streamInfo.minuteAppeared = true;
                        torrentSession.disableSequential();
                        torrentStreamer.disableSequential();
                        smartBuffer.enable();
                        debug("🔓 Sequential OFF - minutagem OK");
                    }
                    timeText.setText(formatTime(time) + " / " + formatTime(length));
                    if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                    smartBuffer.checkAndBuffer(time);
                }
            }
            handler.postDelayed(timeUpdater, 200);
        };
        
        torrentSession.start();
        httpServer.start();
        debug("=== STREAM MODULAR ===");
    }
    
    private void initViews() {
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
        
        btnPlay.setOnClickListener(v -> startDownload());
        btnTorrent.setOnClickListener(v -> pickTorrentFile());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer != null) { if (vlcPlayer.isPlaying()) vlcPlayer.pause(); else vlcPlayer.resume(); } });
        btnSeekBack.setOnClickListener(v -> seekRelative(-10000));
        btnSeekForward.setOnClickListener(v -> seekRelative(10000));
        btnSkip20.setOnClickListener(v -> seekTo(20 * 60 * 1000));
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && vlcPlayer.getLength() > 0) seekTo(vlcPlayer.getLength() * p / 100); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
    }
    
    private void startDownload() {
        String m = magnetInput.getText().toString().trim();
        if (m.startsWith("magnet:") && streamInfo != null) {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
            streamInfo.reset();
            torrentStreamer.reset();
            torrentSession.startDownload(m, savePath);
        }
    }
    
    private void pickTorrentFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_TORRENT);
    }
    
    private void watch() {
        if (streamInfo.videoFile == null || !streamInfo.videoFile.exists()) { debug("❌ Aguarde"); return; }
        debug("▶️ Iniciando player...");
        smartBuffer.enable();
        handler.post(() -> {
            try {
                videoSurface.setVisibility(View.VISIBLE);
                btnWatch.setVisibility(View.GONE);
                spinnerBar.setVisibility(View.VISIBLE);
                videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
                    @Override public void surfaceCreated(SurfaceHolder holder) { vlcPlayer.attachToSurface(videoSurface); vlcPlayer.play("http://127.0.0.1:8080/video"); handler.post(timeUpdater); }
                    @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {}
                    @Override public void surfaceDestroyed(SurfaceHolder holder) {}
                });
                if (videoSurface.getHolder().getSurface().isValid()) { vlcPlayer.attachToSurface(videoSurface); vlcPlayer.play("http://127.0.0.1:8080/video"); handler.post(timeUpdater); }
            } catch (Exception e) { debug("❌ " + e.getMessage()); }
        });
    }
    
    private void seekTo(long timeMs) {
        if (vlcPlayer == null || torrentStreamer.isPreloading()) return;
        vlcPlayer.seekTo(timeMs);
        int piece = streamInfo.timeToPiece(timeMs);
        if (piece >= 0) {
            spinnerBar.setVisibility(View.VISIBLE);
            debug("🎯 Seek → p" + (piece-1) + "-" + piece + "-" + (piece+1));
            new Thread(() -> {
                boolean ok = torrentStreamer.seekToPiece(piece, 20000);
                handler.post(() -> { spinnerBar.setVisibility(View.GONE); debug(ok ? "✅ OK" : "❌ Off"); });
            }).start();
        }
    }
    
    private void seekRelative(long delta) { if (vlcPlayer != null && vlcPlayer.getLength() > 0) seekTo(Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + delta))); }
    
    private void stop() {
        if (vlcPlayer != null) vlcPlayer.stop();
        torrentSession.stop();
        torrentStreamer.reset();
        smartBuffer.disable();
        videoPlaying = false;
        videoSurface.setVisibility(View.GONE);
        playerControls.setVisibility(View.GONE);
        centerControls.setVisibility(View.GONE);
        audioScroll.setVisibility(View.GONE); subtitleScroll.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        handler.removeCallbacks(timeUpdater);
    }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        org.videolan.libvlc.MediaPlayer.TrackDescription[] t = vlcPlayer.getAudioTracks();
        int c = vlcPlayer.getAudioTrack();
        audioMenu.removeAllViews();
        debug("🎵 Áudios: " + (t != null ? t.length : 0));
        if (t != null) for (org.videolan.libvlc.MediaPlayer.TrackDescription tr : t) {
            if (tr.id >= 0) {
                TextView tv = new TextView(this); tv.setText("🎵 " + tr.name + (tr.id == c ? " ✓" : "")); tv.setTextColor(tr.id == c ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16,12,16,12);
                final int id = tr.id; tv.setOnClickListener(v -> { vlcPlayer.setAudioTrack(id); audioScroll.setVisibility(View.GONE); });
                audioMenu.addView(tv);
            }
        }
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (vlcPlayer == null) return;
        org.videolan.libvlc.MediaPlayer.TrackDescription[] t = vlcPlayer.getSubtitleTracks();
        int c = vlcPlayer.getSubtitleTrack();
        subtitleMenu.removeAllViews();
        debug("📝 Legendas: " + (t != null ? t.length : 0));
        TextView off = new TextView(this); off.setText("📝 Desligado" + (c == -1 ? " ✓" : "")); off.setTextColor(c == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); off.setTextSize(12); off.setPadding(16,12,16,12);
        off.setOnClickListener(v -> { vlcPlayer.setSubtitleTrack(-1); subtitleScroll.setVisibility(View.GONE); });
        subtitleMenu.addView(off);
        if (t != null) for (org.videolan.libvlc.MediaPlayer.TrackDescription tr : t) {
            if (tr.id >= 0) {
                TextView tv = new TextView(this); tv.setText("📝 " + tr.name + (tr.id == c ? " ✓" : "")); tv.setTextColor(tr.id == c ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16,12,16,12);
                final int id = tr.id; tv.setOnClickListener(v -> { vlcPlayer.setSubtitleTrack(id); subtitleScroll.setVisibility(View.GONE); });
                subtitleMenu.addView(tv);
            }
        }
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); audioScroll.setVisibility(View.GONE);
    }
    
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms / 1000); return (s/60) + ":" + String.format("%02d", s%60); }
    
    private void debug(String msg) { String line = "[" + sdf.format(new Date()) + "] " + msg + "\n"; android.util.Log.d("TS", msg); debugLog.append(line); handler.post(() -> { statusText.setText(msg); debugText.setText(debugLog.toString()); }); }
    
    @Override protected void onActivityResult(int r, int res, Intent data) {
        super.onActivityResult(r, res, data);
        if (r == PICK_TORRENT && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream is = getContentResolver().openInputStream(data.getData());
                File tf = new File(savePath, "torrent_file.torrent");
                FileOutputStream fos = new FileOutputStream(tf); byte[] b = new byte[8192]; int l;
                while ((l = is.read(b)) > 0) fos.write(b, 0, l); fos.close(); is.close();
                btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE);
                torrentSession.startDownload(tf.getAbsolutePath(), savePath);
            } catch (Exception e) { debug("❌ " + e.getMessage()); }
        }
    }
    
    @Override protected void onDestroy() { stop(); httpServer.stop(); if (vlcPlayer != null) vlcPlayer.release(); super.onDestroy(); }
}