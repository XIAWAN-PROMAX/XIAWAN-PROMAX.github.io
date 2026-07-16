package com.flash.recorder;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GalleryActivity extends AppCompatActivity {

    private VideoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.gallery_title);
        }

        RecyclerView recycler = findViewById(R.id.recycler);
        TextView empty = findViewById(R.id.empty);
        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new VideoAdapter(this::openPlayer);
        recycler.setAdapter(adapter);

        loadVideos(empty);
    }

    private void loadVideos(TextView empty) {
        File dir = VideoStore.getOutputDir(this);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".mp4"));
        List<File> videos;
        if (files == null || files.length == 0) {
            videos = Collections.emptyList();
            empty.setVisibility(View.VISIBLE);
        } else {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            videos = new ArrayList<>(Arrays.asList(files));
            empty.setVisibility(View.GONE);
        }
        adapter.submit(videos);
    }

    private void openPlayer(File file) {
        startActivity(new android.content.Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_PATH, file.getAbsolutePath()));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        TextView empty = findViewById(R.id.empty);
        loadVideos(empty);
    }
}
