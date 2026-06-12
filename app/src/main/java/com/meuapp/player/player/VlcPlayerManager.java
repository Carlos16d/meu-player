package com.meuapp.player.player;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.meuapp.player.model.StreamInfo;

import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.*;

import java.util.ArrayList;

/**
 * Gerencia o player VLC.
 * Pré-carrega informações de áudio/legenda.
 */
public class VlcPlayerManager {
    private final LibVLC libVLC;
    private final MediaPlayer mediaPlayer;
    private final StreamInfo info;
    private final PlayerCallback callback;
    
    private MediaPlayer.TrackDescription[] cachedAudioTracks;
    private MediaPlayer.TrackDescription[] cachedSubtitleTracks;
    private boolean surfaceAttached = false;
    
    public interface PlayerCallback {
        void onPlaying();
        void onPaused();
        void onStopped();
        void onBuffering();
        void onTimeChanged(long time, long length);
    }
    
    public VlcPlayerManager(Context context, StreamInfo info, PlayerCallback callback) {
        this.info = info;
        this.callback = callback;
        
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=2000");
        options.add("--file-caching=1000");
        options.add("--clock-synchro=0");
        options.add("--no-audio-time-stretch");
        
        libVLC = new LibVLC(context, options);
        mediaPlayer = new MediaPlayer(libVLC);
        
        mediaPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    callback.onPlaying();
                    break;
                case MediaPlayer.Event.Paused:
                    callback.onPaused();
                    break;
                case MediaPlayer.Event.Stopped:
                    callback.onStopped();
                    break;
                case MediaPlayer.Event.Buffering:
                    callback.onBuffering();
                    break;
                case MediaPlayer.Event.TimeChanged:
                    callback.onTimeChanged(event.getTimeChanged(), mediaPlayer.getLength());
                    break;
            }
        });
    }
    
    /**
     * Anexa o player à superfície de vídeo
     */
    public void attachToSurface(SurfaceView surfaceView) {
        if (surfaceAttached) return;
        
        try {
            SurfaceHolder holder = surfaceView.getHolder();
            mediaPlayer.getVLCVout().setVideoSurface(holder.getSurface(), holder);
            mediaPlayer.getVLCVout().setWindowSize(surfaceView.getWidth(), surfaceView.getHeight());
            mediaPlayer.getVLCVout().attachViews();
            surfaceAttached = true;
        } catch (Exception e) {
            // Se falhar, tenta de novo sem attachViews
            try {
                SurfaceHolder holder = surfaceView.getHolder();
                mediaPlayer.getVLCVout().setVideoSurface(holder.getSurface(), holder);
                surfaceAttached = true;
            } catch (Exception e2) {
                e2.printStackTrace();
            }
        }
    }
    
    public void play(String url) {
        try {
            Media m = new Media(libVLC, Uri.parse(url));
            m.setHWDecoderEnabled(true, true);
            m.addOption(":network-caching=2000");
            m.addOption(":file-caching=1000");
            mediaPlayer.setMedia(m);
            m.release();
            mediaPlayer.play();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void pause() {
        try { mediaPlayer.pause(); } catch (Exception e) {}
    }
    
    public void resume() {
        try { mediaPlayer.play(); } catch (Exception e) {}
    }
    
    public void stop() {
        try { mediaPlayer.stop(); } catch (Exception e) {}
    }
    
    public void seekTo(long timeMs) {
        try { mediaPlayer.setTime(timeMs); } catch (Exception e) {}
    }
    
    public long getTime() {
        try { return mediaPlayer.getTime(); } catch (Exception e) { return 0; }
    }
    
    public long getLength() {
        try { return mediaPlayer.getLength(); } catch (Exception e) { return 0; }
    }
    
    public boolean isPlaying() {
        try { return mediaPlayer.isPlaying(); } catch (Exception e) { return false; }
    }
    
    // ==================== ÁUDIO/LEGENDA CACHED ====================
    
    public MediaPlayer.TrackDescription[] getAudioTracks() {
        if (cachedAudioTracks == null) {
            try { cachedAudioTracks = mediaPlayer.getAudioTracks(); } catch (Exception e) {}
        }
        return cachedAudioTracks;
    }
    
    public MediaPlayer.TrackDescription[] getSubtitleTracks() {
        if (cachedSubtitleTracks == null) {
            try { cachedSubtitleTracks = mediaPlayer.getSpuTracks(); } catch (Exception e) {}
        }
        return cachedSubtitleTracks;
    }
    
    public int getAudioTrack() {
        try { return mediaPlayer.getAudioTrack(); } catch (Exception e) { return -1; }
    }
    
    public int getSubtitleTrack() {
        try { return mediaPlayer.getSpuTrack(); } catch (Exception e) { return -1; }
    }
    
    public void setAudioTrack(int track) {
        try { mediaPlayer.setAudioTrack(track); } catch (Exception e) {}
    }
    
    public void setSubtitleTrack(int track) {
        try { mediaPlayer.setSpuTrack(track); } catch (Exception e) {}
    }
    
    public void refreshTracks() {
        cachedAudioTracks = null;
        cachedSubtitleTracks = null;
    }
    
    public void release() {
        try {
            mediaPlayer.stop();
            mediaPlayer.getVLCVout().detachViews();
        } catch (Exception e) {}
        try { mediaPlayer.release(); } catch (Exception e) {}
        try { libVLC.release(); } catch (Exception e) {}
    }
}