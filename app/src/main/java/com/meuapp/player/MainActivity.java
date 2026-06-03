private void handleClient(Socket client) {
    int reqNum = requestCount;
    try {
        client.setSoTimeout(10000);
        OutputStream out = client.getOutputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
        
        String request = in.readLine();
        
        String range = null;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("range:")) {
                range = line.substring(6).trim();
            }
        }
        
        if (request == null || !request.contains("/video")) {
            send(out, "HTTP/1.1 404\r\n\r\n");
            client.close();
            return;
        }
        
        File vf = videoFile;
        if (vf == null || !vf.exists() || vf.length() < 131072) { // Pelo menos 128KB
            send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n");
            client.close();
            return;
        }
        
        // VERIFICA CABEÇALHO
        byte[] headerCheck = new byte[16];
        try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
            raf.read(headerCheck);
        }
        boolean valid = false;
        if (headerCheck[4] == 'f' && headerCheck[5] == 't' && headerCheck[6] == 'y' && headerCheck[7] == 'p') valid = true;
        if ((headerCheck[0] & 0xFF) == 0x1A && headerCheck[1] == 0x45 && headerCheck[2] == (byte)0xDF && headerCheck[3] == (byte)0xA3) valid = true;
        
        if (!valid) {
            log("   ❌ 503 - Header inválido: " + bytesToHex(headerCheck));
            send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n");
            client.close();
            return;
        }
        
        long fileLen = vf.length();
        String mime = vf.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
        
        if (range != null) {
            // Range request
            String r = range.replace("bytes=", "");
            String[] parts = r.split("-");
            long start = Long.parseLong(parts[0]);
            long end = (parts.length > 1 && !parts[1].isEmpty()) ? 
                Long.parseLong(parts[1]) : fileLen - 1;
            
            if (start >= fileLen) { send(out, "HTTP/1.1 416\r\n\r\n"); client.close(); return; }
            if (end >= fileLen) end = fileLen - 1;
            if (end - start > 131072) end = start + 131072;
            
            int len = (int)(end - start + 1);
            byte[] buf = new byte[len];
            int total = 0;
            try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
                raf.seek(start);
                while (total < len) { int r2 = raf.read(buf, total, len - total); if (r2 == -1) break; total += r2; }
            }
            if (total == 0) { send(out, "HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n"); client.close(); return; }
            
            out.write("HTTP/1.1 206\r\n".getBytes());
            out.write(("Content-Type: " + mime + "\r\n").getBytes());
            out.write(("Content-Range: bytes " + start + "-" + (start + total - 1) + "/" + fileLen + "\r\n").getBytes());
            out.write(("Content-Length: " + total + "\r\n").getBytes());
            out.write("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n".getBytes());
            out.write(buf, 0, total);
        } else {
            // Primeira requisição
            int firstChunk = (int)Math.min(262144, fileLen);
            byte[] buf = new byte[firstChunk];
            int total = 0;
            try (RandomAccessFile raf = new RandomAccessFile(vf, "r")) {
                while (total < firstChunk) { int r2 = raf.read(buf, total, firstChunk - total); if (r2 == -1) break; total += r2; }
            }
            
            log("   ✅ 200 - " + total + " bytes, Header: " + bytesToHex(headerCheck));
            
            out.write("HTTP/1.1 200 OK\r\n".getBytes());
            out.write(("Content-Type: " + mime + "\r\n").getBytes());
            out.write(("Content-Length: " + total + "\r\n").getBytes());
            out.write("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n".getBytes());
            out.write(buf, 0, total);
        }
        out.flush();
        client.close();
        
    } catch (Exception e) {
        try { client.close(); } catch (IOException ex) {}
    }
}

private String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02X ", b));
    return sb.toString();
}
