package com.meuapp.player.player;

import android.net.Uri;
import android.util.Log;
import android.view.View;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.audio.*;
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
        
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(0);
        playerView.setKeepScreenOn(true);
        
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
                Log.e(TAG, "Player error", error);
                if (playerListener != null) {
                    playerListener.onError(error.getMessage());
                }
            }
        });
    }
    
    public void play(String url) {
        Uri videoUri = Uri.parse(url);
        
        // Configura renderers para suportar mais codecs
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(playerView.getContext())
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        
        // DataSource
        DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true);
        
        ProgressiveMediaSource.Factory mediaSourceFactory = 
            new ProgressiveMediaSource.Factory(dataSourceFactory);
        
        MediaItem mediaItem = MediaItem.fromUri(videoUri);
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
}