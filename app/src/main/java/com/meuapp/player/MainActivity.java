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
    private volatile boolean downloading, playing = false, seeking = false;
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
    
    private int currentPiece = -1;
    private final Object torrentLock = new Object();
    private Set<Integer> criticalPieces = new HashSet<>();
    
    private static class EBMLPattern {
        final long id; final String name;
        EBMLPattern(long id, String name) { this.id = id; this.name = name; }
    }

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
        
        // VLC com timeouts baixos para resposta rápida
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=1000");
        options.add("--file-caching=500");
        options.add("--clock-synchro=0");
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
        
        timeUpdater = () -> {
            if (vlcPlayer != null && isPlaying && !vlcPreparing && !seeking) {
                long time = vlcPlayer.getTime();
                long length = vlcPlayer.getLength();
                if (length > 0) {
                    videoDurationMs = length;
                    if (time >= 0) {
                        timeText.setText(formatTime(time) + " / " + formatTime(length));
                        if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                        
                        // Baixar APENAS peça atual + 5 à frente
                        if (pieceLength > 0 && totalSize > 0) {
                            int piece = (int)(time * totalSize / length / pieceLength);
                            if (piece != currentPiece) {
                                currentPiece = piece;
                                downloadRange(piece, Math.min(numPieces - 1, piece + 5));
                            }
                        }
                        
                        long min = time / 60000;
                        if (min != lastMinuteLog) { lastMinuteLog = min; logMinute(min); }
                    }
                }
            }
            handler.postDelayed(timeUpdater, 500);
        };
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { surfaceHolder = h; surfaceReady = true; if (pendingUrl != null) { playWithVlc(pendingUrl); pendingUrl = null; } }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; surfaceHolder = null; }
        });
        
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer != null && !vlcPreparing) { if (isPlaying) vlcPlayer.pause(); else vlcPlayer.play(); } });
        btnSeekBack.setOnClickListener(v -> seekToTime(-10000));
        btnSeekForward.setOnClickListener(v -> seekToTime(10000));
        btnSkip20.setOnClickListener(v -> seekToPosition(20 * 60 * 1000));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && vlcPlayer.getLength() > 0) seekToPosition(vlcPlayer.getLength() * p / 100); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        debug("=== STREMIO MODE: 27s start / 6s seek ===");
        new Thread(() -> { try { session = new SessionManager(); session.start(); debug("✅ Sessão OK"); } catch (Exception e) { debug("❌ " + e.getMessage()); } }).start();
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
    }
    
    // ==================== DOWNLOAD CONTROLADO ====================
    private void downloadRange(int startPiece, int endPiece) {
        if (!playing || seeking) return;
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) return;
            try {
                byte_vector z = new byte_vector();
                for (int i = 0; i < numPieces; i++) z.add((byte)0);
                torrentHandle.swig().prioritize_pieces_ex(z);
                
                for (int i = startPiece; i <= endPiece; i++) {
                    if (!torrentHandle.havePiece(i)) {
                        torrentHandle.swig().piece_priority_ex(i, (byte)7);
                        torrentHandle.swig().set_piece_deadline(i, 5000);
                    }
                }
            } catch (Exception e) {}
        }
    }
    
    private void seekToTime(long delta) {
        if (vlcPlayer == null || vlcPlayer.getLength() <= 0) return;
        long newTime = Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + delta));
        seekToPosition(newTime);
    }
    
    private void seekToPosition(final long timeMs) {
        if (vlcPlayer == null || pieceLength <= 0 || totalSize <= 0) {
            if (vlcPlayer != null) vlcPlayer.setTime(timeMs);
            return;
        }
        
        final long bytePos = timeMs * totalSize / Math.max(1, videoDurationMs > 0 ? videoDurationMs : totalSize);
        final int targetPiece = (int)(bytePos / pieceLength);
        
        long min = timeMs / 60000;
        long sec = (timeMs / 1000) % 60;
        debug("🔥 Seek: " + min + ":" + String.format("%02d", sec) + " → peça " + targetPiece);
        
        vlcPlayer.setTime(timeMs);
        seeking = true;
        spinnerBar.setVisibility(View.VISIBLE);
        
        new Thread(() -> {
            long startWait = System.currentTimeMillis();
            
            synchronized (torrentLock) {
                if (torrentHandle == null || !torrentHandle.isValid()) { seeking = false; return; }
                
                try {
                    // Verificar se já existe
                    if (torrentHandle.havePiece(targetPiece)) {
                        handler.post(() -> { debug("✅ Já existe"); seeking = false; spinnerBar.setVisibility(View.GONE); });
                        return;
                    }
                    
                    // Prioridade ABSOLUTA: peça alvo + 3 vizinhas
                    byte_vector z = new byte_vector();
                    for (int i = 0; i < numPieces; i++) z.add((byte)0);
                    torrentHandle.swig().prioritize_pieces_ex(z);
                    
                    torrentHandle.swig().piece_priority_ex(targetPiece, (byte)7);
                    torrentHandle.swig().set_piece_deadline(targetPiece, 3000);
                    
                    for (int i = targetPiece + 1; i <= Math.min(numPieces - 1, targetPiece + 3); i++) {
                        torrentHandle.swig().piece_priority_ex(i, (byte)6);
                        torrentHandle.swig().set_piece_deadline(i, 5000);
                    }
                    
                    // Aguardar até 6 segundos
                    while (!torrentHandle.havePiece(targetPiece) && downloading && 
                           (System.currentTimeMillis() - startWait) < 6000) {
                        Thread.sleep(200);
                        torrentHandle.swig().set_piece_deadline(targetPiece, 3000);
                    }
                    
                    final double elapsed = (System.currentTimeMillis() - startWait) / 1000.0;
                    
                    if (torrentHandle.havePiece(targetPiece)) {
                        handler.post(() -> { debug("✅ Seek OK em " + String.format("%.1f", elapsed) + "s"); });
                        currentPiece = targetPiece;
                    } else {
                        handler.post(() -> { debug("⏰ Timeout após " + String.format("%.1f", elapsed) + "s"); });
                    }
                } catch (Exception e) {}
            }
            
            seeking = false;
            handler.post(() -> spinnerBar.setVisibility(View.GONE));
        }).start();
    }
    
    // ==================== PARSER SEEKHEAD ====================
    private void parseAndFindCriticalPieces() {
        if (videoFile == null || !videoFile.exists() || pieceLength <= 0) return;
        criticalPieces.clear();
        
        try {
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            long fileLen = raf.length();
            byte[] header = new byte[Math.min(262144, (int)fileLen)];
            raf.read(header);
            raf.close();
            
            EBMLPattern[] patterns = new EBMLPattern[] {
                new EBMLPattern(0x1654AE6BL, "Tracks"),
                new EBMLPattern(0x1C53BB6BL, "Cues"),
                new EBMLPattern(0x1254C367L, "Tags"),
                new EBMLPattern(0x114D9B74L, "SeekHead"),
                new EBMLPattern(0x1549A966L, "Info"),
                new EBMLPattern(0x18538067L, "Segment")
            };
            
            debug("🔍 Buscando metadados...");
            
            for (EBMLPattern pattern : patterns) {
                byte[] idBytes = new byte[]{
                    (byte)((pattern.id >> 24) & 0xFF),
                    (byte)((pattern.id >> 16) & 0xFF),
                    (byte)((pattern.id >> 8) & 0xFF),
                    (byte)(pattern.id & 0xFF)
                };
                
                for (int pos : findAllOccurrences(header, idBytes)) {
                    criticalPieces.add(pos / pieceLength);
                }
            }
            
            // Final do arquivo
            if (fileLen > 524288) {
                raf = new RandomAccessFile(videoFile, "r");
                raf.seek(fileLen - 524288);
                byte[] tail = new byte[524288];
                raf.read(tail);
                raf.close();
                
                for (EBMLPattern pattern : patterns) {
                    byte[] idBytes = new byte[]{
                        (byte)((pattern.id >> 24) & 0xFF),
                        (byte)((pattern.id >> 16) & 0xFF),
                        (byte)((pattern.id >> 8) & 0xFF),
                        (byte)(pattern.id & 0xFF)
                    };
                    
                    for (int pos : findAllOccurrences(tail, idBytes)) {
                        criticalPieces.add((int)((fileLen - 524288 + pos) / pieceLength));
                    }
                }
            }
            
            // Sempre incluir cabeçalho
            for (int i = 0; i < Math.min(20, numPieces); i++) criticalPieces.add(i);
            
            debug("🎯 " + criticalPieces.size() + " peças críticas para baixar");
            
        } catch (Exception e) {
            debug("⚠️ Fallback: " + e.getMessage());
            for (int i = 0; i < Math.min(25, numPieces); i++) criticalPieces.add(i);
            for (int i = Math.max(0, numPieces - 8); i < numPieces; i++) criticalPieces.add(i);
        }
    }
    
    private List<Integer> findAllOccurrences(byte[] data, byte[] pattern) {
        List<Integer> results = new ArrayList<>();
        for (int i = 0; i < data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i+j] != pattern[j]) { match = false; break; }
            }
            if (match) results.add(i);
        }
        return results;
    }
    
    private void logMinute(long min) {
        if (videoDurationMs <= 0 || pieceLength <= 0 || totalSize <= 0) return;
        long bp = min * 60 * 1000 * totalSize / videoDurationMs;
        int s = (int)(bp / pieceLength);
        int e = (int)(((min+1)*60*1000*totalSize/videoDurationMs)/pieceLength);
        int have = 0;
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) return;
            for (int i = s; i <= e && i < numPieces; i++) if (torrentHandle.havePiece(i)) have++;
        }
        int tot = e - s + 1;
        if (tot > 0) debug("⏱ Min " + min + ": " + have + "/" + tot + " (" + (have*100/tot) + "%)");
    }
    
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms / 1000); return (s/60) + ":" + String.format("%02d", s%60); }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
        int cur = vlcPlayer.getAudioTrack();
        audioMenu.removeAllViews();
        if (tracks != null) {
            debug("🎵 Áudios: " + tracks.length);
            for (MediaPlayer.TrackDescription t : tracks) {
                if (t.id >= 0) {
                    TextView tv = new TextView(this); 
                    tv.setText("🎵 " + t.name + (t.id == cur ? " ✓" : "")); 
                    tv.setTextColor(t.id == cur ? 0xFF6c5ce7 : 0xFFFFFFFF); 
                    tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                    final int id = t.id; 
                    tv.setOnClickListener(v -> { vlcPlayer.setAudioTrack(id); audioScroll.setVisibility(View.GONE); }); 
                    audioMenu.addView(tv);
                }
            }
        }
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); 
        subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
        int cur = vlcPlayer.getSpuTrack();
        subtitleMenu.removeAllViews();
        debug("📝 Legendas: " + (tracks != null ? tracks.length : 0));
        TextView off = new TextView(this); 
        off.setText("📝 Desligado" + (cur == -1 ? " ✓" : "")); 
        off.setTextColor(cur == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); 
        off.setTextSize(12); off.setPadding(16, 12, 16, 12);
        off.setOnClickListener(v -> { vlcPlayer.setSpuTrack(-1); subtitleScroll.setVisibility(View.GONE); });
        subtitleMenu.addView(off);
        if (tracks != null) for (MediaPlayer.TrackDescription t : tracks) {
            if (t.id >= 0) {
                TextView tv = new TextView(this); 
                tv.setText("📝 " + t.name + (t.id == cur ? " ✓" : "")); 
                tv.setTextColor(t.id == cur ? 0xFF6c5ce7 : 0xFFFFFFFF); 
                tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                final int id = t.id; 
                tv.setOnClickListener(v -> { vlcPlayer.setSpuTrack(id); subtitleScroll.setVisibility(View.GONE); }); 
                subtitleMenu.addView(tv);
            }
        }
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); 
        audioScroll.setVisibility(View.GONE);
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
            m.addOption(":network-caching=1000");
            m.addOption(":file-caching=500");
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
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream(); int b;
            while ((b = in.read()) != -1) { hb.write(b); if (hb.size() > 4) { byte[] d = hb.toByteArray(); if (d[d.length-4]=='\r'&&d[d.length-3]=='\n'&&d[d.length-2]=='\r'&&d[d.length-1]=='\n') break; } }
            String req = new String(hb.toByteArray()); String[] lines = req.split("\r\n");
            if (lines.length == 0 || !lines[0].contains("/video")) { out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            
            long rs = 0, re = -1; boolean hr = false;
            for (String l : lines) { if (l.toLowerCase().startsWith("range: bytes=")) { hr = true; String v = l.substring(13).trim(); String[] p = v.split("-"); rs = Long.parseLong(p[0]); if (p.length > 1 && !p[1].isEmpty()) re = Long.parseLong(p[1]); } }
            
            if (videoFile == null || !videoFile.exists()) { out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            
            long fs = videoFile.length();
            String mime = "video/x-matroska";
            
            if (!hr) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + fs + "\r\nAccept-Ranges: bytes\r\nConnection: keep-alive\r\n\r\n").getBytes());
                out.flush();
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                byte[] data = new byte[65536];
                int read = raf.read(data);
                if (read > 0) out.write(data, 0, read);
                raf.close(); out.flush(); client.close();
                return;
            }
            
            if (re == -1 || re >= fs) re = fs - 1;
            long cl = re - rs + 1;
            
            // Aguardar peça se necessário (máx 3s)
            if (pieceLength > 0 && playing) {
                int needed = (int)(rs / pieceLength);
                synchronized (torrentLock) {
                    if (torrentHandle != null && torrentHandle.isValid() && !torrentHandle.havePiece(needed)) {
                        downloadRange(needed, Math.min(numPieces - 1, needed + 3));
                        long waitStart = System.currentTimeMillis();
                        while (!torrentHandle.havePiece(needed) && (System.currentTimeMillis() - waitStart) < 3000) {
                            try { Thread.sleep(200); } catch (InterruptedException ex) {}
                        }
                    }
                }
            }
            
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: " + mime + "\r\nContent-Range: bytes " + rs + "-" + (rs+cl-1) + "/" + fs + "\r\nContent-Length: " + cl + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n").getBytes());
            out.flush();
            
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(rs);
            byte[] buf = new byte[65536];
            long sent = 0;
            while (sent < cl && downloading) {
                int tr = (int)Math.min(buf.length, cl - sent);
                int read = raf.read(buf, 0, tr);
                if (read <= 0) break;
                out.write(buf, 0, read); out.flush();
                sent += read;
            }
            raf.close(); out.flush(); client.close();
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        downloading = true; playing = false; videoFile = null; torrentHandle = null; 
        pieceLength = 0; numPieces = 0; totalSize = 0; videoDurationMs = 0;
        currentPiece = -1; lastMinuteLog = -1; seeking = false;
        criticalPieces.clear();
        
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE); });
        
        downloadThread = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            debug("⏳ Conectando...");
            
            try {
                add_torrent_params p = source.startsWith("magnet:") ? libtorrent.parse_magnet_uri(source, new error_code()) : add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath);
                p.setFlags(libtorrent.getAuto_managed().or_(libtorrent.getApply_ip_filter()));
                p.setDownload_limit(3*1024*1024); // 3MB/s
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
                    
                    // FASE 1: Cabeçalho mínimo (15 peças)
                    int init = Math.min(15, numPieces);
                    byte_vector z = new byte_vector();
                    for (int i = 0; i < numPieces; i++) z.add((byte)0);
                    torrentHandle.swig().prioritize_pieces_ex(z);
                    
                    for (int i = 0; i < init; i++) {
                        torrentHandle.swig().piece_priority_ex(i, (byte)7);
                        torrentHandle.swig().set_piece_deadline(i, 20000);
                    }
                    
                    int done = 0;
                    while (done < init && downloading) { Thread.sleep(200); done = 0; for (int i = 0; i < init; i++) if (torrentHandle.havePiece(i)) done++; }
                    debug("✅ Cabeçalho: " + done + "/" + init + " (" + ((System.currentTimeMillis()-startTime)/1000) + "s)");
                    
                    // Encontrar arquivo e parsear
                    for (int i = 0; i < 15; i++) { File f = find(new File(savePath)); if (f != null && f.length() > 5*1048576) { videoFile = f; break; } Thread.sleep(200); }
                    
                    if (videoFile != null) {
                        parseAndFindCriticalPieces();
                        
                        // FASE 2: Baixar TODAS peças críticas
                        debug("📋 Baixando " + criticalPieces.size() + " peças críticas...");
                        
                        z = new byte_vector();
                        for (int i = 0; i < numPieces; i++) z.add((byte)0);
                        torrentHandle.swig().prioritize_pieces_ex(z);
                        
                        for (int piece : criticalPieces) {
                            if (piece < numPieces) {
                                torrentHandle.swig().piece_priority_ex(piece, (byte)7);
                                torrentHandle.swig().set_piece_deadline(piece, 15000);
                            }
                        }
                        
                        int total = criticalPieces.size();
                        done = 0;
                        while (done < total && downloading) { 
                            Thread.sleep(200); done = 0; 
                            for (int piece : criticalPieces) { if (piece < numPieces && torrentHandle.havePiece(piece)) done++; }
                        }
                        
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        debug("✅ Tudo pronto! " + done + "/" + total + " em " + elapsed + "s");
                    }
                    
                    handler.post(() -> { btnWatch.setText("🎬 ASSISTIR"); btnWatch.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.GONE); });
                    if (videoFile != null) debug("📁 " + videoFile.getName());
                    
                    // NÃO baixar mais nada até o play
                }
                }
            } catch (Exception e) { debug("❌ " + e.getMessage()); downloading = false; }
        });
        downloadThread.start();
    }
    
    private void watch() { 
        if (videoFile == null || !videoFile.exists()) { debug("❌ Aguarde"); return; } 
        handler.post(() -> { videoSurface.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); spinnerBar.setVisibility(View.VISIBLE); playWithVlc("http://127.0.0.1:8080/video"); }); 
    }
    
    private void stop() { 
        downloading = false; playing = false; seeking = false; vlcPreparing = false;
        if (vlcPlayer != null) vlcPlayer.stop(); 
        videoSurface.setVisibility(View.GONE); 
        playerControls.setVisibility(View.GONE); centerControls.setVisibility(View.GONE); 
        audioScroll.setVisibility(View.GONE); subtitleScroll.setVisibility(View.GONE); 
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE); 
        handler.removeCallbacks(timeUpdater); 
        if (downloadThread != null) downloadThread.interrupt();
        synchronized (torrentLock) { if (torrentHandle != null && session != null) { try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} torrentHandle = null; } }
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File found = find(f); if (found != null) return found; } else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f; } return null; }
    
    @Override protected void onDestroy() { stop(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); if (vlcPlayer != null) vlcPlayer.release(); if (libVLC != null) libVLC.release(); super.onDestroy(); }
}