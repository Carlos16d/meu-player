package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "TorrentStream";
    
    private WebView webView;
    private TextView statusText, progressText, titleText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private LinearLayout glassPanel;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    
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
        Log.d(TAG, "🚀 onCreate iniciado");
        setContentView(R.layout.activity_main);
        
        webView = findViewById(R.id.webview);
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
        
        Log.d(TAG, "✅ Views inicializadas");
        
        webView.post(() -> {
            int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.94);
            int h = (int)(w * 9.0 / 16.0);
            ViewGroup.LayoutParams p = webView.getLayoutParams();
            p.width = w; p.height = h;
            webView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        Log.d(TAG, "📁 Save path: " + savePath);
        
        handler = new Handler(Looper.getMainLooper());
        
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setAllowFileAccess(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.setVisibility(View.GONE);
        
        AlphaAnimation glow = new AlphaAnimation(0.6f, 1.0f);
        glow.setDuration(2000);
        glow.setRepeatMode(Animation.REVERSE);
        glow.setRepeatCount(Animation.INFINITE);
        titleText.startAnimation(glow);
        
        Log.d(TAG, "🔄 Chamando initializeSession()");
        initializeSession();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
        
        log("Pronto para streaming");
        Log.d(TAG, "✅ onCreate finalizado");
    }
    
    private void initializeSession() {
        Log.d(TAG, "📡 initializeSession() iniciado");
        
        new Thread(() -> {
            try {
                log("🔄 Inicializando sessão P2P...");
                Log.d(TAG, "📡 Thread de inicialização iniciada");
                
                // Verifica se libtorrent está disponível
                try {
                    Log.d(TAG, "📡 Testando libtorrent...");
                    String version = libtorrent.version();
                    Log.d(TAG, "📡 libtorrent version: " + version);
                } catch (Exception e) {
                    Log.e(TAG, "❌ libtorrent não disponível", e);
                    log("❌ Erro: libtorrent não disponível");
                    return;
                }
                
                // Tenta criar SessionManager
                Log.d(TAG, "📡 Criando SessionManager...");
                try {
                    session = new SessionManager();
                    Log.d(TAG, "📡 SessionManager criado: " + session);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Erro ao criar SessionManager", e);
                    log("❌ Erro ao criar sessão: " + e.getMessage());
                    return;
                }
                
                // Aguarda inicialização
                Log.d(TAG, "📡 Aguardando 2 segundos...");
                Thread.sleep(2000);
                
                // Verifica se session.swig() funciona
                Log.d(TAG, "📡 Verificando session.swig()...");
                try {
                    session_handle swigSession = session.swig();
                    Log.d(TAG, "📡 session.swig() retornou: " + swigSession);
                    
                    if (swigSession == null) {
                        Log.e(TAG, "❌ session.swig() retornou null");
                        log("❌ Sessão nula - reiniciando...");
                        // Tenta novamente
                        Thread.sleep(1000);
                        swigSession = session.swig();
                        Log.d(TAG, "📡 Segunda tentativa: " + swigSession);
                    }
                    
                    if (swigSession != null) {
                        Log.d(TAG, "✅ Sessão SWIG válida, configurando...");
                        
                        // Configurações otimizadas
                        settings_pack sp = new settings_pack();
                        sp.set_int(settings_pack.int_types.connections_limit.swigValue(), 500);
                        sp.set_int(settings_pack.int_types.unchoke_slots_limit.swigValue(), 20);
                        sp.set_int(settings_pack.int_types.active_downloads.swigValue(), 3);
                        sp.set_int(settings_pack.int_types.active_seeds.swigValue(), 3);
                        sp.set_bool(settings_pack.bool_types.strict_end_game_mode.swigValue(), true);
                        sp.set_bool(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true);
                        sp.set_bool(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true);
                        
                        Log.d(TAG, "📡 Aplicando configurações...");
                        swigSession.apply_settings(sp);
                        Log.d(TAG, "📡 Iniciando sessão...");
                        swigSession.start();
                        
                        sessionReady = true;
                        Log.d(TAG, "✅ Sessão P2P pronta!");
                        log("✅ Conectado à rede P2P");
                    } else {
                        Log.e(TAG, "❌ session.swig() continua null após tentativas");
                        log("❌ Falha ao inicializar sessão P2P");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ Erro ao configurar sessão", e);
                    log("❌ Erro: " + e.getMessage());
                    e.printStackTrace();
                }
                
            } catch (Exception e) { 
                Log.e(TAG, "❌ Erro geral na inicialização", e);
                log("❌ Erro: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }
    
    private void log(String msg) {
        Log.d(TAG, "📱 UI: " + msg);
        handler.post(() -> statusText.setText(msg));
    }
    
    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }
    
    private void startServer() {
        Log.d(TAG, "🌐 Iniciando servidor HTTP...");
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                Log.d(TAG, "🌐 Servidor HTTP rodando na porta 8080");
                while (!Thread.interrupted()) {
                    try { 
                        Socket c = server.accept(); 
                        Log.d(TAG, "🌐 Nova conexão: " + c.getInetAddress());
                        new Thread(() -> handleHttp(c)).start(); 
                    } catch (IOException e) {
                        Log.d(TAG, "🌐 Servidor interrompido");
                    }
                }
                server.close();
            } catch (IOException e) {
                Log.e(TAG, "❌ Erro no servidor HTTP", e);
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleHttp(Socket c) {
        try {
            OutputStream o = c.getOutputStream();
            BufferedReader i = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String r = i.readLine();
            Log.d(TAG, "🌐 Request: " + r);
            
            if (r == null || !r.contains("/video")) { 
                o.write("HTTP/1.1 404\r\n\r\n".getBytes()); 
                o.flush(); 
                c.close(); 
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
                    Log.d(TAG, "🌐 Range: " + s + "-" + e);
                }
            }
            
            File vf = videoFile;
            if (vf == null || !vf.exists() || vf.length() < 4096) {
                Log.d(TAG, "🌐 Arquivo não disponível: " + (vf != null ? vf.length() : "null"));
                o.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes()); 
                o.flush(); 
                c.close(); 
                return;
            }
            
            long len = vf.length();
            if (e == -1 || e >= len) e = len - 1;
            
            String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            int sz = Math.min((int)(e - s + 1), 1048576);
            
            byte[] b = new byte[sz];
            RandomAccessFile raf = new RandomAccessFile(vf, "r");
            raf.seek(s);
            int t = raf.read(b);
            raf.close();
            
            Log.d(TAG, "🌐 Servindo " + t + " bytes de " + vf.getName());
            
            String resp = "HTTP/1.1 206\r\nContent-Type: " + mime + "\r\n" +
                "Content-Range: bytes " + s + "-" + (s+t-1) + "/" + len + "\r\n" +
                "Content-Length: " + t + "\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n";
            o.write(resp.getBytes()); 
            o.write(b, 0, t); 
            o.flush(); 
            c.close();
            
        } catch (Exception ex) { 
            Log.e(TAG, "❌ Erro no handleHttp", ex);
            try { c.close(); } catch (IOException ex2) {}
        }
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        Log.d(TAG, "▶️ Botão Play pressionado. Magnet: " + magnet.substring(0, Math.min(60, magnet.length())));
        
        if (!magnet.startsWith("magnet:") || downloading) {
            Log.d(TAG, "❌ Magnet inválido ou já baixando");
            return;
        }
        
        // Verifica se a sessão está pronta
        Log.d(TAG, "📡 Verificando sessão - ready: " + sessionReady + ", session: " + session);
        if (!sessionReady || session == null) {
            Log.e(TAG, "❌ Sessão não está pronta!");
            log("❌ Aguarde a inicialização da rede P2P...");
            if (!sessionReady) {
                Log.d(TAG, "🔄 Tentando reinicializar sessão...");
                initializeSession();
            }
            return;
        }
        
        try {
            Log.d(TAG, "📡 Verificando session.swig(): " + session.swig());
        } catch (Exception e) {
            Log.e(TAG, "❌ session.swig() falhou", e);
            log("❌ Erro na sessão P2P");
            return;
        }
        
        File dir = new File(savePath);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursive(f);
                }
            }
        }
        new File(savePath).mkdirs();
        
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
            titleText.setText("⬇️ Conectando...");
            bufferBar.setProgress(0);
            progressText.setText("Preparando...");
        });
        
        log("🔍 Buscando peers...");
        Log.d(TAG, "📡 Iniciando download do torrent");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                p.setDownload_limit(0);
                p.setUpload_limit(0);
                
                byte_vector pr = new byte_vector();
                pr.add((byte)7);
                p.set_file_priorities(pr);
                
                Log.d(TAG, "📡 Adicionando torrent...");
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                Log.d(TAG, "📡 Obtendo handles...");
                torrent_handle_vector h = session.swig().get_torrents();
                Log.d(TAG, "📡 Torrents ativos: " + h.size());
                
                if (h.size() > 0) {
                    torrentHandle = h.get(0);
                    log("✅ Torrent adicionado");
                    Log.d(TAG, "📡 Torrent handle obtido: " + torrentHandle.is_valid());
                    
                    while (downloading) {
                        File f = findVideoFile(new File(savePath));
                        Log.d(TAG, "📁 Procurando vídeo: " + (f != null ? f.getName() + " (" + f.length() + " bytes)" : "não encontrado"));
                        
                        if (f != null && f.length() > 5242880) {
                            Log.d(TAG, "📁 Arquivo grande encontrado: " + f.length() + " bytes");
                            
                            if (isValidVideoFile(f)) {
                                videoFile = f;
                                long downloadedMB = f.length() / 1048576;
                                Log.d(TAG, "✅ Vídeo válido: " + downloadedMB + "MB");
                                
                                handler.post(() -> {
                                    progressText.setText(String.format("%d MB baixados", downloadedMB));
                                    bufferBar.setProgress(Math.min((int)((f.length() * 100) / 276134947L), 100));
                                    
                                    if (btnWatch.getVisibility() != View.VISIBLE) {
                                        spinnerBar.setVisibility(View.GONE);
                                        loadingOverlay.setVisibility(View.GONE);
                                        btnWatch.setVisibility(View.VISIBLE);
                                        btnWatch.setAlpha(0f);
                                        btnWatch.animate().alpha(1f).setDuration(500);
                                        titleText.setText("🎬 Pronto para streaming!");
                                        log("✅ " + downloadedMB + "MB - Streaming disponível!");
                                    }
                                });
                            } else {
                                Log.d(TAG, "❌ Arquivo não é vídeo válido");
                            }
                        }
                        Thread.sleep(1000);
                    }
                } else {
                    Log.e(TAG, "❌ Nenhum torrent handle encontrado");
                    log("❌ Erro: Torrent não iniciou");
                }
            } catch (Exception e2) {
                Log.e(TAG, "❌ Erro no download", e2);
                log("❌ " + e2.getMessage()); 
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
    
    private boolean isValidVideoFile(File f) {
        if (f == null || !f.exists() || f.length() < 4096) return false;
        try {
            byte[] header = new byte[8];
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            raf.read(header);
            raf.close();
            
            return (header[4]=='f' && header[5]=='t' && header[6]=='y' && header[7]=='p') ||
                   ((header[0]&0xFF)==0x1A && header[1]==0x45 && header[2]==(byte)0xDF && header[3]==(byte)0xA3) ||
                   (header[0]=='R' && header[1]=='I' && header[2]=='F' && header[3]=='F') ||
                   (header[0]==0x00 && header[1]==0x00 && header[2]==0x00 && header[3]=='m');
        } catch (Exception e) {
            return false;
        }
    }
    
    private void watch() {
        Log.d(TAG, "▶️ Assistir pressionado");
        if (videoFile == null || !videoFile.exists()) { 
            log("❌ Arquivo não encontrado"); 
            return; 
        }
        
        handler.post(() -> { 
            webView.setVisibility(View.VISIBLE);
            webView.setAlpha(0f);
            webView.animate().alpha(1f).setDuration(600);
            glassPanel.setVisibility(View.GONE);
            btnWatch.setVisibility(View.GONE);
            titleText.setText("▶️ Reproduzindo");
        });
        
        String html = "<!DOCTYPE html><html><head>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>" +
            "<style>" +
            "body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh;overflow:hidden;}" +
            "video{width:100%;max-height:100vh;outline:none;border-radius:8px;}" +
            "</style></head><body>" +
            "<video controls autoplay playsinline style='width:100%' preload='auto'>" +
            "<source src='http://127.0.0.1:8080/video' type='video/mp4'>" +
            "</video></body></html>";
        
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }
    
    private void stop() {
        Log.d(TAG, "⏹️ Parando...");
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE); 
        btnStop.setVisibility(View.GONE); 
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); 
        spinnerBar.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        glassPanel.setVisibility(View.GONE);
        titleText.setText("🎬 Torrent Streaming");
        progressText.setText("Pronto para começar");
        log("⏹️ Parado");
        if (torrentHandle != null && session != null && session.swig() != null) {
            try { 
                session.swig().remove_torrent(torrentHandle); 
            } catch (Exception e) {}
            torrentHandle = null;
        }
    }
    
    @Override 
    protected void onDestroy() {
        Log.d(TAG, "💀 onDestroy");
        stop();
        if (serverThread != null) serverThread.interrupt();
        if (session != null) {
            try {
                session.stop();
            } catch (Exception e) {}
        }
        super.onDestroy();
    }
}