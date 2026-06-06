package com.meuapp.player.server;

import android.util.Log;

import com.meuapp.player.utils.LogUtils;

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
            if (requestLine == null) {
                client.close();
                return;
            }
            
            LogUtils.d(TAG, "📡 " + requestLine);
            
            // CORS headers
            String cors = "Access-Control-Allow-Origin: *\r\n" +
                         "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                         "Access-Control-Allow-Headers: Range\r\n\r\n";
            
            // Verifica se é requisição de vídeo
            if (!requestLine.contains("/video") || videoFile == null || !videoFile.exists()) {
                sendResponse(out, 404, "Not Found", "text/plain", "Not Found".getBytes());
                client.close();
                return;
            }
            
            // Parse Range header
            long start = 0;
            long end = videoFile.length() - 1;
            
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    String range = line.substring(6).trim().replace("bytes=", "");
                    String[] parts = range.split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    }
                }
            }
            
            long fileSize = videoFile.length();
            if (end >= fileSize) end = fileSize - 1;
            
            int chunkSize = Math.min((int)(end - start + 1), 2097152); // 2MB
            
            // Lê dados do arquivo
            byte[] data = new byte[chunkSize];
            RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
            raf.seek(start);
            int bytesRead = raf.read(data);
            raf.close();
            
            if (bytesRead < 4096) {
                sendResponse(out, 503, "Service Unavailable", "text/plain", "Buffering...".getBytes());
                client.close();
                return;
            }
            
            // Prepara resposta
            byte[] responseData = new byte[bytesRead];
            System.arraycopy(data, 0, responseData, 0, bytesRead);
            
            String mimeType = getMimeType(videoFile.getName());
            String contentRange = "bytes " + start + "-" + (start + bytesRead - 1) + "/" + fileSize;
            
            StringBuilder headers = new StringBuilder();
            headers.append("HTTP/1.1 206 Partial Content\r\n");
            headers.append("Content-Type: ").append(mimeType).append("\r\n");
            headers.append("Content-Range: ").append(contentRange).append("\r\n");
            headers.append("Content-Length: ").append(bytesRead).append("\r\n");
            headers.append("Accept-Ranges: bytes\r\n");
            headers.append("Cache-Control: no-cache\r\n");
            headers.append(cors);
            headers.append("\r\n");
            
            out.write(headers.toString().getBytes());
            out.write(responseData);
            out.flush();
            client.close();
            
            LogUtils.d(TAG, "✅ Servido: " + bytesRead + " bytes");
            
        } catch (Exception e) {
            LogUtils.e(TAG, "Erro ao processar requisição", e);
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private void sendResponse(OutputStream out, int code, String status, String contentType, byte[] body) throws IOException {
        String response = "HTTP/1.1 " + code + " " + status + "\r\n" +
                         "Content-Type: " + contentType + "\r\n" +
                         "Content-Length: " + (body != null ? body.length : 0) + "\r\n" +
                         "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(response.getBytes());
        if (body != null) {
            out.write(body);
        }
        out.flush();
    }
    
    private String getMimeType(String fileName) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".avi")) return "video/x-msvideo";
        if (name.endsWith(".mov")) return "video/quicktime";
        return "video/mp4";
    }
}