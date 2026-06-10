package com.meuapp.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer exoPlayer;
    private DefaultTrackSelector trackSelector;
    private TextView statusText, debugText, timeText;
    private ProgressBar bufferBar, spinnerBar, bufferProgress;
    private EditText magnetInput;
    private Button btnPlay, btnTorrent, btnStop, btnWatch, btnSkip20;
    private LinearLayout playerControls, centerControls;
    private ScrollView audioScroll, subtitleScroll;
    private LinearLayout audioMenu, subtitleMenu;
    private Button btnPlayPause, btnSeekBack, btnSeekForward, btnAudio, btnSubtitle;
    private SeekBar seekBar;
    private boolean isTracking = false;
    
    private String savePath;
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private long videoStartTime = 0;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();
    private static final int PICK_TORRENT = 100;
    private Runnable timeUpdater;
    private int pieceLength = 0, numPieces = 0;
    private long totalSize = 0;
    private long totalRequests = 0, bytesServed = 0;
    private long lastDownloadLog = 0;
    private long videoDurationMs = 0;
    
    private final Object torrentLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        timeText = findViewById(R.id.time_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        bufferProgress = findViewById(R.id.buffer_progress);
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
        File saveDir = new File(savePath);
        if (saveDir.exists()) {
            File[] files = saveDir.listFiles();
            if (files != null) for (File f : files) {
                if (!f.getName().equals("torrent_file.torrent")) f.delete();
            }
        }
        saveDir.mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        trackSelector = new DefaultTrackSelector(this);
        exoPlayer = new ExoPlayer.Builder(this).setTrackSelector(trackSelector).build();
        playerView.setPlayer(exoPlayer);
        playerView.setUseController(false);
        playerView.setVisibility(View.GONE);
        
        exoPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) handler.post(() -> spinnerBar.setVisibility(View.VISIBLE));
                else if (state == Player.STATE_READY) {
                    videoDurationMs = exoPlayer.getDuration();
                    handler.post(() -> { spinnerBar.setVisibility(View.GONE); btnPlayPause.setText(exoPlayer.getPlayWhenReady() ? "⏸" : "▶"); bufferProgress.setVisibility(View.VISIBLE); });
                    debug("[Exo] ▶ " + (videoDurationMs/60000) + "min");
                } else if (state == Player.STATE_ENDED) handler.post(() -> btnPlayPause.setText("▶"));
            }
            @Override public void onPlayerError(PlaybackException error) {
                debug("[Exo] ❌ " + error.getMessage());
            }
        });
        
        timeUpdater = () -> {
            if (exoPlayer != null && exoPlayer.isPlaying()) {
                long t = exoPlayer.getCurrentPosition(), l = exoPlayer.getDuration();
                if (l > 0) videoDurationMs = l;
                if (t >= 0 && l > 0) { timeText.setText(formatTime(t) + " / " + formatTime(l)); if (!isTracking) seekBar.setProgress((int)(t * 100 / l)); }
            }
            handler.postDelayed(timeUpdater, 500);
        };
        
        btnPlayPause.setOnClickListener(v -> { if (exoPlayer != null) { if (exoPlayer.isPlaying()) exoPlayer.pause(); else exoPlayer.play(); } });
        btnSeekBack.setOnClickListener(v -> seekRelative(-10000));
        btnSeekForward.setOnClickListener(v -> seekRelative(10000));
        btnSkip20.setOnClickListener(v -> { if (exoPlayer != null && videoFile != null) { exoPlayer.seekTo(20 * 60 * 1000); debug("⏭ 20:00"); } });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && exoPlayer != null && exoPlayer.getDuration() > 0) exoPlayer.seekTo((long)(exoPlayer.getDuration() * p / 100.0)); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        debug("=== TORRENT STREAM (ExoPlayer) ===");
        new Thread(() -> { try { session = new SessionManager(); session.start(); debug("✅ Sessão OK"); } catch (Exception e) { debug("❌ " + e.getMessage()); } }).start();
        startServer();
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        debug("📱 Pronto");
    }
    
    private void seekRelative(long d) { if (exoPlayer != null && exoPlayer.getDuration() > 0) exoPlayer.seekTo(Math.max(0, Math.min(exoPlayer.getDuration(), exoPlayer.getCurrentPosition() + d))); }
    
    private void toggleAudioMenu() {
        if (exoPlayer == null) return;
        audioMenu.removeAllViews();
        Tracks tracks = exoPlayer.getCurrentTracks();
        int count = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                if (f.sampleMimeType != null && f.sampleMimeType.startsWith("audio")) {
                    count++;
                    String name = f.label != null ? f.label : "Faixa " + count;
                    boolean sel = g.isTrackSelected(i);
                    TextView tv = new TextView(this); tv.setText("🎵 " + name + (sel ? " ✓" : ""));
                    tv.setTextColor(sel ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16,12,16,12);
                    tv.setOnClickListener(v -> { trackSelector.setParameters(trackSelector.getParameters().buildUpon().setPreferredAudioLanguage(f.language).build()); audioScroll.setVisibility(View.GONE); });
                    audioMenu.addView(tv);
                }
            }
        }
        debug("🎵 " + count + " áudios");
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (exoPlayer == null) return;
        subtitleMenu.removeAllViews();
        TextView off = new TextView(this); off.setText("📝 Sem legenda"); off.setTextColor(0xFFFFFFFF); off.setTextSize(12); off.setPadding(16,12,16,12);
        off.setOnClickListener(v -> { exoPlayer.setTrackSelectionParameters(exoPlayer.getTrackSelectionParameters().buildUpon().setPreferredTextLanguage(null).build()); subtitleScroll.setVisibility(View.GONE); });
        subtitleMenu.addView(off);
        Tracks tracks = exoPlayer.getCurrentTracks();
        int count = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                if (f.sampleMimeType != null && f.sampleMimeType.startsWith("text")) {
                    count++;
                    String name = f.label != null ? f.label : "Legenda " + count;
                    boolean sel = g.isTrackSelected(i);
                    TextView tv = new TextView(this); tv.setText("📝 " + name + (sel ? " ✓" : ""));
                    tv.setTextColor(sel ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16,12,16,12);
                    tv.setOnClickListener(v -> { exoPlayer.setTrackSelectionParameters(exoPlayer.getTrackSelectionParameters().buildUpon().setPreferredTextLanguage(f.language).build()); subtitleScroll.setVisibility(View.GONE); });
                    subtitleMenu.addView(tv);
                }
            }
        }
        debug("📝 " + count + " legendas");
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        audioScroll.setVisibility(View.GONE);
    }
    
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms/1000); int m = s/60; s = s%60; return m + ":" + (s < 10 ? "0" : "") + s; }
    
    private void playWithExoPlayer(String url) {
        try {
            debug("[Exo] 🎬 " + url);
            DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory();
            dataSourceFactory.setConnectTimeoutMs(15000);
            dataSourceFactory.setReadTimeoutMs(120000);
            ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
            exoPlayer.setMediaSource(mediaSource);
            exoPlayer.prepare();
            exoPlayer.play();
            handler.post(() -> { 
                playerView.setVisibility(View.VISIBLE); 
                playerControls.setVisibility(View.VISIBLE); 
                centerControls.setVisibility(View.VISIBLE); 
                btnSkip20.setVisibility(View.VISIBLE); 
                bufferProgress.setVisibility(View.VISIBLE); 
                handler.post(timeUpdater); 
            });
        } catch (Exception e) { debug("[Exo] ❌ " + e.getMessage()); }
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
    
    // ==================== SERVIDOR HTTP (COM SEEK BLOCKING) ====================
    private void startServer() { serverThread = new Thread(() -> { try { ServerSocket s = new ServerSocket(8080, 10); s.setReuseAddress(true); while (!Thread.interrupted()) { try { Socket c = s.accept(); new Thread(() -> handleHttp(c)).start(); } catch (IOException e) {} } s.close(); } catch (IOException e) {} }); serverThread.setDaemon(true); serverThread.start(); }
    
    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(60000);
            InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream(); int b;
            while ((b = in.read()) != -1) { hb.write(b); if (hb.size() > 4) { byte[] d = hb.toByteArray(); if (d[d.length-4]=='\r'&&d[d.length-3]=='\n'&&d[d.length-2]=='\r'&&d[d.length-1]=='\n') break; } }
            String req = new String(hb.toByteArray()); String[] lines = req.split("\r\n");
            if (lines.length == 0 || !lines[0].contains("/video")) { out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            long rs = 0, re = -1; boolean hr = false;
            for (String l : lines) if (l.toLowerCase().startsWith("range: bytes=")) { hr = true; String v = l.substring(13).trim(); String[] p = v.split("-"); rs = Long.parseLong(p[0]); if (p.length > 1 && !p[1].isEmpty()) re = Long.parseLong(p[1]); }
            if (videoFile == null || !videoFile.exists()) { out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            long fs = videoFile.length();
            
            if (!hr) {
                String resp = "HTTP/1.1 200 OK\r\nContent-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nContent-Length: " + fs + "\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
                out.write(resp.getBytes());
                byte[] data = new byte[65536];
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                int read = raf.read(data);
                if (read > 0) out.write(data, 0, read);
                raf.close(); out.flush(); client.close();
                if (videoStartTime == 0) videoStartTime = System.currentTimeMillis();
                return;
            }
            
            if (re == -1 || re >= fs) re = fs - 1;
            long cl = re - rs + 1;
            
            // SEEK com bloqueio (FUNCIONAVA!)
            synchronized (torrentLock) {
                if (torrentHandle != null && pieceLength > 0) {
                    int pieceNeeded = (int)(rs / pieceLength);
                    if (!torrentHandle.havePiece(pieceNeeded)) {
                        long estMs = (videoDurationMs > 0 && fs > 0) ? rs * videoDurationMs / fs : 0;
                        long em = estMs / 60000, es = (estMs / 1000) % 60;
                        debug("⏳ SEEK min " + em + ":" + String.format("%02d", es) + " (peça " + pieceNeeded + ")");
                        
                        torrentHandle.swig().piece_priority_ex(pieceNeeded, (byte)7);
                        torrentHandle.swig().set_piece_deadline(pieceNeeded, 45000);
                        for (int i = pieceNeeded - 3; i <= pieceNeeded + 10; i++) {
                            if (i >= 0 && i < numPieces && !torrentHandle.havePiece(i)) {
                                torrentHandle.swig().piece_priority_ex(i, (byte)6);
                                torrentHandle.swig().set_piece_deadline(i, 45000);
                            }
                        }
                        
                        long ws = System.currentTimeMillis();
                        while (!torrentHandle.havePiece(pieceNeeded) && (System.currentTimeMillis() - ws) < 45000 && downloading) {
                            Thread.sleep(500);
                            try { torrentHandle.swig().set_piece_deadline(pieceNeeded, 45000); } catch (Exception e) { break; }
                        }
                        
                        if (torrentHandle.havePiece(pieceNeeded)) {
                            debug("✅ Peça " + pieceNeeded + " em " + ((System.currentTimeMillis()-ws)/1000) + "s");
                        }
                    }
                }
            }
            
            totalRequests++;
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nContent-Range: bytes " + rs + "-" + (rs+cl-1) + "/" + fs + "\r\nContent-Length: " + cl + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes()); out.flush();
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r"); raf.seek(rs); byte[] buf = new byte[65536]; long sent = 0;
            while (sent < cl && downloading) { int tr = (int)Math.min(buf.length, cl-sent); int read = raf.read(buf, 0, tr); if (read <= 0) { Thread.sleep(100); continue; } out.write(buf, 0, read); out.flush(); sent += read; }
            bytesServed += sent; raf.close(); out.flush(); client.close();
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    // ==================== DOWNLOAD (FUNCIONAVA COM 59 PEÇAS) ====================
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        downloading = true; videoFile = null; torrentHandle = null; videoStartTime = 0; pieceLength = 0; numPieces = 0; totalSize = 0; totalRequests = 0; bytesServed = 0; lastDownloadLog = 0; videoDurationMs = 0;
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE); });
        debug("⏳ Conectando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = source.startsWith("magnet:") ? libtorrent.parse_magnet_uri(source, new error_code()) : add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath);
                p.setFlags(libtorrent.getAuto_managed().or_(libtorrent.getSequential_download()).or_(libtorrent.getApply_ip_filter()));
                p.setDownload_limit(3*1024*1024);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                session.swig().async_add_torrent(p); Thread.sleep(3000);
                
                synchronized (torrentLock) { torrent_handle_vector h = session.swig().get_torrents(); if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0)); }
                int w = 0; while (w < 60 && downloading) { Thread.sleep(1000); w++; synchronized (torrentLock) { if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) break; } }
                
                synchronized (torrentLock) {
                if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) {
                    TorrentInfo ti = torrentHandle.torrentFile();
                    int np = ti.numPieces(), pl = ti.pieceLength();
                    pieceLength = pl; numPieces = np; totalSize = ti.totalSize();
                    torrent_status st = torrentHandle.swig().status();
                    debug("📊 " + (totalSize/1048576) + "MB, " + np + " peças | Peers: " + st.getNum_peers());
                    
                    // Cabeçalho: 20-30 peças | Cues: 10-30 peças (MESMO CÁLCULO QUE FUNCIONAVA)
                    int headerPieces = Math.min(30, Math.max(20, np / 30));
                    int cuePieces = Math.min(30, Math.max(10, np / 20));
                    int cueStart = Math.max(0, np - cuePieces);
                    int totalNeeded = headerPieces + cuePieces;
                    debug("📋 H:0-" + (headerPieces-1) + " C:" + cueStart + "-" + (np-1) + " = " + totalNeeded + " peças");
                    
                    // Prioridade MÁXIMA para cabeçalho+cues, IGNORE para o resto
                    byte_vector hp = new byte_vector();
                    for (int i = 0; i < np; i++) {
                        if (i < headerPieces || i >= cueStart) hp.add((byte)7);
                        else hp.add((byte)0); // IGNORE - NÃO BAIXA TUDO!
                    }
                    torrentHandle.swig().prioritize_pieces_ex(hp);
                    for (int i = 0; i < headerPieces; i++) torrentHandle.swig().set_piece_deadline(i, 500);
                    for (int i = cueStart; i < np; i++) torrentHandle.swig().set_piece_deadline(i, 500);
                    
                    int complete = 0, wt = 0; boolean shown = false;
                    while (wt < 120 && downloading) { 
                        Thread.sleep(500); complete = 0; wt++; 
                        for (int i = 0; i < headerPieces; i++) if (torrentHandle.havePiece(i)) complete++;
                        for (int i = cueStart; i < np; i++) if (torrentHandle.havePiece(i)) complete++;
                        if (wt % 4 == 0) debug("   📋 " + complete + "/" + totalNeeded + " (" + (wt/2) + "s)"); 
                        if (!shown && complete >= totalNeeded) { 
                            shown = true; 
                            debug("✅ OK! " + complete + "/" + totalNeeded + " em " + (wt/2) + "s");
                            
                            // Restaurar prioridades - NÃO BAIXAR TUDO
                            byte_vector np2 = new byte_vector();
                            for (int i = 0; i < np; i++) {
                                if (i < headerPieces + 30 || i >= cueStart) np2.add((byte)4);
                                else np2.add((byte)0); // IGNORE - NÃO BAIXA TUDO!
                            }
                            torrentHandle.swig().prioritize_pieces_ex(np2);
                            
                            for (int i = 0; i < 30; i++) { 
                                File f = find(new File(savePath)); 
                                if (f != null && f.length() > 1048576) { 
                                    byte[] hdr = new byte[8]; 
                                    try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; } 
                                    if ((hdr[4]=='f'&&hdr[5]=='t'&&hdr[6]=='y'&&hdr[7]=='p') || ((hdr[0]&0xFF)==0x1A&&hdr[1]==0x45&&hdr[2]==(byte)0xDF&&hdr[3]==(byte)0xA3)) { 
                                        videoFile = f; 
                                        handler.post(() -> { btnWatch.setText("🎬 ASSISTIR"); btnWatch.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.GONE); }); 
                                        debug("📁 " + f.getName() + " (" + (f.length()/1048576) + "MB)"); 
                                        break; 
                                    } 
                                } 
                                Thread.sleep(500); 
                            } 
                            break; 
                        } 
                    }
                }
                }
            } catch (Exception e2) { debug("❌ " + e2.getMessage()); downloading = false; }
        }).start();
        
        // Monitor de download
        new Thread(() -> {
            while (downloading) {
                try { Thread.sleep(5000);
                    synchronized (torrentLock) {
                        if (torrentHandle != null && torrentHandle.isValid() && videoFile != null) {
                            try {
                                long dl = torrentHandle.swig().status().getTotal_done();
                                if (dl - lastDownloadLog > 10485760) { 
                                    lastDownloadLog = dl;
                                    debug("📥 " + (dl/1048576) + "MB / " + (totalSize/1048576) + "MB (" + (totalSize>0?(dl*100/totalSize):0) + "%)"); 
                                }
                            } catch (Exception e) {}
                        }
                    }
                } catch (Exception e) {}
            }
        }).start();
    }
    
    private void watch() { if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não encontrado"); return; } debug("▶️ ExoPlayer: " + videoFile.getName()); handler.post(() -> { playerView.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.VISIBLE); spinnerBar.setVisibility(View.VISIBLE); playWithExoPlayer("http://127.0.0.1:8080/video"); }); }
    
    private void stop() { 
        downloading = false;
        if (exoPlayer != null) exoPlayer.stop(); 
        playerView.setVisibility(View.GONE); playerControls.setVisibility(View.GONE); centerControls.setVisibility(View.GONE); 
        audioScroll.setVisibility(View.GONE); subtitleScroll.setVisibility(View.GONE); 
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE); bufferProgress.setVisibility(View.GONE); 
        handler.removeCallbacks(timeUpdater); 
        synchronized (torrentLock) { if (torrentHandle != null && session != null) { try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} torrentHandle = null; } } 
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File found = find(f); if (found != null) return found; } else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f; } return null; }
    
    @Override protected void onDestroy() { stop(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); if (exoPlayer != null) exoPlayer.release(); super.onDestroy(); }
}