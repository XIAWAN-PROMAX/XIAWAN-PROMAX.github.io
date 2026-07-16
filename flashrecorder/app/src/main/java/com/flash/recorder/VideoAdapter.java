package com.flash.recorder;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VH> {
    private final Runnable openPlayer;
    private List<File> videos = new ArrayList<>();

    public VideoAdapter(Runnable openPlayer) {
        this.openPlayer = openPlayer;
    }

    public void submit(List<File> list) {
        videos.clear();
        videos.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.video_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(videos.get(position));
    }

    @Override
    public int getItemCount() { return videos.size(); }

    class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView name, time, size;
        VH(View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            name = v.findViewById(R.id.name);
            time = v.findViewById(R.id.time);
            size = v.findViewById(R.id.size);
        }

        void bind(File f) {
            name.setText(f.getName());
            time.setText(new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                    .format(new Date(f.lastModified())));
            size.setText(formatSize(f.length()));

            Context ctx = itemView.getContext();
            try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
                mmr.setDataSource(f.getAbsolutePath());
                thumb.setImageBitmap(mmr.getFrameAtTime(0));
            } catch (Exception e) {
                thumb.setImageResource(R.drawable.ic_launcher_foreground);
            }

            itemView.setOnClickListener(v -> {
                Intent i = new Intent(ctx, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_PATH, f.getAbsolutePath());
                ctx.startActivity(i);
            });

            itemView.setOnLongClickListener(v -> {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("video/mp4");
                share.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                ctx.startActivity(Intent.createChooser(share, ctx.getString(R.string.share)));
                return true;
            });
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024f);
            return String.format("%.1f MB", bytes / (1024f * 1024f));
        }
    }
}
