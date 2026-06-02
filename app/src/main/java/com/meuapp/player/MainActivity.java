package com.meuapp.player;

import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import androidx.appcompat.app.AppCompatActivity;

import java.io.*;
import java.net.*;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private Thread serverThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        startServer();
        
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
        webView.loadUrl("file:///android_asset/www/index.html");
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                
                while (!Thread.interrupted()) {
                    Socket client = server.accept();
                    handleClient(client);
                }
                server.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(5000);
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String request = in.readLine();
            String range = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    range = line.substring(6).trim();
                }
            }
            
            // Cria um vídeo MP4 mínimo para teste
            byte[] video = createTestMp4();
            
            if (range != null) {
                String[] parts = range.replace("bytes=", "").split("-");
                long start = Long.parseLong(parts[0]);
                long end = parts.length > 1 && !parts[1].isEmpty() ? 
                    Long.parseLong(parts[1]) : video.length - 1;
                
                if (start >= video.length) {
                    out.write("HTTP/1.1 416\r\n\r\n".getBytes());
                    client.close();
                    return;
                }
                if (end >= video.length) end = video.length - 1;
                
                int len = (int)(end - start + 1);
                byte[] chunk = new byte[len];
                System.arraycopy(video, (int)start, chunk, 0, len);
                
                String header = "HTTP/1.1 206\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Content-Range: bytes " + start + "-" + end + "/" + video.length + "\r\n" +
                    "Content-Length: " + len + "\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n\r\n";
                
                out.write(header.getBytes());
                out.write(chunk);
            } else {
                String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Content-Length: " + video.length + "\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n\r\n";
                
                out.write(header.getBytes());
                out.write(video);
            }
            
            out.flush();
            client.close();
            
        } catch (Exception e) {
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private byte[] createTestMp4() {
        return new byte[1024];
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serverThread != null) serverThread.interrupt();
    }
}
