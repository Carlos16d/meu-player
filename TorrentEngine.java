package com.seunome.meuapp;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.swig.*;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TorrentEngine {
    
    private Session session;
    private TorrentHandle torrentHandle;
    private Context context;
    private TorrentListener listener;
    private String savePath;
    
    public interface TorrentListener {
        void onProgress(float progress, int downloadSpeed, int peers);
        void onReady(String videoPath);
        void onError(String error);
        void onStatus(String status);
    }
    
    public TorrentEngine(Context context, TorrentListener listener) {
        this.context = context;
        this.listener = listener;
        this.savePath = context.getExternalFilesDir(null).getAbsolutePath() + "/torrents/";
        
        // Cria a pasta de download
        new File(savePath).mkdirs();
        
        // Configura a sessão do libtorrent
        SettingsPack settings = new SettingsPack();
        settings.setInteger(settings_pack_types.alert_mask, 
            alert.category_t.error_notification |
            alert.category_t.storage_notification |
            alert.category_t.status_notification);
        settings.setBoolean(settings_pack_types.enable_dht, true);
        settings.setBoolean(settings_pack_types.enable_lsd, true);
        settings.setBoolean(settings_pack_types.enable_upnp, true);
        settings.setBoolean(settings_pack_types.enable_natpmp, true);
        settings.setInteger(settings_pack_types.download_rate_limit, 0);
        settings.setInteger(settings_pack_types.upload_rate_limit, 0);
        settings.setInteger(settings_pack_types.connections_limit, 200);
        settings.setBoolean(settings_pack_types.announce_to_all_trackers, true);
        settings.setBoolean(settings_pack_types.announce_to_all_tiers, true);
        
        session = new Session(settings);
        
        // Inicia o loop de alertas em uma thread separada
        new Thread(this::alertLoop).start();
    }
    
    private void alertLoop() {
        while (session != null && session.isValid()) {
            try {
                Alert[] alerts = session.popAlerts();
                for (Alert alert : alerts) {
                    handleAlert(alert);
                }
                Thread.sleep(500);
            } catch (Exception e) {
                Log.e("TorrentEngine", "Alert loop error: " + e.getMessage());
            }
        }
    }
    
    private void handleAlert(Alert alert) {
        switch (alert.type()) {
            case torrent_added_alert.alert_type:
                TorrentAddedAlert added = (TorrentAddedAlert) alert;
                listener.onStatus("Torrent adicionado: " + added.torrent_name());
                break;
                
            case torrent_finished_alert.alert_type:
                listener.onStatus("Download completo!");
                listener.onProgress(100, 0, 0);
                findVideoFile();
                break;
                
            case state_changed_alert.alert_type:
                if (torrentHandle != null && torrentHandle.isValid()) {
                    TorrentStatus status = torrentHandle.status();
                    float progress = status.progress() * 100;
                    int speed = status.downloadRate() / 1024; // KB/s
                    int peers = status.numPeers();
                    listener.onProgress(progress, speed, peers);
                }
                break;
                
            case torrent_error_alert.alert_type:
                TorrentErrorAlert errorAlert = (TorrentErrorAlert) alert;
                listener.onError("Erro: " + errorAlert.message());
                break;
                
            case metadata_received_alert.alert_type:
                listener.onStatus("Metadados recebidos! Procurando vídeo...");
                findVideoFile();
                break;
        }
    }
    
    public void addMagnet(String magnetUri) {
        listener.onStatus("Adicionando magnet link...");
        
        byte[] resumeData = null;
        AddTorrentParams params = new AddTorrentParams();
        params.setSavePath(savePath);
        params.setFlags(AddTorrentParams.flag_update_subscribe | 
                        AddTorrentParams.flag_auto_managed | 
                        AddTorrentParams.flag_paused);
        
        if (resumeData != null) {
            params.setResumeData(resumeData);
        }
        
        ErrorCode errorCode = new ErrorCode();
        session.asyncAddTorrent(params, errorCode);
        
        try {
            torrentHandle = session.addTorrent(new AddTorrentParams()
                .setSavePath(savePath)
                .setUrl(magnetUri));
            
            // Prioriza os primeiros e últimos pedaços para preview rápido
            torrentHandle.setSequentialDownload(true);
            
            listener.onStatus("Torrent iniciado! Conectando...");
            
        } catch (Exception e) {
            listener.onError("Erro ao adicionar magnet: " + e.getMessage());
        }
    }
    
    private void findVideoFile() {
        if (torrentHandle == null || !torrentHandle.isValid()) return;
        
        TorrentInfo torrentInfo = torrentHandle.torrentFile();
        if (torrentInfo == null) return;
        
        FileInfo largestFile = null;
        long largestSize = 0;
        
        for (FileInfo file : torrentInfo.files()) {
            String fileName = file.path().toLowerCase();
            if (fileName.endsWith(".mp4") || 
                fileName.endsWith(".mkv") || 
                fileName.endsWith(".avi") || 
                fileName.endsWith(".webm") || 
                fileName.endsWith(".mov")) {
                if (file.size() > largestSize) {
                    largestSize = file.size();
                    largestFile = file;
                }
            }
        }
        
        if (largestFile != null) {
            String videoPath = savePath + largestFile.path();
            listener.onStatus("Vídeo encontrado: " + largestFile.path());
            listener.onReady(videoPath);
        } else {
            listener.onError("Nenhum vídeo encontrado no torrent");
        }
    }
    
    public void pause() {
        if (torrentHandle != null && torrentHandle.isValid()) {
            torrentHandle.pause();
        }
    }
    
    public void resume() {
        if (torrentHandle != null && torrentHandle.isValid()) {
            torrentHandle.resume();
        }
    }
    
    public void destroy() {
        if (torrentHandle != null && torrentHandle.isValid()) {
            torrentHandle.pause();
        }
        if (session != null && session.isValid()) {
            session.abort();
        }
        torrentHandle = null;
        session = null;
    }
}
