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
    private SurfaceView videoSurface;
    private SurfaceHolder surfaceHolder;
    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;
    private TextView timeText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnTorrent, btnStop, btnWatch, btnSkip20;
    private LinearLayout playerControls, audioMenu, subtitleMenu;
    private ScrollView audioScroll, subtitleScroll;
    private ImageButton btnPlayPause, btnSeekBack, btnSeekForward, btnAudio, btnSubtitle;
    private SeekBar seekBar;
    private boolean isTracking = false;
    
    private String savePath;
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private volatile boolean downloading, playing = false;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private int pieceLength = 0, numPieces = 0;
    private long totalSize = 0;
    private long videoDurationMs = 0;
    private int currentPlayingPiece = -1;
    private boolean seeking = false;
    private final Object torrentLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoSurface = findViewById(R.id.video_surface);
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
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { surfaceHolder = h; }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceHolder = null; }
        });
        
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=1500");
        options.add("--file-caching=800");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.Playing) { handler.post(() -> { spinnerBar.setVisibility(View.GONE); btnPlayPause.setImageResource(android.R.drawable.ic_media_pause); }); }
            if (event.type == MediaPlayer.Event.Paused) { handler.post(() -> btnPlayPause.setImageResource(android.R.drawable.ic_media_play)); }
            if (event.type == MediaPlayer.Event.Buffering) { handler.post(() -> spinnerBar.setVisibility(View.VISIBLE)); }
        });
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, 100); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer.isPlaying()) vlcPlayer.pause(); else vlcPlayer.play(); });
        btnSeekBack.setOnClickListener(v -> vlcPlayer.setTime(vlcPlayer.getTime() - 10000));
        btnSeekForward.setOnClickListener(v -> vlcPlayer.setTime(vlcPlayer.getTime() + 10000));
        btnSkip20.setOnClickListener(v -> seekToPiece(20 * 60 * 1000));
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && vlcPlayer.getLength() > 0) seekToPiece(vlcPlayer.getLength() * p / 100); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        
        new Thread(() -> { try { session = new SessionManager(); session.start(); } catch (Exception e) {} }).start();
        startServer();
        
        // Time updater
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (vlcPlayer != null && vlcPlayer.isPlaying() && !seeking && playing) {
                    long time = vlcPlayer.getTime();
                    long length = vlcPlayer.getLength();
                    if (length > 0) {
                        videoDurationMs = length;
                        timeText.setText((time/60000) + ":" + String.format("%02d", (time/1000)%60) + " / " + (length/60000) + ":" + String.format("%02d", (length/1000)%60));
                        if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                        if (pieceLength > 0 && totalSize > 0) {
                            int piece = (int)(time * totalSize / length / pieceLength);
                            if (piece != currentPlayingPiece && piece >= 0 && piece < numPieces) {
                                currentPlayingPiece = piece;
                                maintainBuffer(piece);
                            }
                        }
                    }
                }
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }
    
    private void maintainBuffer(int piece) {
        if (!playing || seeking) return;
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) return;
            try {
                int buffered = 0;
                for (int i = piece; i < Math.min(numPieces, piece + 30); i++) if (torrentHandle.havePiece(i)) buffered++; else break;
                if (buffered < 10) {
                    int start = piece + buffered, end = Math.min(numPieces - 1, start + 20);
                    torrentHandle.setSequentialRange(start, end);
                    for (int i = start; i <= end; i++) if (!torrentHandle.havePiece(i)) { torrentHandle.swig().piece_priority_ex(i, (byte)7); torrentHandle.swig().set_piece_deadline(i, 5000); }
                    for (int i = 0; i < Math.max(0, piece - 20); i++) torrentHandle.swig().piece_priority_ex(i, (byte)0);
                }
            } catch (Exception e) {}
        }
    }
    
    private void seekToPiece(long timeMs) {
        if (vlcPlayer == null || pieceLength <= 0 || totalSize <= 0 || videoDurationMs <= 0) return;
        vlcPlayer.setTime(timeMs);
        final int piece = (int)(timeMs * totalSize / videoDurationMs / pieceLength);
        if (piece < 0 || piece >= numPieces) return;
        seeking = true;
        handler.post(() -> spinnerBar.setVisibility(View.VISIBLE));
        new Thread(() -> {
            synchronized (torrentLock) {
                if (torrentHandle == null || !torrentHandle.isValid()) { seeking = false; handler.post(() -> spinnerBar.setVisibility(View.GONE)); return; }
                try {
                    for (int i = 0; i < piece - 2; i++) torrentHandle.swig().piece_priority_ex(i, (byte)0);
                    for (int i = piece - 1; i <= piece + 1; i++) if (i >= 0 && i < numPieces) { torrentHandle.swig().piece_priority_ex(i, (byte)(i == piece ? 7 : 6)); torrentHandle.swig().set_piece_deadline(i, i == piece ? 2000 : 3000); }
                    torrentHandle.setSequentialRange(piece, numPieces - 1);
                    int waits = 0; boolean ready = false;
                    while (!ready && downloading && waits < 32) {
                        Thread.sleep(250); waits++;
                        int count = 0;
                        for (int i = piece - 1; i <= piece + 1; i++) if (i >= 0 && i < numPieces && torrentHandle.havePiece(i)) count++;
                        if (torrentHandle.havePiece(piece) && count >= 2) { ready = true; break; }
                        if (waits % 4 == 0) torrentHandle.swig().set_piece_deadline(piece, 2000);
                    }
                    if (ready) currentPlayingPiece = piece;
                } catch (Exception e) {}
                seeking = false;
                handler.post(() -> spinnerBar.setVisibility(View.GONE));
            }
        }).start();
    }
    
    private void startServer() { serverThread = new Thread(() -> { try { ServerSocket s = new ServerSocket(8080); while (!Thread.interrupted()) { try { Socket c = s.accept(); new Thread(() -> handleHttp(c)).start(); } catch (IOException e) {} } s.close(); } catch (IOException e) {} }); serverThread.setDaemon(true); serverThread.start(); }
    
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
            if (re == -1 || re >= fs) re = fs - 1;
            long cl = re - rs + 1;
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nContent-Range: bytes " + rs + "-" + (rs+cl-1) + "/" + fs + "\r\nContent-Length: " + cl + "\r\n\r\n").getBytes()); out.flush();
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r"); raf.seek(rs); byte[] buf = new byte[65536]; long sent = 0;
            while (sent < cl) { int tr = (int)Math.min(buf.length, cl - sent); int read = raf.read(buf, 0, tr); if (read <= 0) break; out.write(buf, 0, read); sent += read; }
            raf.close(); out.flush(); client.close();
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        stop();
        downloading = true; playing = false; seeking = false; videoFile = null; torrentHandle = null;
        pieceLength = 0; numPieces = 0; totalSize = 0; videoDurationMs = 0; currentPlayingPiece = -1;
        btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        
        new Thread(() -> {
            try {
                add_torrent_params p = source.startsWith("magnet:") ? libtorrent.parse_magnet_uri(source, new error_code()) : add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath);
                p.setFlags(libtorrent.getAuto_managed().or_(libtorrent.getApply_ip_filter()).or_(libtorrent.getSequential_download()));
                p.setDownload_limit(3*1024*1024);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                session.swig().async_add_torrent(p); Thread.sleep(2000);
                
                synchronized (torrentLock) { torrent_handle_vector h = session.swig().get_torrents(); if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0)); }
                
                int w = 0;
                while (w < 60 && downloading) { Thread.sleep(1000); w++;
                    synchronized (torrentLock) { if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) break; }
                }
                
                synchronized (torrentLock) {
                if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) {
                    TorrentInfo ti = torrentHandle.torrentFile();
                    pieceLength = ti.pieceLength(); numPieces = ti.numPieces(); totalSize = ti.totalSize();
                    
                    for (int i = 0; i < Math.min(30, numPieces); i++) { torrentHandle.swig().piece_priority_ex(i, (byte)7); torrentHandle.swig().set_piece_deadline(i, 20000); }
                    for (int i = Math.max(0, numPieces - 8); i < numPieces; i++) { torrentHandle.swig().piece_priority_ex(i, (byte)7); torrentHandle.swig().set_piece_deadline(i, 20000); }
                    
                    int done = 0;
                    while (done < 20 && downloading) { Thread.sleep(500); done = 0; for (int i = 0; i < 20; i++) if (torrentHandle.havePiece(i)) done++; }
                    
                    for (int i = 0; i < 15; i++) { File f = find(new File(savePath)); if (f != null && f.length() > 5*1048576) { videoFile = f; break; } Thread.sleep(200); }
                    
                    handler.post(() -> { btnWatch.setText("🎬 ASSISTIR"); btnWatch.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.GONE); });
                }
                }
            } catch (Exception e) { downloading = false; }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) return;
        playing = true;
        handler.post(() -> {
            videoSurface.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.VISIBLE);
            try {
                vlcPlayer.getVLCVout().setVideoSurface(videoSurface.getHolder().getSurface(), videoSurface.getHolder());
                vlcPlayer.getVLCVout().attachViews();
                Media m = new Media(libVLC, Uri.parse("http://127.0.0.1:8080/video"));
                m.setHWDecoderEnabled(true, true); m.addOption(":network-caching=1500"); m.addOption(":file-caching=800");
                vlcPlayer.setMedia(m); m.release(); vlcPlayer.play();
                playerControls.setVisibility(View.VISIBLE);
            } catch (Exception e) {}
        });
    }
    
    private void stop() {
        downloading = false; playing = false; seeking = false;
        if (vlcPlayer != null) vlcPlayer.stop();
        videoSurface.setVisibility(View.GONE); playerControls.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        synchronized (torrentLock) { if (torrentHandle != null) { try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} torrentHandle = null; } }
    }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] t = vlcPlayer.getAudioTracks();
        audioMenu.removeAllViews();
        if (t != null) for (MediaPlayer.TrackDescription tr : t) {
            if (tr.id >= 0) {
                TextView tv = new TextView(this); tv.setText("🎵 " + tr.name + (tr.id == vlcPlayer.getAudioTrack() ? " ✓" : "")); tv.setTextColor(tr.id == vlcPlayer.getAudioTrack() ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16,12,16,12);
                final int id = tr.id; tv.setOnClickListener(v -> { vlcPlayer.setAudioTrack(id); audioScroll.setVisibility(View.GONE); });
                audioMenu.addView(tv);
            }
        }
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] t = vlcPlayer.getSpuTracks();
        subtitleMenu.removeAllViews();
        TextView off = new TextView(this); off.setText("📝 Desligado" + (vlcPlayer.getSpuTrack() == -1 ? " ✓" : "")); off.setTextColor(vlcPlayer.getSpuTrack() == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); off.setTextSize(12); off.setPadding(16,12,16,12);
        off.setOnClickListener(v -> { vlcPlayer.setSpuTrack(-1); subtitleScroll.setVisibility(View.GONE); });
        subtitleMenu.addView(off);
        if (t != null) for (MediaPlayer.TrackDescription tr : t) {
            if (tr.id >= 0) {
                TextView tv = new TextView(this); tv.setText("📝 " + tr.name + (tr.id == vlcPlayer.getSpuTrack() ? " ✓" : "")); tv.setTextColor(tr.id == vlcPlayer.getSpuTrack() ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16,12,16,12);
                final int id = tr.id; tv.setOnClickListener(v -> { vlcPlayer.setSpuTrack(id); subtitleScroll.setVisibility(View.GONE); });
                subtitleMenu.addView(tv);
            }
        }
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); audioScroll.setVisibility(View.GONE);
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File ff = find(f); if (ff != null) return ff; } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm)$")) return f; } return null; }
    
    @Override protected void onActivityResult(int r, int res, Intent data) {
        super.onActivityResult(r, res, data);
        if (r == 100 && res == RESULT_OK && data != null && data.getData() != null) {
            try { InputStream is = getContentResolver().openInputStream(data.getData()); File tf = new File(savePath, "torrent_file.torrent"); FileOutputStream fos = new FileOutputStream(tf); byte[] b = new byte[8192]; int l; while ((l = is.read(b)) > 0) fos.write(b, 0, l); fos.close(); is.close(); startDownload(tf.getAbsolutePath()); } catch (Exception e) {}
        }
    }
    
    @Override protected void onDestroy() { stop(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); if (vlcPlayer != null) vlcPlayer.release(); if (libVLC != null) libVLC.release(); super.onDestroy(); }
}