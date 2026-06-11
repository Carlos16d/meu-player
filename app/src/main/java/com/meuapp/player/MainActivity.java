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

import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.torrent_flags_t;
import org.libtorrent4j.swig.torrent_handle_vector;
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
    private volatile boolean downloading, playing = false;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread, downloadThread;
    private boolean surfaceReady = false, isPlaying = false, vlcPreparing = false;
    private String pendingUrl = null;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();
    private static final int PICK_TORRENT = 100;
    private Runnable timeUpdater;
    private int pieceLength = 0, numPieces = 0;
    private long totalSize = 0;
    private long videoDurationMs = 0;
    private long lastMinuteLog = -1;
    
    private int currentPlayingPiece = -1;
    private boolean seeking = false;
    private final Object torrentLock = new Object();
    
    private Set<Integer> requiredPieces = new HashSet<>();

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
            if (vlcPlayer != null && isPlaying && !vlcPreparing && !seeking && playing) {
                long time = vlcPlayer.getTime();
                long length = vlcPlayer.getLength();
                if (length > 0) {
                    videoDurationMs = length;
                    if (time >= 0) {
                        timeText.setText(formatTime(time) + " / " + formatTime(length));
                        if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                        if (pieceLength > 0 && totalSize > 0) {
                            int piece = (int)(time * totalSize / length / pieceLength);
                            if (piece != currentPlayingPiece && piece >= 0 && piece < numPieces) {
                                currentPlayingPiece = piece;
                                downloadRange(piece, Math.min(numPieces - 1, piece + 5));
                            }
                        }
                        long min = time / 60000;
                        if (min != lastMinuteLog) { lastMinuteLog = min; logMinute(min); }
                    }
                }
            }
            handler.postDelayed(timeUpdater, 1000);
        };
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { surfaceHolder = h; surfaceReady = true; if (pendingUrl != null) { playWithVlc(pendingUrl); pendingUrl = null; } }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; surfaceHolder = null; }
        });
        
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=1500");
        options.add("--file-caching=800");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing: isPlaying = true; vlcPreparing = false; handler.post(() -> { spinnerBar.setVisibility(View.GONE); btnPlayPause.setText("⏸"); }); break;
                case MediaPlayer.Event.Paused: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.Stopped: isPlaying = false; vlcPreparing = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.Buffering: handler.post(() -> spinnerBar.setVisibility(View.VISIBLE)); break;
                case MediaPlayer.Event.EndReached: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
            }
        });
        
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer != null && !vlcPreparing) { if (isPlaying) vlcPlayer.pause(); else vlcPlayer.play(); } });
        btnSeekBack.setOnClickListener(v -> { if (!vlcPreparing) seekRelative(-10000); });
        btnSeekForward.setOnClickListener(v -> { if (!vlcPreparing) seekRelative(10000); });
        btnSkip20.setOnClickListener(v -> seekToPiece(20 * 60 * 1000));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && vlcPlayer.getLength() > 0) seekToPiece(vlcPlayer.getLength() * p / 100); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        debug("=== STREAM API OFICIAL ===");
        new Thread(() -> { try { session = new SessionManager(); session.start(); debug("✅ Sessão OK"); } catch (Exception e) { debug("❌ " + e.getMessage()); } }).start();
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
    }
    
    // ==================== MÉTODOS SEGUROS (API OFICIAL) ====================
    private boolean thValid() {
        return torrentHandle != null && torrentHandle.isValid();
    }
    
    private boolean havePiece(int p) {
        synchronized (torrentLock) {
            return thValid() && torrentHandle.havePiece(p);
        }
    }
    
    private TorrentInfo getTorrentFile() {
        synchronized (torrentLock) {
            return thValid() ? torrentHandle.torrentFile() : null;
        }
    }
    
    private TorrentStatus getStatus() {
        synchronized (torrentLock) {
            return thValid() ? torrentHandle.status() : null;
        }
    }
    
    private void setPrio(int piece, Priority priority) {
        synchronized (torrentLock) {
            if (thValid()) torrentHandle.piecePriority(piece, priority);
        }
    }
    
    private void setDeadline(int piece, int deadline) {
        synchronized (torrentLock) {
            if (thValid()) torrentHandle.setPieceDeadline(piece, deadline);
        }
    }
    
    private void prioritizeAll(Priority[] priorities) {
        synchronized (torrentLock) {
            if (thValid()) torrentHandle.prioritizePieces(priorities);
        }
    }
    
    private void downloadRange(int start, int end) {
        if (!playing || seeking) return;
        Priority[] prios = new Priority[numPieces];
        for (int i = 0; i < numPieces; i++) prios[i] = Priority.ZERO;
        for (int i = start; i <= end; i++) {
            if (!havePiece(i)) { prios[i] = Priority.MAX; setDeadline(i, 5000); }
        }
        prioritizeAll(prios);
    }
    
    private void seekToPiece(long timeMs) {
        if (vlcPlayer == null || pieceLength <= 0 || totalSize <= 0 || videoDurationMs <= 0) return;
        vlcPlayer.setTime(timeMs);
        final int piece = (int)(timeMs * totalSize / videoDurationMs / pieceLength);
        if (piece < 0 || piece >= numPieces) return;
        
        debug("🔥 Seek → peça " + piece);
        seeking = true;
        handler.post(() -> spinnerBar.setVisibility(View.VISIBLE));
        
        new Thread(() -> {
            try {
                if (havePiece(piece)) {
                    handler.post(() -> { seeking = false; spinnerBar.setVisibility(View.GONE); });
                    currentPlayingPiece = piece;
                    return;
                }
                Priority[] prios = new Priority[numPieces];
                for (int i = 0; i < numPieces; i++) prios[i] = Priority.ZERO;
                for (int i = piece - 2; i <= piece + 8; i++) {
                    if (i >= 0 && i < numPieces) {
                        prios[i] = (i == piece) ? Priority.MAX : Priority.HIGH;
                        setDeadline(i, 3000);
                    }
                }
                prioritizeAll(prios);
                
                int waits = 0;
                while (!havePiece(piece) && downloading && waits < 32) {
                    Thread.sleep(250); waits++;
                    if (waits % 4 == 0) setDeadline(piece, 3000);
                }
                final double elapsed = waits / 4.0;
                if (havePiece(piece)) {
                    handler.post(() -> { debug("✅ em " + elapsed + "s"); seeking = false; spinnerBar.setVisibility(View.GONE); });
                    currentPlayingPiece = piece;
                } else {
                    handler.post(() -> { debug("⏰ Timeout"); seeking = false; spinnerBar.setVisibility(View.GONE); });
                }
            } catch (Exception e) { seeking = false; handler.post(() -> spinnerBar.setVisibility(View.GONE)); }
        }).start();
    }
    
    private void seekRelative(long d) { if (vlcPlayer != null && vlcPlayer.getLength() > 0) seekToPiece(Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + d))); }
    
    private void parseAndFindPieces() {
        if (videoFile == null || !videoFile.exists() || pieceLength <= 0) return;
        requiredPieces.clear();
        try {
            // Cabeçalho + buffer inicial + final
            for (int i = 0; i < Math.min(15, numPieces); i++) requiredPieces.add(i);
            for (int i = 0; i < Math.min(10, numPieces); i++) requiredPieces.add(i);
            for (int i = Math.max(0, numPieces - 8); i < numPieces; i++) requiredPieces.add(i);
            requiredPieces.add(numPieces / 2);
            debug("🎯 " + requiredPieces.size() + " peças críticas");
        } catch (Exception e) { debug("⚠️ " + e.getMessage()); }
    }
    
    private void logMinute(long min) {
        if (videoDurationMs <= 0 || pieceLength <= 0 || totalSize <= 0) return;
        long bp = min * 60 * 1000 * totalSize / videoDurationMs;
        int s = (int)(bp / pieceLength), e = (int)(((min+1)*60*1000*totalSize/videoDurationMs)/pieceLength);
        int have = 0;
        for (int i = s; i <= e && i < numPieces; i++) if (havePiece(i)) have++;
        if (e > s) debug("⏱ Min " + min + ": " + have + "/" + (e-s+1) + " (" + (have*100/(e-s+1)) + "%)");
    }
    
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms / 1000); return (s/60) + ":" + String.format("%02d", s%60); }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] t = vlcPlayer.getAudioTracks();
        int c = vlcPlayer.getAudioTrack();
        audioMenu.removeAllViews();
        debug("🎵 Áudios: " + (t != null ? t.length : 0));
        if (t != null) for (MediaPlayer.TrackDescription tr : t) {
            if (tr.id >= 0) {
                TextView tv = new TextView(this); tv.setText("🎵 " + tr.name + (tr.id == c ? " ✓" : "")); tv.setTextColor(tr.id == c ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                final int id = tr.id; tv.setOnClickListener(v -> { vlcPlayer.setAudioTrack(id); audioScroll.setVisibility(View.GONE); });
                audioMenu.addView(tv);
            }
        }
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] t = vlcPlayer.getSpuTracks();
        int c = vlcPlayer.getSpuTrack();
        subtitleMenu.removeAllViews();
        debug("📝 Legendas: " + (t != null ? t.length : 0));
        TextView off = new TextView(this); off.setText("📝 Desligado" + (c == -1 ? " ✓" : "")); off.setTextColor(c == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); off.setTextSize(12); off.setPadding(16, 12, 16, 12);
        off.setOnClickListener(v -> { vlcPlayer.setSpuTrack(-1); subtitleScroll.setVisibility(View.GONE); });
        subtitleMenu.addView(off);
        if (t != null) for (MediaPlayer.TrackDescription tr : t) {
            if (tr.id >= 0) {
                TextView tv = new TextView(this); tv.setText("📝 " + tr.name + (tr.id == c ? " ✓" : "")); tv.setTextColor(tr.id == c ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                final int id = tr.id; tv.setOnClickListener(v -> { vlcPlayer.setSpuTrack(id); subtitleScroll.setVisibility(View.GONE); });
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
            m.addOption(":network-caching=1500");
            m.addOption(":file-caching=800");
            vlcPlayer.setMedia(m); m.release();
            vlcPlayer.play();
            playing = true;
            handler.post(() -> { playerControls.setVisibility(View.VISIBLE); centerControls.setVisibility(View.VISIBLE); btnSkip20.setVisibility(View.VISIBLE); });
            debug("[VLC] ▶ Reproduzindo");
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
    
    private void startServer() { serverThread = new Thread(() -> { try { ServerSocket s = new ServerSocket(8080, 10); s.setReuseAddress(true); while (!Thread.interrupted()) { try { Socket c = s.accept(); new Thread(() -> handleHttp(c)).start(); } catch (IOException e) {} } s.close(); } catch (IOException e) {} }); serverThread.setDaemon(true); serverThread.start(); }
    
    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream(); int b;
            while ((b = in.read()) != -1) { hb.write(b); if (hb.size() > 4) { byte[] d = hb.toByteArray(); if (d[d.length-4]=='\r'&&d[d.length-3]=='\n'&&d[d.length-2]=='\r'&&d[d.length-1]=='\n') break; } }
            String req = new String(hb.toByteArray()); String[] lines = req.split("\r\n");
            if (!lines[0].contains("/video")) { out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            
            long rs = 0, re = -1; boolean hr = false;
            for (String l : lines) { if (l.toLowerCase().startsWith("range: bytes=")) { hr = true; rs = Long.parseLong(l.substring(13).trim().split("-")[0]); if (l.substring(13).contains("-")) { String[] p = l.substring(13).split("-"); if (p.length > 1 && !p[1].isEmpty()) re = Long.parseLong(p[1]); } } }
            
            if (videoFile == null || !videoFile.exists()) { out.write("HTTP/1.1 503\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            
            final long rSize = totalSize > 0 ? totalSize : videoFile.length();
            String mime = "video/x-matroska";
            
            if (!hr) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + rSize + "\r\nAccept-Ranges: bytes\r\nConnection: keep-alive\r\nCache-Control: no-cache\r\n\r\n").getBytes());
                out.flush();
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                byte[] data = new byte[65536]; int read, sent = 0;
                while (sent < 2097152 && (read = raf.read(data)) != -1) { out.write(data, 0, read); out.flush(); sent += read; }
                raf.close(); out.flush(); client.close();
                return;
            }
            
            if (re == -1 || re >= rSize) re = rSize - 1;
            long cl = re - rs + 1;
            
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: " + mime + "\r\nContent-Range: bytes " + rs + "-" + re + "/" + rSize + "\r\nContent-Length: " + cl + "\r\nAccept-Ranges: bytes\r\nConnection: keep-alive\r\nCache-Control: no-cache\r\n\r\n").getBytes());
            out.flush();
            
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            if (rs < raf.length()) {
                raf.seek(rs);
                long avail = raf.length() - rs;
                int toRead = (int) Math.min(cl, avail);
                if (toRead > 0) { byte[] buf = new byte[toRead]; int read = raf.read(buf); if (read > 0) { out.write(buf, 0, read); out.flush(); } }
            }
            raf.close(); out.flush(); client.close();
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        stop();
        downloading = true; playing = false; seeking = false; videoFile = null; torrentHandle = null;
        pieceLength = 0; numPieces = 0; totalSize = 0; videoDurationMs = 0;
        currentPlayingPiece = -1; lastMinuteLog = -1;
        requiredPieces.clear();
        
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE); });
        
        downloadThread = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            debug("⏳ Conectando...");
            
            try {
                File saveDir = new File(savePath);
                torrent_flags_t flags = new torrent_flags_t();
                
                if (source.startsWith("magnet:")) {
                    // ✅ API OFICIAL: session.download(magnet, saveDir, flags)
                    session.download(source, saveDir, flags);
                } else {
                    // ✅ API OFICIAL: session.download(ti, saveDir)
                    TorrentInfo ti = new TorrentInfo(new org.libtorrent4j.swig.torrent_info(source));
                    session.download(ti, saveDir);
                }
                Thread.sleep(2000);
                
                // ✅ API OFICIAL: session.swig().get_torrents() só para encontrar o handle
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0));
                
                int w = 0;
                while (w < 60 && downloading) { Thread.sleep(1000); w++;
                    if (thValid() && getTorrentFile() != null) break;
                }
                
                TorrentInfo ti = getTorrentFile();
                if (ti == null) { debug("❌ Metadados não disponíveis"); downloading = false; return; }
                
                pieceLength = ti.pieceLength(); numPieces = ti.numPieces(); totalSize = ti.totalSize();
                TorrentStatus st = getStatus();
                int seeds = st != null ? st.seeds() : 0;
                int peers = st != null ? st.peers() : 0;
                debug("📊 " + (totalSize/1048576) + "MB | " + seeds + " Seeds | " + peers + " Peers");
                
                // PRÉ-CARGA
                int inicio = Math.min(15, numPieces);
                int fim = Math.min(5, numPieces);
                int fimStart = numPieces - fim;
                int meio = numPieces / 2;
                
                debug("📋 PRÉ-CARGA: [0-" + (inicio-1) + "] + [" + fimStart + "-" + (numPieces-1) + "] + p" + meio);
                
                Priority[] prios = new Priority[numPieces];
                for (int i = 0; i < numPieces; i++) prios[i] = Priority.ZERO;
                for (int i = 0; i < inicio; i++) { prios[i] = Priority.MAX; setDeadline(i, 20000); }
                for (int i = fimStart; i < numPieces; i++) { prios[i] = Priority.MAX; setDeadline(i, 20000); }
                prios[meio] = Priority.MAX; setDeadline(meio, 20000);
                prioritizeAll(prios);
                
                int doneIni = 0, doneFim = 0;
                while ((doneIni < inicio || doneFim < fim) && downloading) {
                    Thread.sleep(200);
                    doneIni = 0; for (int i = 0; i < inicio; i++) if (havePiece(i)) doneIni++;
                    doneFim = 0; for (int i = fimStart; i < numPieces; i++) if (havePiece(i)) doneFim++;
                }
                debug("✅ Pré-carga: " + doneIni + "/" + inicio + " | " + doneFim + "/" + fim + " (" + ((System.currentTimeMillis()-t0)/1000) + "s)");
                
                for (int i = 0; i < 15; i++) { File f = find(new File(savePath)); if (f != null && f.length() > 5*1048576) { videoFile = f; break; } Thread.sleep(200); }
                
                if (videoFile != null) {
                    if (videoFile.length() < totalSize) {
                        try { RandomAccessFile raf = new RandomAccessFile(videoFile, "rw"); raf.setLength(totalSize); raf.close(); debug("📏 Arquivo: " + (totalSize/1048576) + "MB"); } catch (Exception e) {}
                    }
                    
                    parseAndFindPieces();
                    
                    int total = requiredPieces.size();
                    debug("📥 Complementando " + total + " peças");
                    
                    prios = new Priority[numPieces];
                    for (int i = 0; i < numPieces; i++) prios[i] = Priority.ZERO;
                    for (int piece : requiredPieces) { if (piece < numPieces) { prios[piece] = Priority.MAX; setDeadline(piece, 15000); } }
                    prioritizeAll(prios);
                    
                    int done = 0;
                    long lastLog = System.currentTimeMillis();
                    while (done < total && downloading) {
                        Thread.sleep(150); done = 0;
                        for (int piece : requiredPieces) if (piece < numPieces && havePiece(piece)) done++;
                        
                        long now = System.currentTimeMillis();
                        if (now - lastLog > 800) {
                            lastLog = now;
                            int pct = total > 0 ? done * 100 / total : 0;
                            TorrentStatus st2 = getStatus();
                            long spd = st2 != null ? st2.downloadRate() : 0;
                            final String msg = "📥 Metadados: " + pct + "% | " + (spd/1024) + " KB/s";
                            handler.post(() -> statusText.setText(msg));
                        }
                    }
                    
                    long elapsed = (System.currentTimeMillis() - t0) / 1000;
                    debug("✅ TUDO PRONTO! " + done + "/" + total + " em " + elapsed + "s");
                }
                
                handler.post(() -> { btnWatch.setText("🎬 ASSISTIR"); btnWatch.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.GONE); });
                if (videoFile != null) debug("📁 " + videoFile.getName());
            } catch (Exception e) { debug("❌ " + e.getMessage()); downloading = false; }
        });
        downloadThread.start();
    }
    
    private void watch() { if (videoFile == null || !videoFile.exists()) { debug("❌ Aguarde"); return; } playing = true; handler.post(() -> { videoSurface.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); spinnerBar.setVisibility(View.VISIBLE); playWithVlc("http://127.0.0.1:8080/video"); }); }
    
    private void stop() {
        downloading = false; playing = false; seeking = false; vlcPreparing = false;
        if (vlcPlayer != null) vlcPlayer.stop();
        videoSurface.setVisibility(View.GONE); playerControls.setVisibility(View.GONE); centerControls.setVisibility(View.GONE);
        audioScroll.setVisibility(View.GONE); subtitleScroll.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        handler.removeCallbacks(timeUpdater);
        if (downloadThread != null) downloadThread.interrupt();
        // ✅ API OFICIAL: session.remove() verifica isValid() internamente
        if (torrentHandle != null) session.remove(torrentHandle);
        torrentHandle = null;
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File ff = find(f); if (ff != null) return ff; } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm)$")) return f; } return null; }
    
    @Override protected void onDestroy() { stop(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); if (vlcPlayer != null) vlcPlayer.release(); if (libVLC != null) libVLC.release(); super.onDestroy(); }
}