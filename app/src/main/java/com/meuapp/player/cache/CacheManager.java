package com.meuapp.player.cache;

import android.content.Context;
import android.util.Log;

import com.meuapp.player.utils.LogUtils;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class CacheManager {
    private static final String TAG = "CacheManager";
    private static final String CACHE_DIR = ".stream_cache";
    private static final long MAX_CACHE_SIZE = 500 * 1024 * 1024; // 500MB
    
    private File cacheDir;
    private Map<String, CacheEntry> cacheIndex;
    
    private static class CacheEntry {
        File file;
        long size;
        long lastAccess;
        int accessCount;
    }
    
    public CacheManager(Context context) {
        cacheDir = new File(context.getExternalFilesDir(null), CACHE_DIR);
        cacheDir.mkdirs();
        cacheIndex = new LinkedHashMap<>();
        loadCacheIndex();
    }
    
    private void loadCacheIndex() {
        File indexFile = new File(cacheDir, "cache.idx");
        if (indexFile.exists()) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(indexFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length >= 4) {
                        CacheEntry entry = new CacheEntry();
                        entry.file = new File(cacheDir, parts[0]);
                        entry.size = Long.parseLong(parts[1]);
                        entry.lastAccess = Long.parseLong(parts[2]);
                        entry.accessCount = Integer.parseInt(parts[3]);
                        cacheIndex.put(parts[0], entry);
                    }
                }
                reader.close();
                LogUtils.d(TAG, "Índice de cache carregado: " + cacheIndex.size() + " entradas");
            } catch (Exception e) {
                LogUtils.e(TAG, "Erro ao carregar índice", e);
            }
        }
    }
    
    public void saveCacheIndex() {
        try {
            FileWriter writer = new FileWriter(new File(cacheDir, "cache.idx"));
            for (Map.Entry<String, CacheEntry> entry : cacheIndex.entrySet()) {
                CacheEntry e = entry.getValue();
                writer.write(entry.getKey() + "," + e.size + "," + e.lastAccess + "," + e.accessCount + "\n");
            }
            writer.close();
        } catch (Exception e) {
            LogUtils.e(TAG, "Erro ao salvar índice", e);
        }
    }
    
    public File getCachedFile(String hash) {
        CacheEntry entry = cacheIndex.get(hash);
        if (entry != null && entry.file.exists()) {
            entry.lastAccess = System.currentTimeMillis();
            entry.accessCount++;
            LogUtils.d(TAG, "Cache hit: " + hash);
            return entry.file;
        }
        LogUtils.d(TAG, "Cache miss: " + hash);
        return null;
    }
    
    public void addToCache(String hash, File sourceFile) {
        try {
            File cachedFile = new File(cacheDir, hash);
            copyFile(sourceFile, cachedFile);
            
            CacheEntry entry = new CacheEntry();
            entry.file = cachedFile;
            entry.size = cachedFile.length();
            entry.lastAccess = System.currentTimeMillis();
            entry.accessCount = 1;
            
            cacheIndex.put(hash, entry);
            
            // Limpa cache se estiver muito grande
            cleanCache();
            
            saveCacheIndex();
            LogUtils.d(TAG, "Arquivo adicionado ao cache: " + hash);
        } catch (Exception e) {
            LogUtils.e(TAG, "Erro ao adicionar ao cache", e);
        }
    }
    
    private void cleanCache() {
        long totalSize = 0;
        for (CacheEntry entry : cacheIndex.values()) {
            totalSize += entry.size;
        }
        
        if (totalSize > MAX_CACHE_SIZE) {
            LogUtils.d(TAG, "Limpando cache (" + totalSize + " bytes)");
            
            // Remove entradas mais antigas
            cacheIndex.entrySet().removeIf(entry -> {
                if (totalSize <= MAX_CACHE_SIZE * 0.8) return false;
                totalSize -= entry.getValue().size;
                entry.getValue().file.delete();
                return true;
            });
        }
    }
    
    private void copyFile(File source, File dest) throws IOException {
        FileInputStream fis = new FileInputStream(source);
        FileOutputStream fos = new FileOutputStream(dest);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = fis.read(buffer)) > 0) {
            fos.write(buffer, 0, len);
        }
        fos.close();
        fis.close();
    }
    
    public long getCacheSize() {
        long total = 0;
        for (CacheEntry entry : cacheIndex.values()) {
            total += entry.size;
        }
        return total;
    }
    
    public void clear() {
        for (CacheEntry entry : cacheIndex.values()) {
            entry.file.delete();
        }
        cacheIndex.clear();
        saveCacheIndex();
        LogUtils.d(TAG, "Cache limpo");
    }
}
