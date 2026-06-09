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
import org.libtorrent4j.FileStorage;
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
    private TextView statusText, debugText, timeText, progressText;
    private ProgressBar bufferBar, spinnerBar, metadataProgressBar;
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
    private long videoFileOffset = 0;
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
    
    private final Object torrentLock = new Object();
    private final Object httpLock = new Object();
    private volatile Socket currentClient = null;
    
    private static final long STREAMING_BUFFER_BYTES = 30 * 1024 * 1024;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoSurface = findViewById(R.id.video_surface);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        timeText = findViewById(R.id.time_text);
        progressText = findViewById(R.id.progress_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        metadataProgressBar = findViewById(R.id.metadata_progress);
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
                if (length > 0) videoDurationMs = length;
                if (time >= 0 && length > 0) {
                    timeText.setText(formatTime(time) + " / " + formatTime(length));
                    if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                    long cm = time / 60000;
                    if (cm != lastMinuteLog && time > 0) { lastMinuteLog = cm; logMinuteInfo(cm); }
                }
            }
            handler.postDelayed(timeUpdater, 500);
        };
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { 
                surfaceHolder = h; surfaceReady = true; 
                debug("✅ Superfície pronta");
                if (pendingUrl != null) { String url = pendingUrl; pendingUrl = null; playWithVlc(url); } 
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { 
                surfaceReady = false; surfaceHolder = null; 
                debug("⚠️ Superfície destruída");
            }
        });
        
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=3000");
        options.add("--file-caching=1000");
        options.add("-vv");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Opening: vlcPreparing = true; debug("[VLC] 🔄 Abrindo stream..."); break;
                case MediaPlayer.Event.Playing: 
                    isPlaying = true; vlcPreparing = false; 
                    handler.post(() -> { spinnerBar.setVisibility(View.GONE); btnPlayPause.setText("⏸"); if (!isTracking) handler.post(timeUpdater); }); 
                    debug("[VLC] ▶ Tocando! Duração=" + (vlcPlayer.getLength()/60000) + "min Tracks=" + vlcPlayer.getAudioTracks().length + " áudios/" + vlcPlayer.getSpuTracks().length + " legendas"); 
                    break;
                case MediaPlayer.Event.Paused: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); debug("[VLC] ⏸ Pausado"); break;
                case MediaPlayer.Event.Stopped: isPlaying = false; vlcPreparing = false; handler.post(() -> btnPlayPause.setText("▶")); debug("[VLC] ⏹ Parado"); break;
                case MediaPlayer.Event.Buffering: handler.post(() -> spinnerBar.setVisibility(View.VISIBLE)); debug("[VLC] 🔃 Buffering " + event.buffering + "%"); break;
                case MediaPlayer.Event.EndReached: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); debug("[VLC] 🏁 Fim"); break;
                case MediaPlayer.Event.EncounteredError: debug("[VLC] ❌ Erro!"); break;
            }
        });
        
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer != null && !vlcPreparing) { if (isPlaying) vlcPlayer.pause(); else vlcPlayer.play(); } });
        btnSeekBack.setOnClickListener(v -> { if (!vlcPreparing) seekRelative(-10000); });
        btnSeekForward.setOnClickListener(v -> { if (!vlcPreparing) seekRelative(10000); });
        btnSkip20.setOnClickListener(v -> { if (vlcPlayer != null && !vlcPreparing && videoFile != null) { long t = 20*60*1000; if (vlcPlayer.getLength() > 0 && t < vlcPlayer.getLength()) vlcPlayer.setTime(t); } });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && !vlcPreparing && vlcPlayer.getLength() > 0) seekAbsolute(p); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        debug("=== TORRENT STREAM DEBUG ===");
        new Thread(() -> { try { session = new SessionManager(); session.start(); debug("✅ Sessão libtorrent OK"); } catch (Exception e) { debug("❌ " + e.getMessage()); } }).start();
        startServer();
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        debug("📱 App pronto");
    }
    
    private String byteToMinute(long byteOffset, long fileSize) {
        if (videoDurationMs <= 0 || fileSize <= 0) return "?";
        long ms = byteOffset * videoDurationMs / fileSize;
        long min = ms / 60000;
        long sec = (ms / 1000) % 60;
        return min + ":" + String.format("%02d", sec);
    }
    
    private void logMinuteInfo(long cm) {
        if (videoDurationMs <= 0 || pieceLength <= 0 || videoFile == null) return;
        synchronized (torrentLock) {
            if (!isHandleValidSafe()) return;
            long ba = cm * 60 * 1000 * videoFile.length() / videoDurationMs;
            long bb = (cm + 1) * 60 * 1000 * videoFile.length() / videoDurationMs;
            int sp = (int)((videoFileOffset + ba) / pieceLength);
            int ep = (int)((videoFileOffset + bb) / pieceLength);
            int pd = 0;
            try { for (int i = sp; i <= ep && i < numPieces; i++) if (torrentHandle.havePiece(i)) pd++; } catch (Exception e) { return; }
            int tot = ep - sp + 1; int pct = tot > 0 ? (pd * 100 / tot) : 0;
            if (pct < 100 || cm % 5 == 0) debug("⏱ Min " + cm + ": peças " + sp + "-" + ep + " | " + pd + "/" + tot + " (" + pct + "%)");
        }
    }
    
    private boolean isHandleValidSafe() { try { return torrentHandle != null && torrentHandle.isValid(); } catch (Exception e) { return false; } }
    
    private void seekRelative(long delta) { if (vlcPlayer == null || vlcPlayer.getLength() <= 0 || vlcPreparing) return; vlcPlayer.setTime(Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + delta))); }
    private void seekAbsolute(int pct) { if (vlcPlayer == null || vlcPlayer.getLength() <= 0 || vlcPreparing) return; vlcPlayer.setTime((long)(vlcPlayer.getLength() * pct / 100.0)); }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
        int current = vlcPlayer.getAudioTrack();
        audioMenu.removeAllViews();
        if (tracks != null) for (MediaPlayer.TrackDescription t : tracks) if (t.id >= 0) {
            TextView tv = new TextView(this); tv.setText("🎵 " + t.name + (t.id == current ? " ✓" : ""));
            tv.setTextColor(t.id == current ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16,12,16,12);
            final int id = t.id; tv.setOnClickListener(v -> { vlcPlayer.setAudioTrack(id); audioScroll.setVisibility(View.GONE); }); audioMenu.addView(tv);
        }
        debug("🎵 " + (tracks != null ? tracks.length : 0) + " áudios listados");
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
        int current = vlcPlayer.getSpuTrack();
        subtitleMenu.removeAllViews();
        TextView off = new TextView(this); off.setText("📝 Desligado" + (current == -1 ? " ✓" : ""));
        off.setTextColor(current == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); off.setTextSize(12); off.setPadding(16,12,16,12);
        off.setOnClickListener(v -> { vlcPlayer.setSpuTrack(-1); subtitleScroll.setVisibility(View.GONE); }); subtitleMenu.addView(off);
        if (tracks != null) for (MediaPlayer.TrackDescription t : tracks) if (t.id >= 0) {
            TextView tv = new TextView(this); tv.setText("📝 " + t.name + (t.id == current ? " ✓" : ""));
            tv.setTextColor(t.id == current ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16,12,16,12);
            final int id = t.id; tv.setOnClickListener(v -> { vlcPlayer.setSpuTrack(id); subtitleScroll.setVisibility(View.GONE); }); subtitleMenu.addView(tv);
        }
        debug("📝 " + (tracks != null ? tracks.length : 0) + " legendas listadas");
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); audioScroll.setVisibility(View.GONE);
    }
    
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms/1000); return (s/60) + ":" + (s%60 < 10 ? "0" : "") + (s%60); }
    
    private void playWithVlc(String url) {
        if (!surfaceReady || surfaceHolder == null) { pendingUrl = url; debug("⏳ Superfície não pronta, agendando..."); return; }
        try {
            vlcPreparing = true;
            debug("🎬 Configurando VLC: " + url);
            vlcPlayer.getVLCVout().setVideoSurface(surfaceHolder.getSurface(), null);
            vlcPlayer.getVLCVout().setWindowSize(videoSurface.getWidth(), videoSurface.getHeight());
            vlcPlayer.getVLCVout().attachViews();
            Media m = new Media(libVLC, Uri.parse(url)); m.setHWDecoderEnabled(true, true);
            m.addOption(":network-caching=3000"); m.addOption(":file-caching=1000"); m.addOption(":avcodec-hw=none");
            vlcPlayer.setMedia(m); m.release(); vlcPlayer.play();
            handler.post(() -> { playerControls.setVisibility(View.VISIBLE); centerControls.setVisibility(View.VISIBLE); btnSkip20.setVisibility(View.VISIBLE); });
            debug("[VLC] ▶ Play chamado, aguardando eventos...");
        } catch (Exception e) { vlcPreparing = false; debug("[VLC] ❌ Erro: " + e.getMessage()); }
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
    
    private void updateProgress(int current, int total, String label) {
        int pct = total > 0 ? (current * 100 / total) : 0;
        String text = label + ": " + current + "/" + total + " (" + pct + "%)";
        handler.post(() -> {
            progressText.setText(text);
            progressText.setVisibility(View.VISIBLE);
            metadataProgressBar.setProgress(pct);
            metadataProgressBar.setVisibility(View.VISIBLE);
        });
    }
    
    private void hideProgress() {
        handler.post(() -> {
            progressText.setVisibility(View.GONE);
            metadataProgressBar.setVisibility(View.GONE);
        });
    }
    
    private void startServer() { serverThread = new Thread(() -> { try { ServerSocket s = new ServerSocket(8080, 1); s.setReuseAddress(true); debug("🌐 Servidor HTTP porta 8080 iniciado"); while (!Thread.interrupted()) { try { Socket c = s.accept(); debug("🔗 Nova conexão HTTP de " + c.getInetAddress()); synchronized (httpLock) { if (currentClient != null) { try { currentClient.close(); debug("❌ Conexão anterior fechada"); } catch (Exception e) {} } currentClient = c; } handleHttp(c); synchronized (httpLock) { currentClient = null; } debug("🔌 Conexão HTTP finalizada"); } catch (IOException e) {} } s.close(); } catch (IOException e) { debug("❌ Erro servidor: " + e.getMessage()); } }); serverThread.setDaemon(true); serverThread.start(); }
    
    private void handleHttp(Socket client) {
        debug("📡 Iniciando processamento HTTP...");
        try {
            client.setSoTimeout(120000);
            InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream();
            int requestCount = 0;
            
            while (downloading && !client.isClosed()) {
                ByteArrayOutputStream hb = new ByteArrayOutputStream(); int b;
                try { while ((b = in.read()) != -1) { hb.write(b); if (hb.size() > 4) { byte[] d = hb.toByteArray(); if (d[d.length-4]=='\r'&&d[d.length-3]=='\n'&&d[d.length-2]=='\r'&&d[d.length-1]=='\n') break; } } } catch (SocketTimeoutException e) { debug("⏰ Timeout leitura HTTP após " + requestCount + " requisições"); break; }
                if (hb.size() == 0) { debug("📭 Fim do stream HTTP"); break; }
                
                requestCount++;
                String req = new String(hb.toByteArray()); String[] lines = req.split("\r\n");
                if (lines.length == 0) break;
                
                debug("📨 Req #" + requestCount + ": " + lines[0]);
                if (!lines[0].contains("/video")) { out.write("HTTP/1.1 404\r\nConnection: close\r\n\r\n".getBytes()); out.flush(); break; }
                
                long rs = 0, re = -1; boolean hr = false;
                for (String l : lines) if (l.toLowerCase().startsWith("range: bytes=")) { hr = true; String v = l.substring(13).trim(); String[] p = v.split("-"); rs = Long.parseLong(p[0]); if (p.length > 1 && !p[1].isEmpty()) re = Long.parseLong(p[1]); }
                
                if (videoFile == null || !videoFile.exists()) { out.write("HTTP/1.1 404\r\nConnection: close\r\n\r\n".getBytes()); out.flush(); break; }
                long fs = videoFile.length();
                
                synchronized (httpLock) { if (currentClient != client) { debug("❌ Conexão cancelada"); try { out.flush(); client.close(); } catch (Exception e) {} return; } }
                
                if (!hr) {
                    // Requisição inicial: enviar o máximo possível
                    long toSend = Math.min(52428800, fs); // 50MB inicial
                    debug("📦 Requisição inicial - enviando até " + (toSend/1048576) + "MB...");
                    
                    // Aguardar peças iniciais
                    synchronized (torrentLock) {
                        if (isHandleValidSafe() && pieceLength > 0) {
                            long tb = videoFileOffset + toSend;
                            int lastPieceNeeded = (int)(tb / pieceLength);
                            int ready = 0;
                            for (int i = 0; i <= lastPieceNeeded && i < numPieces; i++) if (torrentHandle.havePiece(i)) ready++;
                            debug("🔍 Peças 0-" + lastPieceNeeded + ": " + ready + "/" + (lastPieceNeeded+1) + " prontas");
                            
                            if (ready < lastPieceNeeded + 1) {
                                debug("⏳ Aguardando peças iniciais...");
                                for (int i = 0; i <= lastPieceNeeded && i < numPieces; i++) if (!torrentHandle.havePiece(i)) try { torrentHandle.swig().piece_priority_ex(i, (byte)7); torrentHandle.swig().set_piece_deadline(i, 10000); } catch (Exception e) {}
                                long ws = System.currentTimeMillis();
                                while ((System.currentTimeMillis() - ws) < 20000 && downloading) {
                                    ready = 0; for (int i = 0; i <= lastPieceNeeded && i < numPieces; i++) if (torrentHandle.havePiece(i)) ready++;
                                    if (ready >= lastPieceNeeded + 1) break;
                                    Thread.sleep(500);
                                }
                                debug("🔍 Após espera: " + ready + "/" + (lastPieceNeeded+1) + " prontas");
                            }
                        }
                    }
                    
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nContent-Length: " + fs + "\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n").getBytes()); out.flush();
                    
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r"); byte[] data = new byte[262144]; long sent = 0;
                    while (sent < toSend && downloading) {
                        long currentByte = sent;
                        synchronized (torrentLock) {
                            if (isHandleValidSafe() && pieceLength > 0) {
                                int pn = (int)((videoFileOffset + currentByte) / pieceLength);
                                if (!torrentHandle.havePiece(pn)) {
                                    debug("⏳ Aguardando peça " + pn + " para envio inicial...");
                                    try { torrentHandle.swig().piece_priority_ex(pn, (byte)7); torrentHandle.swig().set_piece_deadline(pn, 10000); } catch (Exception e) {}
                                    long ws = System.currentTimeMillis();
                                    while (!torrentHandle.havePiece(pn) && (System.currentTimeMillis() - ws) < 10000 && downloading) { Thread.sleep(500); }
                                }
                            }
                        }
                        int tr = (int)Math.min(data.length, toSend - sent); int read = raf.read(data, 0, tr);
                        if (read <= 0) break;
                        try { out.write(data, 0, read); out.flush(); sent += read; } catch (SocketException e) { debug("⚠️ Cliente desconectou após " + (sent/1024) + "KB"); break; }
                    }
                    try { raf.close(); } catch (Exception e) {}
                    debug("📦 Enviado " + (sent/1024) + "KB iniciais. VLC deve começar a tocar...");
                    if (videoStartTime == 0) videoStartTime = System.currentTimeMillis();
                    continue;
                }
                
                // Requisição com Range
                if (re == -1 || re >= fs) re = fs - 1;
                long cl = re - rs + 1;
                if (cl > 4194304) { cl = 4194304; re = rs + cl - 1; }
                
                String minuto = byteToMinute(rs, fs);
                debug("📏 Range #" + requestCount + ": bytes " + rs + "-" + (rs+cl-1) + " (min " + minuto + ", " + (cl/1024) + "KB)");
                
                synchronized (torrentLock) {
                    if (isHandleValidSafe() && pieceLength > 0) {
                        long tb = videoFileOffset + rs;
                        int pn = (int)(tb / pieceLength);
                        try {
                            if (!torrentHandle.havePiece(pn)) {
                                debug("⏳ Peça " + pn + " (min " + minuto + ") NÃO baixada - AGUARDANDO...");
                                int pecasBuffer = (int)(STREAMING_BUFFER_BYTES / pieceLength) + 1;
                                try { torrentHandle.swig().set_flags(libtorrent.getAuto_managed().or_(libtorrent.getApply_ip_filter())); } catch (Exception e) {}
                                for (int i = 0; i < numPieces; i++) try { torrentHandle.swig().piece_priority_ex(i, (byte)((i >= pn && i <= pn + pecasBuffer) ? 7 : 0)); } catch (Exception e) { break; }
                                try { torrentHandle.swig().set_piece_deadline(pn, 30000); } catch (Exception e) {}
                                long ws = System.currentTimeMillis();
                                while (isHandleValidSafe() && !torrentHandle.havePiece(pn) && (System.currentTimeMillis() - ws) < 30000 && downloading) {
                                    synchronized (httpLock) { if (currentClient != client) { try { out.flush(); client.close(); } catch (Exception e) {} return; } }
                                    Thread.sleep(500); try { torrentHandle.swig().set_piece_deadline(pn, 30000); } catch (Exception e) { break; }
                                }
                                if (isHandleValidSafe() && torrentHandle.havePiece(pn)) {
                                    debug("✅ Peça " + pn + " (min " + minuto + ") baixada em " + ((System.currentTimeMillis()-ws)/1000) + "s");
                                    try { torrentHandle.swig().set_flags(libtorrent.getAuto_managed().or_(libtorrent.getSequential_download()).or_(libtorrent.getApply_ip_filter())); } catch (Exception e) {}
                                } else { debug("⏰ Timeout peça " + pn); }
                            } else { debug("✅ Peça " + pn + " (min " + minuto + ") já pronta"); }
                        } catch (Exception e) { debug("⚠️ Erro: " + e.getMessage()); }
                    }
                }
                
                totalRequests++;
                try {
                    out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nContent-Range: bytes " + rs + "-" + (rs+cl-1) + "/" + fs + "\r\nContent-Length: " + cl + "\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n").getBytes()); out.flush();
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r"); raf.seek(rs); byte[] buf = new byte[262144]; long sent = 0;
                    while (sent < cl && downloading) { synchronized (httpLock) { if (currentClient != client) { try { raf.close(); } catch (Exception e) {} try { out.flush(); client.close(); } catch (Exception e) {} return; } } int tr = (int)Math.min(buf.length, cl - sent); int read = raf.read(buf, 0, tr); if (read <= 0) { Thread.sleep(100); continue; } try { out.write(buf, 0, read); out.flush(); sent += read; } catch (SocketException e) { debug("⚠️ Cliente desconectou durante envio"); break; } }
                    bytesServed += sent; try { raf.close(); } catch (Exception e) {}
                    debug("📤 Range enviado: " + (sent/1024) + "KB");
                } catch (Exception e) { debug("⚠️ Erro ao enviar Range: " + e.getMessage()); break; }
            }
            try { out.flush(); client.close(); } catch (Exception e) {}
            debug("📡 HTTP finalizado após " + requestCount + " requisições, " + (bytesServed/1048576) + "MB servidos");
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} debug("❌ Erro HTTP: " + e.getMessage()); }
    }
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        downloading = true; videoFile = null; torrentHandle = null; videoFileOffset = 0; videoStartTime = 0; pieceLength = 0; numPieces = 0; totalSize = 0; totalRequests = 0; bytesServed = 0; lastDownloadLog = 0; lastMinuteLog = -1; videoDurationMs = 0;
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE); });
        debug("⏳ Conectando ao tracker...");
        updateProgress(0, 100, "Conectando ao tracker");
        
        new Thread(() -> {
            try {
                add_torrent_params p = source.startsWith("magnet:") ? libtorrent.parse_magnet_uri(source, new error_code()) : add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath);
                p.setFlags(libtorrent.getAuto_managed().or_(libtorrent.getSequential_download()).or_(libtorrent.getApply_ip_filter()));
                p.setDownload_limit(3*1024*1024);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                session.swig().async_add_torrent(p); Thread.sleep(3000);
                
                synchronized (torrentLock) { torrent_handle_vector h = session.swig().get_torrents(); if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0)); }
                
                updateProgress(10, 100, "Obtendo metadados...");
                int w = 0; while (w < 120 && downloading) { Thread.sleep(1000); w++; synchronized (torrentLock) { if (isHandleValidSafe() && torrentHandle.torrentFile() != null) break; } }
                
                synchronized (torrentLock) {
                if (isHandleValidSafe() && torrentHandle.torrentFile() != null) {
                    TorrentInfo ti = torrentHandle.torrentFile();
                    int np = ti.numPieces(), pl = ti.pieceLength(); pieceLength = pl; numPieces = np; totalSize = ti.totalSize();
                    debug("📊 Torrent: " + (totalSize/1048576) + "MB, " + np + " peças " + (pl/1024) + "KB | Limite: 3MB/s");
                    
                    updateProgress(20, 100, "Procurando arquivo de vídeo...");
                    File f = null;
                    for (int i = 0; i < 60; i++) {
                        f = find(new File(savePath));
                        if (f != null && f.length() > 1048576) {
                            byte[] hdr = new byte[8];
                            try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; }
                            if ((hdr[4]=='f'&&hdr[5]=='t'&&hdr[6]=='y'&&hdr[7]=='p') || ((hdr[0]&0xFF)==0x1A&&hdr[1]==0x45&&hdr[2]==(byte)0xDF&&hdr[3]==(byte)0xA3)) break;
                        }
                        Thread.sleep(1000);
                    }
                    
                    if (f == null) { debug("❌ Arquivo não encontrado"); downloading = false; hideProgress(); return; }
                    videoFile = f;
                    try { FileStorage fs = ti.files(); for (int j = 0; j < fs.numFiles(); j++) { if (fs.fileName(j).equals(f.getName()) || f.getAbsolutePath().endsWith(fs.fileName(j))) { videoFileOffset = fs.fileOffset(j); break; } } } catch (Exception e3) { videoFileOffset = 0; }
                    
                    long videoFileSize = f.length();
                    debug("📁 Arquivo: " + f.getName() + " (" + (videoFileSize/1048576) + "MB) offset=" + videoFileOffset);
                    
                    int firstPiece = (int)(videoFileOffset / pieceLength);
                    int lastPiece = (int)((videoFileOffset + videoFileSize) / pieceLength);
                    int headerPieces = Math.min(20, np - firstPiece);
                    int cuePieces = Math.min(5, lastPiece - firstPiece + 1);
                    int cueStartPiece = Math.max(firstPiece, lastPiece - cuePieces + 1);
                    int cueEndPiece = lastPiece;
                    int totalNeeded = headerPieces + (cueEndPiece - cueStartPiece + 1);
                    
                    debug("📋 Cabeçalho: peças " + firstPiece + "-" + (firstPiece+headerPieces-1) + " (~" + headerPieces + "MB)");
                    debug("📋 Cues: peças " + cueStartPiece + "-" + cueEndPiece + " (~" + (cueEndPiece-cueStartPiece+1) + "MB)");
                    debug("📋 Total: " + totalNeeded + " peças (~" + totalNeeded + "MB) para iniciar");
                    
                    byte_vector initP = new byte_vector();
                    for (int i = 0; i < np; i++) {
                        boolean isHeader = (i >= firstPiece && i < firstPiece + headerPieces);
                        boolean isCue = (i >= cueStartPiece && i <= cueEndPiece);
                        initP.add((byte)((isHeader || isCue) ? 7 : 0));
                    }
                    torrentHandle.swig().prioritize_pieces_ex(initP);
                    for (int i = firstPiece; i < firstPiece + headerPieces; i++) torrentHandle.swig().set_piece_deadline(i, 1000);
                    for (int i = cueStartPiece; i <= cueEndPiece; i++) torrentHandle.swig().set_piece_deadline(i, 1000);
                    
                    int complete = 0, wt = 0; boolean shown = false;
                    while (wt < 300 && downloading) { Thread.sleep(500); complete = 0; wt++;
                        synchronized (torrentLock) { if (!isHandleValidSafe()) break; try { for (int i = firstPiece; i < firstPiece + headerPieces; i++) if (torrentHandle.havePiece(i)) complete++; for (int i = cueStartPiece; i <= cueEndPiece; i++) if (torrentHandle.havePiece(i)) complete++; } catch (Exception e) { break; } }
                        int pct = totalNeeded > 0 ? (complete * 100 / totalNeeded) : 0;
                        int progressPct = 30 + (pct * 60 / 100);
                        updateProgress(complete, totalNeeded, "Baixando metadados");
                        handler.post(() -> bufferBar.setProgress(progressPct));
                        
                        if (wt % 2 == 0) {
                            debug("   📋 " + complete + "/" + totalNeeded + " (" + pct + "%) - " + (wt/2) + "s");
                            try { for (int i = firstPiece; i < firstPiece + headerPieces; i++) if (!torrentHandle.havePiece(i)) torrentHandle.swig().set_piece_deadline(i, 1000); for (int i = cueStartPiece; i <= cueEndPiece; i++) if (!torrentHandle.havePiece(i)) torrentHandle.swig().set_piece_deadline(i, 1000); } catch (Exception e) {}
                        }
                        
                        if (!shown && complete >= totalNeeded) { 
                            shown = true; 
                            debug("✅ METADADOS COMPLETOS! " + complete + "/" + totalNeeded + " em " + (wt/2) + "s");
                            updateProgress(totalNeeded, totalNeeded, "Metadados prontos!");
                            handler.post(() -> bufferBar.setProgress(100));
                            
                            byte_vector sp = new byte_vector(); 
                            for (int i = 0; i < np; i++) sp.add((byte)((i >= firstPiece && i < firstPiece + headerPieces + 10) ? 4 : 1));
                            torrentHandle.swig().prioritize_pieces_ex(sp);
                            
                            handler.post(() -> { 
                                btnWatch.setText("🎬 ASSISTIR"); 
                                btnWatch.setVisibility(View.VISIBLE); 
                                bufferBar.setVisibility(View.GONE);
                                hideProgress();
                            }); 
                            break; 
                        }
                    }
                    if (!shown) { debug("⚠️ Timeout: " + complete + "/" + totalNeeded); hideProgress(); }
                }
                }
            } catch (Exception e2) { debug("❌ " + e2.getMessage()); downloading = false; hideProgress(); }
        }).start();
        
        new Thread(() -> { while (downloading) { try { Thread.sleep(5000); synchronized (torrentLock) { if (isHandleValidSafe() && videoFile != null) { try { long dl = torrentHandle.swig().status().getTotal_done(); if (dl - lastDownloadLog > 10485760) { lastDownloadLog = dl; debug("📥 " + (dl/1048576) + "MB / " + (totalSize/1048576) + "MB (" + (totalSize>0?(dl*100/totalSize):0) + "%)"); } } catch (Exception e) {} } } } catch (Exception e) {} } }).start();
    }
    
    private void watch() { 
        if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não encontrado"); return; } 
        debug("▶️ Botão ASSISTIR pressionado");
        handler.post(() -> { 
            videoSurface.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.VISIBLE); spinnerBar.setVisibility(View.VISIBLE);
            debug("📺 Superfície visível, surfaceReady=" + surfaceReady);
            if (surfaceReady && surfaceHolder != null) {
                debug("✅ Superfície OK, iniciando VLC...");
                playWithVlc("http://127.0.0.1:8080/video");
            } else {
                pendingUrl = "http://127.0.0.1:8080/video";
                debug("⏳ Superfície não pronta, agendando VLC...");
            }
        }); 
    }
    
    private void stop() { 
        debug("⏹ Parando...");
        downloading = false; vlcPreparing = false; 
        if (vlcPlayer != null) vlcPlayer.stop(); 
        videoSurface.setVisibility(View.GONE); playerControls.setVisibility(View.GONE); centerControls.setVisibility(View.GONE); 
        audioScroll.setVisibility(View.GONE); subtitleScroll.setVisibility(View.GONE); 
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE); hideProgress(); 
        handler.removeCallbacks(timeUpdater); 
        synchronized (torrentLock) { if (torrentHandle != null && session != null) { try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} torrentHandle = null; } } 
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File found = find(f); if (found != null) return found; } else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f; } return null; }
    
    @Override protected void onDestroy() { stop(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); if (vlcPlayer != null) vlcPlayer.release(); if (libVLC != null) libVLC.release(); super.onDestroy(); }
}