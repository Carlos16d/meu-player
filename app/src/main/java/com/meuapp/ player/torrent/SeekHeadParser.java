package com.meuapp.player.torrent;

import com.meuapp.player.model.StreamInfo;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.*;

/**
 * Parser do SeekHead MKV para encontrar posições exatas
 * dos elementos Cues, Tracks, Tags, Info, etc.
 */
public class SeekHeadParser {
    private final StreamInfo info;
    private final Set<Integer> requiredPieces = new HashSet<>();
    
    public SeekHeadParser(StreamInfo info) {
        this.info = info;
    }
    
    /**
     * Parseia o SeekHead e retorna as peças críticas
     */
    public Set<Integer> parse() {
        requiredPieces.clear();
        
        if (info.videoFile == null || !info.videoFile.exists() || info.pieceLength <= 0) {
            return requiredPieces;
        }
        
        try {
            RandomAccessFile raf = new RandomAccessFile(info.videoFile, "r");
            long fileLen = raf.length();
            
            // Ler 128KB do início
            byte[] header = new byte[Math.min(131072, (int)fileLen)];
            raf.read(header);
            raf.close();
            
            // Encontrar Segmento
            int segPos = findPattern(header, new byte[]{(byte)0x18, (byte)0x53, (byte)0x80, (byte)0x67});
            if (segPos < 0) return requiredPieces;
            
            int segDataStart = segPos + 4 + eblen(header, segPos + 4);
            
            // Encontrar SeekHead
            int shPos = findPattern(header, new byte[]{(byte)0x11, (byte)0x4D, (byte)0x9B, (byte)0x74}, segDataStart);
            if (shPos < 0) return requiredPieces;
            
            int shDataStart = shPos + 4 + eblen(header, shPos + 4);
            long shSize = ebsize(header, shPos + 4);
            int shEnd = (int)(shDataStart + shSize);
            
            // Parsear entradas Seek
            Map<Long, Long> positions = new HashMap<>();
            int pos = shDataStart;
            
            while (pos < shEnd && pos < header.length - 8) {
                if (header[pos] == (byte)0x4D && header[pos+1] == (byte)0xBB) {
                    pos += 2;
                    long entrySize = ebsize(header, pos);
                    pos += eblen(header, pos);
                    int entryEnd = (int)(pos + entrySize);
                    
                    long elemId = -1, elemPos = -1;
                    int ip = pos;
                    while (ip < entryEnd && ip < header.length - 8) {
                        if (header[ip] == (byte)0x53 && header[ip+1] == (byte)0xAB) {
                            ip += 2;
                            long idSize = ebsize(header, ip);
                            ip += eblen(header, ip);
                            elemId = readUInt(header, ip, (int)idSize);
                            ip += (int)idSize;
                        } else if (header[ip] == (byte)0x53 && header[ip+1] == (byte)0xAC) {
                            ip += 2;
                            long posSize = ebsize(header, ip);
                            ip += eblen(header, ip);
                            elemPos = readUInt(header, ip, (int)posSize);
                            ip += (int)posSize;
                        } else {
                            ip++;
                        }
                    }
                    
                    if (elemId > 0 && elemPos >= 0) {
                        positions.put(elemId, segDataStart + elemPos);
                    }
                    pos = entryEnd;
                } else {
                    pos++;
                }
            }
            
            // Identificar elementos importantes
            long[] elemIds = {0x1C53BB6BL, 0x1654AE6BL, 0x1254C367L, 0x1043A770L, 0x1549A966L, 0x1941A469L};
            
            for (long id : elemIds) {
                if (positions.containsKey(id)) {
                    long bytePos = positions.get(id);
                    int piece = info.byteToPiece(bytePos);
                    requiredPieces.add(piece);
                    
                    if (id == 0x1C53BB6BL) {
                        info.cuesPosition = bytePos;
                    } else if (id == 0x1654AE6BL) {
                        info.tracksPosition = bytePos;
                    } else if (id == 0x1549A966L) {
                        info.infoPosition = bytePos;
                    } else if (id == 0x1254C367L) {
                        info.tagsPosition = bytePos;
                    }
                }
            }
            
            // Calcular tamanho das Cues
            if (info.cuesPosition > 0) {
                Long nextPos = null;
                for (long p : positions.values()) {
                    if (p > info.cuesPosition && (nextPos == null || p < nextPos)) {
                        nextPos = p;
                    }
                }
                
                if (nextPos != null) {
                    info.cuesSize = nextPos - info.cuesPosition;
                } else {
                    // Procurar Tags no final do arquivo
                    raf = new RandomAccessFile(info.videoFile, "r");
                    if (fileLen > 524288) {
                        raf.seek(fileLen - 524288);
                        byte[] tail = new byte[524288];
                        raf.read(tail);
                        raf.close();
                        
                        int tagsIdx = findPattern(tail, new byte[]{(byte)0x12, (byte)0x54, (byte)0xC3, (byte)0x67});
                        if (tagsIdx >= 0) {
                            info.cuesSize = (fileLen - 524288 + tagsIdx) - info.cuesPosition;
                        } else {
                            info.cuesSize = fileLen - info.cuesPosition;
                        }
                    } else {
                        info.cuesSize = fileLen - info.cuesPosition;
                    }
                }
                
                // Adicionar todas as peças das Cues
                int cs = info.byteToPiece(info.cuesPosition);
                int ce = info.byteToPiece(info.cuesPosition + info.cuesSize);
                for (int i = cs; i <= ce && i < info.numPieces; i++) {
                    requiredPieces.add(i);
                }
            }
            
            // Sempre incluir cabeçalho (15 peças)
            for (int i = 0; i < Math.min(15, info.numPieces); i++) {
                requiredPieces.add(i);
            }
            
        } catch (Exception e) {
            // Fallback: cabeçalho + final
            for (int i = 0; i < Math.min(20, info.numPieces); i++) requiredPieces.add(i);
            for (int i = Math.max(0, info.numPieces - 10); i < info.numPieces; i++) requiredPieces.add(i);
        }
        
        return requiredPieces;
    }
    
    // ==================== HELPERS EBML ====================
    
    private int findPattern(byte[] data, byte[] pattern) {
        return findPattern(data, pattern, 0);
    }
    
    private int findPattern(byte[] data, byte[] pattern, int start) {
        for (int i = start; i < data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i+j] != pattern[j]) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }
    
    private long ebsize(byte[] data, int offset) {
        if (offset >= data.length) return 0;
        int firstByte = data[offset] & 0xFF, mask = 0x80, len = 0;
        for (int i = 0; i < 8; i++) {
            if ((firstByte & mask) != 0) { len = i + 1; break; }
            mask >>= 1;
        }
        if (len == 0 || offset + len > data.length) return 0;
        long size = firstByte & (0xFF >> len);
        for (int i = 1; i < len; i++) size = (size << 8) | (data[offset + i] & 0xFF);
        return size;
    }
    
    private int eblen(byte[] data, int offset) {
        if (offset >= data.length) return 0;
        int firstByte = data[offset] & 0xFF, mask = 0x80;
        for (int i = 0; i < 8; i++) {
            if ((firstByte & mask) != 0) return i + 1;
            mask >>= 1;
        }
        return 1;
    }
    
    private long readUInt(byte[] data, int offset, int size) {
        long val = 0;
        for (int i = 0; i < size && offset + i < data.length; i++) {
            val = (val << 8) | (data[offset + i] & 0xFF);
        }
        return val;
    }
}
