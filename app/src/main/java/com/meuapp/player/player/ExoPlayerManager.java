package com.meuapp.player.player;

import android.net.Uri;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;

public class ExoPlayerManager {
    private SimpleExoPlayer player;
    private PlayerView playerView;
    
    public ExoPlayerManager(PlayerView playerView, SimpleExoPlayer player) {
        this.playerView = playerView;
        this.player = player;
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setKeepScreenOn(true);
    }
    
    public void play(String url) {
        Uri videoUri = Uri.parse(url);
        
        DataSource.Factory factory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(10000);
        
        ProgressiveMediaSource.Factory mediaFactory = new ProgressiveMediaSource.Factory(factory);
        MediaSource mediaSource = mediaFactory.createMediaSource(MediaItem.fromUri(videoUri));
        
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
}
