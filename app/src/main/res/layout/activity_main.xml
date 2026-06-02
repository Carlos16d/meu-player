<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0a0a0a">
    
    <VideoView
        android:id="@+id/video_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />
    
    <ScrollView
        android:id="@+id/log_scroll"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:padding="12dp">
        
        <TextView
            android:id="@+id/log_text"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="#4ecb71"
            android:textSize="10sp"
            android:fontFamily="monospace" />
    </ScrollView>
    
    <LinearLayout
        android:id="@+id/control_panel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:orientation="vertical"
        android:padding="12dp"
        android:background="#cc0a0a0a">
        
        <EditText
            android:id="@+id/magnet_input"
            android:layout_width="match_parent"
            android:layout_height="80dp"
            android:background="#1a1a1a"
            android:gravity="top"
            android:hint="Cole seu magnet link aqui..."
            android:textColorHint="#555"
            android:inputType="textMultiLine"
            android:padding="10dp"
            android:textColor="#fff"
            android:textSize="12sp" />
        
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="8dp">
            
            <Button
                android:id="@+id/btn_play"
                android:layout_width="0dp"
                android:layout_height="45dp"
                android:layout_weight="1"
                android:backgroundTint="#e74c3c"
                android:text="▶️ INICIAR"
                android:textColor="#fff"
                android:textStyle="bold" />
            
            <Button
                android:id="@+id/btn_stop"
                android:layout_width="0dp"
                android:layout_height="45dp"
                android:layout_weight="1"
                android:layout_marginStart="6dp"
                android:backgroundTint="#555"
                android:text="⏹️ PARAR"
                android:textColor="#fff"
                android:textStyle="bold"
                android:visibility="gone" />
        </LinearLayout>
        
        <ProgressBar
            android:id="@+id/buffer_bar"
            style="?android:attr/progressBarStyleHorizontal"
            android:layout_width="match_parent"
            android:layout_height="5dp"
            android:layout_marginTop="8dp"
            android:progressTint="#e74c3c"
            android:visibility="gone" />
        
        <LinearLayout
            android:id="@+id/stats_row"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:orientation="horizontal"
            android:visibility="gone">
            
            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="center"
                android:orientation="vertical"
                android:background="#1a1a1a"
                android:padding="6dp"
                android:layout_marginLeft="2dp"
                android:layout_marginRight="2dp">
                <TextView android:id="@+id/stat_progress" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="0%" android:textColor="#e74c3c" android:textSize="14sp" android:textStyle="bold" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Progresso" android:textColor="#888" android:textSize="9sp" />
            </LinearLayout>
            
            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="center"
                android:orientation="vertical"
                android:background="#1a1a1a"
                android:padding="6dp"
                android:layout_marginLeft="2dp"
                android:layout_marginRight="2dp">
                <TextView android:id="@+id/stat_speed" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="0 KB/s" android:textColor="#e74c3c" android:textSize="14sp" android:textStyle="bold" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Velocidade" android:textColor="#888" android:textSize="9sp" />
            </LinearLayout>
            
            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="center"
                android:orientation="vertical"
                android:background="#1a1a1a"
                android:padding="6dp"
                android:layout_marginLeft="2dp"
                android:layout_marginRight="2dp">
                <TextView android:id="@+id/stat_peers" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="0" android:textColor="#e74c3c" android:textSize="14sp" android:textStyle="bold" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Peers UDP" android:textColor="#888" android:textSize="9sp" />
            </LinearLayout>
        </LinearLayout>
    </LinearLayout>
    
</FrameLayout>
