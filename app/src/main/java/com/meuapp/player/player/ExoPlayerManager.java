package com.meuapp.player.player;

import android.net.Uri;
import android.util.Log;
import android.view.View;

import com.google.android.exoplayer2.*;
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
        
        Log.d(TAG, "Inicializando ExoPlayer...");
        
        // Configura controles de áudio/legendas
        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(5000);
        playerView.setKeepScreenOn(true);
        playerView.setControllerAutoShow(true);
        
        // Mostra botões de áudio e legendas
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        
        setupPlayerListener();
        Log.d(TAG, "ExoPlayer configurado");
    }
    
    private void setupPlayerListener() {
        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                int audioTracks = 0;
                int subtitleTracks = 0;
                
                Log.d(TAG, "=== TRACKS DISPONÍVEIS ===");
                Log.d(TAG, "Grupos: " + tracks.getGroups().size());
                
                for (Tracks.Group group : tracks.getGroups()) {
                    int type = group.getMediaTrackGroup().type;
                    String typeName = type == C.TRACK_TYPE_AUDIO ? "ÁUDIO" : 
                                     type == C.TRACK_TYPE_TEXT ? "LEGENDA" : 
                                     type == C.TRACK_TYPE_VIDEO ? "VÍDEO" : "OUTRO";
                    
                    Log.d(TAG, "Grupo " + typeName + ": " + group.length + " faixas");
                    
                    for (int i = 0; i < group.length; i++) {
                        Format format = group.getTrackFormat(i);
                        Log.d(TAG, "  Faixa " + i + ": " + format.language + 
                              " - " + format.label + 
                              " - " + format.sampleMimeType);
                    }
                    
                    if (type == C.TRACK_TYPE_AUDIO) {
                        audioTracks += group.length;
                    }
                    if (type == C.TRACK_TYPE_TEXT) {
                        subtitleTracks += group.length;
                    }
                }
                
                Log.d(TAG, "Total áudio: " + audioTracks + ", legendas: " + subtitleTracks);
                
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
                Log.d(TAG, "Estado do player: " + stateName);
                
                if (playerListener != null) {
                    playerListener.onBuffering(state == Player.STATE_BUFFERING);
                }
            }
            
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "ERRO NO PLAYER: " + error.getMessage(), error);
                if (playerListener != null) {
                    playerListener.onError(error.getMessage());
                }
            }
            
            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPos, 
                                                 Player.PositionInfo newPos, 
                                                 int reason) {
                Log.d(TAG, "Seek: " + oldPos.positionMs + " -> " + newPos.positionMs + " (reason: " + reason + ")");
            }
        });
    }
    
    public void play(String url) {
        Log.d(TAG, "Iniciando reprodução: " + url);
        
        Uri videoUri = Uri.parse(url);
        
        DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true);
        
        ProgressiveMediaSource.Factory mediaSourceFactory = 
            new ProgressiveMediaSource.Factory(dataSourceFactory);
        
        MediaItem mediaItem = new MediaItem.Builder()
            .setUri(videoUri)
            .build();
        
        MediaSource mediaSource = mediaSourceFactory.createMediaSource(mediaItem);
        
        player.setMediaSource(mediaSource);
        player.prepare();
        player.setPlayWhenReady(true);
        
        Log.d(TAG, "Reprodução iniciada");
    }
    
    public void stop() {
        Log.d(TAG, "Parando player");
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
    }
    
    public void release() {
        Log.d(TAG, "Liberando player");
        if (player != null) {
            player.release();
            player = null;
        }
    }
    
    public void setPlayerListener(PlayerListener listener) {
        this.playerListener = listener;
    }
    
    public void show() { 
        Log.d(TAG, "Mostrando player");
        playerView.setVisibility(View.VISIBLE); 
    }
    
    public void hide() { 
        Log.d(TAG, "Escondendo player");
        playerView.setVisibility(View.GONE); 
    }
}