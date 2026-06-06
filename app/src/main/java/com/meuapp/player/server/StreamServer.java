package com.meuapp.player.server;

import java.io.*;
import java.net.*;

public class StreamServer {
    private ServerSocket serverSocket;
    private boolean running = false;
    private final HttpHandler httpHandler = new HttpHandler();
    
    public void setVideoFile(File f) { httpHandler.setVideoFile(f); }
    
    public void start() {
        if (running) return;
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8080);
                serverSocket.setReuseAddress(true);
                while (running) {
                    try {
                        Socket c = serverSocket.accept();
                        new Thread(() -> httpHandler.handle(c)).start();
                    } catch (IOException e) {}
                }
            } catch (IOException e) {}
        }).start();
    }
    
    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) {}
    }
}