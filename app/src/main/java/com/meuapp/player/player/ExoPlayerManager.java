package com.meuapp.player.player;

import android.net.Uri;
import android.util.Log;
import android.view.View;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.trackselection.*;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.util.*;

public class ExoPlayerManager {
    private static final String TAG = "ExoPlayer";
    private SimpleExoPlayer player;
    private PlayerView playerView;
    private PlayerListener playerListener;
    
    public interface PlayerListener {
        void onTracksAvailable(int audioTracks, int subtitleTracks);
        void onBuffering(boolean buffering);
        void onError(String error);
    }
    
    public ExoPlayerManager(PlayerView view, SimpleExoPlayer p) {
        this.playerView = view;
        this.player = p;
        
        // Configuração mínima
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(0); // Sempre visível
        playerView.setKeepScreenOn(true);
        
        // Listener
        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                int audioTracks = 0;
                int subtitleTracks = 0;
                
                for (Tracks.Group group : tracks.getGroups()) {
                    if (group.getMediaTrackGroup().type == C.TRACK_TYPE_AUDIO) {
                        audioTracks += group.length;
                        // Log das faixas de áudio
                        for (int i = 0; i < group.length; i++) {
                            Format f = group.getTrackFormat(i);
                            Log.d(TAG, "Audio track " + i + ": " + f.sampleMimeType + " " + f.language + " " + f.codecs);
                        }
                    }
                    if (group.getMediaTrackGroup().type == C.TRACK_TYPE_TEXT) {
                        subtitleTracks += group.length;
                    }
                }
                
                Log.d(TAG, "Tracks: audio=" + audioTracks + " subs=" + subtitleTracks);
                
                if (playerListener != null) {
                    playerListener.onTracksAvailable(audioTracks, subtitleTracks);
                }
            }
            
            @Override
            public void onPlaybackStateChanged(int state) {
                String stateName;
                switch (state) {
                    case Player.STATE_IDLE: stateName = "IDLE"; break;
                    case Player.STATE_BUFFERING: stateName = "BUFFERING"; break;
                    case Player.STATE_READY: stateName = "READY"; break;
                    case Player.STATE_ENDED: stateName = "ENDED"; break;
                    default: stateName = "UNKNOWN"; break;
                }
                Log.d(TAG, "State: " + stateName);
                
                if (playerListener != null) {
                    playerListener.onBuffering(state == Player.STATE_BUFFERING);
                }
            }
            
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Player error: " + error.getErrorCodeName(), error);
                if (playerListener != null) {
                    playerListener.onError(error.getMessage());
                }
            }
            
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                Log.d(TAG, "IsPlaying: " + isPlaying);
            }
        });
    }
    
    public void play(String url) {
        Log.d(TAG, "Play: " + url);
        
        Uri videoUri = Uri.parse(url);
        
        // Configura DataSource com buffer maior e timeout maior
        DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true);
        
        // Configura o fonte de mídia progressiva
        ProgressiveMediaSource.Factory mediaSourceFactory = 
            new ProgressiveMediaSource.Factory(dataSourceFactory)
                .setContinueLoadingCheckIntervalBytes(524288); // 512KB
        
        MediaItem mediaItem = new MediaItem.Builder()
            .setUri(videoUri)
            .setMimeType("video/mp4") // Força MP4
            .build();
        
        MediaSource mediaSource = mediaSourceFactory.createMediaSource(mediaItem);
        
        // Configura o player com loadControl para mais buffer
        player.setMediaSource(mediaSource);
        player.prepare();
        player.setPlayWhenReady(true);
    }
    
    public void stop() {
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
    }
    
    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }
    
    public void setPlayerListener(PlayerListener listener) { 
        this.playerListener = listener; 
    }
}