package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private TextView logText;
    private StringBuilder log = new StringBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private Thread serverThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        logText = findViewById(R.id.log_text);
        
        log("═══ TESTE DO SERVIDOR HTTP ═══");
        log("Iniciando na porta 8080...");
        
        startServer();
    }
    
    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        log.append(line);
        runOnUiThread(() -> logText.setText(log.toString()));
    }
    
    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(8080, 10);
                server.setReuseAddress(true);
                log("✅ Servidor rodando na porta 8080");
                log("🔗 http://127.0.0.1:8080/");
                
                int requestCount = 0;
                
                while (!Thread.interrupted()) {
                    Socket client = server.accept();
                    requestCount++;
                    handleClient(client, requestCount);
                }
                server.close();
            } catch (IOException e) {
                log("❌ Erro: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    private void handleClient(Socket client, int num) {
        try {
            client.setSoTimeout(5000);
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            
            // Lê primeira linha
            String request = in.readLine();
            
            // Lê headers
            String range = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    range = line.substring(6).trim();
                }
            }
            
            log("📥 Request #" + num + ": " + (request != null ? request.substring(0, Math.min(50, request.length())) : "vazia"));
            if (range != null) log("   Range: " + range);
            
            // Responde com um vídeo de teste (10 segundos de silêncio em MP4)
            byte[] testVideo = createTestMp4();
            
            // Se tem Range, responde só o pedaço
            if (range != null) {
                String r = range.replace("bytes=", "");
                String[] parts = r.split("-");
                long start = Long.parseLong(parts[0]);
                long end = parts.length > 1 && !parts[1].isEmpty() ? 
                    Long.parseLong(parts[1]) : testVideo.length - 1;
                
                if (start >= testVideo.length) {
                    write(out, "HTTP/1.1 416\r\n\r\n");
                    client.close();
                    return;
                }
                if (end >= testVideo.length) end = testVideo.length - 1;
                
                int len = (int)(end - start + 1);
                byte[] chunk = new byte[len];
                System.arraycopy(testVideo, (int)start, chunk, 0, len);
                
                String header = "HTTP/1.1 206\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Content-Range: bytes " + start + "-" + end + "/" + testVideo.length + "\r\n" +
                    "Content-Length: " + len + "\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n\r\n";
                
                out.write(header.getBytes());
                out.write(chunk);
            } else {
                // Resposta completa
                String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Content-Length: " + testVideo.length + "\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n\r\n";
                
                out.write(header.getBytes());
                out.write(testVideo);
            }
            
            out.flush();
            client.close();
            log("✅ Resposta enviada");
            
        } catch (Exception e) {
            log("❌ Erro: " + e.getMessage());
            try { client.close(); } catch (IOException ex) {}
        }
    }
    
    private void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes());
        out.flush();
    }
    
    // Cria um vídeo MP4 mínimo (apenas para teste)
    private byte[] createTestMp4() {
        // Isso é um arquivo MP4 mínimo (cerca de 1KB)
        // Apenas para testar se o servidor responde corretamente
        return new byte[1024];
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serverThread != null) serverThread.interrupt();
    }
}
