package com.meuapp.player.server;

import java.io.*;
import java.net.Socket;

public class HttpHandler {
    private File videoFile;
    
    public void setVideoFile(File f) { this.videoFile = f; }
    
    public void handle(Socket client) {
        try {
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String req = in.readLine();
            
            if (req == null || !req.contains("/video") || videoFile == null) {
                out.write("HTTP/1.1 404\r\n\r\n".getBytes());
                out.flush();
                client.close();
                return;
            }
            
            long start = 0, end = videoFile.length() - 1;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    String[] p = line.substring(6).trim().replace("bytes=", "").split("-");
                    start = Long.parseLong(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) end = Long.parseLong(p[1]);
                }
            }
            
            long len = videoFile.length();
            if (end >= len) end = len - 1;
            int size = Math.min((int)(end - start + 1), 2097152);
            
            byte[] data = new byte[size];
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(start);
            int read = raf.read(data);
            raf.close();
            
            if (read < 4096) {
                out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
                out.flush();
                client.close();
                return;
            }
            
            String mime = videoFile.getName().toLowerCase().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            String resp = "HTTP/1.1 206\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "Content-Range: bytes " + start + "-" + (start+read-1) + "/" + len + "\r\n" +
                "Content-Length: " + read + "\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n";
            
            out.write(resp.getBytes());
            out.write(data, 0, read);
            out.flush();
            client.close();
        } catch (Exception e) {
            try { client.close(); } catch (IOException ex) {}
        }
    }
}