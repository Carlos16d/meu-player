package com.meuapp.player;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.server.StreamServer;
import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.swig.torrent_handle;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.*;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    
    private VLCVideoLayout videoLayout;
    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;
    private TorrentEngine torrentEngine;
    private StreamServer streamServer;
    
    private TextView statusText, progressText, titleText;
    private ProgressBar bufferBar, spinnerBar;
    private View loadingOverlay;
    private EditText magnetInput;
    private Button btnStream, btnStop, btnWatch;
    
    private String savePath;
    private StringBuilder logBuilder = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        requestPermissions();
        
        videoLayout = findViewById(R.id.video_surface);
        statusText = findViewById(R.id.status_text);
        progressText = findViewById(R.id.progress_text);
        titleText = findViewById(R.id.title_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        magnetInput = findViewById(R.id.magnet_input);
        btnStream = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        statusText.setMovementMethod(new ScrollingMovementMethod());
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        addLog("╔══════════════════════════════╗");
        addLog("║  TORRENT STREAM VLC v2       ║");
        addLog("║  Servidor HTTP Nativo Java   ║");
        addLog("╚══════════════════════════════╝");
        addLog("Android: " + Build.VERSION.SDK_INT + " | " + Build.MANUFACTURER + " " + Build.MODEL);
        addLog("SavePath: " + savePath);
        
        // Configura VLC
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=5000");
        options.add("--file-caching=3000");
        options.add("--http-reconnect");
        options.add("--clock-synchro=0");
        options.add("-vvv"); // Modo verbose
        
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        vlcPlayer.attachViews(videoLayout, null, false, false);
        
        vlcPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        addLog("[VLC] ▶ Playing");
                        runOnUiThread(() -> loadingOverlay.setVisibility(View.GONE));
                        break;
                    case MediaPlayer.Event.Buffering:
                        addLog("[VLC] 🔄 Buffering " + event.getBuffering() + "%");
                        runOnUiThread(() -> loadingOverlay.setVisibility(View.VISIBLE));
                        break;
                    case MediaPlayer.Event.Stopped:
                        addLog("[VLC] ⏹ Stopped");
                        break;
                    case MediaPlayer.Event.EndReached:
                        addLog("[VLC] 🏁 End reached");
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        addLog("[VLC] ❌ ERRO!");
                        break;
                    case MediaPlayer.Event.TimeChanged:
                        // Não logar para não floodar
                        break;
                    default:
                        addLog("[VLC] Event: " + event.type);
                        break;
                }
            }
        });
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { addLog("[ENG] ✅ Pronto"); }
            public void onError(String e) { addLog("[ENG] ❌ " + e); }
            
            public void onProgress(TorrentInfo info) {
                runOnUiThread(() -> {
                    bufferBar.setProgress(info.progress);
                    progressText.setText(info.progress + "% | " + (info.speed/1024) + "KB/s | " + info.peers + " peers | " + (info.downloaded/1048576) + "MB");
                });
            }
            
            public void onStreamReady(torrent_handle handle, String sp) {
                streamServer.setSavePath(sp);
                streamServer.setTorrent(handle);
                addLog("[ENG] ✅ STREAM READY");
                runOnUiThread(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    loadingOverlay.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                    titleText.setText("🎬 Pronto! Clique ASSISTIR");
                });
            }
            
            public void onStatus(String s) { addLog("[ENG] 📡 " + s); }
            public void onLog(String log) { addLog("[ENG] " + log); }
        });
        
        streamServer = new StreamServer();
        try {
            streamServer.start();
            addLog("[SRV] ✅ HTTP:8080 iniciado");
        } catch (Exception e) {
            addLog("[SRV] ❌ ERRO: " + e.getMessage());
            Log.e(TAG, "Erro servidor", e);
        }
        
        torrentEngine.start();
        
        btnStream.setOnClickListener(v -> {
            String m = magnetInput.getText().toString().trim();
            if (m.startsWith("magnet:")) {
                addLog("[UI] 🔗 Iniciando stream: " + m.substring(0, Math.min(50, m.length())) + "...");
                startStream(m);
            }
        });
        
        btnStop.setOnClickListener(v -> {
            addLog("[UI] ⏹ Parando...");
            stop();
        });
        
        btnWatch.setOnClickListener(v -> {
            addLog("[UI] ▶ Assistindo...");
            addLog("[UI] Server stats: " + streamServer.getStats());
            watch();
        });
    }
    
    private void addLog(String msg) {
        String time = sdf.format(new Date());
        String line = time + " " + msg + "\n";
        Log.d(TAG, msg);
        logBuilder.insert(0, line);
        if (logBuilder.length() > 15000) logBuilder.setLength(15000);
        runOnUiThread(() -> statusText.setText(logBuilder.toString()));
    }
    
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = {Manifest.permission.INTERNET, Manifest.permission.WRITE_EXTERNAL_STORAGE};
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, perms, 1);
                    break;
                }
            }
        }
    }
    
    private void startStream(String magnet) {
        bufferBar.setVisibility(View.VISIBLE);
        spinnerBar.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        videoLayout.setVisibility(View.GONE);
        titleText.setText("⬇️ Preparando stream...");
        torrentEngine.startDownload(magnet, savePath);
    }
    
    private void watch() {
        btnWatch.setVisibility(View.GONE);
        videoLayout.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        titleText.setText("▶️ Reproduzindo");
        
        String url = "http://127.0.0.1:8080/video";
        addLog("[VLC] URL: " + url);
        
        // Verifica se o servidor está respondendo
        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) 
                    new java.net.URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Range", "bytes=0-1023");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.connect();
                
                int code = conn.getResponseCode();
                String type = conn.getContentType();
                long len = conn.getContentLength();
                String range = conn.getHeaderField("Content-Range");
                
                addLog("[HTTP] Teste: " + code + " | Type: " + type + " | Len: " + len);
                addLog("[HTTP] Content-Range: " + range);
                
                if (code == 206) {
                    InputStream is = conn.getInputStream();
                    byte[] buf = new byte[1024];
                    int read = is.read(buf);
                    is.close();
                    addLog("[HTTP] Bytes lidos: " + read);
                    if (read > 4) {
                        addLog("[HTTP] Magic: " + String.format("%02X %02X %02X %02X", 
                            buf[0] & 0xFF, buf[1] & 0xFF, buf[2] & 0xFF, buf[3] & 0xFF));
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                addLog("[HTTP] ❌ Teste falhou: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }).start();
        
        Media media = new Media(libVLC, Uri.parse(url));
        media.setHWDecoderEnabled(true, true);
        media.addOption(":network-caching=5000");
        media.addOption(":file-caching=3000");
        media.addOption(":http-reconnect");
        media.addOption(":clock-synchro=0");
        
        vlcPlayer.setMedia(media);
        media.release();
        vlcPlayer.play();
        
        addLog("[VLC] Play chamado");
    }
    
    private void stop() {
        torrentEngine.stop();
        if (vlcPlayer != null) vlcPlayer.stop();
        videoLayout.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        titleText.setText("🎬 Torrent Stream");
        progressText.setText("Pronto");
        addLog("[UI] ⏹ Parado");
    }
    
    @Override
    protected void onDestroy() {
        addLog("[UI] 💀 onDestroy");
        if (torrentEngine != null) torrentEngine.destroy();
        if (streamServer != null) streamServer.stop();
        if (vlcPlayer != null) { vlcPlayer.release(); vlcPlayer = null; }
        if (libVLC != null) { libVLC.release(); libVLC = null; }
        super.onDestroy();
    }
}