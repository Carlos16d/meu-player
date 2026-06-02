package com.meuapp.player;

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
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends AppCompatActivity {
    private VideoView videoView;
    private LinearLayout loadingOverlay, controlPanel, statsRow;
    private TextView loadingTitle, loadingProgress, loadingSpeed, loadingPeers, loadingStatus;
    private TextView statProgress, statSpeed, statPeers;
    private ProgressBar bufferBar;
    private EditText magnetInput;
    private Button btnPlay, btnStop;
    
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private boolean downloading = false;
    private StreamServer streamServer;
    private File videoFile = null;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statsUpdater;
    private Runnable fileWatcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoView = findViewById(R.id.video_view);
        loadingOverlay = findViewById(R.id.loading_overlay);
        controlPanel = findViewById(R.id.control_panel);
        statsRow = findViewById(R.id.stats_row);
        loadingTitle = findViewById(R.id.loading_title);
        loadingProgress = findViewById(R.id.loading_progress);
        loadingSpeed = findViewById(R.id.loading_speed);
        loadingPeers = findViewById(R.id.loading_peers);
        loadingStatus = findViewById(R.id.loading_status);
        statProgress = findViewById(R.id.stat_progress);
        statSpeed = findViewById(R.id.stat_speed);
        statPeers = findViewById(R.id.stat_peers);
        bufferBar = findViewById(R.id.buffer_bar);
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        try {
            session = new SessionManager();
            session.start();
            
            streamServer = new StreamServer(8080);
            streamServer.start();
            
            Toast.makeText(this, "UDP + Streaming OK!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        
        btnPlay.setOnClickListener(v -> startStream());
        btnStop.setOnClickListener(v -> stopStream());
    }
    
    private void startStream() {
        String magnet = magnetInput.getText().toString().trim();
        if (!magnet.startsWith("magnet:")) {
            Toast.makeText(this, "Cole um magnet link!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (downloading) return;
        downloading = true;
        videoFile = null;
        
        loadingOverlay.setVisibility(View.VISIBLE);
        videoView.setVisibility(View.VISIBLE);
        controlPanel.setVisibility(View.GONE);
        statsRow.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        
        loadingTitle.setText("Conectando...");
        loadingStatus.setText("Iniciando trackers UDP...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                
                string_vector trackers = new string_vector();
                trackers.add("udp://tracker.opentrackr.org:1337/announce");
                trackers.add("udp://tracker.openbittorrent.com:6969/announce");
                p.setTrackers(trackers);
                
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(3 * 1024 * 1024);
                p.setMax_connections(200);
                p.setMax_uploads(10);
                
                byte_vector priorities = new byte_vector();
                priorities.add((byte)7);
                p.set_file_priorities(priorities);
                
                session.swig().async_add_torrent(p);
                
                Thread.sleep(3000);
                
                torrent_handle_vector handles = session.swig().get_torrents();
                if (handles.size() > 0) {
                    torrent = handles.get(0);
                }
                
                // Espera arquivo e conecta ao servidor HTTP
                handler.post(() -> startFileWatcher());
                
            } catch (Exception e) {
                downloading = false;
            }
        }).start();
        
        statsUpdater = new Runnable() {
            @Override
            public void run() {
                if (torrent != null && torrent.is_valid()) {
                    int prog = (int)(torrent.status().getProgress() * 100);
                    long speed = torrent.status().getDownload_rate();
                    int peers = torrent.status().getNum_peers();
                    
                    String speedStr = speed > 1048576 ? 
                        String.format("%.1f MB/s", speed / 1048576.0) :
                        speed > 1024 ? String.format("%.1f KB/s", speed / 1024.0) :
                        speed + " B/s";
                    
                    statProgress.setText(prog + "%");
                    statSpeed.setText(speedStr);
                    statPeers.setText(String.valueOf(peers));
                    
                    loadingProgress.setText(prog + "%");
                    loadingSpeed.setText(speedStr);
                    loadingPeers.setText(peers + " peers");
                    bufferBar.setProgress(prog);
                    
                    if (prog < 1) loadingStatus.setText("Conectando peers UDP...");
                    else if (prog < 5) loadingStatus.setText("Baixando metadados...");
                    else loadingStatus.setText("Download em andamento...");
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(statsUpdater);
    }
    
    private void startFileWatcher() {
        fileWatcher = new Runnable() {
            @Override
            public void run() {
                if (!downloading) return;
                
                if (videoFile == null) {
                    videoFile = findVideoFile(new File(savePath));
                }
                
                if (videoFile != null && videoFile.exists() && videoFile.length() > 50000) {
                    handler.post(() -> {
                        videoView.setVideoURI(Uri.parse("http://127.0.0.1:8080/video"));
                        videoView.start();
                        loadingOverlay.setVisibility(View.GONE);
                    });
                    return;
                }
                
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(fileWatcher);
    }
    
    private void stopStream() {
        downloading = false;
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE);
        handler.removeCallbacks(statsUpdater);
        handler.removeCallbacks(fileWatcher);
        loadingOverlay.setVisibility(View.GONE);
        controlPanel.setVisibility(View.VISIBLE);
        statsRow.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE);
    }
    
    // Servidor HTTP ultra simples - máximo 128KB por resposta
    class StreamServer extends NanoHTTPD {
        public StreamServer(int port) { super(port); }
        
        @Override
        public Response serve(IHTTPSession ses) {
            if (!"/video".equals(ses.getUri())) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
            }
            
            try {
                if (videoFile == null || !videoFile.exists()) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Aguardando arquivo...");
                }
                
                long fileLength = videoFile.length();
                
                // Range request
                Map<String, String> headers = ses.getHeaders();
                String range = headers.get("range");
                
                long start = 0;
                long end = fileLength - 1;
                
                if (range != null) {
                    String[] parts = range.replace("bytes=", "").split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    } else {
                        end = start + 131072; // 128KB máximo
                    }
                } else {
                    end = Math.min(131072, fileLength - 1); // 128KB máximo
                }
                
                // Limita a 128KB
                if (end - start > 131072) {
                    end = start + 131072;
                }
                
                if (start >= fileLength) {
                    return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Fora do alcance");
                }
                if (end >= fileLength) end = fileLength - 1;
                
                // Lê apenas o pedaço necessário
                int length = (int)(end - start + 1);
                byte[] data = new byte[length];
                
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                raf.seek(start);
                int read = raf.read(data);
                raf.close();
                
                if (read <= 0) {
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Aguardando dados...");
                }
                
                // Se leu menos, ajusta
                if (read < length) {
                    byte[] trimmed = new byte[read];
                    System.arraycopy(data, 0, trimmed, 0, read);
                    data = trimmed;
                    length = read;
                }
                
                Response resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT,
                    "video/mp4", new ByteArrayInputStream(data), length);
                resp.addHeader("Content-Range", "bytes " + start + "-" + (start + length - 1) + "/" + fileLength);
                resp.addHeader("Accept-Ranges", "bytes");
                resp.addHeader("Access-Control-Allow-Origin", "*");
                resp.addHeader("Connection", "close");
                return resp;
                
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Erro");
            }
        }
    }
    
    private File findVideoFile(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findVideoFile(f);
                    if (found != null) return found;
                } else {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".webm")) {
                        return f;
                    }
                }
            }
        }
        return null;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloading = false;
        handler.removeCallbacks(statsUpdater);
        handler.removeCallbacks(fileWatcher);
        if (streamServer != null) streamServer.stop();
        if (session != null) session.stop();
    }
}
