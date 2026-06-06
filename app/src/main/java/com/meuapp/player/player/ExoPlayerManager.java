package com.meuapp.player.player;

import android.net.Uri;
import android.view.View;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.trackselection.*;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.util.*;

public class ExoPlayerManager {
    private SimpleExoPlayer player;
    private PlayerView playerView;
    private TrackSelector trackSelector;
    private PlayerListener playerListener;
    
    public interface PlayerListener {
        void onTracksAvailable(int audioTracks, int subtitleTracks);
        void onBuffering(boolean buffering);
        void onError(String error);
    }
    
    public ExoPlayerManager(PlayerView view, SimpleExoPlayer p) {
        this.playerView = view;
        this.player = p;
        
        // Configura o player para mostrar controles de áudio/legendas
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(5000);
        playerView.setKeepScreenOn(true);
        
        // Habilita seek em qualquer posição
        playerView.setControllerAutoShow(true);
        
        setupPlayerListener();
    }
    
    private void setupPlayerListener() {
        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                int audioTracks = 0;
                int subtitleTracks = 0;
                
                for (Tracks.Group group : tracks.getGroups()) {
                    if (group.getMediaTrackGroup().type == C.TRACK_TYPE_AUDIO) {
                        audioTracks += group.length;
                    }
                    if (group.getMediaTrackGroup().type == C.TRACK_TYPE_TEXT) {
                        subtitleTracks += group.length;
                    }
                }
                
                if (playerListener != null) {
                    playerListener.onTracksAvailable(audioTracks, subtitleTracks);
                }
            }
            
            @Override
            public void onPlaybackStateChanged(int state) {
                if (playerListener != null) {
                    playerListener.onBuffering(state == Player.STATE_BUFFERING);
                }
            }
            
            @Override
            public void onPlayerError(PlaybackException error) {
                if (playerListener != null) {
                    playerListener.onError(error.getMessage());
                }
            }
            
            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, 
                                                 Player.PositionInfo newPosition, 
                                                 int reason) {
                // Seek foi realizado - o player vai pedir os dados via HTTP Range
            }
        });
    }
    
    public void play(String url) {
        Uri videoUri = Uri.parse(url);
        
        // Configura DataSource para suportar Range requests (essencial para seek)
        DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true);
        
        // Usa ProgressiveMediaSource que suporta seek em qualquer posição
        ProgressiveMediaSource.Factory mediaSourceFactory = 
            new ProgressiveMediaSource.Factory(dataSourceFactory);
        
        MediaItem mediaItem = new MediaItem.Builder()
            .setUri(videoUri)
            .build();
        
        MediaSource mediaSource = mediaSourceFactory.createMediaSource(mediaItem);
        
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
    
    public void show() { playerView.setVisibility(View.VISIBLE); }
    public void hide() { playerView.setVisibility(View.GONE); }
}