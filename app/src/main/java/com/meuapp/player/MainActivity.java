package com.meuapp.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private VideoView videoView;
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
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private StringBuilder debugLog = new StringBuilder();
    private int httpReqCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoView = findViewById(R.id.video_view);
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
        
        videoView.post(() -> {
            int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.92);
            int h = (int)(w * 9.0 / 16.0);
            ViewGroup.LayoutParams p = videoView.getLayoutParams();
            p.width = w; p.height = h;
            videoView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        MediaController mediaController = new MediaController(this, false);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);
        
        videoView.setOnPreparedListener(mp -> {
            debug("✅ onPrepared");
            debug("   Duração: " + videoView.getDuration() + "ms");
            debug("   Largura: " + mp.getVideoWidth());
            debug("   Altura: " + mp.getVideoHeight());
            debug("   Codec: " + (mp.getVideoWidth() > 0 ? "VÍDEO OK" : "SEM VÍDEO"));
            loadingOverlay.setVisibility(View.GONE);
            spinnerBar.setVisibility(View.GONE);
        });
        
        videoView.setOnErrorListener((mp, what, extra) -> {
            String tipo;
            switch (what) {
                case -1: tipo = "UNKNOWN"; break;
                case 1: tipo = "MEDIA_ERROR_UNKNOWN"; break;
                case 100: tipo = "MEDIA_ERROR_SERVER_DIED"; break;
                case 200: tipo = "MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK"; break;
                case -2147483648: tipo = "MEDIA_ERROR_IO / DATA"; break;
                default: tipo = "CÓDIGO " + what;
            }
            debug("❌ onError: " + tipo + " | extra=" + extra);
            debug("   Arquivo existe: " + (videoFile != null && videoFile.exists()));
            debug("   Tamanho: " + (videoFile != null ? videoFile.length() : 0) + " bytes");
            
            if (videoFile != null && videoFile.exists()) {
                try {
                    byte[] hdr = new byte[16];
                    RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                    raf.read(hdr);
                    raf.close();
                    StringBuilder hex = new StringBuilder();
                    for (byte b : hdr) hex.append(String.format("%02X ", b));
                    debug("   Header: " + hex.toString());
                } catch (Exception ex) {}
            }
            
            handler.postDelayed(() -> {
                if (downloading && videoFile != null && videoFile.exists()) {
                    debug("🔄 Retry...");
                    videoView.setVideoURI(Uri.parse("http://127.0.0.1:8080/video"));
                    videoView.start();
                }
            }, 2000);
            return true;
        });
        
        videoView.setOnCompletionListener(mp -> debug("🏁 Fim"));
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); debug("✅ Sessão OK"); } 
            catch (Exception e) { debug("❌ Sessão: " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("══════ APP INICIADO ══════");
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
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 5);
                server.setReuseAddress(true);
                debug("🌐 HTTP :8080");
                while (!Thread.interrupted()) {
                    try { 
                        Socket c = server.accept(); 
                        httpReqCount++;
                        handleHttp(c, httpReqCount); 
                    } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {
                debug("❌ HTTP: " + e.getMessage());
            }
        }).start();
    }
    
    private void handleHttp(Socket c, int num) {
        try {
            OutputStream o = c.getOutputStream();
            BufferedReader i = new BufferedReader(new InputStreamReader(c.getInputStream()));
            
            String req = i.readLine();
            String rangeStr = null;
            String l;
            while ((l = i.readLine()) != null && !l.isEmpty()) {
                if (l.toLowerCase().startsWith("range:")) rangeStr = l.substring(6).trim();
            }
            
            debug("📥 HTTP #" + num + " | " + (req != null ? req : "NULL") + 
                  (rangeStr != null ? " | Range: " + rangeStr : ""));
            
            if (req == null || !req.contains("/video")) {
                o.write("HTTP/1.1 404\r\n\r\n".getBytes()); o.flush(); c.close();
                debug("   ↪ 404");
                return;
            }
            
            long startByte = 0, endByte = -1;
            if (rangeStr != null) {
                String x = rangeStr.replace("bytes=", "");
                String[] p = x.split("-");
                startByte = Long.parseLong(p[0]);
                if (p.length > 1 && !p[1].isEmpty()) endByte = Long.parseLong(p[1]);
            }
            
            if (torrentHandle != null && torrentHandle.is_valid()) {
                int sp = (int)(startByte / 262144);
                int ep = Math.min(sp + 50, 9999);
                for (int j = sp; j <= ep; j++) {
                    try { torrentHandle.set_piece_deadline(j, 30); } catch (Exception ex) {}
                }
                debug("   🎯 Peças " + sp + "-" + ep);
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists()) {
                o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close();
                debug("   ↪ 503 (sem arquivo)");
                return;
            }
            
            long fileLen = vf.length();
            if (fileLen < 4096) {
                o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close();
                debug("   ↪ 503 (tamanho=" + fileLen + ")");
                return;
            }
            
            if (endByte == -1 || endByte >= fileLen) endByte = fileLen - 1;
            
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            int size = (int)(endByte - startByte + 1);
            if (size > 524288) size = 524288; // 512KB por chunk
            
            byte[] buf = new byte[size];
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(startByte);
            int total = raf.read(buf);
            raf.close();
            
            if (total <= 1024) {
                o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); o.flush(); c.close();
                debug("   ↪ 503 (leu só " + total + " bytes)");
                return;
            }
            
            String resp = "HTTP/1.1 206\r\nContent-Type: " + mime + "\r\n" +
                "Content-Range: bytes " + startByte + "-" + (startByte+total-1) + "/" + fileLen + "\r\n" +
                "Content-Length: " + total + "\r\nAccept-Ranges: bytes\r\n\r\n";
            o.write(resp.getBytes()); o.write(buf, 0, total); o.flush(); c.close();
            
            debug("   ✅ 206 | " + total + " bytes | " + startByte + "-" + (startByte+total-1));
            
        } catch (Exception ex) { 
            try { c.close(); } catch (IOException ex2) {}
            debug("   ❌ " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        httpReqCount = 0;
        debugLog.setLength(0);
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        debug("══════ INICIANDO ══════");
        debug("📡 Magnet: " + magnet.substring(0, Math.min(50, magnet.length())) + "...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(0));
                p.setDownload_limit(2 * 1024 * 1024); // 2 MB/s
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrentHandle = h.get(0);
                    torrent_status ts = torrentHandle.status();
                    debug("📊 Torrent: " + ts.getNum_peers() + " peers | " + 
                          (ts.getTotal_wanted()/1048576) + "MB | " + 
                          (int)(ts.getProgress()*100) + "%");
                }
                
                debug("🔍 Procurando arquivo...");
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 1048576) {
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; }
                        
                        String hex = "";
                        for (byte b : hdr) hex += String.format("%02X ", b);
                        boolean isMP4 = (hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p');
                        boolean isMKV = ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3);
                        
                        debug("   Tentativa " + (i+1) + ": " + f.getName() + 
                              " (" + (f.length()/1024) + "KB) Header: " + hex + 
                              (isMP4 ? " [MP4]" : isMKV ? " [MKV]" : " [??]" ));
                        
                        if (isMP4 || isMKV) {
                            videoFile = f;
                            long mb = f.length()/1048576;
                            debug("✅ Arquivo válido! " + mb + "MB");
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
        debug("══════ REPRODUZINDO ══════");
        debug("📁 " + videoFile.getAbsolutePath());
        debug("📏 " + videoFile.length() + " bytes");
        debug("🔗 http://127.0.0.1:8080/video");
        
        handler.post(() -> { 
            videoView.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE);
            loadingOverlay.setVisibility(View.VISIBLE);
            spinnerBar.setVisibility(View.VISIBLE);
        });
        
        videoView.setVideoURI(Uri.parse("http://127.0.0.1:8080/video"));
        videoView.start();
    }
    
    private void stop() {
        debug("══════ PARANDO ══════");
        debug("📊 HTTP reqs: " + httpReqCount);
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (torrentHandle != null && session != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
            torrentHandle = null;
        }
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); loadingOverlay.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
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
        if (session != null) session.stop();
        super.onDestroy();
    }
}