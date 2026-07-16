package com.flash.recorder;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

public class FloatingWindowService extends Service {
    private WindowManager windowManager;
    private View rootView;
    private ImageView icon;
    private WindowManager.LayoutParams params;
    private boolean attached = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        startForegroundCompat(buildNotification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!attached) attachWindow();
        return START_STICKY;
    }

    private void attachWindow() {
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 24;
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(dm);
        params.y = dm.heightPixels / 3;

        rootView = LayoutInflater.from(this).inflate(R.layout.floating_button, null, false);
        icon = rootView.findViewById(R.id.floatIcon);

        setupDragAndClick();
        try {
            windowManager.addView(rootView, params);
            attached = true;
        } catch (Exception e) {
            Toast.makeText(this, "悬浮窗显示失败，请检查悬浮窗权限", Toast.LENGTH_LONG).show();
            stopSelf();
        }

        ScreenRecorderService.setStateListenerGlobal((state, path) ->
                updateVisualOnUiThread(state));
        updateVisualOnUiThread(currentRecorderState());
    }

    private int currentRecorderState() {
        ScreenRecorderService s = ScreenRecorderService.getInstance();
        return s != null ? s.getState() : ScreenRecorderService.STATE_IDLE;
    }

    private void updateVisualOnUiThread(int state) {
        if (icon == null) return;
        icon.post(() -> {
            switch (state) {
                case ScreenRecorderService.STATE_BUFFERING:
                    icon.setImageResource(R.drawable.float_idle);
                    rootView.setBackgroundResource(R.drawable.float_bg_idle);
                    break;
                case ScreenRecorderService.STATE_SAVING:
                case ScreenRecorderService.STATE_TRANSITION:
                    icon.setImageResource(R.drawable.float_rec);
                    rootView.setBackgroundResource(R.drawable.float_bg_rec);
                    break;
                default:
                    icon.setImageResource(R.drawable.float_idle);
                    rootView.setBackgroundResource(R.drawable.float_bg_idle);
                    break;
            }
        });
    }

    private void setupDragAndClick() {
        rootView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float touchX, touchY;
            private boolean moved;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        moved = false;
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchX;
                        float dy = event.getRawY() - touchY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
                        params.x = initialX + (int) dx;
                        params.y = initialY + (int) dy;
                        try { windowManager.updateViewLayout(rootView, params); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        long dur = System.currentTimeMillis() - downTime;
                        if (!moved && dur < 500) onTap();
                        return true;
                }
                return false;
            }
        });
    }

    private void onTap() {
        ScreenRecorderService svc = ScreenRecorderService.getInstance();
        if (svc == null) {
            Toast.makeText(this, "录屏服务未运行", Toast.LENGTH_SHORT).show();
            return;
        }
        int s = svc.getState();
        if (s == ScreenRecorderService.STATE_BUFFERING) {
            svc.requestStartSaving();
        } else if (s == ScreenRecorderService.STATE_SAVING) {
            svc.requestStopSaving();
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, App.CHANNEL_FLOATING)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.floating_notif_title))
                .setContentText(getString(R.string.floating_notif_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void startForegroundCompat(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(2, n);
            }
        } catch (Exception e) {}
    }

    @Override public void onDestroy() {
        if (attached) {
            try { windowManager.removeView(rootView); } catch (Exception ignored) {}
            attached = false;
        }
        super.onDestroy();
    }
}
