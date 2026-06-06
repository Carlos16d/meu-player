package com.meuapp.player.player;

import android.net.Uri;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.util.*;

import com.meuapp.player.utils.LogUtils;

public class ExoPlayerManager {
    private static final String TAG = "ExoPlayerManager";
    
    private SimpleExoPlayer player;
    private PlayerView playerView;
    private PlayerListener playerListener;
    
    public interface PlayerListener {
        void onPlayerReady();
        void onPlayerBuffering();
        void onPlayerError(String error);
        void onTracksAvailable(int audioTracks, int subtitleTracks);
    }
    
    public ExoPlayerManager(PlayerView playerView, SimpleExoPlayer player) {
        this.playerView = playerView;
        this.player = player;
        
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setKeepScreenOn(true);
        
        setupPlayerListener();
    }
    
    private void setupPlayerListener() {
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        LogUtils.d(TAG, "Buffering...");
                        if (playerListener != null) playerListener.onPlayerBuffering();
                        break;
                    case Player.STATE_READY:
                        LogUtils.d(TAG, "Pronto para reproduzir");
                        if (playerListener != null) playerListener.onPlayerReady();
                        break;
                    case Player.STATE_ENDED:
                        LogUtils.d(TAG, "Reprodução finalizada");
                        break;
                }
            }
            
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
                
                LogUtils.d(TAG, "Tracks - Áudio: " + audioTracks + ", Legendas: " + subtitleTracks);
                if (playerListener != null) {
                    playerListener.onTracksAvailable(audioTracks, subtitleTracks);
                }
            }
            
            @Override
            public void onPlayerError(PlaybackException error) {
                LogUtils.e(TAG, "Erro no player", error);
                if (playerListener != null) {
                    playerListener.onPlayerError(error.getMessage());
                }
            }
        });
    }
    
    public void play(String url) {
        LogUtils.d(TAG, "Iniciando reprodução: " + url);
        
        Uri videoUri = Uri.parse(url);
        
        DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(10000)
            .setAllowCrossProtocolRedirects(true);
        
        ProgressiveMediaSource.Factory mediaSourceFactory = 
            new ProgressiveMediaSource.Factory(dataSourceFactory);
        
        MediaSource mediaSource = mediaSourceFactory.createMediaSource(
            MediaItem.fromUri(videoUri));
        
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
    
    public void show() {
        if (playerView != null) {
            playerView.setVisibility(android.view.View.VISIBLE);
        }
    }
    
    public void hide() {
        if (playerView != null) {
            playerView.setVisibility(android.view.View.GONE);
        }
    }
}