package com.meuapp.player.model;

public class TorrentInfo {
    public long downloaded;
    public long total;
    public int speed;
    public int peers;
    public int seeds;
    public int progress;
    public String fileName;
    public long fileSize;
    
    public String getDownloadedMB() {
        return String.format("%.1f MB", downloaded / 1048576.0);
    }
    
    public String getTotalMB() {
        return String.format("%.1f MB", total / 1048576.0);
    }
    
    public String getSpeedKB() {
        return (speed / 1024) + " KB/s";
    }
    
    public String getPeersInfo() {
        return peers + " peers (" + seeds + " seeds)";
    }
}
