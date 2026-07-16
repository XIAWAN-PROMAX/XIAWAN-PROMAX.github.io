package com.flash.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private MediaProjectionManager projectionManager;
    private TextView statusText;
    private Button startBtn;

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            startRecordingService(result.getResultCode(), result.getData());
                        } else {
                            Toast.makeText(this, "需要录屏权限才能开始", Toast.LENGTH_LONG).show();
                        }
                    });

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    res -> { });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        startBtn = findViewById(R.id.startBtn);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        startBtn.setOnClickListener(v -> onStartClicked());
        findViewById(R.id.galleryBtn).setOnClickListener(v ->
                startActivity(new Intent(this, GalleryActivity.class)));

        findViewById(R.id.exitBtn).setOnClickListener(v -> {
            stopService(new Intent(this, ScreenRecorderService.class).setAction(ScreenRecorderService.ACTION_SHUTDOWN));
            stopService(new Intent(this, FloatingWindowService.class));
            finishAffinity();
        });

        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ScreenRecorderService.setStateListenerGlobal((state, path) -> runOnUiThread(this::updateUi));
        updateUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ScreenRecorderService.setStateListenerGlobal(null);
    }

    private void onStartClicked() {
        if (!ensurePermissions()) return;
        Intent intent = projectionManager.createScreenCaptureIntent();
        projectionLauncher.launch(intent);
    }

    private boolean ensurePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限后重试", Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return false;
        }
        java.util.List<String> need = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        if (!need.isEmpty()) {
            permLauncher.launch(need.toArray(new String[0]));
            return false;
        }
        return true;
    }

    private void startRecordingService(int resultCode, Intent data) {
        Intent svc = new Intent(this, ScreenRecorderService.class);
        svc.setAction(ScreenRecorderService.ACTION_START);
        svc.putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(ScreenRecorderService.EXTRA_DATA, data);
        ContextCompat.startForegroundService(this, svc);

        Intent flt = new Intent(this, FloatingWindowService.class);
        ContextCompat.startForegroundService(this, flt);

        Toast.makeText(this, "已开始预录制，最近30秒正在缓冲中", Toast.LENGTH_LONG).show();
        updateUi();
    }

    private void updateUi() {
        ScreenRecorderService svc = ScreenRecorderService.getInstance();
        int state = svc != null ? svc.getState() : ScreenRecorderService.STATE_IDLE;
        switch (state) {
            case ScreenRecorderService.STATE_BUFFERING:
                statusText.setText(R.string.tap_to_save);
                statusText.setTextColor(ContextCompat.getColor(this, R.color.record_green));
                startBtn.setText(R.string.start_record);
                startBtn.setEnabled(false);
                break;
            case ScreenRecorderService.STATE_SAVING:
                statusText.setText(R.string.tap_to_stop);
                statusText.setTextColor(ContextCompat.getColor(this, R.color.record_red));
                startBtn.setText(R.string.stop_record);
                startBtn.setEnabled(false);
                break;
            case ScreenRecorderService.STATE_TRANSITION:
                statusText.setText("处理中…");
                startBtn.setEnabled(false);
                break;
            default:
                statusText.setText(R.string.tap_to_save);
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                startBtn.setText(R.string.start_record);
                startBtn.setEnabled(true);
                break;
        }
    }
}
