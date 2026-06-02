package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private String savePath;
    private SessionManager session;
    private torrent_handle torrent;
    private boolean downloading = false;
    private StreamServer streamServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        try {
            session = new SessionManager();
            session.start();
            
            streamServer = new StreamServer(8080);
            streamServer.start();
            
            Toast.makeText(this, "UDP + Streaming na porta 8080", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        
        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "App");
        webView.loadUrl("file:///android_asset/www/index.html");
    }
    
    class StreamServer extends NanoHTTPD {
        public StreamServer(int port) {
            super(port);
        }
        
        @Override
        public Response serve(IHTTPSession ses) {
            String uri = ses.getUri();
            if ("/video".equals(uri) && torrent != null && torrent.is_valid()) {
                return serveVideo(ses);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        
        private Response serveVideo(IHTTPSession ses) {
            try {
                torrent_info info = torrent.torrent_file();
                long fileSize = info.total_size();
                long pieceLength = info.piece_length();
                
                Map<String, String> headers = ses.getHeaders();
                String rangeHeader = headers.get("range");
                
                long start = 0;
                long end = Math.min(pieceLength * 30, fileSize - 1);
                
                if (rangeHeader != null) {
                    String[] parts = rangeHeader.replace("bytes=", "").split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    } else {
                        end = Math.min(start + pieceLength * 30, fileSize - 1);
                    }
                }
                
                int startPiece = (int)(start / pieceLength);
                int endPiece = (int)(end / pieceLength);
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                for (int i = startPiece; i <= endPiece && i < info.num_pieces(); i++) {
                    if (torrent.have_piece(i)) {
                        torrent.read_piece(i);
                        // Lê do arquivo salvo
                        File videoFile = findVideoFile(new File(savePath));
                        if (videoFile != null) {
                            try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
                                long pieceStart = i * pieceLength;
                                raf.seek(pieceStart);
                                byte[] buffer = new byte[(int)Math.min(pieceLength, fileSize - pieceStart)];
                                raf.read(buffer);
                                baos.write(buffer);
                            }
                        }
                    }
                }
                
                byte[] data = baos.toByteArray();
                long offset = start % pieceLength;
                int len = (int)Math.min(data.length - offset, end - start + 1);
                if (len <= 0) len = data.length;
                
                byte[] responseData = new byte[len];
                System.arraycopy(data, (int)offset, responseData, 0, len);
                
                Response resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT,
                    "video/mp4", new ByteArrayInputStream(responseData), len);
                resp.addHeader("Content-Range", "bytes " + start + "-" + (start + len - 1) + "/" + fileSize);
                resp.addHeader("Accept-Ranges", "bytes");
                resp.addHeader("Access-Control-Allow-Origin", "*");
                return resp;
                
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
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
    }
    
    public class Bridge {
        @JavascriptInterface
        public void startDownload(String magnet) {
            if (downloading) return;
            downloading = true;
            
            new Thread(() -> {
                try {
                    add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                    p.setSave_path(savePath);
                    
                    string_vector trackers = new string_vector();
                    trackers.add("udp://tracker.opentrackr.org:1337/announce");
                    trackers.add("udp://tracker.openbittorrent.com:6969/announce");
                    p.setTrackers(trackers);
                    
                    p.setFlags(torrent_flags_t.from_int(9));
                    p.setDownload_limit(0);
                    
                    byte_vector priorities = new byte_vector();
                    priorities.add((byte)7);
                    p.set_file_priorities(priorities);
                    
                    session.swig().async_add_torrent(p);
                    
                    Thread.sleep(5000);
                    
                    torrent_handle_vector handles = session.swig().get_torrents();
                    if (handles.size() > 0) {
                        torrent = handles.get(0);
                        
                        torrent_info info = torrent.torrent_file();
                        if (info != null) {
                            int totalPieces = info.num_pieces();
                            byte_vector piecePriorities = new byte_vector();
                            for (int i = 0; i < totalPieces; i++) {
                                byte priority = (i < 20) ? (byte)7 : (i < 50) ? (byte)6 : (i < 100) ? (byte)5 : (byte)4;
                                piecePriorities.add(priority);
                            }
                            torrent.prioritize_pieces_ex(piecePriorities);
                        }
                    }
                    
                    runOnUiThread(() -> 
                        Toast.makeText(MainActivity.this, "Streaming UDP ativado!", Toast.LENGTH_SHORT).show()
                    );
                } catch (Exception e) {
                    downloading = false;
                }
            }).start();
        }
        
        @JavascriptInterface
        public String getStreamUrl() {
            return "http://127.0.0.1:8080/video";
        }
        
        @JavascriptInterface
        public String getProgress() {
            if (torrent != null && torrent.is_valid()) {
                return String.valueOf((int)(torrent.status().getProgress() * 100));
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getPeers() {
            if (torrent != null && torrent.is_valid()) {
                return String.valueOf(torrent.status().getNum_peers());
            }
            return "0";
        }
        
        @JavascriptInterface
        public String getSpeed() {
            if (torrent != null && torrent.is_valid()) {
                long speed = torrent.status().getDownload_rate();
                if (speed > 1048576) return String.format("%.1f MB/s", speed / 1048576.0);
                if (speed > 1024) return String.format("%.1f KB/s", speed / 1024.0);
                return speed + " B/s";
            }
            return "0 B/s";
        }
    }
}
