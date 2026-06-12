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
import java.text.SimpleDateFormat;
import java.util.*;

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
    private volatile boolean downloading, playing = false;
    private volatile File videoFile;
    private Handler handler;
    private Thread serverThread, downloadThread;
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
    
    private int currentPlayingPiece = -1;
    private boolean seeking = false;
    private final Object torrentLock = new Object();
    
    private long cuesBytePos = -1, cuesByteSize = -1;
    private Set<Integer> requiredPieces = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
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
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        handler = new Handler(Looper.getMainLooper());
        
        timeUpdater = () -> {
            if (vlcPlayer != null && isPlaying && !vlcPreparing && !seeking && playing) {
                long time = vlcPlayer.getTime();
                long length = vlcPlayer.getLength();
                if (length > 0) {
                    videoDurationMs = length;
                    if (time >= 0) {
                        timeText.setText(formatTime(time) + " / " + formatTime(length));
                        if (!isTracking) seekBar.setProgress((int)(time * 100 / length));
                        
                        if (pieceLength > 0 && totalSize > 0) {
                            int piece = (int)(time * totalSize / length / pieceLength);
                            if (piece != currentPlayingPiece && piece >= 0 && piece < numPieces) {
                                currentPlayingPiece = piece;
                                maintainBuffer(piece);
                            }
                        }
                        
                        long min = time / 60000;
                        if (min != lastMinuteLog) { lastMinuteLog = min; logMinute(min); }
                    }
                }
            }
            handler.postDelayed(timeUpdater, 1000);
        };
        
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { surfaceHolder = h; surfaceReady = true; if (pendingUrl != null) { playWithVlc(pendingUrl); pendingUrl = null; } }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h2) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; surfaceHolder = null; }
        });
        
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=1500");
        options.add("--file-caching=800");
        options.add("--clock-synchro=0");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new MediaPlayer(libVLC);
        
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing: isPlaying = true; vlcPreparing = false; handler.post(() -> { spinnerBar.setVisibility(View.GONE); btnPlayPause.setText("⏸"); }); break;
                case MediaPlayer.Event.Paused: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.Stopped: isPlaying = false; vlcPreparing = false; handler.post(() -> btnPlayPause.setText("▶")); break;
                case MediaPlayer.Event.Buffering: handler.post(() -> spinnerBar.setVisibility(View.VISIBLE)); break;
                case MediaPlayer.Event.EndReached: isPlaying = false; handler.post(() -> btnPlayPause.setText("▶")); break;
            }
        });
        
        btnPlayPause.setOnClickListener(v -> { if (vlcPlayer != null && !vlcPreparing) { if (isPlaying) vlcPlayer.pause(); else vlcPlayer.play(); } });
        btnSeekBack.setOnClickListener(v -> { if (!vlcPreparing) seekRelative(-10000); });
        btnSeekForward.setOnClickListener(v -> { if (!vlcPreparing) seekRelative(10000); });
        btnSkip20.setOnClickListener(v -> seekToPiece(20 * 60 * 1000));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && vlcPlayer != null && vlcPlayer.getLength() > 0) seekToPiece(vlcPlayer.getLength() * p / 100); }
            @Override public void onStartTrackingTouch(SeekBar s) { isTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isTracking = false; }
        });
        
        btnAudio.setOnClickListener(v -> toggleAudioMenu());
        btnSubtitle.setOnClickListener(v -> toggleSubtitleMenu());
        
        debug("=== STREAM v2.1 + STREMIO SEEK ===");
        new Thread(() -> { try { session = new SessionManager(); session.start(); debug("✅ Sessão OK"); } catch (Exception e) { debug("❌ " + e.getMessage()); } }).start();
        startServer();
        
        btnPlay.setOnClickListener(v -> start());
        btnTorrent.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i, PICK_TORRENT); });
        btnStop.setOnClickListener(v -> stop());
        btnWatch.setOnClickListener(v -> watch());
    }
    
    // ==================== BUFFER INTELIGENTE ====================
    private void maintainBuffer(int piece) {
        if (!playing || seeking) return;
        synchronized (torrentLock) {
            if (torrentHandle == null || !torrentHandle.isValid()) return;
            try {
                // Contar buffer disponível
                int buffered = 0;
                for (int i = piece; i < Math.min(numPieces, piece + 30); i++) {
                    if (torrentHandle.havePiece(i)) buffered++; else break;
                }
                
                // Se menos de 10 peças de buffer, baixar mais 20
                if (buffered < 10) {
                    int start = piece + buffered;
                    int end = Math.min(numPieces - 1, start + 20);
                    
                    // Foco total neste range
                    torrentHandle.setSequentialRange(start, end);
                    
                    // Prioridade máxima para o range
                    for (int i = start; i <= end; i++) {
                        if (!torrentHandle.havePiece(i)) {
                            torrentHandle.swig().piece_priority_ex(i, (byte)7);
                            torrentHandle.swig().set_piece_deadline(i, 5000);
                        }
                    }
                    
                    // Prioridade zero para peças muito atrás
                    for (int i = 0; i < Math.max(0, piece - 20); i++) {
                        torrentHandle.swig().piece_priority_ex(i, (byte)0);
                    }
                    
                    debug("📥 Buffer " + buffered + " → baixando " + start + "-" + end);
                }
            } catch (Exception e) {}
        }
    }
    
    // ==================== SEEK ESTILO STREMIO ====================
    private void seekToPiece(long timeMs) {
        if (vlcPlayer == null || pieceLength <= 0 || totalSize <= 0 || videoDurationMs <= 0) return;
        vlcPlayer.setTime(timeMs);
        final int piece = (int)(timeMs * totalSize / videoDurationMs / pieceLength);
        if (piece < 0 || piece >= numPieces) return;
        
        final long min = timeMs / 60000;
        final long sec = (timeMs / 1000) % 60;
        debug("🔥 Seek: " + min + ":" + String.format("%02d", sec) + " → peça " + piece);
        
        seeking = true;
        handler.post(() -> spinnerBar.setVisibility(View.VISIBLE));
        
        new Thread(() -> {
            synchronized (torrentLock) {
                if (torrentHandle == null || !torrentHandle.isValid()) { 
                    seeking = false; 
                    handler.post(() -> spinnerBar.setVisibility(View.GONE)); 
                    return; 
                }
                try {
                    // ⚡ ESTRATÉGIA STREMIO: Zerar tudo antes do seek, focar dali pra frente
                    
                    // 1. Desativar sequential download
                    torrent_flags_t flags = torrentHandle.swig().flags();
                    flags = flags.and_(libtorrent.getSequential_download().inv());
                    torrentHandle.swig().set_flags(flags);
                    
                    // 2. Prioridade ZERO para peças antes do alvo
                    for (int i = 0; i < piece - 2; i++) {
                        torrentHandle.swig().piece_priority_ex(i, (byte)0);
                    }
                    
                    // 3. Prioridade MÁXIMA para peça alvo + vizinhas
                    torrentHandle.swig().piece_priority_ex(piece - 1, (byte)6);
                    torrentHandle.swig().piece_priority_ex(piece, (byte)7);
                    torrentHandle.swig().piece_priority_ex(piece + 1, (byte)6);
                    
                    torrentHandle.swig().set_piece_deadline(piece - 1, 3000);
                    torrentHandle.swig().set_piece_deadline(piece, 2000);
                    torrentHandle.swig().set_piece_deadline(piece + 1, 3000);
                    
                    // 4. Forçar download sequencial a partir da peça alvo
                    torrentHandle.setSequentialRange(piece, numPieces - 1);
                    
                    // 5. Aguardar as 3 peças (máx 8 segundos)
                    int waits = 0;
                    boolean ready = false;
                    
                    while (!ready && downloading && waits < 32) {
                        Thread.sleep(250); waits++;
                        
                        int count = 0;
                        for (int i = piece - 1; i <= piece + 1; i++) {
                            if (i >= 0 && i < numPieces && torrentHandle.havePiece(i)) count++;
                        }
                        
                        if (torrentHandle.havePiece(piece) && count >= 2) {
                            ready = true;
                            break;
                        }
                        
                        if (waits % 4 == 0) {
                            torrentHandle.swig().set_piece_deadline(piece, 2000);
                        }
                    }
                    
                    final double elapsed = waits / 4.0;
                    
                    if (ready) {
                        handler.post(() -> { 
                            debug("✅ Peça " + piece + " OK em " + String.format("%.1f", elapsed) + "s"); 
                            seeking = false; 
                            spinnerBar.setVisibility(View.GONE); 
                        });
                        currentPlayingPiece = piece;
                    } else {
                        handler.post(() -> { 
                            debug("⏰ Timeout após " + String.format("%.1f", elapsed) + "s"); 
                            seeking = false; 
                            spinnerBar.setVisibility(View.GONE); 
                        });
                    }
                } catch (Exception e) {
                    handler.post(() -> debug("❌ Erro seek: " + e.getMessage()));
                    seeking = false;
                    handler.post(() -> spinnerBar.setVisibility(View.GONE));
                }
            }
        }).start();
    }
    
    private void seekRelative(long d) { if (vlcPlayer != null && vlcPlayer.getLength() > 0) seekToPiece(Math.max(0, Math.min(vlcPlayer.getLength(), vlcPlayer.getTime() + d))); }
    
    // ==================== PARSER SEEKHEAD (original v2.1) ====================
    private long readEBMLSize(byte[] data, int offset) {
        if (offset >= data.length) return 0;
        int firstByte = data[offset] & 0xFF, mask = 0x80, len = 0;
        for (int i = 0; i < 8; i++) { if ((firstByte & mask) != 0) { len = i + 1; break; } mask >>= 1; }
        if (len == 0 || offset + len > data.length) return 0;
        long size = firstByte & (0xFF >> len);
        for (int i = 1; i < len; i++) size = (size << 8) | (data[offset + i] & 0xFF);
        return size;
    }
    
    private int getEBMLSizeLen(byte[] data, int offset) {
        if (offset >= data.length) return 0;
        int firstByte = data[offset] & 0xFF, mask = 0x80;
        for (int i = 0; i < 8; i++) { if ((firstByte & mask) != 0) return i + 1; mask >>= 1; }
        return 1;
    }
    
    private long readUInt(byte[] data, int offset, int size) {
        long val = 0;
        for (int i = 0; i < size && offset + i < data.length; i++) val = (val << 8) | (data[offset + i] & 0xFF);
        return val;
    }
    
    private void parseSeekHeadAndFindMissingPieces() {
        if (videoFile == null || !videoFile.exists() || pieceLength <= 0) return;
        requiredPieces.clear();
        cuesBytePos = -1; cuesByteSize = -1;
        
        try {
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            long fileLen = raf.length();
            byte[] header = new byte[Math.min(131072, (int)fileLen)];
            raf.read(header);
            raf.close();
            
            int segPos = -1;
            byte[] segId = {(byte)0x18, (byte)0x53, (byte)0x80, (byte)0x67};
            for (int i = 0; i < header.length - 4; i++) {
                if (header[i]==segId[0] && header[i+1]==segId[1] && header[i+2]==segId[2] && header[i+3]==segId[3]) { segPos = i; break; }
            }
            if (segPos < 0) { debug("⚠️ Segmento não encontrado"); return; }
            
            int segDataStart = segPos + 4 + getEBMLSizeLen(header, segPos + 4);
            
            int shPos = -1;
            byte[] shId = {(byte)0x11, (byte)0x4D, (byte)0x9B, (byte)0x74};
            for (int i = segDataStart; i < header.length - 4; i++) {
                if (header[i]==shId[0] && header[i+1]==shId[1] && header[i+2]==shId[2] && header[i+3]==shId[3]) { shPos = i; break; }
            }
            if (shPos < 0) { debug("⚠️ SeekHead não encontrado"); return; }
            
            int shDataStart = shPos + 4 + getEBMLSizeLen(header, shPos + 4);
            long shSize = readEBMLSize(header, shPos + 4);
            int shEnd = (int)(shDataStart + shSize);
            
            debug("🔍 SeekHead: " + shSize + " bytes");
            
            Map<Long, Long> positions = new HashMap<>();
            int pos = shDataStart;
            
            while (pos < shEnd && pos < header.length - 8) {
                if (header[pos] == (byte)0x4D && header[pos+1] == (byte)0xBB) {
                    pos += 2;
                    long entrySize = readEBMLSize(header, pos);
                    pos += getEBMLSizeLen(header, pos);
                    int entryEnd = (int)(pos + entrySize);
                    
                    long elemId = -1, elemPos = -1;
                    int ip = pos;
                    while (ip < entryEnd && ip < header.length - 8) {
                        if (header[ip] == (byte)0x53 && header[ip+1] == (byte)0xAB) {
                            ip += 2;
                            long idSize = readEBMLSize(header, ip);
                            ip += getEBMLSizeLen(header, ip);
                            elemId = readUInt(header, ip, (int)idSize);
                            ip += idSize;
                        } else if (header[ip] == (byte)0x53 && header[ip+1] == (byte)0xAC) {
                            ip += 2;
                            long posSize = readEBMLSize(header, ip);
                            ip += getEBMLSizeLen(header, ip);
                            elemPos = readUInt(header, ip, (int)posSize);
                            ip += posSize;
                        } else { ip++; }
                    }
                    
                    if (elemId > 0 && elemPos >= 0) positions.put(elemId, segDataStart + elemPos);
                    pos = entryEnd;
                } else { pos++; }
            }
            
            long[] elemIds = {0x1C53BB6BL, 0x1654AE6BL, 0x1254C367L, 0x1043A770L, 0x1549A966L, 0x1941A469L};
            String[] elemNames = {"Cues", "Tracks", "Tags", "Chapters", "Info", "Attachments"};
            
            for (int i = 0; i < elemIds.length; i++) {
                if (positions.containsKey(elemIds[i])) {
                    long bytePos = positions.get(elemIds[i]);
                    int piece = (int)(bytePos / pieceLength);
                    requiredPieces.add(piece);
                    if (elemIds[i] == 0x1C53BB6BL) cuesBytePos = bytePos;
                    debug("   📍 " + elemNames[i] + " → peça " + piece);
                }
            }
            
            if (cuesBytePos > 0) {
                Long nextPos = null;
                for (long p : positions.values()) if (p > cuesBytePos && (nextPos == null || p < nextPos)) nextPos = p;
                
                if (nextPos != null) {
                    cuesByteSize = nextPos - cuesBytePos;
                } else {
                    raf = new RandomAccessFile(videoFile, "r");
                    if (fileLen > 524288) {
                        raf.seek(fileLen - 524288);
                        byte[] tail = new byte[524288];
                        raf.read(tail);
                        raf.close();
                        byte[] tagsId = {(byte)0x12, (byte)0x54, (byte)0xC3, (byte)0x67};
                        int tagsIdx = -1;
                        for (int j = 0; j < tail.length - 4; j++) {
                            if (tail[j]==tagsId[0] && tail[j+1]==tagsId[1] && tail[j+2]==tagsId[2] && tail[j+3]==tagsId[3]) { tagsIdx = j; break; }
                        }
                        cuesByteSize = (tagsIdx >= 0) ? (fileLen - 524288 + tagsIdx) - cuesBytePos : fileLen - cuesBytePos;
                    } else {
                        cuesByteSize = fileLen - cuesBytePos;
                    }
                }
                
                int cs = (int)(cuesBytePos / pieceLength);
                int ce = (int)((cuesBytePos + cuesByteSize) / pieceLength);
                for (int i = cs; i <= ce && i < numPieces; i++) requiredPieces.add(i);
                debug("   📏 Cues: " + (cuesByteSize/1024) + "KB → " + (ce-cs+1) + " peças");
            }
            
            for (int i = 0; i < Math.min(10, numPieces); i++) requiredPieces.add(i);
            debug("🎯 Total: " + requiredPieces.size() + " peças");
            
        } catch (Exception e) {
            debug("⚠️ Erro SeekHead: " + e.getMessage());
        }
    }
    
    private void logMinute(long min) {
        if (videoDurationMs <= 0 || pieceLength <= 0 || totalSize <= 0) return;
        long bp = min * 60 * 1000 * totalSize / videoDurationMs;
        int s = (int)(bp / pieceLength), e = (int)(((min+1)*60*1000*totalSize/videoDurationMs)/pieceLength);
        int have = 0;
        synchronized (torrentLock) { if (torrentHandle != null && torrentHandle.isValid()) for (int i = s; i <= e && i < numPieces; i++) if (torrentHandle.havePiece(i)) have++; }
        if (e > s) debug("⏱ Min " + min + ": " + have + "/" + (e-s+1) + " (" + (have*100/(e-s+1)) + "%)");
    }
    
    private String formatTime(long ms) { if (ms < 0) return "0:00"; int s = (int)(ms / 1000); return (s/60) + ":" + String.format("%02d", s%60); }
    
    private void toggleAudioMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] t = vlcPlayer.getAudioTracks();
        int c = vlcPlayer.getAudioTrack();
        audioMenu.removeAllViews();
        debug("🎵 Áudios: " + (t != null ? t.length : 0));
        if (t != null) for (MediaPlayer.TrackDescription tr : t) {
            if (tr.id >= 0) {
                TextView tv = new TextView(this); tv.setText("🎵 " + tr.name + (tr.id == c ? " ✓" : "")); tv.setTextColor(tr.id == c ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                final int id = tr.id; tv.setOnClickListener(v -> { vlcPlayer.setAudioTrack(id); audioScroll.setVisibility(View.GONE); });
                audioMenu.addView(tv);
            }
        }
        audioScroll.setVisibility(audioScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); subtitleScroll.setVisibility(View.GONE);
    }
    
    private void toggleSubtitleMenu() {
        if (vlcPlayer == null) return;
        MediaPlayer.TrackDescription[] t = vlcPlayer.getSpuTracks();
        int c = vlcPlayer.getSpuTrack();
        subtitleMenu.removeAllViews();
        debug("📝 Legendas: " + (t != null ? t.length : 0));
        TextView off = new TextView(this); off.setText("📝 Desligado" + (c == -1 ? " ✓" : "")); off.setTextColor(c == -1 ? 0xFF6c5ce7 : 0xFFFFFFFF); off.setTextSize(12); off.setPadding(16, 12, 16, 12);
        off.setOnClickListener(v -> { vlcPlayer.setSpuTrack(-1); subtitleScroll.setVisibility(View.GONE); });
        subtitleMenu.addView(off);
        if (t != null) for (MediaPlayer.TrackDescription tr : t) {
            if (tr.id >= 0) {
                TextView tv = new TextView(this); tv.setText("📝 " + tr.name + (tr.id == c ? " ✓" : "")); tv.setTextColor(tr.id == c ? 0xFF6c5ce7 : 0xFFFFFFFF); tv.setTextSize(12); tv.setPadding(16, 12, 16, 12);
                final int id = tr.id; tv.setOnClickListener(v -> { vlcPlayer.setSpuTrack(id); subtitleScroll.setVisibility(View.GONE); });
                subtitleMenu.addView(tv);
            }
        }
        subtitleScroll.setVisibility(subtitleScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); audioScroll.setVisibility(View.GONE);
    }
    
    private void playWithVlc(String url) {
        if (!surfaceReady || surfaceHolder == null) { pendingUrl = url; return; }
        try {
            vlcPreparing = true;
            vlcPlayer.getVLCVout().setVideoSurface(surfaceHolder.getSurface(), null);
            vlcPlayer.getVLCVout().setWindowSize(videoSurface.getWidth(), videoSurface.getHeight());
            vlcPlayer.getVLCVout().attachViews();
            Media m = new Media(libVLC, Uri.parse(url));
            m.setHWDecoderEnabled(true, true);
            m.addOption(":network-caching=1500");
            m.addOption(":file-caching=800");
            vlcPlayer.setMedia(m); m.release();
            vlcPlayer.play();
            playing = true;
            handler.post(() -> { playerControls.setVisibility(View.VISIBLE); centerControls.setVisibility(View.VISIBLE); btnSkip20.setVisibility(View.VISIBLE); });
            debug("[VLC] ▶ Reproduzindo");
        } catch (Exception e) { vlcPreparing = false; }
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
    
    private void debug(String msg) { String line = "[" + sdf.format(new Date()) + "] " + msg + "\n"; Log.d("TS", msg); debugLog.append(line); handler.post(() -> { statusText.setText(msg); debugText.setText(debugLog.toString()); }); }
    
    private void startServer() { serverThread = new Thread(() -> { try { ServerSocket s = new ServerSocket(8080, 10); s.setReuseAddress(true); while (!Thread.interrupted()) { try { Socket c = s.accept(); new Thread(() -> handleHttp(c)).start(); } catch (IOException e) {} } s.close(); } catch (IOException e) {} }); serverThread.setDaemon(true); serverThread.start(); }
    
    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream(); int b;
            while ((b = in.read()) != -1) { hb.write(b); if (hb.size() > 4) { byte[] d = hb.toByteArray(); if (d[d.length-4]=='\r'&&d[d.length-3]=='\n'&&d[d.length-2]=='\r'&&d[d.length-1]=='\n') break; } }
            String req = new String(hb.toByteArray()); String[] lines = req.split("\r\n");
            if (!lines[0].contains("/video")) { out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            
            long rs = 0, re = -1; boolean hr = false;
            for (String l : lines) { if (l.toLowerCase().startsWith("range: bytes=")) { hr = true; rs = Long.parseLong(l.substring(13).trim().split("-")[0]); if (l.substring(13).contains("-")) { String[] p = l.substring(13).split("-"); if (p.length > 1 && !p[1].isEmpty()) re = Long.parseLong(p[1]); } } }
            
            if (videoFile == null || !videoFile.exists()) { out.write("HTTP/1.1 503\r\n\r\n".getBytes()); out.flush(); client.close(); return; }
            
            long fs = videoFile.length();
            if (!hr) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/x-matroska\r\nContent-Length: " + fs + "\r\nAccept-Ranges: bytes\r\nConnection: keep-alive\r\n\r\n").getBytes());
                out.flush();
                RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
                byte[] data = new byte[65536]; int read = raf.read(data);
                if (read > 0) out.write(data, 0, read);
                raf.close(); out.flush(); client.close();
                return;
            }
            
            if (re == -1 || re >= fs) re = fs - 1;
            long cl = re - rs + 1;
            
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nContent-Range: bytes " + rs + "-" + (rs+cl-1) + "/" + fs + "\r\nContent-Length: " + cl + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n").getBytes());
            out.flush();
            
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(rs);
            byte[] buf = new byte[65536];
            long sent = 0;
            while (sent < cl && downloading) {
                int tr = (int)Math.min(buf.length, cl - sent);
                int read = raf.read(buf, 0, tr);
                if (read <= 0) break;
                out.write(buf, 0, read); out.flush();
                sent += read;
            }
            raf.close(); out.flush(); client.close();
        } catch (Exception e) { try { client.close(); } catch (IOException ex) {} }
    }
    
    private void start() { String m = magnetInput.getText().toString().trim(); if (m.startsWith("magnet:") && !downloading) startDownload(m); }
    
    private void startDownload(String source) {
        stop();
        
        downloading = true; playing = false; seeking = false; videoFile = null; torrentHandle = null;
        pieceLength = 0; numPieces = 0; totalSize = 0; videoDurationMs = 0;
        currentPlayingPiece = -1; lastMinuteLog = -1;
        requiredPieces.clear(); cuesBytePos = -1; cuesByteSize = -1;
        
        handler.post(() -> { btnStop.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE); });
        
        downloadThread = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            debug("⏳ Conectando...");
            
            try {
                add_torrent_params p = source.startsWith("magnet:") ? libtorrent.parse_magnet_uri(source, new error_code()) : add_torrent_params.load_torrent_file(source, new error_code());
                p.setSave_path(savePath);
                p.setFlags(libtorrent.getAuto_managed().or_(libtorrent.getApply_ip_filter()));
                p.setDownload_limit(3*1024*1024);
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
                    torrent_status st = torrentHandle.swig().status();
                    debug("📊 " + (totalSize/1048576) + "MB | " + st.getNum_seeds() + " Seeds | " + st.getNum_peers() + " Peers");
                    
                    // PRÉ-CARGA: 15 início + 5 final + 1 meio
                    int inicio = Math.min(15, numPieces);
                    int fim = Math.min(5, numPieces);
                    int fimStart = numPieces - fim;
                    int meio = numPieces / 2;
                    
                    debug("📋 PRÉ-CARGA: [0-" + (inicio-1) + "] + [" + fimStart + "-" + (numPieces-1) + "] + p" + meio);
                    
                    byte_vector z = new byte_vector();
                    for (int i = 0; i < numPieces; i++) z.add((byte)0);
                    torrentHandle.swig().prioritize_pieces_ex(z);
                    
                    for (int i = 0; i < inicio; i++) { torrentHandle.swig().piece_priority_ex(i, (byte)7); torrentHandle.swig().set_piece_deadline(i, 20000); }
                    for (int i = fimStart; i < numPieces; i++) { torrentHandle.swig().piece_priority_ex(i, (byte)7); torrentHandle.swig().set_piece_deadline(i, 20000); }
                    torrentHandle.swig().piece_priority_ex(meio, (byte)7); torrentHandle.swig().set_piece_deadline(meio, 20000);
                    
                    int doneIni = 0, doneFim = 0;
                    while ((doneIni < inicio || doneFim < fim) && downloading) {
                        Thread.sleep(200);
                        doneIni = 0; for (int i = 0; i < inicio; i++) if (torrentHandle.havePiece(i)) doneIni++;
                        doneFim = 0; for (int i = fimStart; i < numPieces; i++) if (torrentHandle.havePiece(i)) doneFim++;
                    }
                    debug("✅ Pré-carga: " + doneIni + "/" + inicio + " | " + doneFim + "/" + fim + " (" + ((System.currentTimeMillis()-t0)/1000) + "s)");
                    
                    for (int i = 0; i < 15; i++) { File f = find(new File(savePath)); if (f != null && f.length() > 5*1048576) { videoFile = f; break; } Thread.sleep(200); }
                    
                    if (videoFile != null) {
                        parseSeekHeadAndFindMissingPieces();
                        
                        int total = requiredPieces.size();
                        debug("📥 Complementando " + total + " peças");
                        
                        z = new byte_vector();
                        for (int i = 0; i < numPieces; i++) z.add((byte)0);
                        torrentHandle.swig().prioritize_pieces_ex(z);
                        for (int piece : requiredPieces) {
                            if (piece < numPieces) { torrentHandle.swig().piece_priority_ex(piece, (byte)7); torrentHandle.swig().set_piece_deadline(piece, 15000); }
                        }
                        
                        int done = 0;
                        while (done < total && downloading) { Thread.sleep(150); done = 0;
                            for (int piece : requiredPieces) if (piece < numPieces && torrentHandle.havePiece(piece)) done++; }
                        
                        long elapsed = (System.currentTimeMillis() - t0) / 1000;
                        debug("✅ TUDO PRONTO! " + done + "/" + total + " em " + elapsed + "s");
                    }
                    
                    handler.post(() -> { btnWatch.setText("🎬 ASSISTIR"); btnWatch.setVisibility(View.VISIBLE); bufferBar.setVisibility(View.GONE); });
                    if (videoFile != null) debug("📁 " + videoFile.getName());
                }
                }
            } catch (Exception e) { debug("❌ " + e.getMessage()); downloading = false; }
        });
        downloadThread.start();
    }
    
    private void watch() { if (videoFile == null || !videoFile.exists()) { debug("❌ Aguarde"); return; } playing = true; handler.post(() -> { videoSurface.setVisibility(View.VISIBLE); btnWatch.setVisibility(View.GONE); spinnerBar.setVisibility(View.VISIBLE); playWithVlc("http://127.0.0.1:8080/video"); }); }
    
    private void stop() {
        downloading = false; playing = false; seeking = false; vlcPreparing = false;
        if (vlcPlayer != null) vlcPlayer.stop();
        videoSurface.setVisibility(View.GONE); playerControls.setVisibility(View.GONE); centerControls.setVisibility(View.GONE);
        audioScroll.setVisibility(View.GONE); subtitleScroll.setVisibility(View.GONE);
        btnStop.setVisibility(View.GONE); btnWatch.setVisibility(View.GONE); btnSkip20.setVisibility(View.GONE);
        bufferBar.setVisibility(View.GONE); spinnerBar.setVisibility(View.GONE);
        handler.removeCallbacks(timeUpdater);
        if (downloadThread != null) downloadThread.interrupt();
        synchronized (torrentLock) { if (torrentHandle != null) { try { session.swig().remove_torrent(torrentHandle.swig()); } catch (Exception e) {} torrentHandle = null; } }
    }
    
    private File find(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) { if (f.isDirectory()) { File ff = find(f); if (ff != null) return ff; } else if (f.getName().matches(".*\\.(mp4|mkv|avi|webm)$")) return f; } return null; }
    
    @Override protected void onDestroy() { stop(); if (serverThread != null) serverThread.interrupt(); if (session != null) session.stop(); if (vlcPlayer != null) vlcPlayer.release(); if (libVLC != null) libVLC.release(); super.onDestroy(); }
}