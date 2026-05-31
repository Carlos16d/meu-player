package com.seuapp;

import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class StreamServer {
    private int port;
    private ServerSocket serverSocket;
    private TorrentHandle torrentHandle;
    private int fileIndex;
    private File cacheDir;
    private boolean running = false;
    
    public StreamServer(int port) {
        this.port = port;
    }
    
    public void setTorrentHandle(TorrentHandle handle, int fileIndex, File cacheDir) {
        this.torrentHandle = handle;
        this.fileIndex = fileIndex;
        this.cacheDir = cacheDir;
    }
    
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        
        new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }).start();
    }
    
    private void handleClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream())
            );
            OutputStream out = client.getOutputStream();
            
            String line = reader.readLine();
            if (line == null) return;
            
            String[] parts = line.split(" ");
            String path = parts[1];
            
            String response = "HTTP/1.1 200 OK\r\n";
            response += "Access-Control-Allow-Origin: *\r\n";
            response += "Access-Control-Allow-Methods: GET, OPTIONS\r\n";
            response += "Access-Control-Allow-Headers: Content-Type, Range\r\n";
            
            if (path.equals("/stream") && torrentHandle != null && torrentHandle.isValid()) {
                serveVideo(out, response);
            } else if (path.equals("/status")) {
                String status = "{\"progress\":" + 
                    (torrentHandle != null ? torrentHandle.status().progress() * 100 : 0) + "}";
                response += "Content-Type: application/json\r\n";
                response += "Content-Length: " + status.length() + "\r\n\r\n";
                response += status;
                out.write(response.getBytes());
            } else {
                response = "HTTP/1.1 404 Not Found\r\n\r\n";
                out.write(response.getBytes());
            }
            
            out.flush();
            client.close();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void serveVideo(OutputStream out, String headers) throws Exception {
        TorrentInfo ti = torrentHandle.torrentFile();
        long fileSize = ti.files().fileSize(fileIndex);
        
        headers += "Content-Type: video/mp4\r\n";
        headers += "Accept-Ranges: bytes\r\n";
        headers += "Content-Length: " + fileSize + "\r\n";
        headers += "Connection: keep-alive\r\n\r\n";
        
        out.write(headers.getBytes());
        
        byte[] buffer = new byte[65536];
        long sent = 0;
        
        while (sent < fileSize && running) {
            int pieceIndex = (int)(sent / ti.pieceLength());
            if (torrentHandle.havePiece(pieceIndex)) {
                File tempFile = new File(cacheDir, ti.files().filePath(fileIndex));
                if (tempFile.exists()) {
                    try (RandomAccessFile raf = new RandomAccessFile(tempFile, "r")) {
                        raf.seek(sent);
                        int read = raf.read(buffer, 0, 
                            (int)Math.min(buffer.length, fileSize - sent));
                        if (read > 0) {
                            out.write(buffer, 0, read);
                            sent += read;
                        }
                    }
                }
            }
            Thread.sleep(10);
        }
    }
    
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {}
    }
}
