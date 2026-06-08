package com.meuapp.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView statusText, debugText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnTorrent, btnStop, btnWatch;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private int pieceLength;
    private int numPieces;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnTorrent = findViewById(R.id.btn_torrent);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setKeepScreenOn(true);
        playerView.setVisibility(View.GONE);
        
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                String s = state == Player.STATE_BUFFERING ? "BUFFERING" : 
                          state == Player.STATE_READY ? "READY" : "IDLE";
                debug("[EXO] " + s);
                handler.post(() -> spinnerBar.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE));
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                debug("[EXO] ❌ " + error.getErrorCodeName() + ": " + error.getMessage());
            }
        });
        
        debug("=== TORRENT STREAM ===");
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); debug("✅ OK"); } 
            catch (Exception e) { debug("❌ " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> startMagnet());
        btnTorrent.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, 100);
        });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("📱 Pronto");
    }
    
    @Override protected void onActivityResult(int r, int res, Intent data) {
        super.onActivityResult(r, res, data);
        if (r == 100 && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) try {
                InputStream is = getContentResolver().openInputStream(uri);
                File tf = new File(savePath, "torrent_file.torrent");
                FileOutputStream fos = new FileOutputStream(tf);
                byte[] b = new byte[8192]; int l;
                while ((l = is.read(b)) > 0) fos.write(b, 0, l);
                fos.close(); is.close();
                startDownload(tf.getAbsolutePath());
            } catch (Exception e) { debug("❌ " + e.getMessage()); }
        }
    }
    
    private void debug(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        debugLog.append(line);
        handler.post(() -> { statusText.setText(msg); debugText.setText(debugLog.toString()); });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 5);
                server.setReuseAddress(true);
                debug("[SRV] ✅ HTTP:8080");
                while (!Thread.interrupted()) {
                    try { Socket client = server.accept(); handleHttp(client); } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) { debug("[SRV] ❌ " + e.getMessage()); }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleHttp(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();
            
            String line = in.readLine();
            if (line == null || !line.contains("/video")) {
                out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return;
            }
            
            long rangeStart = 0, rangeEnd = -1;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    String v = line.substring(6).trim().replace("bytes=", "");
                    String[] p = v.split("-");
                    rangeStart = Long.parseLong(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) rangeEnd = Long.parseLong(p[1]);
                }
            }
            
            if (videoFile == null || !videoFile.exists()) {
                out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return;
            }
            
            long totalLength = videoFile.length();
            if (rangeEnd == -1) rangeEnd = totalLength - 1;
            long contentLength = rangeEnd - rangeStart + 1;
            
            // 🔥 SEEK: Força download sequencial na região
            if (torrentHandle != null && pieceLength > 0) {
                int startPiece = (int)(rangeStart / pieceLength);
                int rs = Math.max(0, startPiece - 5);
                int re = Math.min(startPiece + 60, numPieces);
                
                torrentHandle.set_sequential_range(rs, re);
                for (int i = 0; i < rs - 10; i++)
                    try { torrentHandle.piece_priority_ex(i, (byte)0); } catch (Exception e) {}
                for (int i = rs; i <= re; i++) {
                    try { torrentHandle.piece_priority_ex(i, (byte)7); torrentHandle.set_piece_deadline(i, 500); } catch (Exception e) {}
                }
                
                if (rangeStart > 10485760) // Log só para seeks > 10MB
                    debug("🔥 SEEK: " + (rangeStart/1048576) + "MB | peças " + rs + "-" + re);
            }
            
            String mime = "video/mp4";
            if (videoFile.getName().toLowerCase().endsWith(".mkv")) mime = "video/x-matroska";
            
            String headers = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + totalLength + "\r\n" +
                "Content-Length: " + contentLength + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
            
            out.write(headers.getBytes());
            
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(rangeStart);
            byte[] buffer = new byte[65536];
            long bytesSent = 0;
            
            while (bytesSent < contentLength && downloading) {
                int toRead = (int)Math.min(buffer.length, contentLength - bytesSent);
                int read = raf.read(buffer, 0, toRead);
                if (read == -1) break;
                out.write(buffer, 0, read);
                out.flush();
                bytesSent += read;
            }
            raf.close();
            out.flush();
            client.close();
            
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void startMagnet() {
        String m = magnetInput.getText().toString().trim();
        if (m.startsWith("magnet:") && !downloading) startDownload(m);
    }
    
    private void startDownload(String source) {
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        
        handler.post(() -> {
            bufferBar.setVisibility(View.VISIBLE); spinnerBar.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE);
            playerView.setVisibility(View.GONE);
        });
        
        debug("⏳ Conectando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p;
                if (source.startsWith("magnet:")) {
                    p = libtorrent.parse_magnet_uri(source, new error_code());
                } else {
                    p = add_torrent_params.load_torrent_file(source, new error_code());
                }
                p.setSave_path(savePath);
                p.setDownload_limit(3 * 1024 * 1024);
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrentHandle = h.get(0);
                
                int w = 0;
                torrent_status st = torrentHandle.status();
                while (!st.getHas_metadata() && w < 60 && downloading) {
                    Thread.sleep(1000); w++; st = torrentHandle.status();
                }
                
                if (st.getHas_metadata()) {
                    torrent_info ti = torrentHandle.torrent_file_ptr();
                    if (ti != null) {
                        pieceLength = ti.piece_length();
                        numPieces = ti.num_pieces();
                        
                        for (int i = 0; i < numPieces; i++)
                            torrentHandle.piece_priority_ex(i, (byte)(i < 200 ? 7 : 1));
                        for (int i = 0; i < Math.min(100, numPieces); i++)
                            torrentHandle.set_piece_deadline(i, 2000);
                    }
                }
                
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 65536) {
                        videoFile = f;
                        debug("📁 " + f.getName() + " (" + (f.length()/1048576) + "MB)");
                        handler.post(() -> {
                            bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
                            btnWatch.setVisibility(View.VISIBLE);
                        });
                        break;
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) { debug("❌ " + e.getMessage()); downloading = false; }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { debug("❌ Arquivo não encontrado"); return; }
        debug("▶️ " + videoFile.getName());
        handler.post(() -> { playerView.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); });
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
    }
    
    private void stop() {
        downloading = false;
        if (player != null) { player.stop(); player.clearMediaItems(); }
        playerView.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        if (torrentHandle != null && session != null) {
            try { session.swig().remove_torrent(torrentHandle); } catch (Exception e) {}
            torrentHandle = null;
        }
        debug("⏹ Parado");
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
        if (serverThread != null) serverThread.interrupt();
        if (player != null) player.release();
        if (session != null) session.stop();
        super.onDestroy();
    }
}