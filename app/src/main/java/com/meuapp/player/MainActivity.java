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
    private long videoDurationMs = 0;
    
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
                if (length > 0) videoDurationMs = length;
                if (time >= 0 && length > 0) {
                    timeText.setText(formatTime(time) + " / " + formatTime(length));
                    if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                }
            }
            handler.postDelayed(timeUpdater, 500);
        };
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { surfaceHolder = h; surfaceReady = true; if (pendingUrl != null) { playWithVlc(pendingUrl); pendingUrl = null; } }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; surfaceHolder = null; }
        });
        
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=3000");
        options.add("--file-caching=2000");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Opening: vlcPreparing = true; break;
                case MediaPlayer.Event.Playing: 
                    isPlaying = true; vlcPreparing = false;
                    handler.post(() -> { spinnerBar.setVisibility(View.GONE); btnPlayPause.setText("⏸"); if (!isTracking) handler.post(timeUpdater); }); 
                    break;
                case MediaPlayer.Event.Paused: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.Stopped: isPlaying = false; vlcPreparing = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.Buffering: handler.post(() -> spinnerBar.setVisibility(View.VISIBLE)); break;
                case MediaPlayer.Event.EndReached: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
            }
        });
        
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer != null && !vlcPreparing) { if (isPlaying) { vlcPlayer.pause(); } else { vlcPlayer.play(); } } });
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
        
        debug("=== TORRENT STREAM ===");
        new Thread(() -> { try { session = new SessionManager(); session.start(); debug("✅ Sessão OK"); } catch (Exception e) { debug("❌ " + e.getMessage()); } }).start();
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        debug("📱 Pronto");
    }
    
    private void seekRelative(long delta) { if (vlcPlayer == null || vlcPlayer.getLength() <= 0 || vlcPreparing) return; long t = Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + delta)); vlcPlayer.setTime(t); }
    private void seekAbsolute(int pct) { if (vlcPlayer == null || vlcPlayer.getLength() <= 0 || vlcPreparing) return; long t = (long)(vlcPlayer.getLength() * pct / 100.0); vlcPlayer.setTime(t); }
    
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
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); audioScroll.setVisibility(View.GONE);
    }
    
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms/1000); int m = s / 60; s = s % 60; return m + ":" + (s < 10 ? "0" : "") + s; }
    
    private void playWithVlc(String url) {
        if (!surfaceReady || surfaceHolder == null) { pendingUrl = url; return; }
        try {
            vlcPreparing = true;
            vlcPlayer.getVLCVout().setVideoSurface(surfaceHolder.getSurface(), null);
            vlcPlayer.getVLCVout().setWindowSize(videoSurface.getWidth(), videoSurface.getHeight());
            vlcPlayer.getVLCVout().attachViews();
            Media m = new Media(libVLC, Uri.parse(url)); m.setHWDecoderEnabled(true, true);
            m.addOption(":network-caching=3000"); m.addOption(":file-caching=2000");
            vlcPlayer.setMedia(m); m.release(); vlcPlayer.play();
            handler.post(() -> { playerControls.setVisibility(View.VISIBLE); centerControls.setVisibility(View.VISIBLE); btnSkip20.setVisibility(View.VISIBLE); });
            debug("[VLC] ▶ Reproduzindo");
        } catch (Exception e) { vlcPreparing = false; }
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
    
    // ==================== SERVIDOR HTTP ====================
    
    private void startServer() { 
        serverThread = new Thread(() -> { 
            try { 
                ServerSocket s = new ServerSocket(8080, 5); 
                s.setReuseAddress(true); 
                debug("🌐 HTTP porta 8080");
                while (!Thread.interrupted()) { 
                    try { 
                        Socket c = s.accept(); 
                        new Thread(() -> handleHttp(c)).start(); 
                    } catch (IOException e) {} 
                } 
                s.close(); 
            } catch (IOException e) { debug("❌ Servidor: " + e.getMessage()); } 
        }); 
        serverThread.setDaemon(true); 
        serverThread.start(); 
    }
    
    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(60000);
            InputStream in = client.getInputStream(); 
            OutputStream out = client.getOutputStream();
            
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
                out.write("HTTP/1.1 404\r\n\r\n".getBytes()); 
                out.flush(); client.close(); return; 
            }
            
            long rs = 0, re = -1; 
            boolean hr = false;
            for (String l : lines) { 
                if (l.toLowerCase().startsWith("range: bytes=")) { 
                    hr = true; 
                    String v = l.substring(13).trim(); 
                    String[] p = v.split("-"); 
                    rs = Long.parseLong(p[0]); 
                    if (p.length > 1 && !p[1].isEmpty()) re = Long.parseLong(p[1]); 
                } 
            }
            
            if (videoFile == null || !videoFile.exists()) { 
                out.write("HTTP/1.1 503\r\n\r\n".getBytes()); 
                out.flush(); client.close(); return; 
            }
            
            long fs = videoFile.length();
            
            if (!hr) {
                // Requisição inicial - enviar 20MB
                long toSend = Math.min(20971520, fs);
                debug("📦 Inicial: " + (toSend/1048576) + "MB");
                
                // Aguardar peças iniciais
                synchronized (torrentLock) {
                    if (torrentHandle != null && pieceLength > 0) {
                        int lastPiece = (int)(toSend / pieceLength);
                        for (int i = 0; i <= lastPiece && i < numPieces; i++) {
                            if (!torrentHandle.havePiece(i)) {
                                try { 
                                    torrentHandle.swig().piece_priority_ex(i, (byte)7); 
                                    torrentHandle.swig().set_piece_deadline(i, 15000); 
                                } catch (Exception e) {}
                            }
                        }
                        
                        long ws = System.currentTimeMillis();
                        while ((System.currentTimeMillis() - ws) < 20000 && downloading) {
                            boolean allReady = true;
                            for (int i = 0; i <= lastPiece && i < numPieces; i++) {
                                if (!torrentHandle.havePiece(i)) { allReady = false; break; }
                            }
                            if (allReady) break;
                            Thread.sleep(500);
                        }
                    }
                }
                
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nContent-Length: " + fs + "\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n").getBytes());
                out.flush();
                
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                byte[] data = new byte[262144]; 
                long sent = 0;
                while (sent < toSend && downloading) {
                    int tr = (int)Math.min(data.length, toSend - sent);
                    int read = raf.read(data, 0, tr);
                    if (read <= 0) break;
                    try { out.write(data, 0, read); out.flush(); sent += read; } 
                    catch (SocketException e) { break; }
                }
                raf.close(); out.flush(); client.close();
                debug("📦 Enviado " + (sent/1024) + "KB");
                if (videoStartTime == 0) videoStartTime = System.currentTimeMillis();
                return;
            }
            
            // Range request
            if (re == -1 || re >= fs) re = fs - 1;
            long cl = re - rs + 1;
            if (cl > 4194304) { cl = 4194304; re = rs + cl - 1; }
            
            long estMs = (videoDurationMs > 0 && fs > 0) ? rs * videoDurationMs / fs : 0;
            long em = estMs / 60000;
            long es = (estMs / 1000) % 60;
            
            // 🔥 SEEK: Aguardar peça
            synchronized (torrentLock) {
                if (torrentHandle != null && pieceLength > 0) {
                    int pn = (int)(rs / pieceLength);
                    
                    if (!torrentHandle.havePiece(pn)) {
                        debug("⏳ SEEK min " + em + ":" + String.format("%02d", es) + " (peça " + pn + ")");
                        
                        try {
                            for (int i = 0; i < numPieces; i++) {
                                torrentHandle.swig().piece_priority_ex(i, (byte)((i >= pn && i <= pn + 30) ? 7 : 0));
                            }
                            torrentHandle.swig().set_piece_deadline(pn, 30000);
                        } catch (Exception e) {}
                        
                        long ws = System.currentTimeMillis();
                        while (!torrentHandle.havePiece(pn) && (System.currentTimeMillis() - ws) < 30000 && downloading) {
                            Thread.sleep(500);
                            try { torrentHandle.swig().set_piece_deadline(pn, 30000); } catch (Exception e) { break; }
                        }
                        
                        if (torrentHandle.havePiece(pn)) {
                            debug("✅ Peça " + pn + " em " + ((System.currentTimeMillis()-ws)/1000) + "s");
                        } else {
                            debug("⏰ Timeout peça " + pn);
                        }
                    }
                }
            }
            
            totalRequests++;
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nContent-Range: bytes " + rs + "-" + (rs+cl-1) + "/" + fs + "\r\nContent-Length: " + cl + "\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n").getBytes());
            out.flush();
            
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(rs);
            byte[] buf = new byte[262144];
            long sent = 0;
            while (sent < cl && downloading) {
                int tr = (int)Math.min(buf.length, cl - sent);
                int read = raf.read(buf, 0, tr);
                if (read <= 0) { Thread.sleep(100); continue; }
                try { out.write(buf, 0, read); out.flush(); sent += read; } 
                catch (SocketException e) { break; }
            }
            bytesServed += sent;
            raf.close(); out.flush(); client.close();
            
            if (totalRequests % 50 == 0) debug("📡 HTTP: " + totalRequests + " reqs, " + (bytesServed/1048576) + "MB");
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    // ==================== DOWNLOAD ====================
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        downloading = true; videoFile = null; torrentHandle = null; videoStartTime = 0; 
        pieceLength = 0; numPieces = 0; totalSize = 0; totalRequests = 0; bytesServed = 0; 
        lastDownloadLog = 0; videoDurationMs = 0;
        
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE); });
        debug("⏳ Conectando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = source.startsWith("magnet:") ? 
                    libtorrent.parse_magnet_uri(source, new error_code()) : 
                    add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath);
                p.setFlags(libtorrent.getAuto_managed().or_(libtorrent.getSequential_download()).or_(libtorrent.getApply_ip_filter()));
                p.setDownload_limit(3*1024*1024);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p); 
                Thread.sleep(3000);
                
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
                    
                    torrent_status st = torrentHandle.swig().status();
                    debug("📊 " + (totalSize/1048576) + "MB, " + np + " peças | Peers: " + st.getNum_peers());
                    
                    // Baixar cabeçalho: 20 peças
                    int meta = Math.min(20, np);
                    debug("📋 Cabeçalho: " + meta + " peças");
                    
                    byte_vector hp = new byte_vector();
                    for (int i = 0; i < np; i++) hp.add((byte)(i < meta ? 7 : 0));
                    torrentHandle.swig().prioritize_pieces_ex(hp);
                    for (int i = 0; i < meta; i++) torrentHandle.swig().set_piece_deadline(i, 500);
                    
                    int complete = 0, wt = 0; 
                    boolean shown = false;
                    while (wt < 120 && downloading) { 
                        Thread.sleep(500); complete = 0; wt++;
                        for (int i = 0; i < meta; i++) if (torrentHandle.havePiece(i)) complete++;
                        if (wt % 4 == 0) debug("   📋 " + complete + "/" + meta + " (" + (wt/2) + "s)");
                        
                        if (!shown && complete >= meta) { 
                            shown = true; 
                            debug("✅ Cabeçalho OK! " + complete + "/" + meta);
                            
                            // Restaurar prioridades
                            byte_vector np2 = new byte_vector(); 
                            for (int i = 0; i < np; i++) np2.add((byte)1);
                            torrentHandle.swig().prioritize_pieces_ex(np2);
                            
                            // Encontrar arquivo de vídeo
                            for (int i = 0; i < 30; i++) { 
                                File f = find(new File(savePath)); 
                                if (f != null && f.length() > 1048576) { 
                                    byte[] hdr = new byte[8]; 
                                    try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; }
                                    if ((hdr[4]=='f'&&hdr[5]=='t'&&hdr[6]=='y'&&hdr[7]=='p') || 
                                        ((hdr[0]&0xFF)==0x1A&&hdr[1]==0x45&&hdr[2]==(byte)0xDF&&hdr[3]==(byte)0xA3)) { 
                                        videoFile = f;
                                        handler.post(() -> { 
                                            btnWatch.setText("🎬 ASSISTIR"); 
                                            btnWatch.setVisibility(View.VISIBLE); 
                                            bufferBar.setVisibility(View.GONE); 
                                        });
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
                                debug("📥 " + (dl/1048576) + "MB / " + (totalSize/1048576) + "MB (" + 
                                    (totalSize>0?(dl*100/totalSize):0) + "%)"); 
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
