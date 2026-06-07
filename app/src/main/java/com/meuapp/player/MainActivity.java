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

import com.meuapp.player.engine.TorrentEngine;
import com.meuapp.player.server.StreamServer;
import com.meuapp.player.model.TorrentInfo;

import org.libtorrent4j.swig.torrent_handle;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private TextView statusText, debugText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    
    private String savePath;
    private TorrentEngine torrentEngine;
    private StreamServer streamServer;
    private volatile File videoFile;
    private Handler handler;
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
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.setVisibility(View.GONE);
        
        debug("=== TORRENT STREAM PRO ===");
        
        streamServer = new StreamServer();
        try { streamServer.start(); debug("[SRV] OK"); } 
        catch (Exception e) { debug("[SRV] " + e.getMessage()); }
        
        torrentEngine = new TorrentEngine(new TorrentEngine.EngineCallback() {
            public void onReady() { debug("[ENG] OK"); }
            public void onError(String e) { debug("[ENG] " + e); }
            
            public void onProgress(TorrentInfo info) {
                handler.post(() -> {
                    bufferBar.setProgress(info.progress);
                    statusText.setText(info.progress + "% | " + (info.speed/1024) + "KB/s");
                });
            }
            
            public void onStreamReady(torrent_handle handle, String sp) {
                File vf = findVideoFile(new File(sp));
                if (vf != null) {
                    videoFile = vf;
                    streamServer.setVideoFile(vf);
                    debug("[ENG] Video: " + vf.getName() + " (" + (vf.length()/1048576) + "MB)");
                }
                debug("[ENG] STREAM READY");
                handler.post(() -> {
                    spinnerBar.setVisibility(View.GONE);
                    btnWatch.setVisibility(View.VISIBLE);
                });
            }
            
            public void onStatus(String s) { debug("[ENG] " + s); }
            public void onLog(String log) { debug("[ENG] " + log); }
        });
        
        torrentEngine.start();
        
        btnPlay.setOnClickListener(v -> {
            String m = magnetInput.getText().toString().trim();
            if (m.startsWith("magnet:")) startStream(m);
        });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        debug("Pronto");
    }
    
    private void debug(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        debugLog.append(line);
        handler.post(() -> debugText.setText(debugLog.toString()));
    }
    
    private File findVideoFile(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideoFile(f);
                    if (found != null) return found;
                } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm|mov)$") && f.length() > 0) {
                    return f;
                }
            }
        }
        return null;
    }
    
    private void startStream(String magnet) {
        bufferBar.setVisibility(View.VISIBLE);
        spinnerBar.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnWatch.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        debugLog.setLength(0);
        torrentEngine.startDownload(magnet, savePath);
    }
    
    private void watch() {
        if (videoFile == null || !videoFile.exists()) { 
            debug("Video não encontrado"); 
            return; 
        }
        
        debug("Iniciando player PRO...");
        
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
            
            // Linha de botões
            + "#buttons-row{display:flex;align-items:center;justify-content:space-between;}"
            + "#left-buttons,#right-buttons{display:flex;align-items:center;gap:16px;}"
            
            // Botões
            + ".btn{background:none;border:none;color:#fff;cursor:pointer;"
            + "font-size:20px;padding:8px;border-radius:50%;width:40px;height:40px;"
            + "display:flex;align-items:center;justify-content:center;transition:background 0.2s;}"
            + ".btn:hover{background:rgba(255,255,255,0.1);}"
            + ".btn:active{background:rgba(255,255,255,0.2);}"
            + ".btn svg{width:20px;height:20px;fill:#fff;}"
            
            // Tempo
            + "#time-display{color:#fff;font-size:13px;font-weight:500;"
            + "font-variant-numeric:tabular-nums;}"
            
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
            + "#controls:hover ~ #video-title,#controls.active ~ #video-title{opacity:0;}"
            + "#player-container:hover #video-title{opacity:1;}"
            
            // Loading central
            + "#loading-indicator{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);"
            + "color:#fff;font-size:14px;display:none;z-index:5;}"
            + "#loading-indicator.show{display:block;}"
            + ".spinner{width:40px;height:40px;border:3px solid rgba(255,255,255,0.3);"
            + "border-top-color:#fff;border-radius:50%;animation:spin 0.8s linear infinite;"
            + "margin:0 auto 12px auto;}"
            + "@keyframes spin{to{transform:rotate(360deg);}}"
            
            + "</style></head><body>"
            
            + "<div id='player-container'>"
            + "<video id='v' playsinline>"
            + "<source src='http://127.0.0.1:8080/video' type='video/mp4'>"
            + "</video>"
            
            + "<div id='video-title'></div>"
            
            + "<div id='loading-indicator'>"
            + "<div class='spinner'></div>"
            + "<div id='loading-text'>Carregando...</div>"
            + "</div>"
            
            + "<div id='controls'>"
            + "<div id='progress-bar' onmousedown='startSeek(event)' onmousemove='moveSeek(event)' onmouseup='endSeek(event)' ontouchstart='startSeek(event)' ontouchmove='moveSeek(event)' ontouchend='endSeek(event)'>"
            + "<div id='progress-filled'></div>"
            + "<div id='progress-thumb'></div>"
            + "</div>"
            
            + "<div id='buttons-row'>"
            + "<div id='left-buttons'>"
            + "<button class='btn' onclick='togglePlay()' id='btn-play'>"
            + "<svg viewBox='0 0 24 24'><path d='M8 5v14l11-7z'/></svg>"
            + "</button>"
            + "<button class='btn' onclick='skip(-10)'>"
            + "<svg viewBox='0 0 24 24'><path d='M11.99 5V1l-5 5 5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6h-2c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z'/><text x='13' y='17' font-size='9' fill='#fff' font-weight='bold'>10</text></svg>"
            + "</button>"
            + "<button class='btn' onclick='skip(10)'>"
            + "<svg viewBox='0 0 24 24'><path d='M12.01 5V1l5 5-5 5V7c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8z'/><text x='7' y='17' font-size='9' fill='#fff' font-weight='bold'>10</text></svg>"
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
            + "var isSeeking=false;"
            + "var hideTimeout;"
            
            // Extrai nome do arquivo
            + "try{"
            + "  var urlParams=new URLSearchParams(window.location.search);"
            + "  videoTitle.textContent=document.title || 'Reproduzindo';"
            + "}catch(e){}"
            
            // Atualiza progresso
            + "v.addEventListener('timeupdate',function(){"
            + "  if(!isSeeking){"
            + "    var pct=(v.currentTime/v.duration)*100;"
            + "    progressFilled.style.width=pct+'%';"
            + "    progressThumb.style.left=pct+'%';"
            + "    timeDisplay.textContent=formatTime(v.currentTime)+' / '+formatTime(v.duration);"
            + "  }"
            + "});"
            
            // Loading
            + "v.addEventListener('waiting',function(){"
            + "  loading.classList.add('show');"
            + "  loadingText.textContent='Carregando...';"
            + "});"
            + "v.addEventListener('canplay',function(){"
            + "  loading.classList.remove('show');"
            + "});"
            + "v.addEventListener('playing',function(){"
            + "  loading.classList.remove('show');"
            + "  btnPlay.innerHTML=\"<svg viewBox='0 0 24 24'><path d='M6 19h4V5H6v14zm8-14v14h4V5h-4z'/></svg>\";"
            + "});"
            + "v.addEventListener('pause',function(){"
            + "  btnPlay.innerHTML=\"<svg viewBox='0 0 24 24'><path d='M8 5v14l11-7z'/></svg>\";"
            + "});"
            
            // Mostra controles ao tocar
            + "document.addEventListener('click',function(){"
            + "  controls.classList.add('active');"
            + "  clearTimeout(hideTimeout);"
            + "  hideTimeout=setTimeout(function(){controls.classList.remove('active');},3000);"
            + "});"
            
            + "function togglePlay(){"
            + "  if(v.paused){v.play();}else{v.pause();}"
            + "}"
            
            + "function skip(secs){"
            + "  v.currentTime=Math.max(0,Math.min(v.duration,v.currentTime+secs));"
            + "}"
            
            + "function formatTime(t){"
            + "  if(isNaN(t))return '0:00';"
            + "  var m=Math.floor(t/60);"
            + "  var s=Math.floor(t%60);"
            + "  return m+':'+(s<10?'0':'')+s;"
            + "}"
            
            // Seek
            + "function startSeek(e){"
            + "  isSeeking=true;"
            + "  updateSeek(e);"
            + "}"
            + "function moveSeek(e){if(isSeeking)updateSeek(e);}"
            + "function endSeek(e){"
            + "  if(isSeeking){updateSeek(e);isSeeking=false;}"
            + "}"
            + "function updateSeek(e){"
            + "  var rect=progressBar.getBoundingClientRect();"
            + "  var x=(e.touches?e.touches[0].clientX:e.clientX)-rect.left;"
            + "  var pct=Math.max(0,Math.min(100,(x/rect.width)*100));"
            + "  progressFilled.style.width=pct+'%';"
            + "  progressThumb.style.left=pct+'%';"
            + "  if(e.type=='mouseup'||e.type=='touchend'){"
            + "    v.currentTime=(pct/100)*v.duration;"
            + "  }"
            + "}"
            
            // Menu de tracks
            + "function toggleTrackMenu(type){"
            + "  var tracks=type=='audio'?v.audioTracks:v.textTracks;"
            + "  var html='';"
            + "  if(tracks&&tracks.length>0){"
            + "    for(var i=0;i<tracks.length;i++){"
            + "      var track=tracks[i];"
            + "      var label=track.label||track.language||('Track '+(i+1));"
            + "      var active=(type=='audio'?v.audioTrack:i)==i;"
            + "      html+=\"<div class='track-item\"+(active?\" active\":\"\")+\"' onclick='selectTrack(\\\"\"+type+\"\\\",\"+i+\")'>\"+label+\"<span class='check'>✓</span></div>\";"
            + "    }"
            + "  }else{"
            + "    html=\"<div class='track-item'>Nenhum disponível</div>\";"
            + "  }"
            + "  trackMenu.innerHTML=html;"
            + "  trackMenu.classList.toggle('show');"
            + "}"
            
            + "function selectTrack(type,index){"
            + "  if(type=='audio'){"
            + "    for(var i=0;i<v.audioTracks.length;i++){v.audioTracks[i].enabled=(i==index);}"
            + "  }else{"
            + "    for(var i=0;i<v.textTracks.length;i++){v.textTracks[i].mode=(i==index?'showing':'hidden');}"
            + "  }"
            + "  trackMenu.classList.remove('show');"
            + "}"
            
            + "function toggleFullscreen(){"
            + "  if(document.fullscreenElement){document.exitFullscreen();}"
            + "  else{document.getElementById('player-container').requestFullscreen();}"
            + "}"
            
            // Atualiza título com nome do vídeo
            + "v.addEventListener('loadedmetadata',function(){"
            + "  videoTitle.textContent='▶️ ' + Math.floor(v.duration) + 's';"
            + "  setTimeout(function(){videoTitle.style.opacity='0';},3000);"
            + "});"
            
            + "v.addEventListener('error',function(){"
            + "  videoTitle.textContent='❌ Erro ao carregar';"
            + "  videoTitle.style.opacity='1';"
            + "});"
            
            + "</script></body></html>";
        
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        debug("Player PRO carregado");
    }
    
    private void stop() {
        torrentEngine.stop();
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE); 
        btnStop.setVisibility(View.GONE); 
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        spinnerBar.setVisibility(View.GONE);
    }
    
    @Override protected void onDestroy() {
        stop();
        if (streamServer != null) streamServer.stop();
        super.onDestroy();
    }
}