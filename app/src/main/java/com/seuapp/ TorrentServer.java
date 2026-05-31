package com.seuapp;

import android.content.Context;
import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.alerts.*;
import com.frostwire.jlibtorrent.swig.settings_pack;

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
        SettingsPack sp = new SettingsPack();
        sp.setBoolean(settings_pack.bool_types.enable_dht, true);
        sp.setInteger(settings_pack.int_types.dht_announce_interval, 300);
        sp.setBoolean(settings_pack.bool_types.enable_lsd, true);
        sp.setBoolean(settings_pack.bool_types.enable_upnp, true);
        sp.setBoolean(settings_pack.bool_types.enable_natpmp, true);
        sp.setInteger(settings_pack.int_types.alert_mask, 
            AlertCategory.ALL.getSwig());
        sp.setInteger(settings_pack.int_types.connections_limit, 200);
        sp.setInteger(settings_pack.int_types.download_rate_limit, 0);
        sp.setInteger(settings_pack.int_types.upload_rate_limit, 0);
        sp.setBoolean(settings_pack.bool_types.announce_to_all_trackers, true);
        sp.setBoolean(settings_pack.bool_types.announce_to_all_tiers, true);
        
        session = new SessionManager(sp);
        session.start();
        
        // Adiciona DHT routers
        session.addDhtNode(new Pair<>("router.bittorrent.com", 6881));
        session.addDhtNode(new Pair<>("dht.transmissionbt.com", 6881));
        session.addDhtNode(new Pair<>("dht.libtorrent.org", 25401));
        
        // Adiciona trackers UDP padrão
        addDefaultTrackers();
        
        // Thread para processar alertas
        startAlertListener();
    }
    
    private void addDefaultTrackers() {
        String[][] trackers = {
            {"udp://tracker.opentrackr.org:1337"},
            {"udp://tracker.openbittorrent.com:6969"},
            {"udp://open.stealth.si:80"},
            {"udp://tracker.torrent.eu.org:451"},
            {"udp://tracker.moeking.me:6969"},
            {"udp://explodie.org:6969"},
            {"udp://tracker.cyberia.is:6969"},
            {"udp://9.rarbg.to:2710"},
            {"udp://tracker.coppersurfer.tk:6969"}
        };
        
        for (String[] tracker : trackers) {
            session.addTrackers(new Pair<>(tracker[0], 0));
        }
    }
    
    public void playTorrent(String magnetURI) {
        playTorrent(magnetURI, 0);
    }
    
    public void playTorrent(String magnetURI, int fileIndex) {
        try {
            // Remove torrent anterior se existir
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
            byte[] data = magnetURI.getBytes();
            session.download(data, cacheDir, null, null, null);
            
            // Aguarda metadados
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                } catch (Exception e) {}
                
                TorrentHandle[] handles = session.find(magnetURI);
                if (handles.length > 0) {
                    torrentHandle = handles[0];
                    
                    // Configura download sequencial
                    torrentHandle.setSequentialDownload(true);
                    
                    // Define prioridade apenas para o arquivo de vídeo
                    TorrentInfo ti = torrentHandle.torrentFile();
                    if (ti != null && ti.numFiles() > fileIndex) {
                        // Prioridade alta para o arquivo selecionado
                        int[] filePriorities = new int[ti.numFiles()];
                        for (int i = 0; i < filePriorities.length; i++) {
                            filePriorities[i] = (i == fileIndex) ? 7 : 0;
                        }
                        torrentHandle.prioritizeFiles(Priority.array(filePriorities));
                        
                        // Configura peças para download sequencial
                        long fileSize = ti.files().fileSize(fileIndex);
                        currentFile = new File(cacheDir, ti.files().filePath(fileIndex));
                    }
                    
                    // Atualiza o StreamServer com o arquivo
                    streamServer.setTorrentHandle(torrentHandle, fileIndex, cacheDir);
                }
            }).start();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void startAlertListener() {
        new Thread(() -> {
            while (session != null) {
                Alert[] alerts = session.alerts();
                for (Alert alert : alerts) {
                    switch (alert.type()) {
                        case TORRENT_UPDATE:
                            updateStats();
                            break;
                        case TORRENT_FINISHED:
                            System.out.println("Download completo!");
                            break;
                    }
                }
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {}
            }
        }).start();
    }
    
    private void updateStats() {
        if (torrentHandle != null) {
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
            session.stop();
        }
    }
}
