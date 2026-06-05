package com.meuapp.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity implements IVLCVout.OnNewVideoLayout {
    private SurfaceView videoSurface;
    private TextView statusText, debugText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    private ScrollView debugScroll;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private StringBuilder debugLog = new StringBuilder();
    private int httpReqCount = 0;
    
    // VLC
    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoSurface = findViewById(R.id.video_surface);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        debugScroll = findViewById(R.id.debug_scroll);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        videoSurface.post(() -> {
            int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.92);
            int h = (int)(w * 9.0 / 16.0);
            ViewGroup.LayoutParams p = videoSurface.getLayoutParams();
            p.width = w; p.height = h;
            videoSurface.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        // Configura VLC
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=2000");
        options.add("--file-caching=2000");
        options.add("--clock-synchro=0");
        options.add("--no-audio-time-stretch");
        options.add("-vvv");
        
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        IVLCVout vout = vlcPlayer.getVLCVout();
        vout.setVideoView(videoSurface);
        vout.attachViews(this);
        
        vlcPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                switch (event.type) {
                    case MediaPlayer.Event.Opening:
                        debug("🎬 VLC: Opening...");
                        break;
                    case MediaPlayer.Event.Playing:
                        debug("▶️ VLC: Playing");
                        loadingOverlay.setVisibility(View.GONE);
                        spinnerBar.setVisibility(View.GONE);
                        break;
                    case MediaPlayer.Event.Buffering:
                        float buffering = event.getBuffering();
                        debug("⏳ VLC: Buffering " + buffering + "%");
                        loadingOverlay.setVisibility(View.VISIBLE);
                        spinnerBar.setVisibility(View.VISIBLE);
                        break;
                    case MediaPlayer.Event.Stopped:
                        debug("⏹️ VLC: Stopped");
                        break;
                    case MediaPlayer.Event.EndReached:
                        debug("🏁 VLC: End");
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        debug("❌ VLC: Error");
                        break;
                }
            }
        });
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); debug("✅ OK"); } 
            catch (Exception e) { debug("❌ " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("══════ APP INICIADO ══════");
    }
    
    @Override
    public void onNewVideoLayout(IVLCVout vout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        debug("📐 VLC: " + width + "x" + height);
    }
    
    private void debug(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        debugLog.append(line);
        handler.post(() -> {
            statusText.setText(msg);
            debugText.setText(debugLog.toString());
            debugScroll.post(() -> debugScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 5);
                server.setReuseAddress(true);
                while (!Thread.interrupted()) {
                    try { Socket c = server.accept(); handleHttp(c); } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleHttp(Socket c) {
        try {
            OutputStream o = c.getOutputStream();
            BufferedReader i = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String r = i.readLine();
            if (r == null || !r.contains("/video")) { o.write("HTTP/1.1 404\r\n\r\n".getBytes()); o.flush(); c.close(); return; }
            
            long s = 0, e = -1;
            String l;
            while ((l = i.readLine()) != null && !l.isEmpty()) {
                if (l.toLowerCase().startsWith("range:")) {
                    String x = l.substring(6).trim().replace("bytes=", "");
                    String[] p = x.split("-");
                    s = Long.parseLong(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) e = Long.parseLong(p[1]);
                }
            }
            
            if (torrentHandle != null && torrentHandle.is_valid()) {
                int pieceLen = 262144;
                int startP = (int)(s / pieceLen);
                int endP = Math.min(startP + 30, 9999);
                for (int j = startP; j <= endP; j++) {
                    try { torrentHandle.set_piece_deadline(j, 30); } catch (Exception ex) {}
                }
                debug("🎯 Peças " + startP + "-" + endP + " | Pos " + (s/1048576) + "MB");
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 4096) {
                o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close();
                return;
            }
            
            long len = vf.length();
            if (e == -1 || e >= len) e = len - 1;
            
            String m = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            int sz = (int)(e - s + 1);
            if (sz > 131072) sz = 131072;
            
            byte[] b = new byte[sz];
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(s);
            int t = raf.read(b);
            raf.close();
            
            if (t <= 0) { o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close(); return; }
            
            String resp = "HTTP/1.1 206\r\nContent-Type: " + m + "\r\nContent-Range: bytes " + s + "-" + (s+t-1) + "/" + len + "\r\nContent-Length: " + t + "\r\nAccept-Ranges: bytes\r\n\r\n";
            o.write(resp.getBytes()); o.write(b, 0, t); o.flush(); c.close();
            
        } catch (Exception ex) { try { c.close(); } catch (IOException ex2) {} }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        debugLog.setLength(0);
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        debug("⏳ Baixando (2 MB/s)...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(0));
                p.setDownload_limit(2 * 1024 * 1024);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrentHandle = h.get(0);
                
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 5242880) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; }
                        if ((hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p') ||
                            ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3)) {
                            videoFile = f;
                            long mb = f.length()/1048576;
                            debug("📁 " + f.getName() + " (" + mb + "MB)");
                            handler.post(() -> {
                                btnWatch.setText("🎬 ASSISTIR (" + mb + "MB)");
                                btnWatch.setVisibility(View.VISIBLE);
                            });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e2) { debug("❌ " + e2.getMessage()); }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não encontrado"); return; }
        debug("▶️ VLC Player via HTTP");
        
        handler.post(() -> { 
            videoSurface.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE);
            loadingOverlay.setVisibility(View.VISIBLE);
            spinnerBar.setVisibility(View.VISIBLE);
        });
        
        Media media = new Media(libVLC, Uri.parse("http://127.0.0.1:8080/video"));
        media.setHWDecoderEnabled(true, false);
        vlcPlayer.setMedia(media);
        media.release();
        vlcPlayer.play();
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        vlcPlayer.stop();
        if (torrentHandle != null && session != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
            torrentHandle = null;
        }
        videoSurface.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); loadingOverlay.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        debug("⏹️ Parado");
    }
    
    private File find(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) { File found = find(f); if (found != null) return found; }
            else if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv") || f.getName().endsWith(".avi") || f.getName().endsWith(".webm")) return f;
        }
        return null;
    }
    
    @Override protected void onDestroy() {
        stop();
        vlcPlayer.release();
        libVLC.release();
        if (serverThread != null) serverThread.interrupt();
        if (session != null) session.stop();
        super.onDestroy();
    }
}