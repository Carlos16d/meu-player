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

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.swig.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.*;

import java.io.*;
import java.net.*;
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
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private boolean surfaceReady = false, isPlaying = false, vlcPreparing = false;
    private String pendingUrl = null;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();
    private static final int PICK_TORRENT = 100;
    private Runnable timeUpdater;
    private int pieceLength = 0, numPieces = 0;
    private long totalSize = 0;
    private long totalRequests = 0;
    private long lastMinuteLog = 0;
    private long videoDurationMs = 0;
    
    // Controle de download
    private int downloadPos = 0;
    private boolean seeking = false;
    private int seekTarget = -1;
    
    private final Object torrentLock = new Object();

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
        
        timeUpdater = () -> {
            if (vlcPlayer != null && isPlaying && !vlcPreparing) {
                long time = vlcPlayer.getTime();
                long length = vlcPlayer.getLength();
                if (length > 0) {
                    videoDurationMs = length;
                    if (time >= 0) {
                        timeText.setText(formatTime(time) + " / " + formatTime(length));
                        if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                        
                        if (!seeking) updateDownloadPos(time);
                        
                        long min = time / 60000;
                        if (min != lastMinuteLog) { lastMinuteLog = min; logMinute(min); }
                    }
                }
            }
            handler.postDelayed(timeUpdater, 2000);
        };
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { surfaceHolder = h; surfaceReady = true; if (pendingUrl != null) { playWithVlc(pendingUrl); pendingUrl = null; } }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; surfaceHolder = null; }
        });
        
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=2000");
        options.add("--file-caching=1000");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing: isPlaying = true; vlcPreparing = false; handler.post(() -> { spinnerBar.setVisibility(View.GONE); btnPlayPause.setText("⏸"); handler.post(timeUpdater); }); break;
                case MediaPlayer.Event.Paused: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.Stopped: isPlaying = false; vlcPreparing = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.Buffering: handler.post(() -> spinnerBar.setVisibility(View.VISIBLE)); break;
                case MediaPlayer.Event.EndReached: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
            }
        });
        
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer != null && !vlcPreparing) { if (isPlaying) vlcPlayer.pause(); else vlcPlayer.play(); } });
        btnSeekBack.setOnClickListener(v -> seekDelta(-10000));
        btnSeekForward.setOnClickListener(v -> seekDelta(10000));
        btnSkip20.setOnClickListener(v -> doSeek(20 * 60 * 1000));
        
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && vlcPlayer.getLength() > 0) doSeek(vlcPlayer.getLength() * p / 100); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        debug("=== TORRENT STREAM ===");
        new Thread(() -> { try { session = new SessionManager(); session.start(); } catch (Exception e) {} }).start();
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
    }
    
    // ============ SERVIDOR HTTP ============
    private void startServer() { 
        serverThread = new Thread(() -> { 
            try { 
                ServerSocket s = new ServerSocket(8080); 
                while (!Thread.interrupted()) { 
                    try { Socket c = s.accept(); new Thread(() -> handleHttp(c)).start(); } catch (IOException e) {} 
                } 
                s.close(); 
            } catch (IOException e) {} 
        }); 
        serverThread.setDaemon(true); 
        serverThread.start(); 
    }
    
    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream(); int b;
            while ((b = in.read()) != -1) { hb.write(b); byte[] d = hb.toByteArray(); if (d.length >= 4 && d[d.length-4]=='\r'&&d[d.length-3]=='\n'&&d[d.length-2]=='\r'&&d[d.length-1]=='\n') break; }
            String req = new String(hb.toByteArray()); String[] lines = req.split("\r\n");
            if (lines.length == 0 || !lines[0].contains("/video")) { out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            
            long rs = 0, re = -1; boolean hr = false;
            for (String l : lines) { if (l.toLowerCase().startsWith("range: bytes=")) { hr = true; String v = l.substring(13).trim(); String[] p = v.split("-"); rs = Long.parseLong(p[0]); if (p.length > 1 && !p[1].isEmpty()) re = Long.parseLong(p[1]); } }
            
            if (videoFile == null || !videoFile.exists()) { out.write("HTTP/1.1 503\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            
            long fs = videoFile.length();
            String mime = getMime(videoFile.getName());
            
            if (!hr) {
                totalRequests++;
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + fs + "\r\nAccept-Ranges: bytes\r\nConnection: keep-alive\r\n\r\n").getBytes());
                out.flush();
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                byte[] buf = new byte[131072];
                int read, sent = 0;
                while (sent < 1048576 && (read = raf.read(buf)) > 0) { out.write(buf, 0, read); out.flush(); sent += read; }
                raf.close();
                out.flush(); client.close();
                return;
            }
            
            if (re == -1 || re >= fs) re = fs - 1;
            long cl = re - rs + 1;
            totalRequests++;
            
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: " + mime + "\r\nContent-Range: bytes " + rs + "-" + re + "/" + fs + "\r\nContent-Length: " + cl + "\r\nAccept-Ranges: bytes\r\nConnection: keep-alive\r\n\r\n").getBytes());
            out.flush();
            
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(rs);
            byte[] buf = new byte[65536];
            long sent = 0;
            int empty = 0;
            while (sent < cl && downloading) {
                int tr = (int)Math.min(buf.length, cl - sent);
                int read = raf.read(buf, 0, tr);
                if (read <= 0) { empty++; if (empty > 20) break; Thread.sleep(100); continue; }
                empty = 0;
                out.write(buf, 0, read); out.flush();
                sent += read;
            }
            raf.close();
            out.flush(); client.close();
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private String getMime(String n) { n = n.toLowerCase(); if (n.endsWith(".mkv")) return "video/x-matroska"; if (n.endsWith(".mp4")) return "video/mp4"; if (n.endsWith(".webm")) return "video/webm"; return "video/mp4"; }
    
    // ============ DOWNLOAD ============
    private void updateDownloadPos(long timeMs) {
        if (pieceLength <= 0 || numPieces <= 0 || videoFile == null || videoDurationMs <= 0) return;
        long bytePos = timeMs * videoFile.length() / videoDurationMs;
        int piece = (int)(bytePos / pieceLength);
        if (piece == downloadPos) return;
        downloadPos = piece;
        prioritizeRange(piece);
    }
    
    private void doSeek(long timeMs) {
        if (vlcPlayer == null) return;
        vlcPlayer.setTime(timeMs);
        
        if (pieceLength <= 0 || numPieces <= 0 || videoFile == null || videoDurationMs <= 0) return;
        
        long bytePos = timeMs * videoFile.length() / videoDurationMs;
        int piece = (int)(bytePos / pieceLength);
        downloadPos = piece;
        seekTarget = piece;
        seeking = true;
        
        long min = timeMs / 60000;
        long sec = (timeMs / 1000) % 60;
        debug("🔥 SEEK: " + min + ":" + String.format("%02d", sec) + " → peça " + piece);
        
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) { seeking = false; return; }
            try {
                // Zerar tudo e focar só na peça alvo + poucas à frente
                byte_vector z = new byte_vector();
                for (int i = 0; i < numPieces; i++) z.add((byte)0);
                torrentHandle.swig().prioritize_pieces_ex(z);
                
                torrentHandle.swig().piece_priority_ex(piece, (byte)7);
                torrentHandle.swig().set_piece_deadline(piece, 3000);
                
                for (int i = piece + 1; i <= Math.min(numPieces - 1, piece + 5); i++) {
                    torrentHandle.swig().piece_priority_ex(i, (byte)6);
                    torrentHandle.swig().set_piece_deadline(i, 5000);
                }
                
                // Monitor
                new Thread(() -> {
                    int c = 0;
                    while (seeking && downloading && c < 60) {
                        try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                        c++;
                        synchronized (torrentLock) {
                            if (torrentHandle == null || !torrentHandle.isValid()) break;
                            try {
                                if (torrentHandle.havePiece(piece)) {
                                    handler.post(() -> { debug("✅ Peça " + piece + " OK"); seeking = false; spinnerBar.setVisibility(View.GONE); });
                                    return;
                                }
                                if (c % 4 == 0) torrentHandle.swig().set_piece_deadline(piece, 3000);
                            } catch (Exception e) {}
                        }
                    }
                    handler.post(() -> { if (seeking) { debug("⏰ Timeout"); } seeking = false; spinnerBar.setVisibility(View.GONE); });
                }).start();
                
            } catch (Exception e) { seeking = false; }
        }
    }
    
    private void prioritizeRange(int start) {
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) return;
            try {
                int end = Math.min(numPieces - 1, start + 8); // Apenas 8 peças à frente
                for (int i = 0; i < numPieces; i++) {
                    byte p = (i >= start && i <= end) ? (byte)7 : (i < start && i >= start - 3) ? (byte)2 : (byte)0;
                    torrentHandle.swig().piece_priority_ex(i, p);
                }
                for (int i = start; i <= end; i++) {
                    if (!torrentHandle.havePiece(i)) torrentHandle.swig().set_piece_deadline(i, 8000);
                }
            } catch (Exception e) {}
        }
    }
    
    private void seekDelta(long d) { if (vlcPlayer != null && vlcPlayer.getLength() > 0) doSeek(Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + d))); }
    
    private void logMinute(long min) {
        if (videoDurationMs <= 0 || pieceLength <= 0 || numPieces <= 0 || videoFile == null) return;
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) return;
            long bp = min * 60 * 1000 * videoFile.length() / videoDurationMs;
            int s = (int)(bp / pieceLength);
            long be = (min + 1) * 60 * 1000 * videoFile.length() / videoDurationMs;
            int e = (int)(be / pieceLength);
            int have = 0;
            for (int i = s; i <= e && i < numPieces; i++) if (torrentHandle.havePiece(i)) have++;
            int tot = e - s + 1, pct = tot > 0 ? have * 100 / tot : 0;
            if (pct < 100 || min % 5 == 0) debug("⏱ Min " + min + ": " + have + "/" + tot + " (" + pct + "%)");
        }
    }
    
    // ============ UI ============
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms / 1000); return (s/60) + ":" + (s%60 < 10 ? "0" : "") + (s%60); }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
        int cur = vlcPlayer.getAudioTrack();
        audioMenu.removeAllViews();
        if (tracks != null) for (MediaPlayer.TrackDescription t : tracks) {
            if (t.id >= 0) {
                TextView tv = new TextView(this); tv.setText("🎵 " + t.name + (t.id == cur ? " ✓" : "")); tv.setTextColor(t.id == cur ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                final int id = t.id; tv.setOnClickListener(v -> { vlcPlayer.setAudioTrack(id); audioScroll.setVisibility(View.GONE); });
                audioMenu.addView(tv);
            }
        }
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
        int cur = vlcPlayer.getSpuTrack();
        subtitleMenu.removeAllViews();
        TextView off = new TextView(this); off.setText("📝 Desligado" + (cur == -1 ? " ✓" : "")); off.setTextColor(cur == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); off.setTextSize(12); off.setPadding(16, 12, 16, 12);
        off.setOnClickListener(v -> { vlcPlayer.setSpuTrack(-1); subtitleScroll.setVisibility(View.GONE); });
        subtitleMenu.addView(off);
        if (tracks != null) for (MediaPlayer.TrackDescription t : tracks) {
            if (t.id >= 0) {
                TextView tv = new TextView(this); tv.setText("📝 " + t.name + (t.id == cur ? " ✓" : "")); tv.setTextColor(t.id == cur ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                final int id = t.id; tv.setOnClickListener(v -> { vlcPlayer.setSpuTrack(id); subtitleScroll.setVisibility(View.GONE); });
                subtitleMenu.addView(tv);
            }
        }
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); audioScroll.setVisibility(View.GONE);
    }
    
    private void playWithVlc(String url) {
        if (!surfaceReady || surfaceHolder == null) { pendingUrl = url; return; }
        try {
            vlcPreparing = true;
            vlcPlayer.getVLCVout().setVideoSurface(surfaceHolder.getSurface(), null);
            vlcPlayer.getVLCVout().setWindowSize(videoSurface.getWidth(), videoSurface.getHeight());
            vlcPlayer.getVLCVout().attachViews();
            Media m = new Media(libVLC, Uri.parse(url));
            m.setHWDecoderEnabled(true, true);
            m.addOption(":network-caching=2000");
            m.addOption(":file-caching=1000");
            vlcPlayer.setMedia(m); m.release();
            vlcPlayer.play();
            handler.post(() -> { playerControls.setVisibility(View.VISIBLE); centerControls.setVisibility(View.VISIBLE); btnSkip20.setVisibility(View.VISIBLE); });
        } catch (Exception e) { vlcPreparing = false; }
    }
    
    @Override protected void onActivityResult(int r, int res, Intent data) {
        super.onActivityResult(r, res, data);
        if (r == PICK_TORRENT && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream is = getContentResolver().openInputStream(data.getData());
                File tf = new File(savePath, "torrent_file.torrent");
                FileOutputStream fos = new FileOutputStream(tf); byte[] b = new byte[8192]; int l;
                while ((l = is.read(b)) > 0) fos.write(b, 0, l); fos.close(); is.close();
                startDownload(tf.getAbsolutePath());
            } catch (Exception e) { debug("❌ " + e.getMessage()); }
        }
    }
    
    private void debug(String msg) { String line = "[" + sdf.format(new Date()) + "] " + msg + "\n"; Log.d("TS", msg); debugLog.append(line); handler.post(() -> { statusText.setText(msg); debugText.setText(debugLog.toString()); }); }
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        downloading = true; videoFile = null; torrentHandle = null; pieceLength = 0; numPieces = 0; totalSize = 0; videoDurationMs = 0;
        downloadPos = 0; seeking = false; totalRequests = 0; lastMinuteLog = -1;
        
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); });
        debug("⏳ Conectando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = source.startsWith("magnet:") ? libtorrent.parse_magnet_uri(source, new error_code()) : add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath);
                p.setFlags(libtorrent.getAuto_managed().or_(libtorrent.getApply_ip_filter()));
                p.setDownload_limit(0);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                session.swig().async_add_torrent(p);
                Thread.sleep(2000);
                
                synchronized (torrentLock) {
                    torrent_handle_vector h = session.swig().get_torrents();
                    if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0));
                }
                
                int w = 0;
                while (w < 60 && downloading) { Thread.sleep(1000); w++;
                    synchronized (torrentLock) { if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) break; }
                }
                
                synchronized (torrentLock) {
                if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) {
                    TorrentInfo ti = torrentHandle.torrentFile();
                    pieceLength = ti.pieceLength(); numPieces = ti.numPieces(); totalSize = ti.totalSize();
                    debug("📊 " + (totalSize/1048576) + "MB, " + numPieces + " peças, " + torrentHandle.swig().status().getNum_peers() + " peers");
                    
                    // FASE 1: Cabeçalho (20 peças)
                    int hdr = Math.min(20, numPieces);
                    debug("📋 Cabeçalho: " + hdr + " peças");
                    
                    byte_vector z = new byte_vector();
                    for (int i = 0; i < numPieces; i++) z.add((byte)0);
                    torrentHandle.swig().prioritize_pieces_ex(z);
                    
                    for (int i = 0; i < hdr; i++) { torrentHandle.swig().piece_priority_ex(i, (byte)7); torrentHandle.swig().set_piece_deadline(i, 30000); }
                    
                    int done = 0;
                    while (done < hdr && downloading) { Thread.sleep(500); done = 0; for (int i = 0; i < hdr; i++) if (torrentHandle.havePiece(i)) done++; }
                    debug("✅ Cabeçalho: " + done + "/" + hdr);
                    
                    for (int i = 0; i < 20; i++) { File f = find(new File(savePath)); if (f != null && f.length() > 1048576) { videoFile = f; break; } Thread.sleep(500); }
                    
                    if (videoFile != null) {
                        handler.post(() -> { btnWatch.setText("🎬 ASSISTIR"); btnWatch.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.GONE); });
                        debug("📁 " + videoFile.getName());
                    }
                    
                    // FASE 2: Download sequencial contínuo (apenas 8 peças por vez)
                    debug("📥 Download sequencial...");
                    
                    while (downloading) {
                        if (seeking) { Thread.sleep(500); continue; }
                        
                        synchronized (torrentLock) {
                            if (torrentHandle == null || !torrentHandle.isValid()) break;
                            
                            // Avançar sobre peças já baixadas
                            while (downloadPos < numPieces && torrentHandle.havePiece(downloadPos)) downloadPos++;
                            if (downloadPos >= numPieces) { debug("✅ Completo!"); break; }
                            
                            int end = Math.min(numPieces - 1, downloadPos + 8);
                            for (int i = 0; i < numPieces; i++) {
                                byte prr = (i >= downloadPos && i <= end) ? (byte)7 : (i < downloadPos && i >= downloadPos - 2) ? (byte)2 : (byte)0;
                                torrentHandle.swig().piece_priority_ex(i, prr);
                            }
                            for (int i = downloadPos; i <= end; i++) {
                                if (!torrentHandle.havePiece(i)) torrentHandle.swig().set_piece_deadline(i, 10000);
                            }
                        }
                        
                        long dl = torrentHandle.swig().status().getTotal_done();
                        if (totalRequests % 10 == 0) { int pct = totalSize > 0 ? (int)(dl * 100 / totalSize) : 0; debug("📥 " + (dl/1048576) + "/" + (totalSize/1048576) + "MB (" + pct + "%)"); }
                        totalRequests++;
                        Thread.sleep(2000);
                    }
                }
                }
            } catch (Exception e) { debug("❌ " + e.getMessage()); downloading = false; }
        }).start();
    }
    
    private void watch() { if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não pronto"); return; } handler.post(() -> { videoSurface.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); spinnerBar.setVisibility(View.VISIBLE); playWithVlc("http://127.0.0.1:8080/video"); }); }
    
    private void stop() {
        downloading = false; vlcPreparing = false; seeking = false;
        if (vlcPlayer != null) vlcPlayer.stop();
        videoSurface.setVisibility(View.GONE); playerControls.setVisibility(View.GONE); centerControls.setVisibility(View.GONE);
        audioScroll.setVisibility(View.GONE); subtitleScroll.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        handler.removeCallbacks(timeUpdater);
        synchronized (torrentLock) { if (torrentHandle != null && session != null) { try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} torrentHandle = null; } }
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File ff = find(f); if (ff != null) return ff; } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm)$")) return f; } return null; }
    
    @Override protected void onDestroy() { stop(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); if (vlcPlayer != null) vlcPlayer.release(); if (libVLC != null) libVLC.release(); super.onDestroy(); }
}