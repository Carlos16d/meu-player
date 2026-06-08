package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView statusText, logText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch, btnTorrent;
    private ScrollView logScroll;
    
    private String savePath;
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private int pieceLength;
    private int numPieces;
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        logText = findViewById(R.id.debug_text);
        logScroll = findViewById(R.id.log_scroll);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        btnTorrent = findViewById(R.id.btn_torrent);
        
        playerView.post(() -> {
            int width = (int)(getResources().getDisplayMetrics().widthPixels * 0.92);
            int height = (int)(width * 9.0 / 16.0);
            ViewGroup.LayoutParams p = playerView.getLayoutParams();
            p.width = width; p.height = height;
            playerView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setVisibility(View.GONE);
        
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loadingOverlay.setVisibility(View.GONE);
                    spinnerBar.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    loadingOverlay.setVisibility(View.VISIBLE);
                    spinnerBar.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                log("❌ " + error.getErrorCodeName() + ": " + error.getMessage());
            }
        });
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); log("✅ Sessão OK"); } 
            catch (Exception e) { log("❌ " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, 100);
        });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        log("📱 Pronto");
    }
    
    @Override protected void onActivityResult(int r, int res, android.content.Intent data) {
        super.onActivityResult(r, res, data);
        if (r == 100 && res == RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri != null) try {
                InputStream is = getContentResolver().openInputStream(uri);
                File tf = new File(savePath, "torrent_file.torrent");
                FileOutputStream fos = new FileOutputStream(tf);
                byte[] b = new byte[8192]; int l;
                while ((l = is.read(b)) > 0) fos.write(b, 0, l);
                fos.close(); is.close();
                startDownload(tf.getAbsolutePath());
            } catch (Exception e) { log("❌ " + e.getMessage()); }
        }
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        fullLog.append(line);
        handler.post(() -> {
            statusText.setText(msg);
            logText.setText(fullLog.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 5);
                server.setReuseAddress(true);
                log("🌐 HTTP:8080");
                while (!Thread.interrupted()) {
                    try { Socket client = server.accept(); handleHttpClient(client); } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) { log("❌ " + e.getMessage()); }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleHttpClient(Socket client) {
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
            
            // 🚀 PRIORIZAÇÃO (COM TRY-CATCH PARA NÃO CRASHAR)
            try {
                if (torrentHandle != null && torrentHandle.isValid()) {
                    TorrentInfo info = torrentHandle.torrentFile();
                    if (info != null) {
                        pieceLength = info.pieceLength();
                        numPieces = info.numPieces();
                        
                        int startPiece = (int)(rangeStart / pieceLength);
                        int endPiece = Math.min(startPiece + 20, numPieces - 1);
                        
                        torrentHandle.setSequentialRange(Math.max(0, startPiece - 5), Math.min(endPiece + 40, numPieces - 1));
                        
                        for (int i = startPiece; i <= endPiece; i++) {
                            try { torrentHandle.setPieceDeadline(i, 1000); } catch (Exception e) {}
                        }
                    }
                }
            } catch (Exception e) {
                // Ignora erros de priorização - não crasha
            }
            
            String mime = videoFile.getName().toLowerCase().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            
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
            
        } catch (Exception e) {
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        startDownload(magnet);
    }
    
    private void startDownload(String source) {
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        log("⏳ Conectando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p;
                if (source.startsWith("magnet:")) {
                    p = libtorrent.parse_magnet_uri(source, new error_code());
                } else {
                    p = add_torrent_params.load_torrent_file(source, new error_code());
                }
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(3 * 1024 * 1024);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrentHandle = new TorrentHandle(h.get(0));
                    log("✅ Torrent adicionado");
                }
                
                // Aguarda arquivo (MP4 E MKV)
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 65536) {
                        // Verifica magic bytes
                        byte[] hdr = new byte[8];
                        try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; }
                        
                        boolean isMP4 = (hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p');
                        boolean isMKV = ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3);
                        
                        if (isMP4 || isMKV) {
                            videoFile = f;
                            long mb = f.length()/1048576;
                            log("📁 " + f.getName() + " (" + mb + "MB) " + (isMKV ? "[MKV]" : "[MP4]"));
                            handler.post(() -> {
                                btnWatch.setText("🎬 ASSISTIR (" + mb + "MB)");
                                btnWatch.setVisibility(View.VISIBLE);
                                bufferBar.setVisibility(View.GONE);
                            });
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e2) { log("❌ " + e2.getMessage()); downloading = false; }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { log("❌ Arquivo não encontrado"); return; }
        log("▶️ " + videoFile.getName());
        handler.post(() -> { playerView.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); });
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.clearMediaItems(); }
        playerView.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); loadingOverlay.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        if (torrentHandle != null && session != null) {
            try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {}
            torrentHandle = null;
        }
        log("⏹️ Parado");
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