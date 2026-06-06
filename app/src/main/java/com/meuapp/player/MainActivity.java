package com.meuapp.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.trackselection.*;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.util.*;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "TorrentStream";
    
    private PlayerView playerView;
    private SimpleExoPlayer player;
    private TextView statusText, progressText, titleText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private LinearLayout glassPanel;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch, btnTorrentFile;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private volatile boolean sessionReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        progressText = findViewById(R.id.progress_text);
        titleText = findViewById(R.id.title_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        glassPanel = findViewById(R.id.glass_panel);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        btnTorrentFile = findViewById(R.id.btn_torrent_file);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        // ExoPlayer
        player = new SimpleExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setKeepScreenOn(true);
        
        AlphaAnimation glow = new AlphaAnimation(0.6f, 1.0f);
        glow.setDuration(2000);
        glow.setRepeatMode(Animation.REVERSE);
        glow.setRepeatCount(Animation.INFINITE);
        titleText.startAnimation(glow);
        
        // Inicia sessão e servidor
        startSession();
        startServer();
        
        btnPlay.setOnClickListener(v -> startMagnet());
        btnTorrentFile.setOnClickListener(v -> openTorrentFile());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        log("✅ Pronto! Cole o magnet ou selecione .torrent");
    }
    
    private void startSession() {
        new Thread(() -> {
            try {
                log("🔄 Iniciando sessão...");
                session = new SessionManager();
                
                // Aguarda um pouco
                Thread.sleep(3000);
                
                if (session != null && session.swig() != null) {
                    sessionReady = true;
                    log("✅ Sessão pronta!");
                    Log.d(TAG, "Sessão iniciada com sucesso");
                } else {
                    log("❌ Erro na sessão");
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro sessão", e);
                log("❌ " + e.getMessage());
            }
        }).start();
    }
    
    private void log(String msg) {
        Log.d(TAG, msg);
        handler.post(() -> statusText.setText(msg));
    }
    
    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        f.delete();
    }
    
    private void startServer() {
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080);
                server.setReuseAddress(true);
                Log.d(TAG, "Servidor HTTP:8080");
                
                while (!Thread.interrupted()) {
                    try {
                        Socket c = server.accept();
                        new Thread(() -> handleHttp(c)).start();
                    } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {
                Log.e(TAG, "Erro servidor", e);
            }
        }).start();
    }
    
    private void handleHttp(Socket c) {
        try {
            OutputStream o = c.getOutputStream();
            BufferedReader i = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String r = i.readLine();
            
            if (r == null || !r.contains("/video")) {
                o.write("HTTP/1.1 404\r\n\r\n".getBytes());
                o.flush();
                c.close();
                return;
            }
            
            // Headers CORS
            String cors = "Access-Control-Allow-Origin: *\r\n";
            
            long start = 0, end = -1;
            String l;
            while ((l = i.readLine()) != null && !l.isEmpty()) {
                if (l.toLowerCase().startsWith("range:")) {
                    String x = l.substring(6).trim().replace("bytes=", "");
                    String[] p = x.split("-");
                    start = Long.parseLong(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) end = Long.parseLong(p[1]);
                }
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 4096) {
                o.write(("HTTP/1.1 503\r\n" + cors + "Retry-After: 1\r\n\r\n").getBytes());
                o.flush();
                c.close();
                return;
            }
            
            long len = vf.length();
            if (end == -1 || end >= len) end = len - 1;
            
            String mime = vf.getName().toLowerCase().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            int sz = Math.min((int)(end - start + 1), 2097152);
            
            byte[] b = new byte[sz];
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(start);
            int t = raf.read(b);
            raf.close();
            
            if (t < 4096) {
                o.write(("HTTP/1.1 503\r\n" + cors + "Retry-After: 1\r\n\r\n").getBytes());
                o.flush();
                c.close();
                return;
            }
            
            String resp = "HTTP/1.1 206\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "Content-Range: bytes " + start + "-" + (start+t-1) + "/" + len + "\r\n" +
                "Content-Length: " + t + "\r\n" +
                "Accept-Ranges: bytes\r\n" +
                cors + "\r\n";
            
            o.write(resp.getBytes());
            o.write(b, 0, t);
            o.flush();
            c.close();
            
        } catch (Exception e) {
            try { c.close(); } catch (IOException ex) {}
        }
    }
    
    private void startMagnet() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        startDownload(magnet);
    }
    
    private void openTorrentFile() {
        // Abre seletor de arquivos .torrent
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"application/x-bittorrent", "application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, 100);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                // Copia o arquivo .torrent
                InputStream is = getContentResolver().openInputStream(uri);
                File torrentFile = new File(savePath, "temp.torrent");
                FileOutputStream fos = new FileOutputStream(torrentFile);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                is.close();
                
                log("📁 Arquivo .torrent carregado!");
                startDownload(torrentFile.getAbsolutePath());
            } catch (Exception e) {
                log("❌ Erro ao ler arquivo");
            }
        }
    }
    
    private void startDownload(String source) {
        if (!sessionReady || session == null) {
            log("❌ Aguarde a sessão iniciar...");
            return;
        }
        
        // Limpa downloads anteriores
        File dir = new File(savePath);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.getName().equals("temp.torrent")) deleteRecursive(f);
                }
            }
        }
        
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        
        handler.post(() -> {
            glassPanel.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            spinnerBar.setVisibility(View.VISIBLE);
            loadingOverlay.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
            playerView.setVisibility(View.GONE);
            titleText.setText("⬇️ Baixando...");
            bufferBar.setProgress(0);
        });
        
        log("🔍 Conectando a peers...");
        
        new Thread(() -> {
            try {
                add_torrent_params p;
                
                if (source.startsWith("magnet:")) {
                    p = libtorrent.parse_magnet_uri(source, new error_code());
                } else {
                    // Carrega de arquivo .torrent
                    byte[] torrentData = new byte[(int) new File(source).length()];
                    new FileInputStream(source).read(torrentData);
                    byte_vector bv = new byte_vector();
                    for (byte b : torrentData) bv.add(b);
                    p = libtorrent.parse_torrent_buffer(bv, new error_code());
                }
                
                p.setSave_path(savePath);
                p.setDownload_limit(0);
                p.setUpload_limit(0);
                
                byte_vector pr = new byte_vector();
                pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrentHandle = h.get(0);
                    log("✅ Conectado! Baixando...");
                    
                    // Monitora download
                    while (downloading) {
                        File f = findVideoFile(new File(savePath));
                        
                        if (f != null && f.length() > 5242880) { // 5MB
                            videoFile = f;
                            long mb = f.length() / 1048576;
                            
                            handler.post(() -> {
                                int pct = Math.min((int)((f.length() * 100) / 276134947L), 100);
                                bufferBar.setProgress(pct);
                                progressText.setText(mb + " MB baixados");
                                
                                if (btnWatch.getVisibility() != View.VISIBLE) {
                                    spinnerBar.setVisibility(View.GONE);
                                    loadingOverlay.setVisibility(View.GONE);
                                    btnWatch.setVisibility(View.VISIBLE);
                                    btnWatch.setAlpha(0f);
                                    btnWatch.animate().alpha(1f).setDuration(500);
                                    titleText.setText("🎬 Pronto!");
                                    log("✅ " + mb + "MB - Clique ASSISTIR");
                                }
                            });
                        }
                        
                        // Atualiza progresso
                        if (torrentHandle != null && torrentHandle.is_valid()) {
                            torrent_status st = torrentHandle.status();
                            long progress = st.get_progress();
                            final int pct = (int)(progress * 100);
                            handler.post(() -> {
                                bufferBar.setProgress(pct);
                                progressText.setText(pct + "% - " + (videoFile != null ? videoFile.length()/1048576 : 0) + " MB");
                            });
                        }
                        
                        Thread.sleep(1000);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro download", e);
                log("❌ " + e.getMessage());
                downloading = false;
            }
        }).start();
    }
    
    private File findVideoFile(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideoFile(f);
                    if (found != null) return found;
                } else if (f.getName().toLowerCase().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) {
            log("❌ Arquivo não encontrado");
            return;
        }
        
        handler.post(() -> {
            glassPanel.setVisibility(View.GONE);
            btnWatch.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);
            titleText.setText("▶️ Reproduzindo");
        });
        
        Uri videoUri = Uri.parse("http://127.0.0.1:8080/video");
        
        DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(10000);
        
        ProgressiveMediaSource.Factory mediaSourceFactory =
            new ProgressiveMediaSource.Factory(dataSourceFactory);
        
        MediaSource mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(videoUri));
        
        player.setMediaSource(mediaSource);
        player.prepare();
        player.setPlayWhenReady(true);
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        
        playerView.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        glassPanel.setVisibility(View.GONE);
        
        titleText.setText("🎬 Torrent Streaming");
        progressText.setText("Pronto");
        log("⏹️ Parado");
        
        if (torrentHandle != null && session != null && session.swig() != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
            torrentHandle = null;
        }
    }
    
    @Override
    protected void onDestroy() {
        stop();
        if (player != null) {
            player.release();
            player = null;
        }
        if (session != null) {
            try { session.stop(); } catch (Exception e) {}
        }
        super.onDestroy();
    }
}