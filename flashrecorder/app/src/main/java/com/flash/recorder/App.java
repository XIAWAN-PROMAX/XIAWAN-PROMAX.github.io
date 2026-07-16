package com.flash.recorder;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class App extends Application {
    public static final String CHANNEL_RECORDING = "channel_recording";
    public static final String CHANNEL_FLOATING = "channel_floating";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel rec = new NotificationChannel(
                    CHANNEL_RECORDING,
                    getString(R.string.recording_channel),
                    NotificationManager.IMPORTANCE_LOW);
            rec.setDescription("Foreground notification while pre-recording");
            nm.createNotificationChannel(rec);

            NotificationChannel flt = new NotificationChannel(
                    CHANNEL_FLOATING,
                    getString(R.string.floating_channel),
                    NotificationManager.IMPORTANCE_MIN);
            flt.setDescription("Foreground notification for floating control window");
            nm.createNotificationChannel(flt);
        }
    }
}
