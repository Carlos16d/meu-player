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

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private LinearLayout controlPanel, statsRow;
    private TextView logText, statProgress, statSpeed, statPeers;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    private ScrollView logScroll;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private volatile boolean downloading = false;
    private volatile File videoFile = null;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        log("═══════════════════════════");
        log("  APP INICIADO");
        log("═══════════════════════════");
        log("Android SDK: " + android.os.Build.VERSION.SDK_INT);
        log("Dispositivo: " + android.os.Build.MODEL);
        log("Pasta download: " + getExternalFilesDir(null));
        
        controlPanel = findViewById(R.id.control_panel);
        statsRow = findViewById(R.id.stats_row);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        statProgress = findViewById(R.id.stat_progress);
        statSpeed = findViewById(R.id.stat_speed);
        statPeers = findViewById(R.id.stat_peers);
        bufferBar = findViewById(R.id.buffer_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        log("✅ Views inicializadas");
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        log("📁 Pasta torrent: " + savePath);
        log("📁 Existe: " + new File(savePath).exists());
        
        log("🔄 Iniciando sessão torrent...");
        new Thread(() -> {
            try {
                session = new SessionManager();
                session.start();
                log("✅ Sessão torrent OK");
                log("   DHT: " + (session.swig().is_dht_running() ? "ATIVO" : "OFF"));
                log("   Porta: " + session.swig().listen_port());
            } catch (Exception e) {
                log("❌ ERRO SESSÃO: " + e.getMessage());
            }
        }).start();
        
        btnPlay.setOnClickListener(v -> {
            log("🖱️ Botão PLAY clicado");
            start();
        });
        btnStop.setOnClickListener(v -> {
            log("🖱️ Botão STOP clicado");
            stop();
        });
        btnWatch.setOnClickListener(v -> {
            log("🖱️ Botão WATCH clicado");
            watch();
        });
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        fullLog.append(line);
        handler.post(() -> {
            logText.setText(fullLog.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
        android.util.Log.d("APP_DEBUG", msg);
    }
    
    private void start() {
        String magnet = magnetInput.getText().toString().trim();
        log("📝 Magnet: " + magnet.substring(0, Math.min(60, magnet.length())) + "...");
        
        if (!magnet.startsWith("magnet:")) {
            log("❌ ERRO: Não é um magnet link!");
            return;
        }
        if (downloading) {
            log("⚠️ Já está baixando!");
            return;
        }
        
        downloading = true;
        videoFile = null;
        
        handler.post(() -> {
            controlPanel.setVisibility(View.GONE);
            statsRow.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        log("═══════════════════════════");
        log("  INICIANDO DOWNLOAD");
        log("═══════════════════════════");
        
        new Thread(() -> {
            try {
                log("🔍 Parseando magnet...");
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                log("✅ Magnet parseado");
                
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(0);
                p.setMax_connections(200);
                
                log("⚙️ Config: flags=9, download=ilimitado, conexões=200");
                
                byte_vector pr = new byte_vector();
                pr.add((byte)7);
                p.set_file_priorities(pr);
                log("⚙️ Prioridade arquivo: 7 (máxima)");
                
                log("📤 Enviando para sessão...");
                session.swig().async_add_torrent(p);
                log("✅ Magnet enviado!");
                
                log("⏳ Aguardando metadados (3s)...");
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                log("📊 Torrents ativos: " + h.size());
                
                if (h.size() > 0) {
                    torrent = h.get(0);
                    torrent_status ts = torrent.status();
                    log("✅ Torrent obtido!");
                    log("📊 Progresso: " + (int)(ts.getProgress()*100) + "%");
                    log("📊 Peers conectados: " + ts.getNum_peers());
                    log("📊 Peers totais: " + ts.getNum_complete());
                    log("📊 Download rate: " + ts.getDownload_rate() + " B/s");
                    log("📊 Upload rate: " + ts.getUpload_rate() + " B/s");
                    log("📊 Estado: " + ts.getState());
                    log("📊 Tamanho total: " + (ts.getTotal_wanted()/1048576) + "MB");
                } else {
                    log("❌ Nenhum torrent encontrado!");
                }
                
                log("🔍 Procurando arquivo de vídeo...");
                int tentativas = 0;
                while (downloading && videoFile == null && tentativas < 30) {
                    tentativas++;
                    File f = find(new File(savePath));
                    if (f != null && f.exists()) {
                        long size = f.length();
                        log("📁 Arquivo #" + tentativas + ": " + f.getName() + " (" + (size/1024) + "KB)");
                        log("   Path: " + f.getAbsolutePath());
                        log("   Existe: " + f.exists());
                        log("   Pode ler: " + f.canRead());
                        log("   É arquivo: " + f.isFile());
                        
                        if (size > 50000) {
                            videoFile = f;
                            log("✅ Arquivo pronto para reprodução!");
                            log("📊 Tamanho: " + (size/1048576) + "MB");
                            handler.post(() -> {
                                btnWatch.setText("🎬 ASSISTIR (" + (size/1048576) + "MB)");
                                btnWatch.setVisibility(View.VISIBLE);
                                log("🟢 Botão ASSISTIR visível");
                            });
                            break;
                        } else {
                            log("⏳ Arquivo ainda pequeno (" + size + " bytes), aguardando...");
                        }
                    } else {
                        log("🔍 Tentativa " + tentativas + ": arquivo não encontrado");
                    }
                    Thread.sleep(2000);
                }
                
                if (videoFile == null) {
                    log("⚠️ Arquivo não encontrado após " + tentativas + " tentativas");
                }
                
            } catch (Exception e) {
                downloading = false;
                log("❌ EXCEÇÃO: " + e.getClass().getSimpleName());
                log("❌ Mensagem: " + e.getMessage());
            }
        }).start();
        
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (downloading && torrent != null && torrent.is_valid()) {
                    torrent_status ts = torrent.status();
                    statProgress.setText((int)(ts.getProgress() * 100) + "%");
                    long speed = ts.getDownload_rate();
                    statSpeed.setText(speed > 1048576 ? String.format("%.1f MB/s", speed/1048576.0) :
                        speed > 1024 ? String.format("%.1f KB/s", speed/1024.0) : speed + " B/s");
                    statPeers.setText(String.valueOf(ts.getNum_peers()));
                    bufferBar.setProgress((int)(ts.getProgress() * 100));
                }
                if (downloading) handler.postDelayed(this, 500);
            }
        });
    }
    
    private void watch() {
        log("═══════════════════════════");
        log("  TENTANDO REPRODUZIR");
        log("═══════════════════════════");
        
        if (videoFile == null) {
            log("❌ videoFile é NULL");
            return;
        }
        
        log("📁 Arquivo: " + videoFile.getAbsolutePath());
        log("📊 Existe: " + videoFile.exists());
        log("📊 Tamanho: " + videoFile.length() + " bytes");
        log("📊 Pode ler: " + videoFile.canRead());
        
        if (!videoFile.exists()) {
            log("❌ Arquivo não existe!");
            return;
        }
        
        // Verifica os primeiros bytes do arquivo
        try {
            FileInputStream fis = new FileInputStream(videoFile);
            byte[] header = new byte[16];
            int read = fis.read(header);
            fis.close();
            log("📊 Primeiros bytes: " + bytesToHex(header, read));
        } catch (Exception e) {
            log("❌ Erro ao ler header: " + e.getMessage());
        }
        
        log("🔗 Criando Intent...");
        try {
            Uri uri = Uri.fromFile(videoFile);
            log("🔗 URI: " + uri.toString());
            
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            log("🔗 Intent criada, abrindo...");
            startActivity(intent);
            log("✅ Intent enviada com sucesso!");
        } catch (Exception e) {
            log("❌ ERRO ao abrir: " + e.getClass().getSimpleName());
            log("❌ " + e.getMessage());
            Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString();
    }
    
    private void stop() {
        log("⏹️ Parando...");
        downloading = false;
        handler.removeCallbacksAndMessages(null);
        controlPanel.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
        btnWatch.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE);
        log("✅ Parado");
    }
    
    private File find(File dir) {
        log("🔍 Procurando em: " + dir.getAbsolutePath());
        File[] files = dir.listFiles();
        if (files == null) {
            log("❌ listFiles() retornou NULL");
            return null;
        }
        log("📊 " + files.length + " itens na pasta");
        for (File f : files) {
            if (f.isDirectory()) {
                File found = find(f);
                if (found != null) return found;
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".mp4") || n.endsWith(".mkv") || 
                    n.endsWith(".avi") || n.endsWith(".webm")) {
                    log("🎬 Encontrado: " + f.getName());
                    return f;
                }
            }
        }
        return null;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloading = false;
        if (session != null) session.stop();
        log("💀 App destruído");
    }
}
