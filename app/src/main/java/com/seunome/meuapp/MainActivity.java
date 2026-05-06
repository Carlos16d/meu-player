package com.seunome.meuapp;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Esconder a ActionBar (sem dar erro)
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }

        // 2. Esconder a barra de status, navegação e deixar tela cheia
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |       // Esconde a barra de cima (notch/hora)
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |  // Esconde os botões de navegação
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | // Modo imersivo
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }
}
