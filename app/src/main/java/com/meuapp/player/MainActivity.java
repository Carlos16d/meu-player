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
    private TextView statusText, debugText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnTorrent, btnStop, btnWatch;
    
    // Controles do player
    private LinearLayout playerControls;
    private Button btnPause, btnSeekBack, btnSeekForward, btnAudio, btnSubtitle;
    
    private String savePath;
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private long lastSeekTime = 0;
    private long videoStartTime = 0;
    private boolean surfaceReady = false;
    private String pendingUrl = null;
    private boolean isPlaying = false;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();
    private static final int PICK_TORRENT = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoSurface = findViewById(R.id.video_surface);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnTorrent = findViewById(R.id.btn_torrent);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        // Controles do player
        playerControls = findViewById(R.id.player_controls);
        btnPause = findViewById(R.id.btn_pause);
        btnSeekBack = findViewById(R.id.btn_seek_back);
        btnSeekForward = findViewById(R.id.btn_seek_forward);
        btnAudio = findViewById(R.id.btn_audio);
        btnSubtitle = findViewById(R.id.btn_subtitle);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                surfaceHolder = holder; surfaceReady = true;
                if (pendingUrl != null) { playWithVlc(pendingUrl); pendingUrl = null; }
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false; surfaceHolder = null;
            }
        });
        
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=3000");
        options.add("--http-reconnect");
        options.add("--file-caching=2000");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override public void onEvent(MediaPlayer.Event event) {
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        debug("[VLC] ▶ Playing");
                        isPlaying = true;
                        handler.post(() -> { 
                            spinnerBar.setVisibility(View.GONE);
                            btnPause.setText("⏸");
                        });
                        break;
                    case MediaPlayer.Event.Paused:
                        debug("[VLC] ⏸ Paused");
                        isPlaying = false;
                        handler.post(() -> btnPause.setText("▶"));
                        break;
                    case MediaPlayer.Event.Stopped:
                        debug("[VLC] ⏹ Stopped");
                        isPlaying = false;
                        handler.post(() -> btnPause.setText("▶"));
                        break;
                    case MediaPlayer.Event.Buffering:
                        debug("[VLC] Buffering " + event.getBuffering() + "%");
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        debug("[VLC] ❌ ERRO!");
                        break;
                }
            }
        });
        
        // Eventos dos botões de controle
        btnPause.setOnClickListener(v -> {
            if (vlcPlayer != null) {
                if (isPlaying) vlcPlayer.pause();
                else vlcPlayer.play();
            }
        });
        
        btnSeekBack.setOnClickListener(v -> {
            if (vlcPlayer != null && vlcPlayer.getLength() > 0) {
                long newTime = vlcPlayer.getTime() - 10000; // -10 segundos
                vlcPlayer.setTime(Math.max(0, newTime));
                debug("⏪ -10s");
            }
        });
        
        btnSeekForward.setOnClickListener(v -> {
            if (vlcPlayer != null && vlcPlayer.getLength() > 0) {
                long newTime = vlcPlayer.getTime() + 10000; // +10 segundos
                vlcPlayer.setTime(Math.min(vlcPlayer.getLength(), newTime));
                debug("⏩ +10s");
            }
        });
        
        btnAudio.setOnClickListener(v -> {
            if (vlcPlayer != null) {
                int track = vlcPlayer.getAudioTrack();
                int count = vlcPlayer.getAudioTracksCount();
                if (count > 0) {
                    int newTrack = (track + 1) % count;
                    vlcPlayer.setAudioTrack(newTrack);
                    debug("🎵 Áudio: " + newTrack + "/" + count);
                    Toast.makeText(this, "🎵 Áudio " + (newTrack+1) + "/" + count, Toast.LENGTH_SHORT).show();
                }
            }
        });
        
        btnSubtitle.setOnClickListener(v -> {
            if (vlcPlayer != null) {
                int track = vlcPlayer.getSpuTrack();
                int count = vlcPlayer.getSpuTracksCount();
                if (count > 0) {
                    int newTrack = (track + 1) % count;
                    vlcPlayer.setSpuTrack(newTrack);
                    debug("📝 Legenda: " + newTrack + "/" + count);
                    Toast.makeText(this, "📝 Legenda " + (newTrack+1) + "/" + count, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "📝 Sem legendas", Toast.LENGTH_SHORT).show();
                }
            }
        });
        
        debug("=== TORRENT STREAM VLC ===");
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); debug("✅ OK"); } 
            catch (Exception e) { debug("❌ " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, PICK_TORRENT);
        });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("📱 Pronto");
    }
    
    private void playWithVlc(String url) {
        if (!surfaceReady || surfaceHolder == null) {
            pendingUrl = url;
            return;
        }
        try {
            vlcPlayer.getVLCVout().setVideoSurface(surfaceHolder.getSurface(), null);
            vlcPlayer.getVLCVout().setWindowSize(videoSurface.getWidth(), videoSurface.getHeight());
            vlcPlayer.getVLCVout().attachViews();
            
            Media media = new Media(libVLC, Uri.parse(url));
            media.setHWDecoderEnabled(true, true);
            media.addOption(":network-caching=3000");
            media.addOption(":http-reconnect");
            media.addOption(":file-caching=2000");
            vlcPlayer.setMedia(media); media.release(); vlcPlayer.play();
            
            handler.post(() -> playerControls.setVisibility(View.VISIBLE));
            debug("[VLC] ✅ Playing");
        } catch (Exception e) { debug("[VLC] ❌ " + e.getMessage()); }
    }
    
    // ... (resto do código igual ao anterior: onActivityResult, debug, startServer, handleHttp, start, startDownload, watch, stop, find, onDestroy)
    
    @Override protected void onActivityResult(int r, int res, Intent data) {
        super.onActivityResult(r, res, data);
        if (r == PICK_TORRENT && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) try {
                InputStream is = getContentResolver().openInputStream(uri);
                File tf = new File(savePath, "torrent_file.torrent");
                FileOutputStream fos = new FileOutputStream(tf); byte[] b = new byte[8192]; int l;
                while ((l = is.read(b)) > 0) fos.write(b, 0, l);
                fos.close(); is.close(); startDownload(tf.getAbsolutePath());
            } catch (Exception e) { debug("❌ " + e.getMessage()); }
        }
    }
    
    private void debug(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        Log.d("TS", msg); debugLog.append(line);
        handler.post(() -> { statusText.setText(msg); debugText.setText(debugLog.toString()); });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                while (!Thread.interrupted()) {
                    try { Socket client = server.accept(); new Thread(() -> handleHttp(client)).start(); } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {}
        });
        serverThread.setDaemon(true); serverThread.start();
    }
    
    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(10000);
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
                byte[] data = new byte[1048576]; RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                int read = raf.read(data); if (read > 0) out.write(data, 0, read); raf.close();
                out.flush(); client.close(); if (videoStartTime == 0) videoStartTime = System.currentTimeMillis(); return;
            }
            
            if (re == -1 || re >= fs) re = fs - 1; long cl = re - rs + 1;
            if (hr && rs > 0) {
                long now = System.currentTimeMillis();
                if ((videoStartTime == 0 || now - videoStartTime > 5000) && now - lastSeekTime > 3000) {
                    lastSeekTime = now;
                    try { if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) { TorrentInfo ti = torrentHandle.torrentFile(); int pl = ti.pieceLength(), np = ti.numPieces(); int tp = (int)(rs / pl); int rss = Math.max(0, tp-2), ree = Math.min(tp+7, np-1); torrentHandle.setSequentialRange(rss, ree); for (int i = 0; i < np; i++) torrentHandle.piecePriority(i, org.libtorrent4j.Priority.IGNORE); for (int i = rss; i <= ree; i++) { torrentHandle.piecePriority(i, org.libtorrent4j.Priority.TOP_PRIORITY); torrentHandle.setPieceDeadline(i, 300); } debug("🔥 SEEK: " + (rs/1048576) + "MB"); } } catch (Exception e) {}
                }
            }
            
            String headers = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nContent-Range: bytes " + rs + "-" + (rs+cl-1) + "/" + fs + "\r\nContent-Length: " + cl + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
            out.write(headers.getBytes());
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r"); raf.seek(rs); byte[] buf = new byte[65536]; long sent = 0;
            while (sent < cl && downloading) { int tr = (int)Math.min(buf.length, cl - sent); int read = raf.read(buf, 0, tr); if (read <= 0) break; out.write(buf, 0, read); out.flush(); sent += read; }
            raf.close(); out.flush(); client.close();
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        downloading = true; videoFile = null; torrentHandle = null; videoStartTime = 0;
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); });
        debug("⏳ Conectando...");
        new Thread(() -> {
            try {
                add_torrent_params p; if (source.startsWith("magnet:")) p = libtorrent.parse_magnet_uri(source, new error_code()); else p = add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath); p.setFlags(torrent_flags_t.from_int(9)); p.setDownload_limit(3*1024*1024);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                session.swig().async_add_torrent(p); Thread.sleep(3000);
                torrent_handle_vector h = session.swig().get_torrents(); if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0));
                int w = 0; while (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() == null && w < 60 && downloading) { Thread.sleep(1000); w++; }
                if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) {
                    TorrentInfo ti = torrentHandle.torrentFile(); int np = ti.numPieces(), pl = ti.pieceLength();
                    debug("📊 " + (ti.totalSize()/1048576) + "MB, " + np + " peças");
                    int meta = Math.min(100, np);
                    for (int i = 0; i < meta; i++) { try { torrentHandle.piecePriority(i, org.libtorrent4j.Priority.TOP_PRIORITY); torrentHandle.setPieceDeadline(i, 500); } catch (Exception e) {} }
                    for (int i = meta; i < np; i++) { try { torrentHandle.piecePriority(i, org.libtorrent4j.Priority.IGNORE); } catch (Exception e) {} }
                    int complete = 0, wt = 0; boolean shown = false;
                    while (wt < 300 && downloading) { Thread.sleep(500); complete = 0; wt++; for (int i = 0; i < meta; i++) if (torrentHandle.havePiece(i)) complete++; if (wt % 4 == 0) debug("   📋 " + complete + "/" + meta + " (" + (wt/2) + "s)"); if (!shown && complete >= 15) { shown = true; for (int i = 0; i < 30; i++) { File f = find(new File(savePath)); if (f != null && f.length() > 1048576) { byte[] hdr = new byte[8]; try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; } if ((hdr[4]=='f'&&hdr[5]=='t'&&hdr[6]=='y'&&hdr[7]=='p') || ((hdr[0]&0xFF)==0x1A&&hdr[1]==0x45&&hdr[2]==(byte)0xDF&&hdr[3]==(byte)0xA3)) { videoFile = f; handler.post(() -> { btnWatch.setText("🎬 ASSISTIR"); btnWatch.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.GONE); }); break; } } Thread.sleep(500); } } if (complete >= meta) { debug("✅ Metadados OK!"); break; } }
                    for (int i = meta; i < np; i++) { try { torrentHandle.piecePriority(i, org.libtorrent4j.Priority.DEFAULT); } catch (Exception e) {} }
                }
            } catch (Exception e2) { debug("❌ " + e2.getMessage()); downloading = false; }
        }).start();
    }
    
    private void watch() { 
        if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não encontrado"); return; } 
        debug("▶️ VLC: " + videoFile.getName()); 
        handler.post(() -> { 
            videoSurface.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE); 
            spinnerBar.setVisibility(View.VISIBLE); 
            playWithVlc("http://127.0.0.1:8080/video"); 
        }); 
    }
    
    private void stop() { 
        downloading = false; 
        if (vlcPlayer != null) vlcPlayer.stop(); 
        videoSurface.setVisibility(View.GONE); 
        playerControls.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); 
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE); 
        if (torrentHandle != null && session != null) { try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} torrentHandle = null; } 
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File found = find(f); if (found != null) return found; } else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f; } return null; }
    
    @Override protected void onDestroy() { stop(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); if (vlcPlayer != null) vlcPlayer.release(); if (libVLC != null) libVLC.release(); super.onDestroy(); }
}