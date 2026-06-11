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
    private long videoStartTime = 0;
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
    
    // Controle de prioridade
    private int currentPlayingPiece = -1;
    private boolean isSeeking = false;
    private long seekTargetTime = 0;
    private long seekStartTime = 0;
    private int seekTargetPiece = -1;
    private Thread seekMonitorThread;
    
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
        
        // timeUpdater
        timeUpdater = () -> {
            if (vlcPlayer != null && isPlaying && !vlcPreparing) {
                long time = vlcPlayer.getTime();
                long length = vlcPlayer.getLength();
                
                if (length > 0) {
                    videoDurationMs = length;
                    if (time >= 0) {
                        timeText.setText(formatTime(time) + " / " + formatTime(length));
                        if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                        
                        if (!isSeeking) maintainBuffer(time);
                        
                        long currentMinute = time / 60000;
                        if (currentMinute != lastMinuteLog && time > 0) {
                            lastMinuteLog = currentMinute;
                            logMinuteInfo(currentMinute);
                        }
                    }
                }
            }
            handler.postDelayed(timeUpdater, 2000);
        };
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { 
                surfaceHolder = h; surfaceReady = true; 
                if (pendingUrl != null) { playWithVlc(pendingUrl); pendingUrl = null; } 
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; surfaceHolder = null; }
        });
        
        // Configuração VLC OTIMIZADA para streaming
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=6000");     // 6 segundos de buffer de rede
        options.add("--file-caching=3000");        // 3 segundos de buffer de arquivo
        options.add("--clock-synchro=0");          // Não sincronizar clock
        options.add("--live-caching=3000");        // Cache para live (ajuda streaming)
        options.add("--no-drop-late-frames");      // Não dropar frames atrasados
        options.add("--no-skip-frames");           // Não pular frames
        options.add("-vvv");                       // Log verbose
        
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Opening: 
                    vlcPreparing = true; 
                    debug("[VLC] Abrindo stream...");
                    break;
                case MediaPlayer.Event.Playing: 
                    isPlaying = true; 
                    vlcPreparing = false;
                    handler.post(() -> { 
                        spinnerBar.setVisibility(View.GONE); 
                        btnPlayPause.setText("⏸");
                        handler.post(timeUpdater);
                    }); 
                    debug("[VLC] ▶ Tocando! Duração: " + (vlcPlayer.getLength()/1000) + "s");
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
                    float buf = event.getBuffering();
                    handler.post(() -> {
                        spinnerBar.setVisibility(View.VISIBLE);
                        debug("[VLC] Buffering: " + buf + "%");
                    });
                    break;
                case MediaPlayer.Event.EndReached:
                    isPlaying = false;
                    handler.post(() -> btnPlayPause.setText("▶"));
                    debug("[VLC] Fim do vídeo");
                    break;
                case MediaPlayer.Event.EncounteredError:
                    debug("[VLC] ❌ Erro!");
                    vlcPreparing = false;
                    handler.post(() -> spinnerBar.setVisibility(View.GONE));
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
            if (vlcPlayer != null && !vlcPreparing && videoFile != null) {
                executeSeek(20 * 60 * 1000);
            }
        });
        
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { 
                if (user && vlcPlayer != null && !vlcPreparing && vlcPlayer.getLength() > 0) {
                    long t = (long)(vlcPlayer.getLength() * p / 100.0);
                    executeSeek(t);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        debug("=== TORRENT STREAM v5 ===");
        new Thread(() -> { try { session = new SessionManager(); session.start(); debug("✅ Sessão OK"); } catch (Exception e) { debug("❌ " + e.getMessage()); } }).start();
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        debug("📱 Pronto");
    }
    
    // ==================== SERVIDOR HTTP CORRIGIDO ====================
    private void startServer() { 
        serverThread = new Thread(() -> { 
            try { 
                ServerSocket s = new ServerSocket(8080, 10); 
                s.setReuseAddress(true); 
                debug("🌐 Servidor HTTP na porta 8080");
                while (!Thread.interrupted()) { 
                    try { Socket c = s.accept(); new Thread(() -> handleHttp(c)).start(); } 
                    catch (IOException e) {} 
                } 
                s.close(); 
            } catch (IOException e) {
                debug("❌ Erro servidor: " + e.getMessage());
            } 
        }); 
        serverThread.setDaemon(true); 
        serverThread.start(); 
    }
    
    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream(); 
            OutputStream out = client.getOutputStream();
            
            // Ler headers
            ByteArrayOutputStream hb = new ByteArrayOutputStream(); 
            int b;
            while ((b = in.read()) != -1) { 
                hb.write(b); 
                if (hb.size() > 4) { 
                    byte[] d = hb.toByteArray(); 
                    if (d[d.length-4]=='\r'&&d[d.length-3]=='\n'&&d[d.length-2]=='\r'&&d[d.length-1]=='\n') break; 
                } 
            }
            String req = new String(hb.toByteArray()); 
            String[] lines = req.split("\r\n");
            
            if (lines.length == 0 || !lines[0].contains("/video")) { 
                out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes()); 
                out.flush(); client.close(); return; 
            }
            
            // Parse Range
            long rangeStart = 0, rangeEnd = -1; 
            boolean hasRange = false;
            for (String l : lines) { 
                if (l.toLowerCase().startsWith("range: bytes=")) { 
                    hasRange = true; 
                    String v = l.substring(13).trim(); 
                    String[] p = v.split("-"); 
                    rangeStart = Long.parseLong(p[0]); 
                    if (p.length > 1 && !p[1].isEmpty()) rangeEnd = Long.parseLong(p[1]); 
                } 
            }
            
            if (videoFile == null || !videoFile.exists()) { 
                String body = "Video not ready";
                out.write(("HTTP/1.1 404 Not Found\r\nContent-Length: " + body.length() + "\r\n\r\n" + body).getBytes()); 
                out.flush(); client.close(); return; 
            }
            
            long fileSize = videoFile.length();
            
            // Verificar se arquivo tem conteúdo real (> 1MB de dados não-nulos)
            if (fileSize < 1024 * 1024) {
                String body = "File too small: " + fileSize + " bytes";
                out.write(("HTTP/1.1 503 Service Unavailable\r\nContent-Length: " + body.length() + "\r\nRetry-After: 2\r\n\r\n" + body).getBytes());
                out.flush(); client.close(); return;
            }
            
            // Primeira requisição: enviar arquivo inteiro para VLC detectar formato
            if (!hasRange) {
                totalRequests++;
                debug("📡 HTTP #" + totalRequests + ": 200 OK (full file, " + (fileSize/1048576) + "MB)");
                
                // Para MKV, enviar Content-Type correto
                String mime = "video/x-matroska";
                String name = videoFile.getName().toLowerCase();
                if (name.endsWith(".mp4")) mime = "video/mp4";
                else if (name.endsWith(".webm")) mime = "video/webm";
                else if (name.endsWith(".avi")) mime = "video/x-msvideo";
                
                StringBuilder response = new StringBuilder();
                response.append("HTTP/1.1 200 OK\r\n");
                response.append("Content-Type: " + mime + "\r\n");
                response.append("Content-Length: " + fileSize + "\r\n");
                response.append("Accept-Ranges: bytes\r\n");
                response.append("Connection: keep-alive\r\n");
                response.append("Access-Control-Allow-Origin: *\r\n");
                response.append("Cache-Control: no-cache\r\n");
                response.append("\r\n");
                out.write(response.toString().getBytes());
                out.flush();
                
                // Enviar dados em chunks
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                byte[] buf = new byte[262144]; // 256KB chunks
                int read;
                long sent = 0;
                while ((read = raf.read(buf)) > 0 && downloading) {
                    out.write(buf, 0, read);
                    out.flush();
                    sent += read;
                    // Pequena pausa para não sobrecarregar
                    if (sent % (1024*1024) == 0) Thread.sleep(10);
                }
                raf.close();
                bytesServed += sent;
                out.flush();
                client.close();
                return;
            }
            
            // Range request
            if (rangeEnd == -1 || rangeEnd >= fileSize) rangeEnd = fileSize - 1;
            long contentLength = rangeEnd - rangeStart + 1;
            
            totalRequests++;
            
            // Log
            if (videoDurationMs > 0 && fileSize > 0) {
                long estimatedTime = rangeStart * videoDurationMs / fileSize;
                long min = estimatedTime / 60000;
                long sec = (estimatedTime / 1000) % 60;
                int piece = pieceLength > 0 ? (int)(rangeStart / pieceLength) : -1;
                if (totalRequests % 5 == 0 || isSeeking) {
                    debug("📡 HTTP #" + totalRequests + ": min " + min + ":" + String.format("%02d", sec) + 
                          " (" + (rangeStart/1048576) + "MB, peça " + piece + ")");
                }
            }
            
            String mime = "video/x-matroska";
            String name = videoFile.getName().toLowerCase();
            if (name.endsWith(".mp4")) mime = "video/mp4";
            else if (name.endsWith(".webm")) mime = "video/webm";
            
            StringBuilder response = new StringBuilder();
            response.append("HTTP/1.1 206 Partial Content\r\n");
            response.append("Content-Type: " + mime + "\r\n");
            response.append("Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + fileSize + "\r\n");
            response.append("Content-Length: " + contentLength + "\r\n");
            response.append("Accept-Ranges: bytes\r\n");
            response.append("Connection: keep-alive\r\n");
            response.append("Access-Control-Allow-Origin: *\r\n");
            response.append("Cache-Control: no-cache\r\n");
            response.append("\r\n");
            out.write(response.toString().getBytes());
            out.flush();
            
            // Enviar dados
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(rangeStart);
            byte[] buf = new byte[262144];
            long sent = 0;
            int emptyReads = 0;
            
            while (sent < contentLength && downloading) {
                int toRead = (int)Math.min(buf.length, contentLength - sent);
                int read = raf.read(buf, 0, toRead);
                
                if (read <= 0) {
                    emptyReads++;
                    if (emptyReads > 100) break; // 10 segundos timeout
                    Thread.sleep(100);
                    // Verificar se arquivo cresceu
                    if (videoFile.length() > fileSize) {
                        fileSize = videoFile.length();
                        if (rangeEnd >= fileSize) rangeEnd = fileSize - 1;
                        contentLength = rangeEnd - rangeStart + 1;
                    }
                    continue;
                }
                
                emptyReads = 0;
                out.write(buf, 0, read);
                out.flush();
                sent += read;
            }
            
            bytesServed += sent;
            raf.close();
            out.flush();
            client.close();
            
        } catch (Exception e) { 
            try { client.close(); } catch (IOException ex) {} 
        }
    }
    
    // ==================== SEEK ====================
    private void executeSeek(long targetTime) {
        if (vlcPlayer == null) return;
        
        // Executar seek no VLC primeiro
        vlcPlayer.setTime(targetTime);
        
        // Se não tem info de peças, só fazer seek no VLC
        if (pieceLength <= 0 || numPieces <= 0 || videoFile == null || videoDurationMs <= 0) {
            return;
        }
        
        long fileSize = videoFile.length();
        long bytePos = targetTime * fileSize / videoDurationMs;
        int targetPiece = (int)(bytePos / pieceLength);
        
        long minute = targetTime / 60000;
        long second = (targetTime / 1000) % 60;
        
        // Cancelar monitor anterior
        isSeeking = true;
        seekTargetTime = targetTime;
        seekTargetPiece = targetPiece;
        seekStartTime = System.currentTimeMillis();
        
        if (seekMonitorThread != null && seekMonitorThread.isAlive()) {
            seekMonitorThread.interrupt();
        }
        
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) {
                isSeeking = false;
                return;
            }
            
            try {
                // Verificar se peça já existe
                if (torrentHandle.havePiece(targetPiece)) {
                    debug("✅ Peça " + targetPiece + " já existe");
                    isSeeking = false;
                    return;
                }
                
                debug("🔥 SEEK min " + minute + ":" + String.format("%02d", second) + 
                      " (peça " + targetPiece + ")");
                
                // ZERAR TODAS prioridades
                byte_vector zeroAll = new byte_vector();
                for (int i = 0; i < numPieces; i++) zeroAll.add((byte)0);
                torrentHandle.swig().prioritize_pieces_ex(zeroAll);
                
                // Remover deadlines
                for (int i = 0; i < numPieces; i++) {
                    try { torrentHandle.swig().reset_piece_deadline(i); } catch (Exception e) {}
                }
                
                // Prioridade máxima para peça alvo
                torrentHandle.swig().piece_priority_ex(targetPiece, (byte)7);
                torrentHandle.swig().set_piece_deadline(targetPiece, 5000);
                
                // Próximas peças
                int endPiece = Math.min(numPieces - 1, targetPiece + 30);
                for (int i = targetPiece + 1; i <= endPiece; i++) {
                    byte prio = (i <= targetPiece + 5) ? (byte)6 : (byte)4;
                    torrentHandle.swig().piece_priority_ex(i, prio);
                    torrentHandle.swig().set_piece_deadline(i, 15000);
                }
                
                // Monitor
                seekMonitorThread = new Thread(() -> {
                    int checks = 0;
                    while (isSeeking && downloading && checks < 120) {
                        try { Thread.sleep(250); } catch (InterruptedException e) { break; }
                        checks++;
                        
                        synchronized (torrentLock) {
                            if (torrentHandle == null || !torrentHandle.isValid()) break;
                            try {
                                if (torrentHandle.havePiece(targetPiece)) {
                                    long elapsed = (System.currentTimeMillis() - seekStartTime) / 1000;
                                    handler.post(() -> {
                                        debug("✅ Peça " + targetPiece + " chegou em " + elapsed + "s!");
                                        isSeeking = false;
                                        spinnerBar.setVisibility(View.GONE);
                                    });
                                    return;
                                }
                                // Reforçar deadline
                                if (checks % 4 == 0) {
                                    torrentHandle.swig().set_piece_deadline(targetPiece, 5000);
                                }
                                if (checks % 20 == 0) {
                                    final int s = checks / 4;
                                    handler.post(() -> debug("   ⏳ " + s + "s aguardando peça " + targetPiece + "..."));
                                }
                            } catch (Exception e) {}
                        }
                    }
                    if (isSeeking) {
                        handler.post(() -> {
                            debug("⏰ Timeout peça " + targetPiece);
                            isSeeking = false;
                            spinnerBar.setVisibility(View.GONE);
                        });
                    }
                });
                seekMonitorThread.setDaemon(true);
                seekMonitorThread.start();
                
            } catch (Exception e) {
                debug("❌ Erro seek: " + e.getMessage());
                isSeeking = false;
            }
        }
    }
    
    private void maintainBuffer(long currentTimeMs) {
        if (pieceLength <= 0 || numPieces <= 0 || videoFile == null || videoDurationMs <= 0) return;
        
        long fileSize = videoFile.length();
        long bytePos = currentTimeMs * fileSize / videoDurationMs;
        int currentPiece = (int)(bytePos / pieceLength);
        
        // 30 segundos de buffer
        long byte30Sec = 30000 * fileSize / videoDurationMs;
        int piecesNeeded = (int)(byte30Sec / pieceLength) + 3;
        
        if (currentPiece == currentPlayingPiece) return;
        currentPlayingPiece = currentPiece;
        
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) return;
            try {
                int endPiece = Math.min(numPieces - 1, currentPiece + piecesNeeded);
                
                // Priorizar apenas range atual
                for (int i = 0; i < numPieces; i++) {
                    if (i >= currentPiece && i <= endPiece) {
                        if (!torrentHandle.havePiece(i)) {
                            byte prio = (i == currentPiece) ? (byte)7 : 
                                       (i <= currentPiece + 3) ? (byte)6 : (byte)4;
                            torrentHandle.swig().piece_priority_ex(i, prio);
                        }
                    } else if (i < currentPiece - 5 || i > endPiece + 5) {
                        torrentHandle.swig().piece_priority_ex(i, (byte)0);
                    }
                }
                
                if (!torrentHandle.havePiece(currentPiece)) {
                    torrentHandle.swig().set_piece_deadline(currentPiece, 8000);
                }
            } catch (Exception e) {}
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
            int downloaded = 0, total = endPiece - startPiece + 1;
            for (int i = startPiece; i <= endPiece && i < numPieces; i++) {
                if (torrentHandle.havePiece(i)) downloaded++;
            }
            int pct = total > 0 ? (downloaded * 100 / total) : 0;
            if (pct < 100 || currentMinute % 5 == 0) {
                debug("⏱ Min " + currentMinute + ": " + downloaded + "/" + total + " peças (" + pct + "%)");
            }
        }
    }
    
    private void seekRelative(long delta) { 
        if (vlcPlayer == null || vlcPlayer.getLength() <= 0 || vlcPreparing) return; 
        long t = Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + delta));
        executeSeek(t);
    }
    
    private String formatTime(long ms) { 
        if (ms < 0) return "0:00"; 
        int s = (int)(ms / 1000); 
        int m = s / 60; 
        s = s % 60; 
        return m + ":" + (s < 10 ? "0" : "") + s; 
    }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
        int current = vlcPlayer.getAudioTrack();
        audioMenu.removeAllViews();
        if (tracks != null) {
            for (MediaPlayer.TrackDescription t : tracks) {
                if (t.id >= 0) {
                    TextView tv = new TextView(this); 
                    tv.setText("🎵 " + t.name + (t.id == current ? " ✓" : "")); 
                    tv.setTextColor(t.id == current ? 0xFF6c5ce7 : 0xFFFFFFFF); 
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
        int current = vlcPlayer.getSpuTrack();
        subtitleMenu.removeAllViews();
        TextView off = new TextView(this); 
        off.setText("📝 Desligado" + (current == -1 ? " ✓" : "")); 
        off.setTextColor(current == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); 
        off.setTextSize(12); off.setPadding(16, 12, 16, 12);
        off.setOnClickListener(v -> { vlcPlayer.setSpuTrack(-1); subtitleScroll.setVisibility(View.GONE); });
        subtitleMenu.addView(off);
        if (tracks != null) {
            for (MediaPlayer.TrackDescription t : tracks) {
                if (t.id >= 0) {
                    TextView tv = new TextView(this); 
                    tv.setText("📝 " + t.name + (t.id == current ? " ✓" : "")); 
                    tv.setTextColor(t.id == current ? 0xFF6c5ce7 : 0xFFFFFFFF); 
                    tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                    final int id = t.id; 
                    tv.setOnClickListener(v -> { vlcPlayer.setSpuTrack(id); subtitleScroll.setVisibility(View.GONE); }); 
                    subtitleMenu.addView(tv);
                }
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
            m.addOption(":network-caching=6000");
            m.addOption(":file-caching=3000");
            m.addOption(":clock-synchro=0");
            m.addOption(":no-audio-time-stretch");
            
            vlcPlayer.setMedia(m);
            m.release();
            vlcPlayer.play();
            
            handler.post(() -> { 
                playerControls.setVisibility(View.VISIBLE); 
                centerControls.setVisibility(View.VISIBLE); 
                btnSkip20.setVisibility(View.VISIBLE);
            });
            debug("[VLC] ▶ Iniciando reprodução...");
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
                FileOutputStream fos = new FileOutputStream(tf); 
                byte[] buf = new byte[8192]; int l;
                while ((l = is.read(buf)) > 0) fos.write(buf, 0, l); 
                fos.close(); is.close();
                startDownload(tf.getAbsolutePath());
            } catch (Exception e) { debug("❌ " + e.getMessage()); }
        }
    }
    
    private void debug(String msg) { 
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n"; 
        Log.d("TS", msg); 
        debugLog.append(line); 
        handler.post(() -> { statusText.setText(msg); debugText.setText(debugLog.toString()); }); 
    }
    
    private void start() { 
        String m = magnetInput.getText().toString().trim(); 
        if (m.startsWith("magnet:") && !downloading) startDownload(m); 
    }
    
    private void startDownload(String source) {
        downloading = true; videoFile = null; torrentHandle = null; videoStartTime = 0; 
        pieceLength = 0; numPieces = 0; totalSize = 0; totalRequests = 0; bytesServed = 0; 
        lastDownloadLog = 0; lastMinuteLog = -1; videoDurationMs = 0;
        currentPlayingPiece = -1; isSeeking = false;
        
        handler.post(() -> { 
            btnStop.setVisibility(View.VISIBLE); 
            bufferBar.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE); 
            btnSkip20.setVisibility(View.GONE); 
        });
        debug("⏳ Conectando ao tracker...");
        
        new Thread(() -> {
            try {
                add_torrent_params p; 
                if (source.startsWith("magnet:")) 
                    p = libtorrent.parse_magnet_uri(source, new error_code()); 
                else 
                    p = add_torrent_params.load_torrent_file(source, new error_code());
                
                p.setSave_path(savePath);
                torrent_flags_t flags = libtorrent.getAuto_managed().or_(libtorrent.getApply_ip_filter());
                p.setFlags(flags);
                p.setDownload_limit(0); // Sem limite
                
                byte_vector pr = new byte_vector(); 
                pr.add((byte)7); 
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p); 
                Thread.sleep(2000);
                
                synchronized (torrentLock) {
                    torrent_handle_vector h = session.swig().get_torrents(); 
                    if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0));
                }
                
                // Aguardar metadados
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
                    pieceLength = ti.pieceLength(); 
                    numPieces = ti.numPieces(); 
                    totalSize = ti.totalSize();
                    
                    torrent_status st = torrentHandle.swig().status();
                    debug("📊 " + (totalSize/1048576) + "MB, " + numPieces + " peças de " + (pieceLength/1024) + "KB | Peers: " + st.getNum_peers());
                    
                    // Baixar 100 primeiras peças (~100MB) para ter buffer robusto
                    int initialPieces = Math.min(100, numPieces);
                    debug("📋 Baixando " + initialPieces + " peças iniciais (~" + (initialPieces*pieceLength/1048576) + "MB)");
                    
                    // Zerar tudo
                    byte_vector zeroAll = new byte_vector();
                    for (int i = 0; i < numPieces; i++) zeroAll.add((byte)0);
                    torrentHandle.swig().prioritize_pieces_ex(zeroAll);
                    
                    // Priorizar peças iniciais
                    for (int i = 0; i < initialPieces; i++) {
                        byte prio = (i < 20) ? (byte)7 : (byte)5;
                        torrentHandle.swig().piece_priority_ex(i, prio);
                        torrentHandle.swig().set_piece_deadline(i, 30000);
                    }
                    
                    // Aguardar primeiras 20 peças para cabeçalho
                    int complete = 0;
                    long startTime = System.currentTimeMillis();
                    while (complete < 20 && downloading) {
                        Thread.sleep(500);
                        complete = 0;
                        for (int i = 0; i < 20; i++) {
                            if (torrentHandle.havePiece(i)) complete++;
                        }
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        if (elapsed % 4 == 0) debug("   📋 " + complete + "/20 (" + elapsed + "s)");
                    }
                    
                    debug("✅ Cabeçalho OK! " + complete + "/20");
                    
                    // Encontrar arquivo de vídeo
                    for (int i = 0; i < 30; i++) { 
                        File f = find(new File(savePath)); 
                        if (f != null && f.length() > 1048576) { 
                            videoFile = f;
                            debug("📁 " + f.getName() + " (" + (f.length()/1048576) + "MB até agora)");
                            
                            handler.post(() -> { 
                                btnWatch.setText("🎬 ASSISTIR"); 
                                btnWatch.setVisibility(View.VISIBLE); 
                                bufferBar.setVisibility(View.GONE); 
                            });
                            break; 
                        } 
                        Thread.sleep(500); 
                    }
                    
                    // Continuar baixando buffer inicial em background
                    debug("📥 Continuando buffer inicial...");
                }
                }
            } catch (Exception e) { 
                debug("❌ " + e.getMessage()); 
                downloading = false; 
            }
        }).start();
    }
    
    private void watch() { 
        if (videoFile == null || !videoFile.exists()) { 
            debug("❌ Arquivo não encontrado"); 
            return; 
        } 
        debug("▶️ VLC: " + videoFile.getName() + " (" + (videoFile.length()/1048576) + "MB)"); 
        handler.post(() -> { 
            videoSurface.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE); 
            spinnerBar.setVisibility(View.VISIBLE); 
            playWithVlc("http://127.0.0.1:8080/video"); 
        }); 
    }
    
    private void stop() { 
        downloading = false; vlcPreparing = false; isSeeking = false;
        if (seekMonitorThread != null) seekMonitorThread.interrupt();
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
    
    private File find(File dir) { 
        File[] files = dir.listFiles(); 
        if (files != null) for (File f : files) { 
            if (f.isDirectory()) { File found = find(f); if (found != null) return found; } 
            else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || 
                     f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f; 
        } 
        return null; 
    }
    
    @Override protected void onDestroy() { 
        stop(); 
        if (serverThread != null) serverThread.interrupt(); 
        if (session != null) session.stop(); 
        if (vlcPlayer != null) vlcPlayer.release(); 
        if (libVLC != null) libVLC.release(); 
        super.onDestroy(); 
    }
}