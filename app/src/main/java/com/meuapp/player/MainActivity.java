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
    // UI
    private SurfaceView videoSurface;
    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;
    private TextView timeText, logText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnTorrent, btnStop, btnWatch, btnSkip20;
    private LinearLayout playerControls, audioMenu, subtitleMenu, startScreen, playerScreen;
    private ScrollView audioScroll, subtitleScroll, logScroll;
    private ImageButton btnPlayPause, btnSeekBack, btnSeekForward, btnAudio, btnSubtitle;
    private SeekBar seekBar;
    private boolean isTracking = false;
    
    // Core
    private String savePath;
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private volatile boolean downloading, playing = false;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread, downloadThread;
    private int pieceLength = 0, numPieces = 0;
    private long totalSize = 0;
    private long videoDurationMs = 0;
    private final Object torrentLock = new Object();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // UI
        videoSurface = findViewById(R.id.video_surface);
        timeText = findViewById(R.id.time_text);
        logText = findViewById(R.id.log_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnTorrent = findViewById(R.id.btn_torrent);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        btnSkip20 = findViewById(R.id.btn_skip_20);
        playerControls = findViewById(R.id.player_controls);
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
        startScreen = findViewById(R.id.start_screen);
        playerScreen = findViewById(R.id.player_screen);
        logScroll = findViewById(R.id.log_scroll);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        // VLC
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=1500");
        options.add("--file-caching=500");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.Playing) handler.post(() -> spinnerBar.setVisibility(View.GONE));
            if (event.type == MediaPlayer.Event.Buffering) handler.post(() -> spinnerBar.setVisibility(View.VISIBLE));
        });
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {}
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {}
        });
        
        // Botões
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, 100); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer.isPlaying()) vlcPlayer.pause(); else vlcPlayer.play(); });
        btnSeekBack.setOnClickListener(v -> { if (vlcPlayer.getLength() > 0) vlcPlayer.setTime(Math.max(0, vlcPlayer.getTime() - 10000)); });
        btnSeekForward.setOnClickListener(v -> { if (vlcPlayer.getLength() > 0) vlcPlayer.setTime(Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + 10000)); });
        btnSkip20.setOnClickListener(v -> seekToPiece(20 * 60 * 1000));
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && vlcPlayer.getLength() > 0) seekToPiece(vlcPlayer.getLength() * p / 100); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        
        // Sessão libtorrent
        new Thread(() -> { try { session = new SessionManager(); session.start(); } catch (Exception e) {} }).start();
        
        // Servidor HTTP
        startServer();
        
        // Timer UI
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (vlcPlayer != null && vlcPlayer.isPlaying() && playing) {
                    long time = vlcPlayer.getTime();
                    long length = vlcPlayer.getLength();
                    if (length > 0) {
                        videoDurationMs = length;
                        timeText.setText((time/60000) + ":" + String.format("%02d", (time/1000)%60) + " / " + (length/60000) + ":" + String.format("%02d", (length/1000)%60));
                        if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                    }
                }
                handler.postDelayed(this, 1000);
            }
        }, 1000);
        
        log("✨ Pronto para começar");
    }
    
    // ==================== LOG ====================
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        android.util.Log.d("TS", msg);
        logBuilder.append(line);
        handler.post(() -> {
            if (logText != null) logText.setText(logBuilder.toString());
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    // ==================== SEEK ====================
    private void seekToPiece(long timeMs) {
        if (vlcPlayer == null || pieceLength <= 0 || totalSize <= 0 || videoDurationMs <= 0) return;
        vlcPlayer.setTime(timeMs);
        final int piece = (int)(timeMs * totalSize / videoDurationMs / pieceLength);
        if (piece < 0 || piece >= numPieces) return;
        
        log("🎯 Seek → peça " + piece);
        handler.post(() -> spinnerBar.setVisibility(View.VISIBLE));
        
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) { handler.post(() -> spinnerBar.setVisibility(View.GONE)); return; }
            try {
                for (int i = 0; i < piece - 2; i++) torrentHandle.swig().piece_priority_ex(i, (byte)0);
                for (int i = piece - 1; i <= piece + 1; i++) {
                    if (i >= 0 && i < numPieces) { 
                        torrentHandle.swig().piece_priority_ex(i, (byte)(i == piece ? 7 : 6)); 
                        torrentHandle.swig().set_piece_deadline(i, i == piece ? 2000 : 3000); 
                    }
                }
                torrentHandle.setSequentialRange(piece, numPieces - 1);
                
                int waits = 0;
                while (!torrentHandle.havePiece(piece) && downloading && waits < 40 && torrentHandle.isValid()) { 
                    try { Thread.sleep(250); } catch (InterruptedException e) { break; }
                    waits++; 
                    if (waits % 4 == 0 && torrentHandle.isValid()) torrentHandle.swig().set_piece_deadline(piece, 2000); 
                }
                log(torrentHandle.havePiece(piece) ? "✅ Peça " + piece + " pronta" : "⏰ Timeout");
            } catch (Exception e) {}
            handler.post(() -> spinnerBar.setVisibility(View.GONE));
        }
    }
    
    // ==================== SERVIDOR HTTP ====================
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
            OutputStream out = client.getOutputStream(); InputStream in = client.getInputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream(); int b;
            while ((b = in.read()) != -1) { hb.write(b); byte[] d = hb.toByteArray(); if (d.length >= 4 && d[d.length-4]=='\r'&&d[d.length-3]=='\n'&&d[d.length-2]=='\r'&&d[d.length-1]=='\n') break; }
            String req = new String(hb.toByteArray());
            if (!req.contains("/video")) { out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            
            long rs = 0, re = -1; boolean hr = false;
            for (String l : req.split("\r\n")) { if (l.toLowerCase().startsWith("range: bytes=")) { hr = true; rs = Long.parseLong(l.substring(13).trim().split("-")[0]); if (l.substring(13).contains("-")) { String[] p = l.substring(13).split("-"); if (p.length > 1 && !p[1].isEmpty()) re = Long.parseLong(p[1]); } } }
            
            if (videoFile == null || !videoFile.exists()) { out.write("HTTP/1.1 503\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            long fs = videoFile.length();
            
            if (!hr) { out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/x-matroska\r\nContent-Length: " + fs + "\r\nAccept-Ranges: bytes\r\n\r\n").getBytes()); out.flush(); RandomAccessFile raf = new RandomAccessFile(videoFile, "r"); byte[] data = new byte[65536]; int read; while ((read = raf.read(data)) != -1) out.write(data, 0, read); raf.close(); out.flush(); client.close(); return; }
            if (re == -1 || re >= fs) re = fs - 1; long cl = re - rs + 1;
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nContent-Range: bytes " + rs + "-" + (rs+cl-1) + "/" + fs + "\r\nContent-Length: " + cl + "\r\n\r\n").getBytes()); out.flush();
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r"); raf.seek(rs); byte[] buf = new byte[65536]; long sent = 0;
            while (sent < cl) { int tr = (int)Math.min(buf.length, cl - sent); int read = raf.read(buf, 0, tr); if (read <= 0) break; out.write(buf, 0, read); sent += read; }
            raf.close(); out.flush(); client.close();
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    // ==================== DOWNLOAD ====================
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        stop();
        downloading = true; playing = false; videoFile = null; torrentHandle = null;
        pieceLength = 0; numPieces = 0; totalSize = 0; videoDurationMs = 0;
        
        // Mostrar tela do player
        handler.post(() -> {
            startScreen.setVisibility(View.GONE);
            playerScreen.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
            btnSkip20.setVisibility(View.GONE);
        });
        
        log("⏳ Conectando ao tracker...");
        
        downloadThread = new Thread(() -> {
            try {
                File[] oldFiles = new File(savePath).listFiles();
                if (oldFiles != null) for (File f : oldFiles) if (!f.getName().equals("torrent_file.torrent")) f.delete();
                
                add_torrent_params p = source.startsWith("magnet:") ? libtorrent.parse_magnet_uri(source, new error_code()) : add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath);
                p.setFlags(libtorrent.getAuto_managed().or_(libtorrent.getSequential_download()));
                p.setDownload_limit(1*1024*1024);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                
                synchronized (torrentLock) {
                    session.swig().async_add_torrent(p);
                    Thread.sleep(3000);
                    torrent_handle_vector h = session.swig().get_torrents();
                    if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0));
                }
                
                if (torrentHandle == null) { log("❌ Falha ao iniciar torrent"); downloading = false; return; }
                
                int w = 0;
                while (w < 60 && downloading && torrentHandle.isValid()) { 
                    Thread.sleep(1000); w++;
                    synchronized (torrentLock) { if (torrentHandle.torrentFile() != null) break; }
                }
                
                synchronized (torrentLock) {
                    if (!torrentHandle.isValid()) { downloading = false; return; }
                    TorrentInfo ti = torrentHandle.torrentFile();
                    if (ti == null) { downloading = false; return; }
                    
                    pieceLength = ti.pieceLength(); numPieces = ti.numPieces(); totalSize = ti.totalSize();
                    log("📊 " + (totalSize/1048576) + "MB | " + numPieces + " peças");
                    
                    for (int i = 0; i < Math.min(30, numPieces); i++) {
                        torrentHandle.swig().piece_priority_ex(i, (byte)7);
                        torrentHandle.swig().set_piece_deadline(i, 30000);
                    }
                }
                
                int done = 0, attempts = 0;
                while (done < 10 && downloading && attempts < 60) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                    attempts++;
                    synchronized (torrentLock) {
                        if (!torrentHandle.isValid()) break;
                        done = 0;
                        for (int i = 0; i < 10; i++) if (torrentHandle.havePiece(i)) done++;
                    }
                    if (attempts % 5 == 0) log("📥 " + done + "/10 peças iniciais");
                }
                
                for (int i = 0; i < 30; i++) { 
                    File f = find(new File(savePath)); 
                    if (f != null && f.length() > 3*1048576) { videoFile = f; break; } 
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                }
                
                if (videoFile != null) {
                    log("📁 " + videoFile.getName());
                    handler.post(() -> { 
                        btnWatch.setVisibility(View.VISIBLE); 
                        bufferBar.setVisibility(View.GONE); 
                    });
                } else {
                    log("❌ Arquivo não encontrado");
                }
            } catch (Exception e) { log("❌ " + e.getMessage()); downloading = false; }
        });
        downloadThread.start();
    }
    
    // ==================== PLAYER ====================
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { log("❌ Aguarde o download"); return; }
        playing = true;
        log("▶️ Iniciando reprodução...");
        handler.post(() -> {
            videoSurface.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE); 
            btnSkip20.setVisibility(View.VISIBLE);
            try {
                vlcPlayer.getVLCVout().setVideoSurface(videoSurface.getHolder().getSurface(), videoSurface.getHolder());
                vlcPlayer.getVLCVout().attachViews();
                Media m = new Media(libVLC, Uri.parse("http://127.0.0.1:8080/video"));
                m.setHWDecoderEnabled(true, true); 
                m.addOption(":network-caching=1500"); 
                m.addOption(":file-caching=500");
                vlcPlayer.setMedia(m); m.release(); vlcPlayer.play();
                playerControls.setVisibility(View.VISIBLE);
            } catch (Exception e) { log("❌ Erro VLC: " + e.getMessage()); }
        });
    }
    
    private void stop() {
        downloading = false; playing = false;
        if (vlcPlayer != null) vlcPlayer.stop();
        handler.post(() -> {
            videoSurface.setVisibility(View.GONE); 
            playerControls.setVisibility(View.GONE);
            btnWatch.setVisibility(View.GONE); 
            btnSkip20.setVisibility(View.GONE);
            bufferBar.setVisibility(View.GONE); 
            spinnerBar.setVisibility(View.GONE);
            startScreen.setVisibility(View.VISIBLE);
            playerScreen.setVisibility(View.GONE);
        });
        if (downloadThread != null) downloadThread.interrupt();
        synchronized (torrentLock) { 
            if (torrentHandle != null) { 
                try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} 
                torrentHandle = null; 
            } 
        }
        log("⏹ Parado");
    }
    
    // ==================== UI ====================
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] t = vlcPlayer.getAudioTracks();
        int cur = vlcPlayer.getAudioTrack();
        audioMenu.removeAllViews();
        log("🎵 Áudios: " + (t != null ? t.length : 0));
        if (t != null) for (MediaPlayer.TrackDescription tr : t) if (tr.id >= 0) {
            TextView tv = new TextView(this); 
            tv.setText("🎵 " + tr.name + (tr.id == cur ? " ✓" : "")); 
            tv.setTextColor(tr.id == cur ? 0xFF6C5CE7 : 0xFF334455); 
            tv.setTextSize(12); tv.setPadding(16,12,16,12);
            final int id = tr.id; 
            tv.setOnClickListener(v -> { vlcPlayer.setAudioTrack(id); audioScroll.setVisibility(View.GONE); });
            audioMenu.addView(tv);
        }
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); 
        subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] t = vlcPlayer.getSpuTracks();
        int cur = vlcPlayer.getSpuTrack();
        subtitleMenu.removeAllViews();
        log("📝 Legendas: " + (t != null ? t.length : 0));
        TextView off = new TextView(this); 
        off.setText("📝 Desligado" + (cur == -1 ? " ✓" : "")); 
        off.setTextColor(cur == -1 ? 0xFF6C5CE7 : 0xFF334455); 
        off.setTextSize(12); off.setPadding(16,12,16,12);
        off.setOnClickListener(v -> { vlcPlayer.setSpuTrack(-1); subtitleScroll.setVisibility(View.GONE); });
        subtitleMenu.addView(off);
        if (t != null) for (MediaPlayer.TrackDescription tr : t) if (tr.id >= 0) {
            TextView tv = new TextView(this); 
            tv.setText("📝 " + tr.name + (tr.id == cur ? " ✓" : "")); 
            tv.setTextColor(tr.id == cur ? 0xFF6C5CE7 : 0xFF334455); 
            tv.setTextSize(12); tv.setPadding(16,12,16,12);
            final int id = tr.id; 
            tv.setOnClickListener(v -> { vlcPlayer.setSpuTrack(id); subtitleScroll.setVisibility(View.GONE); });
            subtitleMenu.addView(tv);
        }
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); 
        audioScroll.setVisibility(View.GONE);
    }
    
    private File find(File dir) { 
        File[] files = dir.listFiles(); 
        if (files != null) for (File f : files) { 
            if (f.isDirectory()) { File ff = find(f); if (ff != null) return ff; } 
            else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm)$")) return f; 
        } 
        return null; 
    }
    
    @Override protected void onActivityResult(int r, int res, Intent data) {
        super.onActivityResult(r, res, data);
        if (r == 100 && res == RESULT_OK && data != null && data.getData() != null) {
            try { 
                InputStream is = getContentResolver().openInputStream(data.getData()); 
                File tf = new File(savePath, "torrent_file.torrent"); 
                FileOutputStream fos = new FileOutputStream(tf); 
                byte[] b = new byte[8192]; int l; 
                while ((l = is.read(b)) > 0) fos.write(b, 0, l); 
                fos.close(); is.close(); 
                startDownload(tf.getAbsolutePath()); 
            } catch (Exception e) {}
        }
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