package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
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
    private LinearLayout statsRow;
    private TextView logText, statProgress, statSpeed, statPeers;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop;
    private ScrollView logScroll;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private volatile boolean downloading = false;
    private volatile File videoFile = null;
    private Thread serverThread;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private int reqCount = 0;
    private int errCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        logScroll = findViewById(R.id.log_scroll);
        logText = findViewById(R.id.log_text);
        statsRow = findViewById(R.id.stats_row);
        statProgress = findViewById(R.id.stat_progress);
        statSpeed = findViewById(R.id.stat_speed);
        statPeers = findViewById(R.id.stat_peers);
        bufferBar = findViewById(R.id.buffer_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                String s = state == Player.STATE_IDLE ? "IDLE" : state == Player.STATE_BUFFERING ? "BUFFERING" : 
                    state == Player.STATE_READY ? "READY ✅" : state == Player.STATE_ENDED ? "ENDED" : "?";
                log("🎬 Player: " + s);
                if (state == Player.STATE_READY) {
                    log("   Duração: " + player.getDuration()/1000 + "s");
                    log("   Posição: " + player.getCurrentPosition()/1000 + "s");
                    log("   Buffered: " + player.getBufferedPercentage() + "%");
                    handler.post(() -> { playerView.setVisibility(View.VISIBLE); logScroll.setVisibility(View.GONE); });
                }
                if (state == Player.STATE_BUFFERING) {
                    log("   Buffered: " + player.getBufferedPercentage() + "%");
                }
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                errCount++;
                log("❌ PLAYER ERROR #" + errCount);
                log("   Code: " + error.getErrorCodeName());
                log("   Msg: " + error.getMessage());
                log("   Localized: " + error.getLocalizedMessage());
                if (error.getCause() != null) {
                    log("   Cause: " + error.getCause().toString());
                }
                log("   Stack: " + android.util.Log.getStackTraceString(error).substring(0, Math.min(200, android.util.Log.getStackTraceString(error).length())));
            }
        });
        
        log("╔══════════════════╗");
        log("║  APP INICIADO    ║");
        log("╚══════════════════╝");
        log("SDK: " + android.os.Build.VERSION.SDK_INT);
        log("Dispositivo: " + android.os.Build.MODEL);
        log("Pasta: " + savePath);
        
        new Thread(() -> {
            try { 
                session = new SessionManager(); 
                session.start();
                log("✅ Sessão torrent OK");
                log("   DHT: " + (session.swig().is_dht_running() ? "ON" : "OFF"));
                log("   Porta: " + session.swig().listen_port());
            } catch (Exception e) { 
                log("❌ Sessão: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        fullLog.append(line);
        handler.post(() -> { logText.setText(fullLog.toString()); logScroll.fullScroll(View.FOCUS_DOWN); });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                log("🌐 Servidor HTTP :8080 iniciado");
                while (!Thread.interrupted()) {
                    try { 
                        Socket client = server.accept();
                        reqCount++;
                        new Thread(() -> handleClient(client, reqCount)).start();
                    } catch (IOException e) {
                        if (!server.isClosed()) log("❌ Accept: " + e.getMessage());
                    }
                }
                server.close();
            } catch (IOException e) { 
                log("❌ Servidor: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleClient(Socket client, int num) {
        long startTime = System.currentTimeMillis();
        try {
            client.setSoTimeout(30000);
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            // Lê request
            String request = in.readLine();
            if (request == null) { client.close(); return; }
            
            log("📥 #" + num + ": " + request.split(" ")[0] + " " + request.split(" ")[1]);
            
            // Lê headers
            String range = null;
            String userAgent = "?";
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    range = line.substring(6).trim();
                    log("   Range: " + range);
                }
                if (line.toLowerCase().startsWith("user-agent:")) {
                    userAgent = line.substring(11).trim();
                }
            }
            
            if (!request.contains("/video")) {
                send(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                client.close();
                log("   ↪ 404");
                return;
            }
            
            // Verifica arquivo
            File vf = videoFile;
            if (vf == null) {
                send(out, "HTTP/1.1 503 No File\r\nRetry-After: 2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                client.close();
                log("   ↪ 503 (videoFile=null)");
                return;
            }
            
            if (!vf.exists()) {
                send(out, "HTTP/1.1 503 Not Found\r\nRetry-After: 2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                client.close();
                log("   ↪ 503 (!exists)");
                return;
            }
            
            long fileLen = vf.length();
            if (fileLen < 131072) {
                send(out, "HTTP/1.1 503 Too Small (" + fileLen + ")\r\nRetry-After: 2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                client.close();
                log("   ↪ 503 (size=" + fileLen + ")");
                return;
            }
            
            // Verifica header do arquivo
            byte[] fhdr = new byte[16];
            try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) { raf.read(fhdr); }
            
            boolean isMP4 = (fhdr[4] == 'f' && fhdr[5] == 't' && fhdr[6] == 'y' && fhdr[7] == 'p');
            boolean isMKV = ((fhdr[0] & 0xFF) == 0x1A && fhdr[1] == 0x45 && fhdr[2] == (byte)0xDF && fhdr[3] == (byte)0xA3);
            
            if (!isMP4 && !isMKV) {
                String hex = "";
                for (byte b : fhdr) hex += String.format("%02X ", b);
                send(out, "HTTP/1.1 503 Bad Header\r\nRetry-After: 2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                client.close();
                log("   ↪ 503 Bad header: " + hex);
                return;
            }
            
            String mime = isMKV ? "video/x-matroska" : "video/mp4";
            
            long rangeStart = 0, rangeEnd = fileLen - 1;
            boolean hasRange = (range != null);
            
            if (hasRange) {
                String r = range.replace("bytes=", "");
                String[] parts = r.split("-");
                try {
                    rangeStart = Long.parseLong(parts[0]);
                    rangeEnd = (parts.length > 1 && !parts[1].isEmpty()) ? Long.parseLong(parts[1]) : fileLen - 1;
                } catch (NumberFormatException e) {
                    send(out, "HTTP/1.1 400 Bad Range\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                    client.close();
                    log("   ↪ 400 Bad range format");
                    return;
                }
                
                if (rangeStart >= fileLen) {
                    send(out, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                    client.close();
                    log("   ↪ 416 (start=" + rangeStart + " >= len=" + fileLen + ")");
                    return;
                }
                if (rangeEnd >= fileLen) rangeEnd = fileLen - 1;
                if (rangeEnd - rangeStart > 262144) rangeEnd = rangeStart + 262144;
            } else {
                rangeEnd = Math.min(262143, fileLen - 1);
            }
            
            int length = (int)(rangeEnd - rangeStart + 1);
            byte[] buffer = new byte[length];
            int totalRead = 0;
            
            try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
                raf.seek(rangeStart);
                while (totalRead < length) {
                    int r = raf.read(buffer, totalRead, length - totalRead);
                    if (r == -1) break;
                    totalRead += r;
                }
            }
            
            if (totalRead == 0) {
                send(out, "HTTP/1.1 503 Empty Read\r\nRetry-After: 1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                client.close();
                log("   ↪ 503 (read 0 bytes)");
                return;
            }
            
            // Resposta
            StringBuilder response = new StringBuilder();
            if (hasRange) {
                response.append("HTTP/1.1 206 Partial Content\r\n");
                response.append("Content-Range: bytes ").append(rangeStart).append("-")
                    .append(rangeStart + totalRead - 1).append("/").append(fileLen).append("\r\n");
            } else {
                response.append("HTTP/1.1 200 OK\r\n");
            }
            response.append("Content-Type: ").append(mime).append("\r\n");
            response.append("Content-Length: ").append(totalRead).append("\r\n");
            response.append("Accept-Ranges: bytes\r\n");
            response.append("Connection: close\r\n");
            response.append("Access-Control-Allow-Origin: *\r\n");
            response.append("\r\n");
            
            out.write(response.toString().getBytes());
            out.write(buffer, 0, totalRead);
            out.flush();
            
            long elapsed = System.currentTimeMillis() - startTime;
            log("   ✅ " + (hasRange ? "206" : "200") + " | " + totalRead + " bytes | " + rangeStart + "-" + (rangeStart+totalRead-1) + " | " + elapsed + "ms");
            
            client.close();
            
        } catch (SocketTimeoutException e) {
            log("   ⏰ Timeout #" + num);
            try { client.close(); } catch (IOException ex) {}
        } catch (Exception e) {
            log("   ❌ #" + num + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private void send(OutputStream out, String s) throws IOException { 
        out.write(s.getBytes()); 
        out.flush(); 
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        downloading = true;
        
        handler.post(() -> { 
            playerView.setVisibility(View.GONE); logScroll.setVisibility(View.VISIBLE); 
            statsRow.setVisibility(View.VISIBLE); btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); 
        });
        
        log("═══ INICIANDO ═══");
        log("Magnet: " + magnet.substring(0, Math.min(60, magnet.length())) + "...");
        
        player.setMediaItem(MediaItem.fromUri("http://127.0.0.1:8080/video"));
        player.prepare();
        player.play();
        log("▶️ Play chamado");
        
        if (torrent == null || !torrent.is_valid()) {
            new Thread(() -> {
                try {
                    add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                    p.setSave_path(savePath); p.setFlags(torrent_flags_t.from_int(9)); p.setDownload_limit(0);
                    byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                    session.swig().async_add_torrent(p);
                    log("📤 Magnet enviado");
                    
                    Thread.sleep(3000);
                    torrent_handle_vector h = session.swig().get_torrents();
                    if (h.size() > 0) {
                        torrent = h.get(0);
                        torrent_status ts = torrent.status();
                        log("📊 Peers: " + ts.getNum_peers() + " | Size: " + (ts.getTotal_wanted()/1048576) + "MB");
                    }
                    
                    log("🔍 Procurando arquivo...");
                    int tentativas = 0;
                    while (downloading && videoFile == null && tentativas < 30) {
                        tentativas++;
                        File f = find(new File(savePath));
                        if (f != null && f.exists() && f.length() > 131072) {
                            videoFile = f;
                            log("📁 Encontrado #" + tentativas + ": " + f.getName() + " (" + (f.length()/1048576) + "MB)");
                            break;
                        }
                        Thread.sleep(1000);
                    }
                } catch (Exception e) { 
                    log("❌ Thread: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            }).start();
        } else {
            log("📁 Torrent já ativo, reusando...");
        }
        
        handler.post(new Runnable() { @Override public void run() {
            if (downloading && torrent != null && torrent.is_valid()) {
                torrent_status ts = torrent.status();
                statProgress.setText((int)(ts.getProgress()*100)+"%");
                long speed = ts.getDownload_rate();
                statSpeed.setText(speed>1048576?String.format("%.1f MB/s",speed/1048576.0):speed>1024?String.format("%.1f KB/s",speed/1024.0):speed+" B/s");
                statPeers.setText("👥"+ts.getNum_peers());
                bufferBar.setProgress((int)(ts.getProgress()*100));
            }
            if (downloading) handler.postDelayed(this, 500);
        }});
    }
    
    private void stop() {
        log("═══ PARANDO ═══");
        log("Total requisições: " + reqCount + " | Erros player: " + errCount);
        downloading = false; player.stop(); handler.removeCallbacksAndMessages(null);
        playerView.setVisibility(View.GONE); logScroll.setVisibility(View.VISIBLE); 
        statsRow.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); bufferBar.setVisibility(View.GONE);
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
        super.onDestroy(); downloading = false;
        if (serverThread != null) serverThread.interrupt();
        if (player != null) player.release();
        if (session != null) session.stop();
    }
}
