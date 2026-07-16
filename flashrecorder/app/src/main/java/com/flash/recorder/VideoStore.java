package com.flash.recorder;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;

/**
 * Shared utility for resolving the directory where recorded MP4 files are stored.
 * Used both by the recorder (writer) and the gallery (reader).
 */
public final class VideoStore {
    private VideoStore() {}

    public static File getOutputDir(Context ctx) {
        File dir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Movies/FlashRecorder - visible in standard gallery apps.
            dir = new File(Environment.getExternalStorageDirectory(),
                    "Movies/FlashRecorder");
        } else {
            dir = new File(Environment.getExternalStorageDirectory(), "FlashRecorder");
        }
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }
}
