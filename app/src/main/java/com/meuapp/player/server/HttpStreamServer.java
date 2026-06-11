package com.meuapp.player.server;

import com.meuapp.player.model.StreamInfo;

import java.io.*;
import java.net.*;

/**
 * Servidor HTTP otimizado para streaming de vídeo.
 * Sem dependência do NanoHTTPD - usa ServerSocket nativo.
 */
public class HttpStreamServer {
    private final int port;
    private final StreamInfo info;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running;
    
    public HttpStreamServer(int port, StreamInfo info) {
        this.port = port;
        this.info = info;
    }
    
    public void start() {
        running = true;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port, 10);
                serverSocket.setReuseAddress(true);
                
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client)).start();
                    } catch (IOException e) {
                        if (running) e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "HttpServer");
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {}
        if (serverThread != null) serverThread.interrupt();
    }
    
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            
            // Ler headers
            ByteArrayOutputStream hb = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                hb.write(b);
                byte[] d = hb.toByteArray();
                if (d.length >= 4 && d[d.length-4]=='\r' && d[d.length-3]=='\n' && 
                    d[d.length-2]=='\r' && d[d.length-1]=='\n') break;
            }
            
            String req = new String(hb.toByteArray());
            String[] lines = req.split("\r\n");
            
            if (lines.length == 0 || !lines[0].contains("/video")) {
                out.write("HTTP/1.1 404\r\n\r\n".getBytes());
                out.flush();
                client.close();
                return;
            }
            
            // Parse Range
            long rangeStart = 0, rangeEnd = -1;
            boolean hasRange = false;
            for (String l : lines) {
                if (l.toLowerCase().startsWith("range: bytes=")) {
                    hasRange = true;
                    String val = l.substring(13).trim();
                    String[] parts = val.split("-");
                    rangeStart = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        rangeEnd = Long.parseLong(parts[1]);
                    }
                    break;
                }
            }
            
            if (info.videoFile == null || !info.videoFile.exists()) {
                out.write("HTTP/1.1 503\r\n\r\n".getBytes());
                out.flush();
                client.close();
                return;
            }
            
            long fileSize = info.videoFile.length();
            
            if (!hasRange) {
                // Primeira requisição: NÃO usar Content-Length grande (evita OOM)
                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nConnection: keep-alive\r\nCache-Control: no-cache\r\n\r\n".getBytes());
                out.flush();
                
                // Enviar apenas 2MB iniciais
                RandomAccessFile raf = new RandomAccessFile(info.videoFile, "r");
                byte[] data = new byte[65536];
                int read, sent = 0;
                while (sent < 2097152 && (read = raf.read(data)) != -1) {
                    out.write(data, 0, read);
                    out.flush();
                    sent += read;
                }
                raf.close();
                out.flush();
                client.close();
                return;
            }
            
            if (rangeEnd == -1 || rangeEnd >= fileSize) rangeEnd = fileSize - 1;
            long contentLength = rangeEnd - rangeStart + 1;
            
            // Range request
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\nContent-Range: bytes " + 
                      rangeStart + "-" + rangeEnd + "/" + fileSize + "\r\nContent-Length: " + contentLength + 
                      "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n").getBytes());
            out.flush();
            
            // Enviar dados (máx 256KB por chunk)
            RandomAccessFile raf = new RandomAccessFile(info.videoFile, "r");
            if (rangeStart < raf.length()) {
                raf.seek(rangeStart);
                long available = raf.length() - rangeStart;
                int toRead = (int) Math.min(contentLength, Math.min(available, 262144));
                if (toRead > 0) {
                    byte[] buf = new byte[toRead];
                    int read = raf.read(buf);
                    if (read > 0) {
                        out.write(buf, 0, read);
                        out.flush();
                    }
                }
            }
            raf.close();
            out.flush();
            client.close();
        } catch (Exception e) {
            try { client.close(); } catch (IOException ex) {}
        }
    }
}
