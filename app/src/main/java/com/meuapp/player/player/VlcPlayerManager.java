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
            }
        });
    }
    
    public void attachToSurface(SurfaceView surfaceView) {
        SurfaceHolder holder = surfaceView.getHolder();
        mediaPlayer.getVLCVout().setVideoSurface(holder.getSurface(), holder);
        mediaPlayer.getVLCVout().setWindowSize(surfaceView.getWidth(), surfaceView.getHeight());
        mediaPlayer.getVLCVout().attachViews();
    }
    
    public void play(String url) {
        Media m = new Media(libVLC, Uri.parse(url));
        m.setHWDecoderEnabled(true, true);
        m.addOption(":network-caching=2000");
        m.addOption(":file-caching=1000");
        mediaPlayer.setMedia(m);
        m.release();
        mediaPlayer.play();
    }
    
    public void pause() {
        mediaPlayer.pause();
    }
    
    public void resume() {
        mediaPlayer.play();
    }
    
    public void stop() {
        mediaPlayer.stop();
    }
    
    public void seekTo(long timeMs) {
        mediaPlayer.setTime(timeMs);
    }
    
    public long getTime() {
        return mediaPlayer.getTime();
    }
    
    public long getLength() {
        return mediaPlayer.getLength();
    }
    
    public boolean isPlaying() {
        return mediaPlayer.isPlaying();
    }
    
    // ==================== ÁUDIO/LEGENDA CACHED ====================
    
    public MediaPlayer.TrackDescription[] getAudioTracks() {
        if (cachedAudioTracks == null) {
            cachedAudioTracks = mediaPlayer.getAudioTracks();
        }
        return cachedAudioTracks;
    }
    
    public MediaPlayer.TrackDescription[] getSubtitleTracks() {
        if (cachedSubtitleTracks == null) {
            cachedSubtitleTracks = mediaPlayer.getSpuTracks();
        }
        return cachedSubtitleTracks;
    }
    
    public int getAudioTrack() {
        return mediaPlayer.getAudioTrack();
    }
    
    public int getSubtitleTrack() {
        return mediaPlayer.getSpuTrack();
    }
    
    public void setAudioTrack(int track) {
        mediaPlayer.setAudioTrack(track);
    }
    
    public void setSubtitleTrack(int track) {
        mediaPlayer.setSpuTrack(track);
    }
    
    public void refreshTracks() {
        cachedAudioTracks = null;
        cachedSubtitleTracks = null;
    }
    
    public void release() {
        mediaPlayer.release();
        libVLC.release();
    }
}
