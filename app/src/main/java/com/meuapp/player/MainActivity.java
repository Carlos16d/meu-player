package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView statusText, logText;
    private ProgressBar bufferBar, spinnerBar;
    private FrameLayout loadingOverlay;
    private EditText magnetInput;
    private Button btnPlay, btnStop, btnWatch;
    private ScrollView logScroll;
    
    private String savePath;
    private SessionManager session;
    private AtomicReference<torrent_handle> torrentRef = new AtomicReference<>();
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    
    private long fileLength = 0;
    private int numPieces = 0;
    private long pieceLength = 262144;
    private int lastPiece = -1;
    
    private StringBuilder fullLog = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

    private static final long TEMPO_REPRODUCAO_MAXIMO = 2000;
    private java.util.concurrent.ScheduledExecutorService timerParada;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        playerView = findViewById(R.id.player_view);
        statusText = findViewById(R.id.status_text);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        bufferBar = findViewById(R.id.buffer_bar);
        spinnerBar = findViewById(R.id.spinner_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        btnWatch = findViewById(R.id.btn_watch);
        
        playerView.post(() -> {
            int width = (int)(getResources().getDisplayMetrics().widthPixels * 0.92);
            int height = (int)(width * 9.0 / 16.0);
            ViewGroup.LayoutParams p = playerView.getLayoutParams();
            p.width = width;
            p.height = height;
            playerView.setLayoutParams(p);
        });
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setVisibility(View.GONE);
        
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loadingOverlay.setVisibility(View.GONE);
                    spinnerBar.setVisibility(View.GONE);
                    iniciarContadorParada();
                } else if (state == Player.STATE_BUFFERING) {
                    loadingOverlay.setVisibility(View.VISIBLE);
                    spinnerBar.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                log("❌ Erro: " + error.getMessage());
                if (error.getCause() instanceof java.io.EOFException) {
                    log("⚠️ Dados incompletos, parando reprodução");
                    pararReproducao();
                }
            }
        });
        
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (downloading && player != null && player.getDuration() > 0) {
                    atualizarPrioridadePecas();
                }
                if (downloading) handler.postDelayed(this, 1000);
            }
        }, 1000);
        
        new Thread(() -> {
            try { 
                session = new SessionManager(); 
                session.start(); 
                log("✅ Sessão iniciada"); 
            } 
            catch (Exception e) { log("❌ Erro sessão: " + e.getMessage()); }
        }).start();
        
        startServer();
        
        btnPlay.setOnClickListener(v -> iniciarDownload());
        btnStop.setOnClickListener(v -> pararTudo());
        btnWatch.setOnClickListener(v -> assistirVideo());
        
        log("📱 Pronto para usar");
    }
    
    private void iniciarContadorParada() {
        if (timerParada != null) timerParada.shutdown();
        timerParada = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        timerParada.schedule(() -> runOnUiThread(this::pararReproducao), 
                             TEMPO_REPRODUCAO_MAXIMO, 
                             java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void pararReproducao() {
        if (player != null) {
            player.pause();
            player.stop();
            player.clearMediaItems();
        }
        if (timerParada != null) timerParada.shutdownNow();
        runOnUiThread(() -> {
            playerView.setVisibility(View.GONE);
            loadingOverlay.setVisibility(View.GONE);
            spinnerBar.setVisibility(View.GONE);
        });
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
    
    private void atualizarPrioridadePecas() {
        torrent_handle th = torrentRef.get();
        if (th == null || !th.is_valid() || numPieces <= 0 || fileLength <= 0) return;
        
        long posicaoAtual = player.getCurrentPosition();
        long duracaoTotal = player.getDuration();
        if (duracaoTotal <= 0 || posicaoAtual < 0) return;
        
        double percentual = (double) posicaoAtual / duracaoTotal;
        int pedacoAtual = (int) (percentual * numPieces);
        
        if (pedacoAtual >= numPieces) pedacoAtual = numPieces - 1;
        if (pedacoAtual < 0) pedacoAtual = 0;
        
        if (pedacoAtual != lastPiece) {
            lastPiece = pedacoAtual;
            int minutos = (int) (posicaoAtual / 60000);
            int segundos = (int) ((posicaoAtual % 60000) / 1000);
            log("🎯 Posição: " + minutos + ":" + String.format("%02d", segundos) + 
                " | Peça: " + pedacoAtual + "/" + numPieces);
        }
        
        try {
            byte_vector prioridades = new byte_vector();
            
            for (int i = 0; i < numPieces; i++) {
                if (i >= pedacoAtual - 5 && i <= pedacoAtual + 40) {
                    prioridades.add((byte) 7);
                } else if (i >= pedacoAtual - 15 && i <= pedacoAtual + 80) {
                    prioridades.add((byte) 5);
                } else if (i < 30) {
                    prioridades.add((byte) 3);
                } else {
                    prioridades.add((byte) 1);
                }
            }
            
            th.prioritize_pieces_ex(prioridades);
            
        } catch (Exception e) {
            Log.e("PRIORIDADE", "Erro ao definir prioridades", e);
        }
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                log("🌐 Servidor rodando em: http://127.0.0.1:8080/video");
                
                while (!Thread.interrupted()) {
                    try { 
                        Socket client = server.accept(); 
                        processarCliente(client); 
                    } catch (IOException e) {
                        Log.e("SERVIDOR", "Erro na conexão", e);
                    }
                }
                server.close();
            } catch (IOException e) {
                log("❌ Servidor: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void processarCliente(Socket client) {
        try {
            OutputStream saida = client.getOutputStream();
            BufferedReader entrada = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String requisicao = entrada.readLine();
            if (requisicao == null || !requisicao.contains("/video")) {
                saida.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                saida.flush(); 
                client.close(); 
                return;
            }
            
            long inicioDados = 0;
            long fimDados = -1;
            String linha;
            
            while ((linha = entrada.readLine()) != null && !linha.isEmpty()) {
                if (linha.toLowerCase().startsWith("range:")) {
                    String range = linha.substring(6).trim().replace("bytes=", "");
                    String[] partes = range.split("-");
                    
                    try {
                        inicioDados = Long.parseLong(partes[0]);
                        if (partes.length > 1 && !partes[1].isEmpty()) {
                            fimDados = Long.parseLong(partes[1]);
                        }
                    } catch (NumberFormatException e) {
                        saida.write("HTTP/1.1 400 Formato inválido\r\n\r\n".getBytes());
                        saida.flush(); 
                        client.close(); 
                        return;
                    }
                }
            }
            
            if (videoFile == null || !videoFile.exists()) {
                saida.write("HTTP/1.1 503 Aguardando arquivo...\r\nRetry-After: 1\r\n\r\n".getBytes());
                saida.flush(); 
                client.close(); 
                log("📤 Cliente aguardando arquivo");
                return;
            }
            
            long tamanhoArquivo = videoFile.length();
            if (tamanhoArquivo < 4096) {
                saida.write("HTTP/1.1 503 Arquivo muito pequeno\r\n\r\n".getBytes());
                saida.flush(); 
                client.close(); 
                return;
            }
            
            if (fimDados == -1 || fimDados >= tamanhoArquivo) fimDados = tamanhoArquivo - 1;
            if (inicioDados >= tamanhoArquivo) {
                saida.write("HTTP/1.1 416 Posição inválida\r\n\r\n".getBytes());
                saida.flush(); 
                client.close(); 
                return;
            }
            
            long quantidadeDados = fimDados - inicioDados + 1;
            if (quantidadeDados > 256 * 1024) {
                fimDados = inicioDados + 256 * 1024 - 1;
            }
            
            boolean dadosProntos = verificarDadosProntos(inicioDados, fimDados);
            if (!dadosProntos) {
                saida.write("HTTP/1.1 503 Dados sendo baixados...\r\nRetry-After: 1\r\n\r\n".getBytes());
                saida.flush();
                client.close();
                log("📤 Aguardando dados: " + inicioDados + " até " + fimDados);
                return;
            }
            
            String tipoMime = videoFile.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            byte[] buffer = new byte[(int) (fimDados - inicioDados + 1)];
            
            RandomAccessFile arquivo = new RandomAccessFile(videoFile, "r");
            arquivo.seek(inicioDados);
            int totalLido = arquivo.read(buffer);
            arquivo.close();
            
            if (totalLido <= 0) {
                saida.write("HTTP/1.1 503 Não foi possível ler os dados\r\n\r\n".getBytes());
                saida.flush();
                client.close();
                return;
            }
            
            String resposta = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: " + tipoMime + "\r\n" +
                    "Content-Range: bytes " + inicioDados + "-" + (inicioDados + totalLido - 1) + "/" + tamanhoArquivo + "\r\n" +
                    "Content-Length: " + totalLido + "\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "\r\n";
            
            saida.write(resposta.getBytes());
            saida.write(buffer, 0, totalLido);
            saida.flush();
            
            log("📤 Enviado: " + totalLido + " bytes | " + inicioDados + " até " + (inicioDados + totalLido - 1));
            
            client.close();
            
        } catch (Exception e) {
            Log.e("SERVIDOR", "Erro ao processar requisição", e);
            try { client.close(); } catch (IOException ex) {}
        }
    }

    private boolean verificarDadosProntos(long inicio, long fim) {
        if (numPieces <= 0 || pieceLength <= 0) return false;
        
        int pedacoInicio = (int) (inicio / pieceLength);
        int pedacoFim = (int) (fim / pieceLength);
        
        try {
            torrent_handle th = torrentRef.get();
            if (th == null || !th.is_valid()) return false;
            
            torrent_status status = th.status();
            byte_vector pecasDisponiveis = status.get_pieces();
            
            for (int i = pedacoInicio; i <= pedacoFim; i++) {
                if (i < 0 || i >= pecasDisponiveis.size()) return false;
                if (pecasDisponiveis.get(i) != (byte) 1) {
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            Log.e("VERIFICAR_DADOS", "Erro ao verificar peças", e);
            return false;
        }
    }
    
    private void iniciarDownload() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:") || downloading) return;
        
        downloading = true;
        videoFile = null;
        numPieces = 0;
        lastPiece = -1;
        torrentRef.set(null);
        
        handler.post(() -> {
            btnStop.setVisibility(View.VISIBLE);
            bufferBar.setVisibility(View.VISIBLE);
            btnWatch.setVisibility(View.GONE);
        });
        
        log("⏳ Baixando...");
        
        new Thread(() -> {
            try {
                error_code ec = new error_code();
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, ec);
                if (ec.value() != 0) {
                    log("❌ Erro magnet: " + ec.message());
                    return;
                }
                
                p.setSave_path(savePath);
                p.setFlags(torrent_flags_t.from_int(0));
                p.setDownload_limit(3 * 1024 * 1024);
                
                byte_vector pr = new byte_vector();
                for (int i = 0; i < 1000; i++) pr.add((byte) 7);
                p.set_file_priorities(pr);
                
                session.swig().async_add_torrent(p);
                Thread.sleep(3000);
                
                torrent_handle_vector h = session.swig().get_torrents();
                if (h.size() > 0) {
                    torrent_handle th = h.get(0);
                    torrentRef.set(th);
                    torrent_status ts = th.status();
                    fileLength = ts.getTotal_wanted();
                    
                    if (fileLength > 0) {
                        pieceLength = ts.get_piece_length();
                        numPieces = ts.get_num_pieces();
                        log("📊 " + (fileLength/1048576) + "MB | ~" + numPieces + " peças");
                    }
                }
                
                for (int