package com.seuapp;

import android.content.Context;

import org.libtorrent4j.AlertListener;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.swig.*;

import java.io.File;

public class TorrentServer {
    private SessionManager session;
    private TorrentHandle torrentHandle;
    private StreamServer streamServer;
    private float progress = 0;
    private int peerCount = 0;
    private long downloadSpeed = 0;
    private File currentFile;
    
    public TorrentServer(Context context, StreamServer streamServer) {
        this.streamServer = streamServer;
        initSession();
    }
    
    private void initSession() {
        // Configurações para UDP, DHT e trackers
        settings_pack sp = new settings_pack();
        sp.set_bool(settings_pack.bool_types.enable_dht, true);
        sp.set_int(settings_pack.int_types.dht_announce_interval, 300);
        sp.set_bool(settings_pack.bool_types.enable_lsd, true);
        sp.set_bool(settings_pack.bool_types.enable_upnp, true);
        sp.set_bool(settings_pack.bool_types.enable_natpmp, true);
        sp.set_int(settings_pack.int_types.alert_mask, 
            alert.category_t.all_categories.swigValue());
        sp.set_int(settings_pack.int_types.connections_limit, 200);
        sp.set_int(settings_pack.int_types.download_rate_limit, 0);
        sp.set_int(settings_pack.int_types.upload_rate_limit, 0);
        sp.set_bool(settings_pack.bool_types.announce_to_all_trackers, true);
        sp.set_bool(settings_pack.bool_types.announce_to_all_tiers, true);
        
        SessionManager.setParams(sp);
        session = new SessionManager();
        
        // Adiciona DHT routers
        session.addDhtNode("router.bittorrent.com", 6881);
        session.addDhtNode("dht.transmissionbt.com", 6881);
        session.addDhtNode("dht.libtorrent.org", 25401);
        
        // Thread para processar alertas
        startAlertListener();
    }
    
    public void playTorrent(String magnetURI) {
        playTorrent(magnetURI, 0);
    }
    
    public void playTorrent(String magnetURI, int fileIndex) {
        try {
            // Remove torrent anterior
            if (torrentHandle != null) {
                session.remove(torrentHandle);
            }
            
            // Cria diretório de cache
            File cacheDir = new File(
                android.os.Environment.getExternalStorageDirectory(), 
                "MeuAppCache"
            );
            cacheDir.mkdirs();
            
            // Adiciona o torrent
            add_torrent_params params = new add_torrent_params();
            params.set_url(magnetURI);
            params.set_save_path(cacheDir.getAbsolutePath());
            
            // Força download sequencial
            params.set_flags(add_torrent_params.flags_t.flag_sequential_download.swigValue());
            
            session.download(params);
            
            // Aguarda metadados e configura
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                } catch (Exception e) {}
                
                // Procura o torrent adicionado
                torrentHandle = session.find(magnetURI);
                
                if (torrentHandle != null) {
                    TorrentInfo ti = torrentHandle.torrentFile();
                    if (ti != null && ti.numFiles() > fileIndex) {
                        // Configura prioridade do arquivo
                        int[] filePriorities = new int[ti.numFiles()];
                        for (int i = 0; i < filePriorities.length; i++) {
                            filePriorities[i] = (i == fileIndex) ? 7 : 0;
                        }
                        torrentHandle.prioritizeFiles(filePriorities);
                        
                        // Configura peças sequenciais
                        torrentHandle.setSequentialDownload(true);
                        
                        // Atualiza o StreamServer
                        streamServer.setTorrentHandle(torrentHandle, fileIndex, cacheDir);
                    }
                }
            }).start();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void startAlertListener() {
        new Thread(() -> {
            while (session != null) {
                try {
                    session.waitForAlerts(1000);
                    updateStats();
                } catch (Exception e) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {}
                }
            }
        }).start();
    }
    
    private void updateStats() {
        if (torrentHandle != null && torrentHandle.isValid()) {
            TorrentStatus status = torrentHandle.status();
            progress = status.progress() * 100;
            peerCount = status.numPeers();
            downloadSpeed = status.downloadRate();
        }
    }
    
    public float getProgress() { return progress; }
    public int getPeerCount() { return peerCount; }
    public String getDownloadSpeed() {
        if (downloadSpeed > 1048576)
            return String.format("%.1f MB/s", downloadSpeed / 1048576.0);
        else if (downloadSpeed > 1024)
            return String.format("%.1f KB/s", downloadSpeed / 1024.0);
        else
            return downloadSpeed + " B/s";
    }
    
    public void stop() {
        if (session != null) {
            session.close();
        }
    }
}
