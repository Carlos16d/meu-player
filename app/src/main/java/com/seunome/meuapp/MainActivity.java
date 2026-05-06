package com.seunome.meuapp;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Esconder ActionBar
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }

        // TELA CHEIA MESMO EM CELULARES 20:9
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // HTML AJUSTADO PARA TELAS 20:9
        String html = "<!DOCTYPE html>" +
                "<html lang='pt-BR'>" +
                "<head>" +
                "<meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>" +
                "<style>" +
                "html, body { width: 100%; height: 100%; background: orchid; display: flex; justify-content: center; align-items: center; margin: 0; padding: 0; overflow: hidden; }" + // OCUPAR TELA TODA
                ".area { position: relative; width: 90vw; height: 90vh; }" + // ALTURA USANDO VH PARA TELAS COMPRIDAS
                ".quadrado { width: 40%; padding-top: 40%; position: relative; cursor: pointer; }" + // GARANTE QUADRADO
                ".quadrado-inner { position: absolute; top: 0; left: 0; width: 100%; height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; }" +
                ".quadrado img { width: 100%; height: 100%; object-fit: cover; border-radius: 15px; }" +
                ".legenda { margin-top: 10px; font-family: sans-serif; font-size: 32px; font-weight: bold; color: white; text-align: center; white-space: nowrap; }" +
                ".azul { top: 0; left: 0; z-index: 10; --tx:0px; --ty:0px; transform: translate(var(--tx), var(--ty)); }" +
                ".verde { top: 0; left: 0; transform: scale(0); opacity:0; pointer-events:none; }" +
                ".vermelho { top: 0; right: 0; transform: scale(0); opacity:0; pointer-events:none; }" +
                ".amarelo { bottom: 0; right: 0; transform: scale(0); opacity:0; pointer-events:none; }" +
                ".p1 .azul { --tx: 150%; --ty: 0px; }" +
                ".p1 .verde { animation: estourar 1.2s ease forwards; }" +
                ".p2 .azul { --tx: 150%; --ty: 150%; }" +
                ".p2 .vermelho { animation: estourar 1.2s ease forwards; }" +
                ".p3 .azul { --tx: 0px; --ty: 150%; }" +
                ".p3 .amarelo { animation: estourar 1.2s ease forwards; }" +
                "@keyframes estourar { 0% { transform: scale(0); opacity: 0; } 60% { transform: scale(1.05); opacity: 1; } 100% { transform: scale(1); opacity: 1; } }" +
                "@keyframes bater { 0% { transform: translate(var(--tx), var(--ty)) translate(-5px, 0); } 50% { transform: translate(var(--tx), var(--ty)) translate(5px, 0); } 100% { transform: translate(var(--tx), var(--ty)) translate(-5px, 0); } }" +
                ".bater { animation: bater 0.1s linear infinite !important; }" +
                "</style>" +
                "</head>" +
                "<body>" +
                "<div class='area' id='area'>" +
                "<div class='quadrado verde'>" +
                "<div class='quadrado-inner'>" +
                "<img src='https://wallpapers.com/images/high/netflix-profile-pictures-1000-x-1000-2fg93funipvqfs9i.webp' alt='Policial'>" +
                "<div class='legenda'>Editar perfil</div>" +
                "</div>" +
                "</div>" +
                "<div class='quadrado vermelho'>" +
                "<div class='quadrado-inner'>" +
                "<img src='https://wallpapers.com/images/high/netflix-profile-pictures-1000-x-1000-88wkdmjrorckekha.webp' alt='Batman'>" +
                "<div class='legenda'>Editar perfil</div>" +
                "</div>" +
                "</div>" +
                "<div class='quadrado amarelo'>" +
                "<div class='quadrado-inner'>" +
                "<img src='https://wallpapers.com/images/high/netflix-profile-pictures-1000-x-1000-qo9h82134t9nv0j.webp' alt='Mulher Maravilha'>" +
                "<div class='legenda'>Editar perfil</div>" +
                "</div>" +
                "</div>" +
                "<div class='quadrado azul' id='azul'>" +
                "<div class='quadrado-inner'>" +
                "<img src='https://loodibee.com/wp-content/uploads/Netflix-avatar-7.png' alt='Mulher'>" +
                "<div class='legenda'>Editar perfil</div>" +
                "</div>" +
                "</div>" +
                "</div>" +
                "<script>" +
                "let passo = 0;" +
                "const area = document.getElementById('area');" +
                "const azul = document.getElementById('azul');" +
                "azul.addEventListener('click', () => {" +
                "if (passo < 3) { passo++; area.classList.add('p' + passo); }" +
                "else { azul.classList.add('bater'); setTimeout(() => { azul.classList.remove('bater'); }, 300); }" +
                "});" +
                "</script>" +
                "</body>" +
                "</html>";

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);

        setContentView(webView);
    }
}
