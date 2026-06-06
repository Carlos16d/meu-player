package com.meuapp.player.engine;

import android.util.Log;

import com.meuapp.player.utils.LogUtils;

import org.libtorrent4j.swig.*;

import java.util.ArrayList;
import java.util.List;

public class PeersManager {
    private static final String TAG = "PeersManager";
    
    private int totalPeers = 0;
    private int connectedPeers = 0;
    private int seeds = 0;
    private List<String> peerAddresses = new ArrayList<>();
    
    public void update(torrent_status status) {
        totalPeers = status.get_num_peers();
        seeds = status.get_num_seeds();
        connectedPeers = totalPeers - seeds;
        
        LogUtils.d(TAG, "Peers: " + totalPeers + " total, " + seeds + " seeds, " + connectedPeers + " connected");
    }
    
    public int getTotalPeers() { return totalPeers; }
    public int getConnectedPeers() { return connectedPeers; }
    public int getSeeds() { return seeds; }
}