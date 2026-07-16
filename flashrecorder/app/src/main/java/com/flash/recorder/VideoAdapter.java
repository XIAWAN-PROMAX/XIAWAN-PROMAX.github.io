package com.flash.recorder;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VH> {

    public interface OnVideoClick { void onClick(File file); }

    private final List<File> items = new ArrayList<>();
    private final OnVideoClick click;
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);

    VideoAdapter(OnVideoClick click) { this.click = click; }

    void submit(List<File> files) {
        items.clear();
        items.addAll(files);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_video, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        File f = items.get(position);
        holder.title.setText(f.getName());
        holder.meta.setText(formatMeta(f));
        holder.itemView.setOnClickListener(v -> click.onClick(f));
        holder.share.setOnClickListener(v -> share(holder.itemView, f));
        holder.thumb.setImageDrawable(null);
        holder.thumb.setTag(f.getAbsolutePath());
        loadThumb(holder, f);
    }

    @Override
    public int getItemCount() { return items.size(); }

    private void loadThumb(VH holder, File f) {
        String path = f.getAbsolutePath();
        thumbExecutor.execute(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(path);
                android.graphics.Bitmap bmp = mmr.getFrameAtTime(0);
                holder.thumb.post(() -> {
                    if (path.equals(holder.thumb.getTag())) {
                        holder.thumb.setImageBitmap(bmp);
                    }
                });
            } catch (Exception ignored) {
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
        });
    }

    private String formatMeta(File f) {
        long dur = durationOf(f);
        String durStr;
        if (dur <= 0) {
            durStr = "00:00";
        } else {
            long m = dur / 1000 / 60, s = (dur / 1000) % 60;
            durStr = String.format(Locale.US, "%02d:%02d", m, s);
        }
        String date = new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                .format(new Date(f.lastModified()));
        return durStr + "  ·  " + (f.length() / 1024 / 1024) + "MB" + "  ·  " + date;
    }

    private long durationOf(File f) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private void share(View v, File f) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("video/mp4");
        i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        v.getContext().startActivity(Intent.createChooser(i, "分享视频"));
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView thumb;
        TextView title, meta;
        View share;
        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            share = v.findViewById(R.id.share);
        }
    }
}
