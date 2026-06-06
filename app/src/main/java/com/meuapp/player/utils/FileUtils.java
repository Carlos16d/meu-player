package com.meuapp.player.utils;

import android.util.Log;

import java.io.*;
import java.security.MessageDigest;

public class FileUtils {
    private static final String TAG = "FileUtils";
    
    public static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
    
    public static String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf(".")).toLowerCase();
    }
    
    public static boolean isVideoFile(String fileName) {
        String ext = getFileExtension(fileName);
        return ext.equals(".mp4") || ext.equals(".mkv") || 
               ext.equals(".avi") || ext.equals(".webm") || 
               ext.equals(".mov") || ext.equals(".flv") ||
               ext.equals(".wmv") || ext.equals(".m4v");
    }
    
    public static String getMimeType(String fileName) {
        String ext = getFileExtension(fileName);
        switch (ext) {
            case ".mkv": return "video/x-matroska";
            case ".webm": return "video/webm";
            case ".avi": return "video/x-msvideo";
            case ".mov": return "video/quicktime";
            case ".flv": return "video/x-flv";
            default: return "video/mp4";
        }
    }
    
    public static String getFileHash(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                md.update(buffer, 0, len);
            }
            fis.close();
            
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao calcular hash", e);
            return "";
        }
    }
    
    public static long getDirectorySize(File dir) {
        long size = 0;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        size += getDirectorySize(file);
                    } else {
                        size += file.length();
                    }
                }
            }
        }
        return size;
    }
    
    public static String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1048576) return String.format("%.1f KB", size / 1024.0);
        if (size < 1073741824) return String.format("%.1f MB", size / 1048576.0);
        return String.format("%.2f GB", size / 1073741824.0);
    }
}
