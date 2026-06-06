package com.meuapp.player.engine;

public class PeersManager {
    private int totalPeers = 0;
    private int seeds = 0;
    
    public void update(int peers, int seeds) {
        this.totalPeers = peers;
        this.seeds = seeds;
    }
    
    public int getTotalPeers() { return totalPeers; }
    public int getConnectedPeers() { return totalPeers - seeds; }
    public int getSeeds() { return seeds; }
}