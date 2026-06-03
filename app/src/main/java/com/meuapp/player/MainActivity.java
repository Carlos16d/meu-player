private void handleHttpClient(Socket client) {
    try {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
        OutputStream out = client.getOutputStream();
        
        String line = in.readLine();
        if (line == null || !line.contains("/video")) {
            out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return;
        }
        
        long rangeStart = 0;
        long rangeEnd = -1;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("range:")) {
                String rangeValue = line.substring(6).trim();
                if (rangeValue.startsWith("bytes=")) {
                    rangeValue = rangeValue.substring(6);
                    String[] parts = rangeValue.split("-");
                    rangeStart = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) rangeEnd = Long.parseLong(parts[1]);
                }
            }
        }
        
        if (videoFile == null || !videoFile.exists() || torrentHandle == null) {
            out.write("HTTP/1.1 404\r\n\r\n".getBytes()); out.flush(); client.close(); return;
        }
        
        long totalLength = videoFile.length();
        if (rangeEnd == -1) rangeEnd = totalLength - 1;
        
        // 🚀 PRIORIZA a região que o player pediu
        if (torrentHandle.torrentFile() != null) {
            TorrentInfo info = torrentHandle.torrentFile();
            pieceLength = info.pieceLength();
            int startPiece = (int)(rangeStart / pieceLength);
            int endPiece = Math.min(startPiece + 5, info.numPieces() - 1);
            
            for (int i = startPiece; i <= endPiece; i++) {
                try { torrentHandle.setPieceDeadline(i, 500); } catch (Exception e) {}
            }
        }
        
        // Verifica se a PRIMEIRA peça já está disponível
        int firstPiece = (pieceLength > 0) ? (int)(rangeStart / pieceLength) : 0;
        if (pieceLength > 0 && !torrentHandle.havePiece(firstPiece)) {
            // Ainda não tem dados - responde 503 e o player tenta de novo
            out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
            out.flush(); client.close();
            log("⏳ Aguardando peça " + firstPiece);
            return;
        }
        
        String mime = videoFile.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
        long contentLength = rangeEnd - rangeStart + 1;
        if (contentLength > 131072) contentLength = 131072;
        rangeEnd = rangeStart + contentLength - 1;
        
        byte[] buf = new byte[(int)contentLength];
        RandomAccessFile raf = new RandomAccessFile(videoFile, "r");
        raf.seek(rangeStart);
        int total = raf.read(buf);
        raf.close();
        
        if (total <= 0) {
            out.write("HTTP/1.1 503\r\nRetry-After: 1\r\n\r\n".getBytes());
            out.flush(); client.close();
            return;
        }
        
        String headers = "HTTP/1.1 206 Partial Content\r\n" +
            "Content-Type: " + mime + "\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "Content-Range: bytes " + rangeStart + "-" + (rangeStart + total - 1) + "/" + totalLength + "\r\n" +
            "Content-Length: " + total + "\r\n" +
            "Connection: close\r\n\r\n";
        
        out.write(headers.getBytes());
        out.write(buf, 0, total);
        out.flush();
        client.close();
        log("✅ " + (total/1024) + "KB da peça " + firstPiece);
        
    } catch (Exception e) {
        try { client.close(); } catch (IOException ex) {}
    }
}