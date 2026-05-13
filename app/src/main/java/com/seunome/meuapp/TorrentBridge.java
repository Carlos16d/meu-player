package com.seunome.meuapp;

import android.content.Context;
import java.io.File;

public class TorrentBridge {
    
    private Context context;
    private TorrentListener listener;
    
    static {
        System.loadLibrary("torrent_bridge");
    }
    
    public interface TorrentListener {
        void onProgress(float progress, int downloadSpeed, int peers);
        void onReady(String videoPath);
        void onError(String error);
        void onStatus(String status);
    }
    
    public TorrentBridge(Context context, TorrentListener listener) {
        this.context = context;
        this.listener = listener;
        
        String savePath = context.getExternalFilesDir(null).getAbsolutePath() + "/torrents/";
        new File(savePath).mkdirs();
        
        initEngine(savePath);
    }
    
    // Métodos nativos
    private native void initEngine(String savePath);
    public native void addMagnet(String magnetUri);
    public native void pause();
    public native void resume();
    public native void destroy();
    
    // Callbacks do C++
    private void onProgress(float progress, int downloadSpeed, int peers) {
        if (listener != null) listener.onProgress(progress, downloadSpeed, peers);
    }
    
    private void onReady(String videoPath) {
        if (listener != null) listener.onReady(videoPath);
    }
    
    private void onError(String error) {
        if (listener != null) listener.onError(error);
    }
    
    private void onStatus(String status) {
        if (listener != null) listener.onStatus(status);
    }
}
