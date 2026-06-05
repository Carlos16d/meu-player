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
        options.add("-vvv");
        
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        vlcPlayer.getVLCVout().setVideoView(videoSurface);
        vlcPlayer.getVLCVout().attachViews();
        
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Opening:
                    debug("🎬 VLC: Opening - iniciando reprodução");
                    break;
                case MediaPlayer.Event.Playing:
                    debug("▶️ VLC: PLAYING - vídeo rodando!");
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
                    float buf = event.getBuffering();
                    debug("⏳ VLC: Buffering " + buf + "%");
                    if (buf < 100) {
                        handler.post(() -> {
                            loadingOverlay.setVisibility(View.VISIBLE);
                            spinnerBar.setVisibility(View.VISIBLE);
                        });
                    }
                    break;
                case MediaPlayer.Event.Stopped:
                    debug("⏹️ VLC: Stopped - motivo: " + (isPlaying ? "fim do stream" : "parado pelo usuário"));
                    isPlaying = false;
                    break;
                case MediaPlayer.Event.EndReached:
                    debug("🏁 VLC: EndReached - vídeo terminou");
                    isPlaying = false;
                    break;
                case MediaPlayer.Event.EncounteredError:
                    debug("❌ VLC: EncounteredError");
                    break;
                case MediaPlayer.Event.TimeChanged:
                    break;
                case MediaPlayer.Event.LengthChanged:
                    debug("📏 VLC: Duração = " + event.getLengthChanged()/1000 + "s");
                    break;
                default:
                    debug("🔔 VLC: Evento " + event.type);
            }
        });
        
        btnPause.setOnClickListener(v -> {
            if (isPlaying) {
                vlcPlayer.pause();
                debug("⏸️ Usuário pausou");
            } else {
                vlcPlayer.play();
                debug("▶️ Usuário deu play");
            }
        });
        
        btnSeekBack.setOnClickListener(v -> {
            long pos = vlcPlayer.getTime();
            long len = vlcPlayer.getLength();
            vlcPlayer.setTime(Math.max(0, pos - 10000));
            debug("⏪ Seek -10s | Agora: " + (vlcPlayer.getTime()/1000) + "s/" + (len/1000) + "s");
        });
        
        btnSeekFwd.setOnClickListener(v -> {
            long pos = vlcPlayer.getTime();
            long len = vlcPlayer.getLength();
            vlcPlayer.setTime(Math.min(len, pos + 10000));
            debug("⏩ Seek +10s | Agora: " + (vlcPlayer.getTime()/1000) + "s/" + (len/1000) + "s");
        });
        
        new Thread(() -> {
            try { 
                session = new SessionManager(); 
                session.start();
                debug("✅ Sessão torrent iniciada");
                debug("   DHT: " + (session.swig().is_dht_running() ? "ATIVO" : "OFF"));
                debug("   Porta: " + session.swig().listen_port());
            } catch (Exception e) { 
                debug("❌ Sessão: " + e.getClass().getSimpleName() + " - " + e.getMessage()); 
            }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("╔══════════════════════════╗");
        debug("║   APP INICIADO           ║");
        debug("╚══════════════════════════╝");
        debug("📱 SDK: " + android.os.Build.VERSION.SDK_INT);
        debug("📱 Modelo: " + android.os.Build.MODEL);
        debug("📁 Pasta: " + savePath);
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
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                debug("🌐 Servidor HTTP iniciado na porta 8080");
                debug("   URL: http://127.0.0.1:8080/video");
                
                while (!Thread.interrupted()) {
                    try { 
                        Socket c = server.accept();
                        httpReqCount++;
                        int num = httpReqCount;
                        debug("📥 Conexão #" + num + " recebida de " + c.getInetAddress());
                        new Thread(() -> handleHttp(c, num)).start();
                    } catch (IOException e) {
                        if (!server.isClosed()) debug("⚠️ Accept error: " + e.getMessage());
                    }
                }
                server.close();
                debug("🌐 Servidor HTTP parado");
            } catch (IOException e) {
                debug("❌ Servidor HTTP: " + e.getMessage());
            }
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
                o.write("HTTP/1.1 404\r\n\r\n".getBytes()); o.flush(); c.close(); 
                debug("📥 #" + reqNum + " → 404");
                return; 
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
            
            debug("📥 #" + reqNum + " | Range: " + s + "-" + (e == -1 ? "?" : e) + 
                  " | User-Agent: VLC");
            
            // Aguarda arquivo ter pelo menos 50MB
            File vf = videoFile;
            if (vf == null || !vf.exists()) {
                o.write("HTTP/1.1 503\r\nRetry-After: 2\r\n\r\n".getBytes()); o.flush(); c.close();
                debug("   #" + reqNum + " ↪ 503 | Arquivo não existe");
                return;
            }
            
            long len = vf.length();
            if (len < 50971520) {
                o.write("HTTP/1.1 503\r\nRetry-After: 2\r\n\r\n".getBytes()); o.flush(); c.close();
                debug("   #" + reqNum + " ↪ 503 | Aguardando 50MB | Tem: " + (len/1048576) + "MB");
                return;
            }
            
            if (e == -1 || e >= len) e = len - 1;
            if (s >= len) { 
                o.write("HTTP/1.1 416\r\n\r\n".getBytes()); o.flush(); c.close(); 
                debug("   #" + reqNum + " ↪ 416 | Range inválido");
                return; 
            }
            
            String m = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            long currentPos = s;
            
            // Prioriza região inicial
            if (torrentHandle != null && torrentHandle.is_valid()) {
                int pieceLen = 262144;
                int startP = (int)(s / pieceLen);
                int endP = Math.min(startP + 50, 9999);
                for (int j = startP; j <= endP; j++) {
                    try { torrentHandle.set_piece_deadline(j, 30); } catch (Exception ex) {}
                }
                debug("   🎯 Priorizando peças " + startP + "-" + endP + " | Pos " + (s/1048576) + "MB");
            }
            
            debug("   🚀 Iniciando streaming contínuo...");
            
            // Loop INFINITO de streaming
            while (downloading && !c.isClosed()) {
                if (currentPos >= len) {
                    // Chegou ao fim, mas continua mandando keep-alive
                    Thread.sleep(500);
                    continue;
                }
                
                long chunkSize = Math.min(262144, len - currentPos);
                byte[] buf = new byte[(int)chunkSize];
                
                RandomAccessFile raf = new RandomAccessFile(vf, "r");
                raf.seek(currentPos);
                int total = raf.read(buf);
                raf.close();
                
                if (total <= 0) {
                    Thread.sleep(200);
                    continue;
                }
                
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
                        int progress = (int)((currentPos * 100) / len);
                        long elapsed = System.currentTimeMillis() - startTime;
                        debug("   📤 #" + reqNum + " | Chunk " + chunkCount + " | " + 
                              (total/1024) + "KB | Total: " + (totalSent/1048576) + "MB/" + 
                              (len/1048576) + "MB | " + progress + "% | " + elapsed + "ms");
                    }
                    
                    // Prioriza próximas peças a cada 10 chunks
                    if (torrentHandle != null && torrentHandle.is_valid() && chunkCount % 10 == 0) {
                        int nextP = (int)(currentPos / 262144);
                        for (int j = nextP; j < nextP + 20; j++) {
                            try { torrentHandle.set_piece_deadline(j, 50); } catch (Exception ex) {}
                        }
                    }
                    
                } catch (SocketException ex) {
                    debug("   #" + reqNum + " ⚠️ Conexão fechada pelo cliente: " + ex.getMessage());
                    break;
                }
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            c.close();
            
            debug("   ✅ #" + reqNum + " | Stream finalizado | " + chunkCount + " chunks | " +
                  (totalSent/1048576) + "MB em " + elapsed + "ms | " +
                  (elapsed > 0 && totalSent > 0 ? String.format("%.1f MB/s", (totalSent*1000.0/elapsed/1048576)) : "N/A"));
            
        } catch (Exception ex) { 
            try { c.close(); } catch (IOException ex2) {}
            long elapsed = System.currentTimeMillis() - startTime;
            debug("   ❌ #" + reqNum + " | " + ex.getClass().getSimpleName() + 
                  ": " + ex.getMessage() + " | " + chunkCount + " chunks | " + 
                  (totalSent/1048576) + "MB | " + elapsed + "ms");
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
            mediaControls.setVisibility(View.GONE);
        });
        
        debug("╔══════════════════════════╗");
        debug("║   INICIANDO DOWNLOAD     ║");
        debug("╚══════════════════════════╝");
        debug("⏳ Baixando (2 MB/s)...");
        debug("📡 Magnet: " + magnet.substring(0, Math.min(60, magnet.length())) + "...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(0));
                p.setDownload_limit(2 * 1024 * 1024);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                debug("📤 Enviando magnet para a sessão...");
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrentHandle = h.get(0);
                    torrent_status ts = torrentHandle.status();
                    debug("📊 Torrent conectado!");
                    debug("   Peers: " + ts.getNum_peers());
                    debug("   Tamanho: " + (ts.getTotal_wanted()/1048576) + "MB");
                    debug("   Progresso: " + (int)(ts.getProgress()*100) + "%");
                    debug("   Download: " + (ts.getDownload_rate()/1024) + "KB/s");
                } else {
                    debug("⚠️ Nenhum torrent encontrado na sessão!");
                }
                
                debug("🔍 Procurando arquivo de vídeo (mínimo 50MB)...");
                for (int i = 0; i < 300 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null) {
                        long fileLen = f.length();
                        if (fileLen > 50971520) {
                            byte[] hdr = new byte[8];
                            try { new RandomAccessFile(f, "r").read(hdr); } catch (Exception e2) { continue; }
                            
                            String hex = "";
                            for (byte b : hdr) hex += String.format("%02X ", b);
                            boolean isMP4 = (hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p');
                            boolean isMKV = ((hdr[0]&0xFF)==0x1A && hdr[1]==0x45 && hdr[2]==(byte)0xDF && hdr[3]==(byte)0xA3);
                            
                            debug("   Tentativa " + (i+1) + ": " + f.getName() + 
                                  " (" + (fileLen/1048576) + "MB) Header: " + hex + 
                                  (isMP4 ? " [MP4]" : isMKV ? " [MKV]" : " [??]"));
                            
                            if (isMP4 || isMKV) {
                                videoFile = f;
                                long mb = fileLen/1048576;
                                debug("✅ Arquivo válido encontrado!");
                                debug("   Nome: " + f.getName());
                                debug("   Tamanho: " + mb + "MB");
                                debug("   Caminho: " + f.getAbsolutePath());
                                handler.post(() -> {
                                    btnWatch.setText("🎬 ASSISTIR (" + mb + "MB)");
                                    btnWatch.setVisibility(View.VISIBLE);
                                });
                                break;
                            }
                        } else if (i % 10 == 0) {
                            debug("   ⏳ Aguardando 50MB... (tem " + (fileLen/1048576) + "MB)");
                        }
                    }
                    Thread.sleep(1000);
                }
                
                if (videoFile == null) {
                    debug("⚠️ Timeout: arquivo não encontrado após 300s");
                }
                
            } catch (Exception e2) { 
                debug("❌ ERRO no download: " + e2.getClass().getSimpleName() + " - " + e2.getMessage());
            }
        }).start();
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { 
            debug("❌ watch(): arquivo não encontrado"); 
            return; 
        }
        
        debug("╔══════════════════════════╗");
        debug("║   INICIANDO PLAYER       ║");
        debug("╚══════════════════════════╝");
        debug("▶️ Conectando VLC ao servidor HTTP...");
        debug("   URL: http://127.0.0.1:8080/video");
        debug("   Arquivo: " + videoFile.getName());
        debug("   Tamanho: " + (videoFile.length()/1048576) + "MB");
        
        handler.post(() -> { 
            videoSurface.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE);
            loadingOverlay.setVisibility(View.VISIBLE);
            spinnerBar.setVisibility(View.VISIBLE);
        });
        
        Media media = new Media(libVLC, Uri.parse("http://127.0.0.1:8080/video"));
        media.setHWDecoderEnabled(true, false);
        media.addOption(":network-caching=3000");
        media.addOption(":file-caching=3000");
        vlcPlayer.setMedia(media);
        media.release();
        vlcPlayer.play();
        
        debug("▶️ vlcPlayer.play() chamado");
    }
    
    private void stop() {
        debug("╔══════════════════════════╗");
        debug("║   PARANDO                ║");
        debug("╚══════════════════════════╝");
        debug("📊 Total requisições HTTP: " + httpReqCount);
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        vlcPlayer.stop();
        if (torrentHandle != null && session != null) {
            try { 
                session.swig().remove_torrent(torrentHandle);
                debug("🗑️ Torrent removido da sessão");
            } catch (Exception e) {
                debug("⚠️ Erro ao remover torrent: " + e.getMessage());
            }
            torrentHandle = null;
        }
        videoSurface.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); loadingOverlay.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        mediaControls.setVisibility(View.GONE);
        debug("⏹️ App parado");
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
        debug("💀 App destruído");
        super.onDestroy();
    }
}