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
    private long lastSeekTime = 0, videoStartTime = 0;
    private boolean surfaceReady = false, isPlaying = false, vlcPreparing = false;
    private String pendingUrl = null;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();
    private static final int PICK_TORRENT = 100;
    private Runnable timeUpdater;
    private int pieceLength = 0, numPieces = 0;
    private long totalSize = 0;
    private long totalRequests = 0, bytesServed = 0;
    private long lastDownloadLog = 0;
    private long lastMinuteLog = 0;
    private long videoDurationMs = 0;
    
    // NOVO: Controle preciso de prioridade
    private int currentPlayingPiece = -1;
    private int lastPrioritizedStart = -1;
    private int lastPrioritizedEnd = -1;
    private long lastPrioritizeTime = 0;
    
    // Lock para proteger acesso ao torrentHandle
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
        
        // Atualizador de tempo - AGORA com priorização inteligente
        timeUpdater = () -> {
            if (vlcPlayer != null && isPlaying && !vlcPreparing) {
                long time = vlcPlayer.getTime();
                long length = vlcPlayer.getLength();
                if (length > 0) videoDurationMs = length;
                if (time >= 0 && length > 0) {
                    timeText.setText(formatTime(time) + " / " + formatTime(length));
                    if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                    
                    // NOVO: Priorizar peça atual baseado no tempo REAL de reprodução
                    prioritizePieceByTime(time);
                    
                    long currentMinute = time / 60000;
                    if (currentMinute != lastMinuteLog && time > 0) {
                        lastMinuteLog = currentMinute;
                        logMinuteInfo(currentMinute);
                    }
                }
            }
            handler.postDelayed(timeUpdater, 1000); // Reduzido para 1s para resposta mais rápida
        };
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { surfaceHolder = h; surfaceReady = true; if (pendingUrl != null) { playWithVlc(pendingUrl); pendingUrl = null; } }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; surfaceHolder = null; }
        });
        
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=3000");  // Aumentado para 3s
        options.add("--file-caching=2000");
        options.add("--no-audio-time-stretch");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Opening: vlcPreparing = true; break;
                case MediaPlayer.Event.Playing: 
                    isPlaying = true; 
                    vlcPreparing = false;
                    handler.post(() -> { 
                        spinnerBar.setVisibility(View.GONE); 
                        btnPlayPause.setText("⏸"); 
                        if (!isTracking) handler.post(timeUpdater); 
                    }); 
                    break;
                case MediaPlayer.Event.Paused: 
                    isPlaying = false; 
                    handler.post(() -> btnPlayPause.setText("▶")); 
                    break;
                case MediaPlayer.Event.Stopped: 
                    isPlaying = false; 
                    vlcPreparing = false;
                    handler.post(() -> btnPlayPause.setText("▶")); 
                    break;
                case MediaPlayer.Event.Buffering:
                    handler.post(() -> spinnerBar.setVisibility(View.VISIBLE));
                    break;
                case MediaPlayer.Event.EndReached:
                    isPlaying = false;
                    handler.post(() -> btnPlayPause.setText("▶"));
                    break;
                // NOVO: Detectar time change (seek)
                case MediaPlayer.Event.TimeChanged:
                    long newTime = vlcPlayer.getTime();
                    if (newTime > 0) {
                        prioritizePieceByTime(newTime);
                    }
                    break;
            }
        });
        
        btnPlayPause.setOnClickListener(v -> { 
            if (vlcPlayer != null && !vlcPreparing) { 
                if (isPlaying) { vlcPlayer.pause(); debug("⏸ Pausado"); } 
                else { vlcPlayer.play(); debug("▶ Play"); } 
            } 
        });
        btnSeekBack.setOnClickListener(v -> { if (!vlcPreparing) seekRelative(-10000); });
        btnSeekForward.setOnClickListener(v -> { if (!vlcPreparing) seekRelative(10000); });
        
        btnSkip20.setOnClickListener(v -> {
            if (vlcPlayer != null && !vlcPreparing && videoFile != null && videoFile.exists()) {
                long targetTime = 20 * 60 * 1000;
                if (vlcPlayer.getLength() > 0 && targetTime < vlcPlayer.getLength()) {
                    debug("⏭ Pulando para 20:00...");
                    vlcPlayer.setTime(targetTime);
                    prioritizePieceByTime(targetTime); // Priorizar imediatamente
                }
            }
        });
        
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && !vlcPreparing && vlcPlayer.getLength() > 0) seekAbsolute(p); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { 
                isTracking = false;
                // NOVO: Priorizar após seek manual
                if (vlcPlayer != null && !vlcPreparing) {
                    prioritizePieceByTime(vlcPlayer.getTime());
                }
            }
        });
        
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        debug("=== TORRENT STREAM DEBUG v2 ===");
        debug("Priorização por tempo real de reprodução");
        new Thread(() -> { try { session = new SessionManager(); session.start(); debug("✅ Sessão OK"); } catch (Exception e) { debug("❌ " + e.getMessage()); } }).start();
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        debug("📱 Pronto");
    }
    
    // NOVO: Método central de priorização
    private void prioritizePieceByTime(long timeMs) {
        if (pieceLength <= 0 || numPieces <= 0 || videoFile == null || videoDurationMs <= 0) return;
        
        // Calcular posição no arquivo baseado no tempo
        long fileSize = videoFile.length();
        if (fileSize <= 0) return;
        
        long bytePosition = timeMs * fileSize / videoDurationMs;
        int targetPiece = (int)(bytePosition / pieceLength);
        
        if (targetPiece == currentPlayingPiece) return; // Já está na peça correta
        
        currentPlayingPiece = targetPiece;
        
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) return;
            
            // Calcular range: peça atual + próximas 20 peças (~10-20 segundos)
            int startPiece = targetPiece;
            int endPiece = Math.min(numPieces - 1, targetPiece + 20);
            
            // Evitar repriorizar o mesmo range em menos de 2 segundos
            if (startPiece == lastPrioritizedStart && endPiece == lastPrioritizedEnd && 
                System.currentTimeMillis() - lastPrioritizeTime < 2000) {
                return;
            }
            
            lastPrioritizedStart = startPiece;
            lastPrioritizedEnd = endPiece;
            lastPrioritizeTime = System.currentTimeMillis();
            
            try {
                // Resetar TODAS as prioridades para 0
                for (int i = 0; i < numPieces; i++) {
                    torrentHandle.swig().piece_priority_ex(i, (byte)0);
                }
                
                // Prioridade máxima para peça atual
                torrentHandle.swig().piece_priority_ex(targetPiece, (byte)7);
                torrentHandle.swig().set_piece_deadline(targetPiece, 10000);
                
                // Prioridade alta para próximas peças (decrescente)
                for (int i = targetPiece + 1; i <= endPiece; i++) {
                    if (i >= numPieces) break;
                    int dist = i - targetPiece;
                    byte priority = (byte)(dist <= 5 ? 7 : (dist <= 10 ? 5 : 3));
                    torrentHandle.swig().piece_priority_ex(i, priority);
                    torrentHandle.swig().set_piece_deadline(i, 15000 + (dist * 1000));
                }
                
                long currentMinute = timeMs / 60000;
                long currentSecond = (timeMs / 1000) % 60;
                debug("🎯 Priorizado: peça " + targetPiece + " (min " + currentMinute + ":" + 
                      String.format("%02d", currentSecond) + ") + " + (endPiece - startPiece) + 
                      " peças à frente");
                
            } catch (Exception e) {
                debug("❌ Erro priorização: " + e.getMessage());
            }
        }
    }
    
    private void logMinuteInfo(long currentMinute) {
        if (videoDurationMs <= 0 || pieceLength <= 0 || numPieces <= 0 || videoFile == null) return;
        
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) return;
            
            long byteAtMinute = currentMinute * 60 * 1000 * videoFile.length() / videoDurationMs;
            int startPiece = (int)(byteAtMinute / pieceLength);
            long byteAtNextMinute = (currentMinute + 1) * 60 * 1000 * videoFile.length() / videoDurationMs;
            int endPiece = (int)(byteAtNextMinute / pieceLength);
            int piecesDownloaded = 0;
            int totalPiecesInMinute = endPiece - startPiece + 1;
            for (int i = startPiece; i <= endPiece && i < numPieces; i++) {
                if (torrentHandle.havePiece(i)) piecesDownloaded++;
            }
            int percentMinute = totalPiecesInMinute > 0 ? (piecesDownloaded * 100 / totalPiecesInMinute) : 0;
            if (percentMinute < 100 || currentMinute % 5 == 0) {
                debug("⏱ Min " + currentMinute + ": peças " + startPiece + "-" + endPiece + " | " + piecesDownloaded + "/" + totalPiecesInMinute + " (" + percentMinute + "%)");
            }
        }
    }
    
    private void seekRelative(long delta) { 
        if (vlcPlayer == null || vlcPlayer.getLength() <= 0 || vlcPreparing) return; 
        long t = Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + delta)); 
        vlcPlayer.setTime(t);
        prioritizePieceByTime(t); // Priorizar após seek
    }
    
    private void seekAbsolute(int pct) { 
        if (vlcPlayer == null || vlcPlayer.getLength() <= 0 || vlcPreparing) return; 
        long t = (long)(vlcPlayer.getLength() * pct / 100.0); 
        vlcPlayer.setTime(t);
        prioritizePieceByTime(t); // Priorizar após seek
    }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
        int current = vlcPlayer.getAudioTrack();
        audioMenu.removeAllViews();
        if (tracks != null) {
            debug("🎵 Áudio disponível: " + tracks.length + " faixas");
            for (MediaPlayer.TrackDescription t : tracks) {
                if (t.id >= 0) {
                    TextView tv = new TextView(this); 
                    tv.setText("🎵 " + t.name + (t.id == current ? " ✓" : "")); 
                    tv.setTextColor(t.id == current ? 0xFF6c5ce7 : 0xFFFFFFFF); 
                    tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                    final int id = t.id; 
                    tv.setOnClickListener(v -> { vlcPlayer.setAudioTrack(id); audioScroll.setVisibility(View.GONE); debug("🎵 Áudio alterado: " + t.name); }); 
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
        int current = vlcPlayer.getSpuTrack();
        subtitleMenu.removeAllViews();
        TextView off = new TextView(this); 
        off.setText("📝 Desligado" + (current == -1 ? " ✓" : "")); 
        off.setTextColor(current == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); 
        off.setTextSize(12); off.setPadding(16, 12, 16, 12);
        off.setOnClickListener(v -> { vlcPlayer.setSpuTrack(-1); subtitleScroll.setVisibility(View.GONE); });
        subtitleMenu.addView(off);
        if (tracks != null) {
            debug("📝 Legendas disponíveis: " + tracks.length + " faixas");
            for (MediaPlayer.TrackDescription t : tracks) {
                if (t.id >= 0) {
                    TextView tv = new TextView(this); 
                    tv.setText("📝 " + t.name + (t.id == current ? " ✓" : "")); 
                    tv.setTextColor(t.id == current ? 0xFF6c5ce7 : 0xFFFFFFFF); 
                    tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                    final int id = t.id; 
                    tv.setOnClickListener(v -> { vlcPlayer.setSpuTrack(id); subtitleScroll.setVisibility(View.GONE); debug("📝 Legenda alterada: " + t.name); }); 
                    subtitleMenu.addView(tv);
                }
            }
        }
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); 
        audioScroll.setVisibility(View.GONE);
    }
    
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms / 1000); int m = s / 60; s = s % 60; return m + ":" + (s < 10 ? "0" : "") + s; }
    
    private void playWithVlc(String url) {
        if (!surfaceReady || surfaceHolder == null) { pendingUrl = url; return; }
        try {
            vlcPreparing = true;
            vlcPlayer.getVLCVout().setVideoSurface(surfaceHolder.getSurface(), null);
            vlcPlayer.getVLCVout().setWindowSize(videoSurface.getWidth(), videoSurface.getHeight());
            vlcPlayer.getVLCVout().attachViews();
            Media m = new Media(libVLC, Uri.parse(url));
            m.setHWDecoderEnabled(true, true);
            m.addOption(":network-caching=3000");
            m.addOption(":file-caching=2000");
            vlcPlayer.setMedia(m);
            m.release();
            vlcPlayer.play();
            handler.post(() -> { 
                playerControls.setVisibility(View.VISIBLE); 
                centerControls.setVisibility(View.VISIBLE); 
                btnSkip20.setVisibility(View.VISIBLE);
            });
            debug("[VLC] ▶ Reproduzindo");
        } catch (Exception e) { 
            vlcPreparing = false;
            debug("[VLC] ❌ " + e.getMessage()); 
        }
    }
    
    @Override protected void onActivityResult(int r, int res, Intent data) {
        super.onActivityResult(r, res, data);
        if (r == PICK_TORRENT && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) try {
                InputStream is = getContentResolver().openInputStream(uri);
                File tf = new File(savePath, "torrent_file.torrent");
                FileOutputStream fos = new FileOutputStream(tf); byte[] b = new byte[8192]; int l;
                while ((l = is.read(b)) > 0) fos.write(b, 0, l); fos.close(); is.close();
                startDownload(tf.getAbsolutePath());
            } catch (Exception e) { debug("❌ " + e.getMessage()); }
        }
    }
    
    private void debug(String msg) { String line = "[" + sdf.format(new Date()) + "] " + msg + "\n"; Log.d("TS", msg); debugLog.append(line); handler.post(() -> { statusText.setText(msg); debugText.setText(debugLog.toString()); }); }
    
    private void startServer() { serverThread = new Thread(() -> { try { ServerSocket s = new ServerSocket(8080, 10); s.setReuseAddress(true); while (!Thread.interrupted()) { try { Socket c = s.accept(); new Thread(() -> handleHttp(c)).start(); } catch (IOException e) {} } s.close(); } catch (IOException e) {} }); serverThread.setDaemon(true); serverThread.start(); }
    
    // Servidor HTTP SIMPLIFICADO - não tenta mais priorizar
    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(60000);
            InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream(); int b;
            while ((b = in.read()) != -1) { hb.write(b); if (hb.size() > 4) { byte[] d = hb.toByteArray(); if (d[d.length-4]=='\r'&&d[d.length-3]=='\n'&&d[d.length-2]=='\r'&&d[d.length-1]=='\n') break; } }
            String req = new String(hb.toByteArray()); String[] lines = req.split("\r\n");
            if (lines.length == 0 || !lines[0].contains("/video")) { out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            
            long rs = 0, re = -1; boolean hr = false;
            for (String l : lines) { if (l.toLowerCase().startsWith("range: bytes=")) { hr = true; String v = l.substring(13).trim(); String[] p = v.split("-"); rs = Long.parseLong(p[0]); if (p.length > 1 && !p[1].isEmpty()) re = Long.parseLong(p[1]); } }
            
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
            
            // SIMPLES: Apenas log e serve - priorização é feita pelo timeUpdater
            long estimatedTimeMs = 0;
            if (videoDurationMs > 0 && fs > 0) estimatedTimeMs = rs * videoDurationMs / fs;
            long estimatedMinute = estimatedTimeMs / 60000;
            long estimatedSecond = (estimatedTimeMs / 1000) % 60;
            int pieceNeeded = pieceLength > 0 ? (int)(rs / pieceLength) : -1;
            
            // Log apenas, sem alterar prioridades
            if (totalRequests % 10 == 0 && pieceNeeded >= 0) {
                debug("📡 HTTP #" + totalRequests + ": min " + estimatedMinute + ":" + 
                      String.format("%02d", estimatedSecond) + " (peça " + pieceNeeded + ")");
            }
            
            totalRequests++;
            String headers = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nContent-Range: bytes " + rs + "-" + (rs+cl-1) + "/" + fs + "\r\nContent-Length: " + cl + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
            out.write(headers.getBytes());
            
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(rs);
            byte[] buf = new byte[65536];
            long sent = 0;
            while (sent < cl && downloading) {
                int tr = (int)Math.min(buf.length, cl - sent);
                int read = raf.read(buf, 0, tr);
                if (read <= 0) { Thread.sleep(100); continue; }
                out.write(buf, 0, read);
                out.flush();
                sent += read;
            }
            bytesServed += sent;
            raf.close();
            out.flush();
            client.close();
            
            if (totalRequests % 100 == 0) debug("📡 HTTP #" + totalRequests + ": " + (bytesServed/1048576) + "MB servidos");
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        downloading = true; videoFile = null; torrentHandle = null; videoStartTime = 0; 
        pieceLength = 0; numPieces = 0; totalSize = 0; totalRequests = 0; bytesServed = 0; 
        lastDownloadLog = 0; lastMinuteLog = -1; videoDurationMs = 0;
        currentPlayingPiece = -1;
        lastPrioritizedStart = -1;
        lastPrioritizedEnd = -1;
        lastPrioritizeTime = 0;
        
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE); });
        debug("⏳ Conectando ao tracker...");
        long startTime = System.currentTimeMillis();
        
        new Thread(() -> {
            try {
                add_torrent_params p; if (source.startsWith("magnet:")) p = libtorrent.parse_magnet_uri(source, new error_code()); else p = add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath);
                torrent_flags_t flags = libtorrent.getAuto_managed().or_(libtorrent.getSequential_download()).or_(libtorrent.getApply_ip_filter());
                p.setFlags(flags);
                p.setDownload_limit(3*1024*1024);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                session.swig().async_add_torrent(p); Thread.sleep(3000);
                
                synchronized (torrentLock) {
                    torrent_handle_vector h = session.swig().get_torrents(); 
                    if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0));
                }
                
                int w = 0; 
                while (w < 60 && downloading) { 
                    Thread.sleep(1000); w++;
                    synchronized (torrentLock) {
                        if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) break;
                    }
                }
                
                synchronized (torrentLock) {
                if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) {
                    TorrentInfo ti = torrentHandle.torrentFile();
                    int np = ti.numPieces(), pl = ti.pieceLength();
                    pieceLength = pl; numPieces = np; totalSize = ti.totalSize();
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    torrent_status st = torrentHandle.swig().status();
                    debug("📊 " + (totalSize/1048576) + "MB, " + np + " peças de " + (pl/1024) + "KB (" + elapsed + "s) | Peers: " + st.getNum_peers() + " seeds: " + st.getNum_seeds());
                    
                    int meta = Math.min(20, np);
                    debug("📋 Cabeçalho: " + meta + " peças (~" + (meta*pl/1048576) + "MB)");
                    
                    // Priorizar cabeçalho
                    byte_vector headerPriorities = new byte_vector();
                    for (int i = 0; i < np; i++) {
                        if (i < meta) headerPriorities.add((byte)7);
                        else headerPriorities.add((byte)0);
                    }
                    torrentHandle.swig().prioritize_pieces_ex(headerPriorities);
                    for (int i = 0; i < meta; i++) torrentHandle.swig().set_piece_deadline(i, 5000);
                    
                    int complete = 0, wt = 0; boolean shown = false;
                    while (wt < 120 && downloading) { 
                        Thread.sleep(500); complete = 0; wt++; 
                        for (int i = 0; i < meta; i++) if (torrentHandle.havePiece(i)) complete++; 
                        if (wt % 4 == 0) debug("   📋 " + complete + "/" + meta + " (" + (wt/2) + "s)"); 
                        if (!shown && complete >= meta) { 
                            shown = true; 
                            long t2 = (System.currentTimeMillis() - startTime) / 1000; 
                            debug("✅ Cabeçalho OK! " + complete + "/" + meta + " em " + t2 + "s");
                            
                            // Após cabeçalho, baixar sequencial do início
                            byte_vector normalPriorities = new byte_vector();
                            for (int i = 0; i < np; i++) {
                                if (i < 50) normalPriorities.add((byte)6); // Primeiras 50 peças
                                else normalPriorities.add((byte)0); // Resto sem prioridade
                            }
                            torrentHandle.swig().prioritize_pieces_ex(normalPriorities);
                            for (int i = 0; i < 50; i++) torrentHandle.swig().set_piece_deadline(i, 30000);
                            
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
                            long dl = torrentHandle.swig().status().getTotal_done();
                            if (dl - lastDownloadLog > 10485760) { 
                                lastDownloadLog = dl;
                                int percent = totalSize > 0 ? (int)(dl * 100 / totalSize) : 0;
                                debug("📥 Download: " + (dl/1048576) + "MB / " + (totalSize/1048576) + "MB (" + percent + "%)");
                            }
                        }
                    }
                } catch (Exception e) {}
            }
        }).start();
    }
    
    private void watch() { 
        if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não encontrado"); return; } 
        debug("▶️ VLC: " + videoFile.getName()); 
        handler.post(() -> { 
            videoSurface.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE); 
            btnSkip20.setVisibility(View.VISIBLE); 
            spinnerBar.setVisibility(View.VISIBLE); 
            playWithVlc("http://127.0.0.1:8080/video"); 
        }); 
    }
    
    private void stop() { 
        downloading = false; vlcPreparing = false;
        if (vlcPlayer != null) vlcPlayer.stop(); 
        videoSurface.setVisibility(View.GONE); 
        playerControls.setVisibility(View.GONE); centerControls.setVisibility(View.GONE); 
        audioScroll.setVisibility(View.GONE); subtitleScroll.setVisibility(View.GONE); 
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE); 
        handler.removeCallbacks(timeUpdater); 
        synchronized (torrentLock) {
            if (torrentHandle != null && session != null) { 
                try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} 
                torrentHandle = null; 
            } 
        }
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File found = find(f); if (found != null) return found; } else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f; } return null; }
    
    @Override protected void onDestroy() { stop(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); if (vlcPlayer != null) vlcPlayer.release(); if (libVLC != null) libVLC.release(); super.onDestroy(); }
}