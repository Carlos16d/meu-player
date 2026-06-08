package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private TextView statusText, debugText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    
    private String savePath;
    private SessionManager session;
    private TorrentHandle torrentHandle;
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
        
        webView = findViewById(R.id.webview);
        statusText = findViewById(R.id.status_text);
        debugText = findViewById(R.id.debug_text);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        webView.post(() -> {
            int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.94);
            int h = (int)(w * 9.0 / 16.0);
            ViewGroup.LayoutParams p = webView.getLayoutParams();
            p.width = w; p.height = h;
            webView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.setVisibility(View.GONE);
        
        debug("=== TORRENT STREAM PRO ===");
        
        new Thread(() -> {
            try { session = new SessionManager(); session.start(); debug("✅ OK"); } 
            catch (Exception e) { debug("❌ " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("📱 Pronto");
    }
    
    private void debug(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        debugLog.append(line);
        handler.post(() -> {
            statusText.setText(msg);
            debugText.setText(debugLog.toString());
        });
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 5);
                server.setReuseAddress(true);
                while (!Thread.interrupted()) {
                    try { Socket client = server.accept(); handleHttp(client); } catch (IOException e) {}
                }
                server.close();
            } catch (IOException e) {}
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
            
            try {
                if (torrentHandle != null && torrentHandle.isValid()) {
                    TorrentInfo info = torrentHandle.torrentFile();
                    if (info != null) {
                        pieceLength = info.pieceLength();
                        numPieces = info.numPieces();
                        int startPiece = (int)(rangeStart / pieceLength);
                        int endPiece = Math.min(startPiece + 20, numPieces - 1);
                        for (int i = startPiece; i <= endPiece; i++) {
                            try { torrentHandle.setPieceDeadline(i, 1000); } catch (Exception e) {}
                        }
                    }
                }
            } catch (Exception e) {}
            
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
        
        downloading = true;
        videoFile = null;
        torrentHandle = null;
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        debug("⏳ Baixando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(3 * 1024 * 1024);
                
                byte_vector pr = new byte_vector(); pr.add((byte)7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0));
                
                for (int i = 0; i < 120 && downloading; i++) {
                    File f = find(new File(savePath));
                    if (f != null && f.length() > 65536) {
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
                                bufferBar.setVisibility(View.GONE);
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
        debug("▶️ " + videoFile.getName());
        
        handler.post(() -> { 
            webView.setVisibility(View.VISIBLE); 
            btnWatch.setVisibility(View.GONE); 
        });
        
        // PLAYER PROFISSIONAL ESTILO NETFLIX
        String html = "<!DOCTYPE html><html><head>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
            + "<style>"
            + "*{margin:0;padding:0;box-sizing:border-box;}"
            + "body{background:#000;overflow:hidden;font-family:Arial,sans-serif;}"
            + "video{width:100%;height:100vh;display:block;}"
            
            // Container principal
            + "#player-container{position:relative;width:100%;height:100vh;}"
            
            // Controles overlay
            + "#controls{position:absolute;bottom:0;left:0;right:0;"
            + "background:linear-gradient(transparent,rgba(0,0,0,0.9));"
            + "padding:40px 16px 16px 16px;opacity:0;transition:opacity 0.3s;z-index:10;}"
            + "#controls:hover,#controls.active{opacity:1;}"
            
            // Barra de progresso
            + "#progress-bar{width:100%;height:4px;background:rgba(255,255,255,0.2);"
            + "border-radius:2px;cursor:pointer;margin-bottom:12px;position:relative;}"
            + "#progress-filled{height:100%;background:#6c5ce7;border-radius:2px;"
            + "width:0%;transition:width 0.1s linear;}"
            + "#progress-thumb{width:14px;height:14px;background:#fff;border-radius:50%;"
            + "position:absolute;top:-5px;left:0%;transform:translateX(-50%);display:none;}"
            + "#progress-bar:hover #progress-thumb{display:block;}"
            
            // Botões
            + "#buttons-row{display:flex;align-items:center;justify-content:space-between;}"
            + "#left-buttons,#right-buttons{display:flex;align-items:center;gap:16px;}"
            + ".btn{background:none;border:none;color:#fff;cursor:pointer;"
            + "font-size:20px;padding:8px;border-radius:50%;width:40px;height:40px;"
            + "display:flex;align-items:center;justify-content:center;transition:background 0.2s;}"
            + ".btn:hover{background:rgba(255,255,255,0.1);}"
            + ".btn:active{background:rgba(255,255,255,0.2);}"
            + ".btn svg{width:20px;height:20px;fill:#fff;}"
            
            // Tempo
            + "#time-display{color:#fff;font-size:13px;font-weight:500;font-variant-numeric:tabular-nums;}"
            
            // Menu de áudio/legendas
            + "#track-menu{position:absolute;bottom:70px;right:16px;"
            + "background:rgba(20,20,30,0.95);border-radius:12px;padding:8px 0;"
            + "min-width:180px;display:none;z-index:20;backdrop-filter:blur(10px);}"
            + "#track-menu.show{display:block;}"
            + ".track-item{color:#fff;padding:10px 16px;font-size:13px;cursor:pointer;"
            + "display:flex;align-items:center;justify-content:space-between;}"
            + ".track-item:hover{background:rgba(255,255,255,0.1);}"
            + ".track-item.active{color:#6c5ce7;}"
            + ".track-item .check{color:#6c5ce7;display:none;}"
            + ".track-item.active .check{display:inline;}"
            
            // Título
            + "#video-title{position:absolute;top:16px;left:16px;color:#fff;"
            + "font-size:16px;font-weight:bold;text-shadow:0 1px 3px rgba(0,0,0,0.8);"
            + "opacity:0;transition:opacity 0.3s;z-index:10;}"
            + "#player-container:hover #video-title{opacity:1;}"
            
            // Loading
            + "#loading-indicator{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);"
            + "color:#fff;font-size:14px;display:none;z-index:5;text-align:center;}"
            + "#loading-indicator.show{display:block;}"
            + ".spinner{width:40px;height:40px;border:3px solid rgba(255,255,255,0.3);"
            + "border-top-color:#fff;border-radius:50%;animation:spin 0.8s linear infinite;"
            + "margin:0 auto 12px auto;}"
            + "@keyframes spin{to{transform:rotate(360deg);}}"
            + "</style></head><body>"
            
            + "<div id='player-container'>"
            + "<video id='v' playsinline crossorigin='anonymous'>"
            + "<source src='http://127.0.0.1:8080/video' type='video/mp4'>"
            + "</video>"
            
            + "<div id='video-title'></div>"
            
            + "<div id='loading-indicator'>"
            + "<div class='spinner'></div><div id='loading-text'>Carregando...</div>"
            + "</div>"
            
            + "<div id='controls'>"
            + "<div id='progress-bar' onmousedown='startSeek(event)' onmousemove='moveSeek(event)' onmouseup='endSeek(event)' ontouchstart='startSeek(event)' ontouchmove='moveSeek(event)' ontouchend='endSeek(event)'>"
            + "<div id='progress-filled'></div><div id='progress-thumb'></div>"
            + "</div>"
            
            + "<div id='buttons-row'>"
            + "<div id='left-buttons'>"
            + "<button class='btn' onclick='togglePlay()' id='btn-play'>"
            + "<svg viewBox='0 0 24 24'><path d='M8 5v14l11-7z'/></svg>"
            + "</button>"
            + "<button class='btn' onclick='skip(-10)'>"
            + "<svg viewBox='0 0 24 24'><path d='M11.99 5V1l-5 5 5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6h-2c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z'/>"
            + "<text x='13' y='17' font-size='9' fill='#fff' font-weight='bold'>10</text></svg>"
            + "</button>"
            + "<button class='btn' onclick='skip(10)'>"
            + "<svg viewBox='0 0 24 24'><path d='M12.01 5V1l5 5-5 5V7c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8z'/>"
            + "<text x='7' y='17' font-size='9' fill='#fff' font-weight='bold'>10</text></svg>"
            + "</button>"
            + "<span id='time-display'>0:00 / 0:00</span>"
            + "</div>"
            
            + "<div id='right-buttons'>"
            + "<button class='btn' id='btn-audio' onclick='toggleTrackMenu(\"audio\")' style='font-size:11px;font-weight:bold;width:auto;padding:8px 12px;border-radius:20px;'>🎵 Áudio</button>"
            + "<button class='btn' id='btn-subs' onclick='toggleTrackMenu(\"subs\")' style='font-size:11px;font-weight:bold;width:auto;padding:8px 12px;border-radius:20px;'>📝 Legendas</button>"
            + "<button class='btn' onclick='toggleFullscreen()'>"
            + "<svg viewBox='0 0 24 24'><path d='M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z'/></svg>"
            + "</button>"
            + "</div>"
            + "</div>"
            + "</div>"
            
            + "<div id='track-menu'></div>"
            + "</div>"
            
            + "<script>"
            + "var v=document.getElementById('v');"
            + "var controls=document.getElementById('controls');"
            + "var progressFilled=document.getElementById('progress-filled');"
            + "var progressThumb=document.getElementById('progress-thumb');"
            + "var progressBar=document.getElementById('progress-bar');"
            + "var timeDisplay=document.getElementById('time-display');"
            + "var loading=document.getElementById('loading-indicator');"
            + "var loadingText=document.getElementById('loading-text');"
            + "var trackMenu=document.getElementById('track-menu');"
            + "var videoTitle=document.getElementById('video-title');"
            + "var btnPlay=document.getElementById('btn-play');"
            + "var isSeeking=false;var hideTimeout;"
            
            + "v.addEventListener('timeupdate',function(){if(!isSeeking){var pct=(v.currentTime/v.duration)*100;progressFilled.style.width=pct+'%';progressThumb.style.left=pct+'%';timeDisplay.textContent=formatTime(v.currentTime)+' / '+formatTime(v.duration);}});"
            + "v.addEventListener('waiting',function(){loading.classList.add('show');loadingText.textContent='Carregando...';});"
            + "v.addEventListener('canplay',function(){loading.classList.remove('show');});"
            + "v.addEventListener('playing',function(){loading.classList.remove('show');btnPlay.innerHTML=\"<svg viewBox='0 0 24 24'><path d='M6 19h4V5H6v14zm8-14v14h4V5h-4z'/></svg>\";});"
            + "v.addEventListener('pause',function(){btnPlay.innerHTML=\"<svg viewBox='0 0 24 24'><path d='M8 5v14l11-7z'/></svg>\";});"
            + "v.addEventListener('seeked',function(){videoTitle.textContent='⏩ '+formatTime(v.currentTime);videoTitle.style.opacity='1';setTimeout(function(){videoTitle.style.opacity='0';},2000);});"
            + "v.addEventListener('loadedmetadata',function(){videoTitle.textContent='▶️ '+Math.floor(v.duration)+'s';videoTitle.style.opacity='1';setTimeout(function(){videoTitle.style.opacity='0';},3000);"
            
            // Atualiza botões de áudio/legendas
            + "var audioTracks=v.audioTracks;var textTracks=v.textTracks;"
            + "if(audioTracks&&audioTracks.length>1){document.getElementById('btn-audio').style.display='flex';}"
            + "if(textTracks&&textTracks.length>0){document.getElementById('btn-subs').style.display='flex';}"
            + "});"
            
            + "document.addEventListener('click',function(){controls.classList.add('active');clearTimeout(hideTimeout);hideTimeout=setTimeout(function(){controls.classList.remove('active');},3000);});"
            + "function togglePlay(){if(v.paused){v.play();}else{v.pause();}}"
            + "function skip(s){v.currentTime=Math.max(0,Math.min(v.duration,v.currentTime+s));}"
            + "function formatTime(t){if(isNaN(t))return'0:00';var m=Math.floor(t/60);var s=Math.floor(t%60);return m+':'+(s<10?'0':'')+s;}"
            
            + "function startSeek(e){isSeeking=true;updateSeek(e);}"
            + "function moveSeek(e){if(isSeeking)updateSeek(e);}"
            + "function endSeek(e){if(isSeeking){updateSeek(e);isSeeking=false;}}"
            + "function updateSeek(e){var rect=progressBar.getBoundingClientRect();var x=(e.touches?e.touches[0].clientX:e.clientX)-rect.left;var pct=Math.max(0,Math.min(100,(x/rect.width)*100));progressFilled.style.width=pct+'%';progressThumb.style.left=pct+'%';if(e.type=='mouseup'||e.type=='touchend'){v.currentTime=(pct/100)*v.duration;}}"
            
            + "function toggleTrackMenu(type){"
            + "var tracks=type=='audio'?v.audioTracks:v.textTracks;"
            + "var html='';"
            + "if(tracks&&tracks.length>0){"
            + "for(var i=0;i<tracks.length;i++){"
            + "var t=tracks[i];var label=t.label||t.language||('Track '+(i+1));"
            + "var active=(type=='audio'?v.audioTrack:i)==i;"
            + "html+=\"<div class='track-item\"+(active?\" active\":\"\")+\"' onclick='selectTrack(\\\"\"+type+\"\\\",\"+i+\")'>\"+label+\"<span class='check'>✓</span></div>\";"
            + "}}else{html=\"<div class='track-item'>Nenhum disponível</div>\";}"
            + "trackMenu.innerHTML=html;trackMenu.classList.toggle('show');"
            + "}"
            
            + "function selectTrack(type,index){"
            + "if(type=='audio'){for(var i=0;i<v.audioTracks.length;i++)v.audioTracks[i].enabled=(i==index);}"
            + "else{for(var i=0;i<v.textTracks.length;i++)v.textTracks[i].mode=(i==index?'showing':'hidden');}"
            + "trackMenu.classList.remove('show');"
            + "}"
            
            + "function toggleFullscreen(){if(document.fullscreenElement){document.exitFullscreen();}else{document.getElementById('player-container').requestFullscreen();}}"
            + "</script></body></html>";
        
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        debug("✅ Player PRO carregado");
    }
    
    private void stop() {
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE); btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        if (torrentHandle != null && session != null) {
            try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {}
            torrentHandle = null;
        }
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
        if (serverThread != null) serverThread.interrupt();
        if (session != null) session.stop();
        super.onDestroy();
    }
}