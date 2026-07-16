package com.flash.recorder;

import android.os.Bundle;
import android.widget.MediaController;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_PATH = "video_path";
    private VideoView videoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.play);
        }

        videoView = findViewById(R.id.videoView);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !new File(path).exists()) {
            finish();
            return;
        }
        videoView.setVideoPath(path);
        MediaController mc = new MediaController(this);
        mc.setAnchorView(videoView);
        videoView.setMediaController(mc);
        videoView.requestFocus();
        videoView.start();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null) videoView.pause();
    }
}
