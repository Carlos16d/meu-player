package com.meuapp.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.swig.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends AppCompatActivity {
    private SurfaceView videoSurface;
    private SurfaceHolder surfaceHolder;
    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;
    private TextView statusText, debugText, timeText;
    private ProgressBar bufferBar, spinnerBar;
    private EditText magnetInput;
    private Button btnPlay, btnTorrent, btnStop, btnWatch, btnSkip20;
    private LinearLayout playerControls, centerControls, audioMenu, subtitleMenu;
    private ScrollView audioScroll, subtitleScroll;
    private Button btnPlayPause, btnSeekBack, btnSeekForward, btnAudio, btnSubtitle;
    private SeekBar seekBar;
    private boolean isTracking = false;
    
    private String savePath;
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private volatile boolean downloading;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread;
    private boolean surfaceReady = false, isPlaying = false, vlcPreparing = false;
    private String pendingUrl = null;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private StringBuilder debugLog = new StringBuilder();
    private static final int PICK_TORRENT = 100;
    private Runnable timeUpdater;
    private int pieceLength = 0, numPieces = 0;
    private long totalSize = 0;
    private long videoDurationMs = 0;
    private long lastMinuteLog = -1;
    
    private int currentPiece = 0;
    private boolean seeking = false;
    
    // Dados do SeekHead
    private long cuesPosition = -1;
    private long cuesSize = -1;
    private long tracksPosition = -1;
    
    private final Object torrentLock = new Object();
    private ExecutorService executor = Executors.newFixedThreadPool(4);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        try {
            videoSurface = findViewById(R.id.video_surface);
            statusText = findViewById(R.id.status_text);
            debugText = findViewById(R.id.debug_text);
            timeText = findViewById(R.id.time_text);
            bufferBar = findViewById(R.id.buffer_bar);
            spinnerBar = findViewById(R.id.spinner_bar);
            magnetInput = findViewById(R.id.magnet_input);
            btnPlay = findViewById(R.id.btn_play);
            btnTorrent = findViewById(R.id.btn_torrent);
            btnStop = findViewById(R.id.btn_stop);
            btnWatch = findViewById(R.id.btn_watch);
            btnSkip20 = findViewById(R.id.btn_skip_20);
            playerControls = findViewById(R.id.player_controls);
            centerControls = findViewById(R.id.center_controls);
            btnPlayPause = findViewById(R.id.btn_play_pause);
            btnSeekBack = findViewById(R.id.btn_seek_back);
            btnSeekForward = findViewById(R.id.btn_seek_forward);
            btnAudio = findViewById(R.id.btn_audio);
            btnSubtitle = findViewById(R.id.btn_subtitle);
            seekBar = findViewById(R.id.seek_bar);
            audioScroll = findViewById(R.id.audio_scroll);
            subtitleScroll = findViewById(R.id.subtitle_scroll);
            audioMenu = findViewById(R.id.audio_menu);
            subtitleMenu = findViewById(R.id.subtitle_menu);
        } catch (Exception e) {
            Log.e("TS", "Erro views: " + e.getMessage());
            finish();
            return;
        }
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        try {
            ArrayList<String> options = new ArrayList<>();
            options.add("--network-caching=2000");
            options.add("--file-caching=1000");
            options.add("--no-drop-late-frames");
            libVLC = new LibVLC(this, options);
            vlcPlayer = new MediaPlayer(libVLC);
            
            vlcPlayer.setEventListener(event -> {
                try {
                    switch (event.type) {
                        case MediaPlayer.Event.Playing: isPlaying = true; vlcPreparing = false; handler.post(() -> { spinnerBar.setVisibility(View.GONE); btnPlayPause.setText("⏸"); }); break;
                        case MediaPlayer.Event.Paused: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                        case MediaPlayer.Event.Stopped: isPlaying = false; vlcPreparing = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                        case MediaPlayer.Event.Buffering: handler.post(() -> spinnerBar.setVisibility(View.VISIBLE)); break;
                        case MediaPlayer.Event.EndReached: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                    }
                } catch (Exception e) {}
            });
        } catch (Exception e) {
            Log.e("TS", "Erro VLC: " + e.getMessage());
            vlcPlayer = null;
        }
        
        timeUpdater = () -> {
            if (vlcPlayer != null && isPlaying && !vlcPreparing) {
                try {
                    long time = vlcPlayer.getTime();
                    long length = vlcPlayer.getLength();
                    if (length > 0) {
                        videoDurationMs = length;
                        if (time >= 0) {
                            timeText.setText(formatTime(time) + " / " + formatTime(length));
                            if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                            if (!seeking) {
                                int p = pieceFromTime(time);
                                if (p != currentPiece && p >= 0) { currentPiece = p; prioritizeRange(p, p + 10); }
                            }
                            long min = time / 60000;
                            if (min != lastMinuteLog) { lastMinuteLog = min; logMinute(min); }
                        }
                    }
                } catch (Exception e) {}
            }
            handler.postDelayed(timeUpdater, 2000);
        };
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { surfaceHolder = h; surfaceReady = true; if (pendingUrl != null) { String url = pendingUrl; pendingUrl = null; playWithVlc(url); } }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; surfaceHolder = null; }
        });
        
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer != null && !vlcPreparing) { try { if (isPlaying) vlcPlayer.pause(); else vlcPlayer.play(); } catch (Exception e) {} } });
        btnSeekBack.setOnClickListener(v -> seekDelta(-10000));
        btnSeekForward.setOnClickListener(v -> seekDelta(10000));
        btnSkip20.setOnClickListener(v -> seekTo(20 * 60 * 1000));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && vlcPlayer.getLength() > 0) seekTo(vlcPlayer.getLength() * p / 100); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        debug("=== TORRENT STREAM v11 - SEEKHEAD REAL ===");
        
        executor.execute(() -> { try { session = new SessionManager(); session.start(); debug("✅ LibTorrent OK"); } catch (Exception e) { debug("❌ " + e.getMessage()); } });
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { try { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); } catch (Exception e) {} });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
    }
    
    // ==================== PARSER EBML/SEEKHEAD REAL ====================
    private void parseSeekHead() {
        if (videoFile == null || !videoFile.exists()) return;
        
        try {
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            byte[] header = new byte[Math.min(131072, (int)raf.length())]; // 128KB
            raf.read(header);
            raf.close();
            
            // Procurar SeekHead (ID EBML: 0x114D9B74)
            int seekHeadOffset = findEBMLId(header, new byte[]{(byte)0x11, (byte)0x4D, (byte)0x9B, (byte)0x74});
            
            if (seekHeadOffset >= 0) {
                debug("🔍 SeekHead encontrado no offset " + seekHeadOffset);
                
                // Dentro do SeekHead, procurar Seek (ID: 0x4DBB)
                int searchPos = seekHeadOffset + 4; // Pular ID do SeekHead
                long seekHeadEnd = seekHeadOffset + 4 + readEBMLSize(header, seekHeadOffset + 4);
                
                while (searchPos < seekHeadEnd && searchPos < header.length - 8) {
                    // Procurar ID de Seek (0x4DBB)
                    if (header[searchPos] == (byte)0x4D && header[searchPos+1] == (byte)0xBB) {
                        int seekEntryStart = searchPos;
                        searchPos += 2; // Pular ID
                        long seekEntrySize = readEBMLSize(header, searchPos);
                        searchPos += getEBMLSizeLength(header, searchPos);
                        
                        long seekEntryEnd = searchPos + seekEntrySize;
                        
                        // Dentro do Seek, procurar SeekID e SeekPosition
                        long elementId = -1;
                        long elementPos = -1;
                        
                        int innerPos = searchPos;
                        while (innerPos < seekEntryEnd && innerPos < header.length - 8) {
                            // SeekID (0x53AB)
                            if (header[innerPos] == (byte)0x53 && header[innerPos+1] == (byte)0xAB) {
                                innerPos += 2;
                                long idSize = readEBMLSize(header, innerPos);
                                innerPos += getEBMLSizeLength(header, innerPos);
                                elementId = readEBMLId(header, innerPos, (int)idSize);
                                innerPos += idSize;
                            }
                            // SeekPosition (0x53AC)
                            else if (header[innerPos] == (byte)0x53 && header[innerPos+1] == (byte)0xAC) {
                                innerPos += 2;
                                long posSize = readEBMLSize(header, innerPos);
                                innerPos += getEBMLSizeLength(header, innerPos);
                                elementPos = readUInt(header, innerPos, (int)posSize);
                                innerPos += posSize;
                            } else {
                                innerPos++;
                            }
                        }
                        
                        // Identificar elemento pela ID
                        if (elementId > 0 && elementPos > 0) {
                            String elemName = getEBMLElementName(elementId);
                            debug("   📍 " + elemName + " → posição " + elementPos + " (ID: 0x" + Long.toHexString(elementId) + ")");
                            
                            if (elemName.equals("Cues")) {
                                cuesPosition = elementPos;
                            } else if (elemName.equals("Tracks")) {
                                tracksPosition = elementPos;
                            }
                        }
                        
                        searchPos = (int)seekEntryEnd;
                    } else {
                        searchPos++;
                    }
                }
                
                // Calcular tamanho das Cues baseado no próximo elemento
                if (cuesPosition > 0) {
                    // Estimar: Cues vão até o final do arquivo ou próximo elemento
                    // Vamos verificar no final do arquivo
                    raf = new RandomAccessFile(videoFile, "r");
                    long fileLen = raf.length();
                    
                    // Ler últimos 64KB para ver onde terminam as Cues
                    raf.seek(Math.max(0, fileLen - 65536));
                    byte[] tail = new byte[(int)Math.min(65536, fileLen)];
                    raf.read(tail);
                    raf.close();
                    
                    // Procurar por Tags (0x1254C367) ou Chapters depois das Cues
                    int tagsIdx = findEBMLId(tail, new byte[]{(byte)0x12, (byte)0x54, (byte)0xC3, (byte)0x67});
                    int chaptersIdx = findEBMLId(tail, new byte[]{(byte)0x10, (byte)0x43, (byte)0xA7, (byte)0x70});
                    
                    if (tagsIdx >= 0) {
                        cuesSize = fileLen - 65536 + tagsIdx - cuesPosition;
                    } else if (chaptersIdx >= 0) {
                        cuesSize = fileLen - 65536 + chaptersIdx - cuesPosition;
                    } else {
                        cuesSize = fileLen - cuesPosition; // Até o final do arquivo
                    }
                    
                    debug("   📏 Cues: tamanho estimado " + (cuesSize/1024) + "KB (" + cuesSize + " bytes)");
                    
                    // Calcular peças necessárias para as Cues
                    long cuesEndByte = cuesPosition + cuesSize;
                    int startPiece = (int)(cuesPosition / pieceLength);
                    int endPiece = (int)(cuesEndByte / pieceLength);
                    debug("   📦 Cues: peças " + startPiece + " a " + endPiece + " (" + (endPiece - startPiece + 1) + " peças)");
                }
            } else {
                debug("⚠️ SeekHead não encontrado no cabeçalho");
            }
        } catch (Exception e) {
            debug("❌ Erro parse: " + e.getMessage());
        }
    }
    
    private int findEBMLId(byte[] data, byte[] id) {
        for (int i = 0; i < data.length - id.length; i++) {
            boolean match = true;
            for (int j = 0; j < id.length; j++) {
                if (data[i+j] != id[j]) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }
    
    private long readEBMLSize(byte[] data, int offset) {
        if (offset >= data.length) return 0;
        int firstByte = data[offset] & 0xFF;
        int mask = 0x80;
        int length = 0;
        
        for (int i = 0; i < 8; i++) {
            if ((firstByte & mask) != 0) {
                length = i + 1;
                break;
            }
            mask >>= 1;
        }
        
        if (length == 0 || offset + length > data.length) return 0;
        
        long size = firstByte & (0xFF >> length);
        for (int i = 1; i < length; i++) {
            size = (size << 8) | (data[offset + i] & 0xFF);
        }
        return size;
    }
    
    private int getEBMLSizeLength(byte[] data, int offset) {
        if (offset >= data.length) return 0;
        int firstByte = data[offset] & 0xFF;
        int mask = 0x80;
        for (int i = 0; i < 8; i++) {
            if ((firstByte & mask) != 0) return i + 1;
            mask >>= 1;
        }
        return 1;
    }
    
    private long readEBMLId(byte[] data, int offset, int size) {
        long id = 0;
        for (int i = 0; i < size && offset + i < data.length; i++) {
            id = (id << 8) | (data[offset + i] & 0xFF);
        }
        return id;
    }
    
    private long readUInt(byte[] data, int offset, int size) {
        long val = 0;
        for (int i = 0; i < size && offset + i < data.length; i++) {
            val = (val << 8) | (data[offset + i] & 0xFF);
        }
        return val;
    }
    
    private String getEBMLElementName(long id) {
        if (id == 0x1C53BB6B) return "Cues";
        if (id == 0x1654AE6B) return "Tracks";
        if (id == 0x1254C367) return "Tags";
        if (id == 0x1043A770) return "Chapters";
        if (id == 0x1549A966) return "Info";
        if (id == 0x1941A469) return "Attachments";
        if (id == 0x114D9B74) return "SeekHead";
        if (id == 0x1F43B675) return "Cluster";
        return "Unknown(0x" + Long.toHexString(id) + ")";
    }
    
    // ==================== SERVIDOR HTTP ====================
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket(8080);
                ss.setSoTimeout(1000);
                while (!Thread.interrupted()) {
                    try { Socket client = ss.accept(); executor.execute(() -> handleHttp(client)); } 
                    catch (SocketTimeoutException e) {} catch (IOException e) {}
                }
                ss.close();
            } catch (IOException e) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();
        debug("🌐 HTTP :8080");
    }
    
    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(10000);
            OutputStream out = client.getOutputStream();
            InputStream in = client.getInputStream();
            
            StringBuilder reqBuilder = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                reqBuilder.append((char) b);
                if (reqBuilder.toString().endsWith("\r\n\r\n") || reqBuilder.length() > 8192) break;
            }
            String req = reqBuilder.toString();
            
            if (!req.contains("/video")) {
                out.write("HTTP/1.1 404\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long rangeStart = 0;
            boolean hasRange = false;
            for (String line : req.split("\r\n")) {
                if (line.toLowerCase().startsWith("range: bytes=")) {
                    hasRange = true;
                    rangeStart = Long.parseLong(line.substring(13).trim().split("-")[0]);
                    break;
                }
            }
            
            if (videoFile == null || !videoFile.exists()) {
                out.write("HTTP/1.1 503\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                out.flush(); client.close(); return;
            }
            
            long fileSize = videoFile.length();
            String mime = videoFile.getName().toLowerCase().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            
            if (!hasRange) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + fileSize + "\r\nAccept-Ranges: bytes\r\nConnection: keep-alive\r\n\r\n").getBytes());
                out.flush();
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                byte[] buf = new byte[65536];
                int read, sent = 0;
                while (sent < 2097152 && (read = raf.read(buf)) != -1) { out.write(buf, 0, read); sent += read; }
                raf.close();
                out.flush(); client.close();
                return;
            }
            
            long rangeEnd = Math.min(fileSize - 1, rangeStart + 524287);
            long contentLength = rangeEnd - rangeStart + 1;
            
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: " + mime + "\r\nContent-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + fileSize + "\r\nContent-Length: " + contentLength + "\r\nAccept-Ranges: bytes\r\nConnection: keep-alive\r\n\r\n").getBytes());
            out.flush();
            
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            if (rangeStart < raf.length()) {
                raf.seek(rangeStart);
                long available = raf.length() - rangeStart;
                int toRead = (int) Math.min(contentLength, available);
                if (toRead > 0) {
                    byte[] buf = new byte[toRead];
                    int read = raf.read(buf);
                    if (read > 0) out.write(buf, 0, read);
                }
            }
            raf.close();
            out.flush(); client.close();
        } catch (Exception e) { try { client.close(); } catch (Exception ex) {} }
    }
    
    // ==================== PRIORIDADES ====================
    private int pieceFromTime(long timeMs) {
        if (pieceLength <= 0 || videoFile == null || videoDurationMs <= 0) return -1;
        try { long bytePos = timeMs * videoFile.length() / videoDurationMs; return (int)(bytePos / pieceLength); } catch (Exception e) { return -1; }
    }
    
    private void prioritizeRange(int startPiece, int endPiece) {
        final int start = startPiece;
        final int end = Math.min(numPieces - 1, endPiece);
        executor.execute(() -> {
            synchronized (torrentLock) {
                if (torrentHandle == null || !torrentHandle.isValid() || seeking) return;
                try {
                    for (int i = 0; i < numPieces; i++) {
                        byte p = (i >= start && i <= end) ? (byte)7 : (byte)0;
                        torrentHandle.swig().piece_priority_ex(i, p);
                    }
                    for (int i = start; i <= end; i++) {
                        if (!torrentHandle.havePiece(i)) torrentHandle.swig().set_piece_deadline(i, 8000);
                    }
                } catch (Exception e) {}
            }
        });
    }
    
    private void seekTo(long timeMs) {
        if (vlcPlayer == null) return;
        try { vlcPlayer.setTime(timeMs); } catch (Exception e) { return; }
        if (pieceLength <= 0 || videoFile == null || videoDurationMs <= 0) return;
        
        final int piece = pieceFromTime(timeMs);
        if (piece < 0) return;
        currentPiece = piece;
        seeking = true;
        
        final long min = timeMs / 60000;
        final long sec = (timeMs / 1000) % 60;
        debug("🔥 Seek: " + min + ":" + String.format("%02d", sec) + " → peça " + piece);
        
        executor.execute(() -> {
            synchronized (torrentLock) {
                if (torrentHandle == null || !torrentHandle.isValid()) { seeking = false; return; }
                try {
                    if (torrentHandle.havePiece(piece)) {
                        handler.post(() -> { debug("✅ Já existe"); seeking = false; spinnerBar.setVisibility(View.GONE); });
                        return;
                    }
                    byte_vector z = new byte_vector();
                    for (int i = 0; i < numPieces; i++) z.add((byte)0);
                    torrentHandle.swig().prioritize_pieces_ex(z);
                    
                    final int seekEnd = Math.min(numPieces - 1, piece + 15);
                    for (int i = piece; i <= seekEnd; i++) {
                        byte prio = (i == piece) ? (byte)7 : (i <= piece + 3) ? (byte)6 : (byte)4;
                        torrentHandle.swig().piece_priority_ex(i, prio);
                        torrentHandle.swig().set_piece_deadline(i, (i == piece) ? 3000 : 8000);
                    }
                    
                    int waits = 0;
                    while (seeking && downloading && waits < 40) {
                        Thread.sleep(250); waits++;
                        if (torrentHandle.havePiece(piece)) {
                            final double elapsedSecs = waits / 4.0;
                            handler.post(() -> { debug("✅ OK em " + elapsedSecs + "s"); seeking = false; spinnerBar.setVisibility(View.GONE); });
                            return;
                        }
                        if (waits % 4 == 0) torrentHandle.swig().set_piece_deadline(piece, 3000);
                    }
                    handler.post(() -> { if (seeking) debug("⏰ Timeout"); seeking = false; spinnerBar.setVisibility(View.GONE); });
                } catch (Exception e) { seeking = false; }
            }
        });
    }
    
    private void seekDelta(long d) { if (vlcPlayer != null && vlcPlayer.getLength() > 0) { try { long t = Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + d)); seekTo(t); } catch (Exception e) {} } }
    
    private void logMinute(final long minute) {
        if (videoDurationMs <= 0 || pieceLength <= 0 || videoFile == null) return;
        executor.execute(() -> {
            synchronized (torrentLock) {
                if (torrentHandle == null || !torrentHandle.isValid()) return;
                try {
                    long bp = minute * 60 * 1000 * videoFile.length() / videoDurationMs;
                    int s = (int)(bp / pieceLength);
                    long be = (minute + 1) * 60 * 1000 * videoFile.length() / videoDurationMs;
                    int e = (int)(be / pieceLength);
                    int have = 0;
                    for (int i = s; i <= e && i < numPieces; i++) if (torrentHandle.havePiece(i)) have++;
                    int tot = e - s + 1, pct = tot > 0 ? have * 100 / tot : 0;
                    if (pct < 100 || minute % 5 == 0) {
                        final String msg = "⏱ Min " + minute + ": " + have + "/" + tot + " (" + pct + "%)";
                        handler.post(() -> debug(msg));
                    }
                } catch (Exception e) {}
            }
        });
    }
    
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms / 1000); return (s/60) + ":" + String.format("%02d", s%60); }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        try {
            MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
            int cur = vlcPlayer.getAudioTrack();
            audioMenu.removeAllViews();
            debug("🎵 Áudios: " + (tracks != null ? tracks.length : 0));
            if (tracks != null) for (MediaPlayer.TrackDescription t : tracks) {
                if (t.id >= 0) {
                    TextView tv = new TextView(this); tv.setText("🎵 " + t.name + (t.id == cur ? " ✓" : ""));
                    tv.setTextColor(t.id == cur ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                    final int id = t.id; tv.setOnClickListener(v -> { try { vlcPlayer.setAudioTrack(id); } catch (Exception e) {} audioScroll.setVisibility(View.GONE); });
                    audioMenu.addView(tv);
                }
            }
        } catch (Exception e) {}
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (vlcPlayer == null) return;
        try {
            MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
            int cur = vlcPlayer.getSpuTrack();
            subtitleMenu.removeAllViews();
            debug("📝 Legendas: " + (tracks != null ? tracks.length : 0));
            TextView off = new TextView(this); off.setText("📝 Desligado" + (cur == -1 ? " ✓" : ""));
            off.setTextColor(cur == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); off.setTextSize(12); off.setPadding(16, 12, 16, 12);
            off.setOnClickListener(v -> { try { vlcPlayer.setSpuTrack(-1); } catch (Exception e) {} subtitleScroll.setVisibility(View.GONE); });
            subtitleMenu.addView(off);
            if (tracks != null) for (MediaPlayer.TrackDescription t : tracks) {
                if (t.id >= 0) {
                    TextView tv = new TextView(this); tv.setText("📝 " + t.name + (t.id == cur ? " ✓" : ""));
                    tv.setTextColor(t.id == cur ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                    final int id = t.id; tv.setOnClickListener(v -> { try { vlcPlayer.setSpuTrack(id); } catch (Exception e) {} subtitleScroll.setVisibility(View.GONE); });
                    subtitleMenu.addView(tv);
                }
            }
        } catch (Exception e) {}
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        audioScroll.setVisibility(View.GONE);
    }
    
    private void playWithVlc(String url) {
        if (vlcPlayer == null) { debug("❌ VLC não inicializado"); return; }
        if (!surfaceReady || surfaceHolder == null) { pendingUrl = url; return; }
        try {
            vlcPreparing = true;
            vlcPlayer.getVLCVout().setVideoSurface(surfaceHolder.getSurface(), null);
            vlcPlayer.getVLCVout().setWindowSize(videoSurface.getWidth(), videoSurface.getHeight());
            vlcPlayer.getVLCVout().attachViews();
            Media m = new Media(libVLC, Uri.parse(url));
            m.setHWDecoderEnabled(true, true);
            m.addOption(":network-caching=2000");
            m.addOption(":file-caching=1000");
            vlcPlayer.setMedia(m); m.release();
            vlcPlayer.play();
            handler.post(() -> { playerControls.setVisibility(View.VISIBLE); centerControls.setVisibility(View.VISIBLE); btnSkip20.setVisibility(View.VISIBLE); });
        } catch (Exception e) { vlcPreparing = false; debug("❌ VLC: " + e.getMessage()); }
    }
    
    @Override protected void onActivityResult(int r, int res, Intent data) {
        super.onActivityResult(r, res, data);
        if (r == PICK_TORRENT && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream is = getContentResolver().openInputStream(data.getData());
                File tf = new File(savePath, "torrent_file.torrent");
                FileOutputStream fos = new FileOutputStream(tf); byte[] b = new byte[8192]; int l;
                while ((l = is.read(b)) > 0) fos.write(b, 0, l); fos.close(); is.close();
                startDownload(tf.getAbsolutePath());
            } catch (Exception e) { debug("❌ " + e.getMessage()); }
        }
    }
    
    private void debug(String msg) { String line = "[" + sdf.format(new Date()) + "] " + msg + "\n"; Log.d("TS", msg); debugLog.append(line); handler.post(() -> { try { statusText.setText(msg); debugText.setText(debugLog.toString()); } catch (Exception e) {} }); }
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        downloading = true; videoFile = null; torrentHandle = null; pieceLength = 0; numPieces = 0; totalSize = 0; videoDurationMs = 0;
        currentPiece = 0; seeking = false; lastMinuteLog = -1;
        cuesPosition = -1; cuesSize = -1; tracksPosition = -1;
        
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE); });
        debug("⏳ Conectando...");
        
        executor.execute(() -> {
            try {
                add_torrent_params p = source.startsWith("magnet:") ? libtorrent.parse_magnet_uri(source, new error_code()) : add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath);
                p.setFlags(libtorrent.getAuto_managed().or_(libtorrent.getApply_ip_filter()));
                p.setDownload_limit(0);
                byte_vector pr = new byte_vector(); pr.add((byte)7); p.set_file_priorities(pr);
                session.swig().async_add_torrent(p);
                Thread.sleep(2000);
                
                synchronized (torrentLock) {
                    torrent_handle_vector h = session.swig().get_torrents();
                    if (h.size() > 0) torrentHandle = new TorrentHandle(h.get(0));
                }
                
                int w = 0;
                while (w < 60 && downloading) { Thread.sleep(1000); w++;
                    synchronized (torrentLock) { if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) break; }
                }
                
                synchronized (torrentLock) {
                if (torrentHandle != null && torrentHandle.isValid() && torrentHandle.torrentFile() != null) {
                    TorrentInfo ti = torrentHandle.torrentFile();
                    pieceLength = ti.pieceLength(); numPieces = ti.numPieces(); totalSize = ti.totalSize();
                    debug("📊 " + (totalSize/1048576) + "MB, " + numPieces + " peças, " + torrentHandle.swig().status().getNum_peers() + " peers");
                    
                    // FASE 1: Baixar cabeçalho COMPLETO (peças 0-19)
                    final int headerPieces = Math.min(20, numPieces);
                    debug("📋 FASE 1: Baixando cabeçalho [0-" + (headerPieces-1) + "]");
                    
                    byte_vector z = new byte_vector();
                    for (int i = 0; i < numPieces; i++) z.add((byte)0);
                    torrentHandle.swig().prioritize_pieces_ex(z);
                    
                    for (int i = 0; i < headerPieces; i++) { torrentHandle.swig().piece_priority_ex(i, (byte)7); torrentHandle.swig().set_piece_deadline(i, 30000); }
                    
                    int hDone = 0;
                    while (hDone < headerPieces && downloading) {
                        Thread.sleep(500); hDone = 0;
                        for (int i = 0; i < headerPieces; i++) if (torrentHandle.havePiece(i)) hDone++;
                        if (hDone % 5 == 0 || hDone == headerPieces)
                            debug("   📋 " + hDone + "/" + headerPieces);
                    }
                    debug("✅ Cabeçalho: " + hDone + "/" + headerPieces);
                    
                    // Encontrar arquivo e parsear SeekHead
                    for (int i = 0; i < 30; i++) { File f = find(new File(savePath)); if (f != null && f.length() > 10*1048576) { videoFile = f; break; } Thread.sleep(500); }
                    
                    if (videoFile != null) {
                        parseSeekHead(); // ⚡ LER SEEKHEAD REAL
                        
                        // FASE 2: Baixar Cues baseado no SeekHead
                        if (cuesPosition > 0 && cuesSize > 0) {
                            long cuesEnd = cuesPosition + cuesSize;
                            int cuesStartPiece = (int)(cuesPosition / pieceLength);
                            int cuesEndPiece = (int)(cuesEnd / pieceLength);
                            
                            debug("📋 FASE 2: Baixando Cues [peças " + cuesStartPiece + "-" + cuesEndPiece + "] (" + (cuesEndPiece - cuesStartPiece + 1) + " peças)");
                            
                            // Zerar prioridades anteriores
                            z = new byte_vector();
                            for (int i = 0; i < numPieces; i++) z.add((byte)0);
                            torrentHandle.swig().prioritize_pieces_ex(z);
                            
                            // Prioridade MÁXIMA para Cues
                            for (int i = cuesStartPiece; i <= cuesEndPiece; i++) {
                                torrentHandle.swig().piece_priority_ex(i, (byte)7);
                                torrentHandle.swig().set_piece_deadline(i, 30000);
                            }
                            
                            int cuesDone = 0;
                            int cuesTotal = cuesEndPiece - cuesStartPiece + 1;
                            while (cuesDone < cuesTotal && downloading) {
                                Thread.sleep(500); cuesDone = 0;
                                for (int i = cuesStartPiece; i <= cuesEndPiece; i++) if (torrentHandle.havePiece(i)) cuesDone++;
                                if (cuesDone % 3 == 0 || cuesDone == cuesTotal)
                                    debug("   📋 Cues: " + cuesDone + "/" + cuesTotal);
                            }
                            debug("✅ Cues: " + cuesDone + "/" + cuesTotal);
                        } else {
                            // Fallback: baixar últimas 5 peças
                            debug("⚠️ SeekHead não encontrou Cues, baixando final padrão");
                            final int tailPieces = Math.min(5, numPieces);
                            final int tailStart = numPieces - tailPieces;
                            for (int i = tailStart; i < numPieces; i++) { torrentHandle.swig().piece_priority_ex(i, (byte)7); torrentHandle.swig().set_piece_deadline(i, 30000); }
                            int tDone = 0;
                            while (tDone < tailPieces && downloading) { Thread.sleep(500); tDone = 0; for (int i = tailStart; i < numPieces; i++) if (torrentHandle.havePiece(i)) tDone++; }
                            debug("✅ Final: " + tDone + "/" + tailPieces);
                        }
                    }
                    
                    handler.post(() -> { btnWatch.setText("🎬 ASSISTIR"); btnWatch.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.GONE); });
                    debug("📁 " + (videoFile != null ? videoFile.getName() : "não encontrado"));
                    
                    // FASE 3: Download sequencial
                    debug("📥 Download sequencial iniciado");
                    int pos = headerPieces;
                    while (downloading) {
                        if (seeking) { Thread.sleep(500); continue; }
                        synchronized (torrentLock) {
                            if (torrentHandle == null || !torrentHandle.isValid()) break;
                            while (pos < numPieces && torrentHandle.havePiece(pos)) pos++;
                            if (pos >= numPieces) { debug("✅ Completo!"); break; }
                            final int end = Math.min(numPieces - 1, pos + 10);
                            final int sp = pos;
                            for (int i = 0; i < numPieces; i++) {
                                byte prr = (i >= sp && i <= end) ? (byte)7 : (i < sp && i >= sp - 3) ? (byte)3 : (byte)0;
                                torrentHandle.swig().piece_priority_ex(i, prr);
                            }
                            for (int i = sp; i <= end; i++) { if (!torrentHandle.havePiece(i)) torrentHandle.swig().set_piece_deadline(i, 10000 + (i - sp) * 500); }
                        }
                        Thread.sleep(2000);
                    }
                }
                }
            } catch (Exception e) { debug("❌ " + e.getMessage()); downloading = false; }
        });
    }
    
    private void watch() { if (videoFile == null || !videoFile.exists()) { debug("❌ Aguarde"); return; } handler.post(() -> { videoSurface.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); spinnerBar.setVisibility(View.VISIBLE); playWithVlc("http://127.0.0.1:8080/video"); }); }
    
    private void stop() {
        downloading = false; vlcPreparing = false; seeking = false;
        if (vlcPlayer != null) vlcPlayer.stop();
        videoSurface.setVisibility(View.GONE); playerControls.setVisibility(View.GONE); centerControls.setVisibility(View.GONE);
        audioScroll.setVisibility(View.GONE); subtitleScroll.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        handler.removeCallbacks(timeUpdater);
        synchronized (torrentLock) { if (torrentHandle != null && session != null) { try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} torrentHandle = null; } }
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File ff = find(f); if (ff != null) return ff; } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm)$")) return f; } return null; }
    
    @Override protected void onDestroy() { stop(); executor.shutdown(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); if (vlcPlayer != null) vlcPlayer.release(); if (libVLC != null) libVLC.release(); super.onDestroy(); }
}