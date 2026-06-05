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

public class MainActivity extends AppCompatActivity {
    private SurfaceView videoSurface;
    private TextView statusText, debugText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    private Button btnPause, btnSeekBack, btnSeekFwd;
    private ScrollView debugScroll;
    private LinearLayout mediaControls;
    
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
    
    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;
    private boolean isPlaying = false;

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
        btnPause = findViewById(R.id.btn_pause);
        btnSeekBack = findViewById(R.id.btn_seek_back);
        btnSeekFwd = findViewById(R.id.btn_seek_fwd);
        mediaControls = findViewById(R.id.media_controls);
        
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
        
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=5000");
        options.add("--file-caching=5000");
        options.add("--clock-synchro=0");
        
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        vlcPlayer.getVLCVout().setVideoView(videoSurface);
        vlcPlayer.getVLCVout().attachViews();
        
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Opening:
                    debug("🎬 VLC: Opening");
                    break;
                case MediaPlayer.Event.Playing:
                    debug("▶️ VLC: PLAYING ✅");
                    isPlaying = true;
                    handler.post(() -> {
                        loadingOverlay.setVisibility(View.GONE);
                        spinnerBar.setVisibility(View.GONE);
                        mediaControls.setVisibility(View.VISIBLE);
                        btnPause.setText("⏸️");
                    });
                    break;
                case MediaPlayer.Event.Paused:
                    debug("⏸️ VLC: Paused");
                    isPlaying = false;
                    handler.post(() -> btnPause.setText("▶️"));
                    break;
                case MediaPlayer.Event.Buffering:
                    debug("⏳ VLC: Buffering " + event.getBuffering() + "%");
                    break;
                case MediaPlayer.Event.Stopped:
                    debug("⏹️ VLC: Stopped");
                    isPlaying = false;
                    break;
                case MediaPlayer.Event.EndReached:
                    debug("🏁 VLC: EndReached");
                    isPlaying = false;
                    break;
                case MediaPlayer.Event.EncounteredError:
                    debug("❌ VLC: Error");
                    break;
                case MediaPlayer.Event.LengthChanged:
                    debug("📏 VLC: Duração = " + event.getLengthChanged()/1000 + "s");
                    break;
            }
        });
        
        btnPause.setOnClickListener(v -> {
            if (isPlaying) vlcPlayer.pause(); else vlcPlayer.play();
        });
        btnSeekBack.setOnClickListener(v -> {
            vlcPlayer.setTime(Math.max(0, vlcPlayer.getTime() - 10000));
            debug("⏪ -10s → " + (vlcPlayer.getTime()/1000) + "s");
        });
        btnSeekFwd.setOnClickListener(v -> {
            vlcPlayer.setTime(Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + 10000));
            debug("⏩ +10s → " + (vlcPlayer.getTime()/1000) + "s");
        });
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); debug("✅ Sessão OK"); } 
            catch (Exception e) { debug("❌ " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("╔══════════════════════════╗");
        debug("║   APP INICIADO           ║");
        debug("╚══════════════════════════╝");
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
    
    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            for (File child : f.listFiles()) deleteRecursive(child);
        }
        f.delete();
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                debug("🌐 HTTP :8080");
                while (!Thread.interrupted()) {
                    try { 
                        Socket c = server.accept();
                        httpReqCount++;
                        int num = httpReqCount;
                        new Thread(() -> handleHttp(c, num)).start();
                    } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleHttp(Socket c, int reqNum) {
        long startTime = System.currentTimeMillis();
        long totalSent = 0;
        int chunkCount = 0;
        
        try {
            c.setSoTimeout(30000);
            OutputStream o = c.getOutputStream();
            BufferedReader i = new BufferedReader(new InputStreamReader(c.getInputStream()));
            
            String r = i.readLine();
            if (r == null || !r.contains("/video")) { 
                o.write("HTTP/1.1 404\r\n\r\n".getBytes()); o.flush(); c.close(); return; 
            }
            
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
            
            debug("📥 #" + reqNum + " | Range: " + s + "-" + (e == -1 ? "?" : e));
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 50971520) {
                o.write("HTTP/1.1 503\r\nRetry-After: 2\r\n\r\n".getBytes()); o.flush(); c.close();
                debug("   #" + reqNum + " ↪ 503 | Min 50MB | Tem: " + (vf != null ? vf.length()/1048576 : 0) + "MB");
                return;
            }
            
            long len = vf.length();
            if (e == -1 || e >= len) e = len - 1;
            if (s >= len) { o.write("HTTP/1.1 416\r\n\r\n".getBytes()); o.flush(); c.close(); return; }
            
            String m = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            long currentPos = s;
            
            if (torrentHandle != null && torrentHandle.is_valid()) {
                int pieceLen = 262144;
                int startP = (int)(s / pieceLen);
                int endP = Math.min(startP + 50, 9999);
                for (int j = startP; j <= endP; j++) {
                    try { torrentHandle.set_piece_deadline(j, 30); } catch (Exception ex) {}
                }
                debug("   🎯 Peças " + startP + "-" + endP + " | Pos " + (s/1048576) + "MB");
            }
            
            debug("   🚀 Streaming...");
            
            while (downloading && !c.isClosed()) {
                if (currentPos >= len) { Thread.sleep(500); continue; }
                
                long chunkSize = Math.min(262144, len - currentPos);
                byte[] buf = new byte[(int)chunkSize];
                
                RandomAccessFile raf = new RandomAccessFile(vf, "r");
                raf.seek(currentPos);
                int total = raf.read(buf);
                raf.close();
                
                if (total <= 0) { Thread.sleep(200); continue; }
                
                String resp = "HTTP/1.1 206\r\nContent-Type: " + m + "\r\n" +
                    "Content-Range: bytes " + currentPos + "-" + (currentPos+total-1) + "/" + len + "\r\n" +
                    "Content-Length: " + total + "\r\n\r\n";
                
                try {
                    o.write(resp.getBytes());
                    o.write(buf, 0, total);
                    o.flush();
                    
                    chunkCount++;
                    totalSent += total;
                    currentPos += total;
                    
                    if (totalSent % 5242880 < 262144 || chunkCount <= 3) {
                        debug("   📤 #" + reqNum + " | Chunk " + chunkCount + " | " + 
                              (total/1024) + "KB | " + (totalSent/1048576) + "MB/" + 
                              (len/1048576) + "MB | " + (System.currentTimeMillis() - startTime) + "ms");
                    }
                    
                    if (torrentHandle != null && chunkCount % 10 == 0) {
                        int nextP = (int)(currentPos / 262144);
                        for (int j = nextP; j < nextP + 20; j++) {
                            try { torrentHandle.set_piece_deadline(j, 50); } catch (Exception ex) {}
                        }
                    }
                } catch (SocketException ex) {
                    debug("   #" + reqNum + " ⚠️ Conexão fechada: " + ex.getMessage());
                    break;
                }
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            c.close();
            debug("   ✅ #" + reqNum + " | " + chunkCount + " chunks | " + 
                  (totalSent/1048576) + "MB | " + elapsed + "ms");
            
        } catch (Exception ex) { 
            try { c.close(); } catch (IOException ex2) {}
            debug("   ❌ #" + reqNum + " | " + ex.getClass().getSimpleName());
        }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        // 🗑️ Deleta arquivos antigos
        File torrentDir = new File(savePath);
        if (torrentDir.exists()) {
            for (File f : torrentDir.listFiles()) deleteRecursive(f);
            debug("🗑️ Arquivos antigos deletados");
        }
        new File(savePath).mkdirs();
        
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        httpReqCount = 0;
        debugLog.setLength(0);
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
            mediaControls.setVisibility(View.GONE);
        });
        
        debug("╔══════════════════════════╗");
        debug("║   INICIANDO              ║");
        debug("╚══════════════════════════╝");
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
                if (h.size() > 0) {
                    torrentHandle = h.get(0);
                    torrent_status ts = torrentHandle.status();
                    debug("📊 " + ts.getNum_peers() + " peers | " + (ts.getTotal_wanted()/1048576) + "MB");
                }
                
                debug("🔍 Procurando arquivo (mín 50MB com dados reais)...");
                for (int i = 0; i < 300 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 50971520) {
                        // Verifica se NÃO são zeros
                        byte[] check = new byte[1024];
                        try { new RandomAccessFile(f, "r").read(check); } catch (Exception e2) { continue; }
                        
                        boolean allZero = true;
                        for (int j = 8; j < check.length; j++) {
                            if (check[j] != 0) { allZero = false; break; }
                        }
                        
                        String hex = "";
                        for (int j = 0; j < 8; j++) hex += String.format("%02X ", check[j]);
                        boolean isMP4 = (check[4]=='f' && check[5]=='t' && check[6]=='y' && check[7]=='p');
                        boolean isMKV = ((check[0]&0xFF)==0x1A && check[1]==0x45 && check[2]==(byte)0xDF && check[3]==(byte)0xA3);
                        
                        debug("   " + f.getName() + " " + (f.length()/1048576) + "MB | " + hex + 
                              (allZero ? " [ZEROS!]" : isMP4 ? " [MP4 OK]" : isMKV ? " [MKV OK]" : " [??]"));
                        
                        if (!allZero && (isMP4 || isMKV)) {
                            videoFile = f;
                            debug("✅ Arquivo válido! " + (f.length()/1048576) + "MB");
                            handler.post(() -> {
                                btnWatch.setText("🎬 ASSISTIR (" + (f.length()/1048576) + "MB)");
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
        debug("▶️ VLC Player iniciando...");
        
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
        debug("⏹️ Parando | HTTP reqs: " + httpReqCount);
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        vlcPlayer.stop();
        if (torrentHandle != null && session != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
            torrentHandle = null;
        }
        videoSurface.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); loadingOverlay.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        mediaControls.setVisibility(View.GONE);
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
        vlcPlayer.release();
        libVLC.release();
        if (serverThread != null) serverThread.interrupt();
        if (session != null) session.stop();
        super.onDestroy();
    }
}