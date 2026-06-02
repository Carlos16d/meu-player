package com.meuapp.player;

import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

import java.io.*;
import java.net.*;

public class MainActivity extends AppCompatActivity {
    private EditText magnetInput;
    private Button btnPlay, btnStop;
    private TextView statusText;
    private SessionManager session;
    private boolean downloading = false;
    private String savePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        magnetInput = findViewById(R.id.magnet_input);
        btnPlay = findViewById(R.id.btn_play);
        btnStop = findViewById(R.id.btn_stop);
        statusText = findViewById(R.id.loading_status);
        
        savePath = new File(getExternalFilesDir(null), "torrents").getAbsolutePath();
        new File(savePath).mkdirs();
        
        try {
            session = new SessionManager();
            session.start();
            statusText.setText("UDP OK!");
        } catch (Exception e) {
            statusText.setText("Erro: " + e.getMessage());
        }
        
        btnPlay.setOnClickListener(v -> {
            String magnet = magnetInput.getText().toString().trim();
            if (magnet.startsWith("magnet:")) {
                startDownload(magnet);
            }
        });
        
        btnStop.setOnClickListener(v -> {
            downloading = false;
            statusText.setText("Parado");
        });
    }
    
    private void startDownload(String magnet) {
        if (downloading) return;
        downloading = true;
        statusText.setText("Conectando...");
        
        new Thread(() -> {
            try {
                add_torrent_params p = libtorrent.parse_magnet_uri(magnet, new error_code());
                p.setSave_path(savePath);
                
                string_vector trackers = new string_vector();
                trackers.add("udp://tracker.opentrackr.org:1337/announce");
                trackers.add("udp://tracker.openbittorrent.com:6969/announce");
                p.setTrackers(trackers);
                
                p.setFlags(torrent_flags_t.from_int(9));
                p.setDownload_limit(3 * 1024 * 1024);
                
                session.swig().async_add_torrent(p);
                
                runOnUiThread(() -> statusText.setText("Baixando..."));
                
            } catch (Exception e) {
                downloading = false;
                runOnUiThread(() -> statusText.setText("Erro: " + e.getMessage()));
            }
        }).start();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) session.stop();
    }
}
