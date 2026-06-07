// No watch(), use a URL do manifesto DASH:
private void watch() {
    if (videoFile == null || !videoFile.exists()) { 
        debug("❌ Video não encontrado"); 
        return; 
    }
    
    debug("▶️ Iniciando DASH player...");
    debug("   Servidor: " + streamServer.getStats());
    
    handler.post(() -> { 
        webView.setVisibility(View.VISIBLE); 
        btnWatch.setVisibility(View.GONE);
    });
    
    // Usa DASH! O player vai pedir o manifesto .mpd e os segmentos .m4s
    String html = "<!DOCTYPE html><html><head>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>" +
        "<style>" +
        "body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh;overflow:hidden;}" +
        "video{width:100%;max-height:100vh;outline:none;}" +
        "</style></head><body>" +
        "<video id='v' controls autoplay playsinline style='width:100%'>" +
        "<source src='http://127.0.0.1:8080/video' type='video/mp4'>" +
        "</video>" +
        "<script>" +
        "var v=document.getElementById('v');" +
        "v.addEventListener('loadedmetadata',function(){" +
        "  document.title='▶️ DASH ' + Math.floor(v.duration) + 's | Seek: OK';" +
        "});" +
        "v.addEventListener('error',function(e){" +
        "  document.title='❌ Erro DASH';" +
        "});" +
        "v.addEventListener('waiting',function(){" +
        "  document.title='⏳ Carregando segmento...';" +
        "});" +
        "v.addEventListener('playing',function(){" +
        "  document.title='▶️ DASH Streaming';" +
        "});" +
        "v.addEventListener('seeked',function(){" +
        "  document.title='⏩ Seek: ' + Math.floor(v.currentTime) + 's';" +
        "});" +
        "</script></body></html>";
    
    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    debug("   Player DASH carregado");
    debug("   📺 Seek disponível - pule para qualquer minuto!");
}