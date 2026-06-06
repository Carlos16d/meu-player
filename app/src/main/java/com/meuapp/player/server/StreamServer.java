package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.net.*;

public class StreamServer {
    private static final String TAG = "StreamServer";
    private static final int PORT = 8080;
    
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private VideoProvider videoProvider;
    
    public interface VideoProvider {
        File getVideoFile();
        byte[] readChunk(long offset, int size) throws IOException;
        long getFileSize();
        String getMimeType();
    }
    
    public void setVideoProvider(VideoProvider provider) {
        this.videoProvider = provider;
    }
    
    public void start() {
        if (running) return;
        running = true;
        
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                Log.d(TAG, "Servidor HTTP: " + PORT);
                
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client)).start();
                    } catch (IOException e) {
                        if (running) Log.e(TAG, "Erro accept", e);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Erro servidor", e);
            }
        }).start();
    }
    
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(5000);
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String request = in.readLine();
            if (request == null || videoProvider == null) {
                client.close();
                return;
            }
            
            // CORS
            String cors = "Access-Control-Allow-Origin: *\r\n";
            
            // Parse Range
            long start = 0;
            long end = videoProvider.getFileSize() - 1;
            
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    String[] parts = line.substring(6).trim().replace("bytes=", "").split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    }
                }
            }
            
            long fileSize = videoProvider.getFileSize();
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 2097152); // 2MB
            
            byte[] data = videoProvider.readChunk(start, chunkSize);
            
            if (data == null || data.length < 4096) {
                out.write(("HTTP/1.1 503\r\n" + cors + "Retry-After: 1\r\n\r\n").getBytes());
                out.flush();
                client.close();
                return;
            }
            
            String response = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: " + videoProvider.getMimeType() + "\r\n" +
                "Content-Range: bytes " + start + "-" + (start + data.length - 1) + "/" + fileSize + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Cache-Control: no-cache\r\n" +
                cors + "\r\n";
            
            out.write(response.getBytes());
            out.write(data);
            out.flush();
            client.close();
            
        } catch (Exception e) {
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {}
    }
}
