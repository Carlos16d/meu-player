package com.meuapp.player.server;

import android.util.Log;

import java.io.*;
import java.net.Socket;

public class HttpHandler {
    private static final String TAG = "HttpHandler";
    private File videoFile;
    
    public void setVideoFile(File videoFile) {
        this.videoFile = videoFile;
    }
    
    public void handle(Socket client) {
        try {
            client.setSoTimeout(5000);
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            String requestLine = in.readLine();
            if (requestLine == null) { client.close(); return; }
            
            if (!requestLine.contains("/video") || videoFile == null || !videoFile.exists()) {
                out.write("HTTP/1.1 404\r\n\r\n".getBytes());
                out.flush();
                client.close();
                return;
            }
            
            long start = 0;
            long end = videoFile.length() - 1;
            
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    String range = line.substring(6).trim().replace("bytes=", "");
                    String[] parts = range.split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                }
            }
            
            long fileSize = videoFile.length();
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 2097152);
            
            byte[] data = new byte[chunkSize];
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(start);
            int bytesRead = raf.read(data);
            raf.close();
            
            if (bytesRead < 4096) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush();
                client.close();
                return;
            }
            
            String mime = "video/mp4";
            String name = videoFile.getName().toLowerCase();
            if (name.endsWith(".mkv")) mime = "video/x-matroska";
            else if (name.endsWith(".webm")) mime = "video/webm";
            
            String resp = "HTTP/1.1 206\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "Content-Range: bytes " + start + "-" + (start + bytesRead - 1) + "/" + fileSize + "\r\n" +
                "Content-Length: " + bytesRead + "\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n";
            
            out.write(resp.getBytes());
            out.write(data, 0, bytesRead);
            out.flush();
            client.close();
            
        } catch (Exception e) {
            try { client.close(); } catch (IOException ex) {}
        }
    }
}