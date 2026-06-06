package com.meuapp.player.player;

import android.net.Uri;
import android.view.View;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.ui.*;
import com.google.android.exoplayer2.upstream.*;

public class ExoPlayerManager {
    private SimpleExoPlayer player;
    private PlayerView playerView;
    
    public ExoPlayerManager(PlayerView view, SimpleExoPlayer p) {
        this.playerView = view;
        this.player = p;
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setKeepScreenOn(true);
    }
    
    public void play(String url) {
        Uri uri = Uri.parse(url);
        DataSource.Factory factory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(10000);
        ProgressiveMediaSource.Factory mediaFactory = new ProgressiveMediaSource.Factory(factory);
        MediaSource source = mediaFactory.createMediaSource(MediaItem.fromUri(uri));
        player.setMediaSource(source);
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
    
    public void show() { playerView.setVisibility(View.VISIBLE); }
    public void hide() { playerView.setVisibility(View.GONE); }
}