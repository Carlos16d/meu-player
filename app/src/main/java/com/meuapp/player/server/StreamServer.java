package com.meuapp.player.server;

import android.util.Log;

import com.meuapp.player.utils.LogUtils;

import java.io.*;
import java.net.*;

public class StreamServer {
    private static final String TAG = "StreamServer";
    private static final int PORT = 8080;
    
    private ServerSocket serverSocket;
    private boolean running = false;
    private HttpHandler httpHandler;
    
    public StreamServer() {
        this.httpHandler = new HttpHandler();
    }
    
    public void setVideoFile(File videoFile) {
        httpHandler.setVideoFile(videoFile);
    }
    
    public void start() {
        if (running) return;
        running = true;
        
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                LogUtils.d(TAG, "🔥 Servidor HTTP iniciado na porta " + PORT);
                
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> httpHandler.handle(client)).start();
                    } catch (IOException e) {
                        if (running) LogUtils.e(TAG, "Erro ao aceitar conexão", e);
                    }
                }
            } catch (IOException e) {
                LogUtils.e(TAG, "Erro ao iniciar servidor", e);
            }
        }).start();
    }
    
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {}
        LogUtils.d(TAG, "Servidor HTTP parado");
    }
}