package com.flash.recorder;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Foreground service that continuously captures the screen + microphone into an
 * in-memory circular buffer holding the most recent 30 seconds. When the user
 * taps the floating control it flushes the buffered frames (the "pre-recorded"
 * 30 seconds) into an MP4 file and keeps appending live frames until the user
 * taps stop, yielding a clip that begins 30s *before* the tap.
 */
public class ScreenRecorderService extends Service {
    private static final String TAG = "FlashRecorder";

    static final String ACTION_START = "com.flash.recorder.START";
    static final String ACTION_SAVE = "com.flash.recorder.SAVE";
    static final String ACTION_STOP_SAVE = "com.flash.recorder.STOP_SAVE";
    static final String ACTION_SHUTDOWN = "com.flash.recorder.SHUTDOWN";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_DATA = "data_intent";

    static final int STATE_IDLE = 0;
    static final int STATE_BUFFERING = 1;   // pre-recording, filling 30s buffer
    static final int STATE_TRANSITION = 2;  // mid save start/stop
    static final int STATE_SAVING = 3;      // writing live + buffered frames to file

    private static final int PRE_BUFFER_US = 30_000_000; // 30s
    private static final int VIDEO_FPS = 30;
    private static final int AUDIO_SAMPLE_RATE = 44_100;
    private static final int AUDIO_BITRATE = 128_000;
    private static final int AUDIO_CHANNEL_COUNT = 1;

    // Singleton accessor for in-process communication with the floating window.
    private static volatile ScreenRecorderService instance;
    public static ScreenRecorderService getInstance() { return instance; }

    public interface StateListener {
        void onStateChanged(int state, String filePath);
    }
    private volatile StateListener stateListener;
    public void setStateListener(StateListener l) { this.stateListener = l; }

    // Global static listener (used by activities that may launch before the service exists).
    private static volatile StateListener globalListener;
    public static void setStateListenerGlobal(StateListener l) { globalListener = l; }

    // ---- Runtime state (guarded by `this`) ----
    private volatile int state = STATE_IDLE;
    private volatile boolean running = false;
    private volatile boolean audioEnabled = true;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    // Video pipeline
    private MediaCodec videoEncoder;
    private Surface inputSurface;
    private int videoWidth, videoHeight;
    private MediaFormat videoFormat;       // captured on first encoded frame
    private final FrameBuffer videoBuffer = new FrameBuffer(PRE_BUFFER_US);
    private Thread videoThread;

    // Audio pipeline
    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private MediaFormat audioFormat;
    private final FrameBuffer audioBuffer = new FrameBuffer(PRE_BUFFER_US);
    private Thread audioThread;
    private final Object audioFormatLock = new Object();

    // Muxer
    private MediaMuxer muxer;
    private boolean muxerStarted;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private long savePtsOffsetUs = 0;
    private File currentOutputFile;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (action == null) return START_STICKY;
        switch (action) {
            case ACTION_START:
                int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                Intent data = intent.getParcelableExtra(EXTRA_DATA);
                startProjection(code, data);
                break;
            case ACTION_SAVE:
                requestStartSaving();
                break;
            case ACTION_STOP_SAVE:
                requestStopSaving();
                break;
            case ACTION_SHUTDOWN:
                shutdown();
                break;
        }
        return START_STICKY;
    }

    public int getState() { return state; }
    public String getCurrentFilePath() {
        return currentOutputFile != null ? currentOutputFile.getAbsolutePath() : null;
    }

    // -------------------------------------------------------------------------
    // Startup
    // -------------------------------------------------------------------------
    private void startProjection(int resultCode, Intent data) {
        if (state != STATE_IDLE) return;
        try {
            startForegroundCompat(buildRecordingNotification(false));
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                Toast.makeText(this, R.string.permission_title, Toast.LENGTH_LONG).show();
                shutdown();
                return;
            }
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { shutdown(); }
            }, null);
            setupVideo();
            if (audioEnabled) setupAudio();
            running = true;
            synchronized (this) { state = STATE_BUFFERING; }
            notifyState();
        } catch (Exception e) {
            Log.e(TAG, "startProjection failed", e);
            shutdown();
        }
    }

    private void setupVideo() throws Exception {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        int sw = dm.widthPixels, sh = dm.heightPixels;
        // Cap to 1080p for performance / battery.
        int maxDim = 1080;
        if (Math.max(sw, sh) > maxDim) {
            float scale = (float) maxDim / Math.max(sw, sh);
            sw = Math.round(sw * scale / 2f) * 2;
            sh = Math.round(sh * scale / 2f) * 2;
        }
        videoWidth = sw; videoHeight = sh;

        MediaFormat fmt = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, sw, sh);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE,
                Math.max(4_000_000, (sw * sh * VIDEO_FPS) / 60));
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        videoEncoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoEncoder.createInputSurface();
        videoEncoder.start();

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "FlashRecorderVD", sw, sh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null);

        videoThread = new Thread(this::videoLoop, "FlashVideo");
        videoThread.start();
    }

    private void setupAudio() throws Exception {
        int minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 2, 8192);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize);

        MediaFormat fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();
        audioRecord.startRecording();

        audioThread = new Thread(this::audioLoop, "FlashAudio");
        audioThread.start();
    }

    // -------------------------------------------------------------------------
    // Encoder drain loops
    // -------------------------------------------------------------------------
    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            int idx = videoEncoder.dequeueOutputBuffer(info, 10_000);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (this) { videoFormat = videoEncoder.getOutputFormat(); }
            } else if (idx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // codec-specific config; release & ignore
                    info.size = 0;
                }
                if (info.size > 0) {
                    ByteBuffer out = videoEncoder.getOutputBuffer(idx);
                    ByteBuffer copy = ByteBuffer.allocateDirect(out.remaining());
                    copy.put(out);
                    copy.flip();
                    handleFrame(true, copy, info);
                }
                videoEncoder.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private void audioLoop() {
        final int inFrameSamples = 1024;            // AAC frame size
        final int inFrameBytes = inFrameSamples * 2; // 16-bit mono
        ByteBuffer pcBuf = ByteBuffer.allocateDirect(inFrameBytes);
        byte[] readBuf = new byte[inFrameBytes];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (running) {
            int read = audioRecord.read(readBuf, 0, readBuf.length);
            if (read <= 0) continue;
            // queue input
            int inIdx = audioEncoder.dequeueInputBuffer(10_000);
            if (inIdx >= 0) {
                ByteBuffer ib = audioEncoder.getInputBuffer(inIdx);
                ib.clear();
                ib.put(readBuf, 0, read);
                audioEncoder.queueInputBuffer(inIdx, 0, read,
                        SystemClock.elapsedRealtimeNanos() / 1000, 0);
            }
            // drain output
            drainAudio(info);
        }
    }

    private void drainAudio(MediaCodec.BufferInfo info) {
        while (true) {
            int idx = audioEncoder.dequeueOutputBuffer(info, 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (audioFormatLock) { audioFormat = audioEncoder.getOutputFormat(); }
                continue;
            }
            if (idx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0) {
                    ByteBuffer out = audioEncoder.getOutputBuffer(idx);
                    ByteBuffer copy = ByteBuffer.allocateDirect(out.remaining());
                    copy.put(out);
                    copy.flip();
                    handleFrame(false, copy, info);
                }
                audioEncoder.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    /** Route an encoded frame either to the circular buffer (pre-recording) or to the muxer (saving). */
    private synchronized void handleFrame(boolean isVideo, ByteBuffer buf, MediaCodec.BufferInfo src) {
        if (state == STATE_BUFFERING) {
            Frame f = new Frame(buf, src);
            if (isVideo) videoBuffer.add(f); else audioBuffer.add(f);
        } else if (state == STATE_SAVING && muxerStarted) {
            int track = isVideo ? videoTrack : audioTrack;
            if (track >= 0) {
                MediaCodec.BufferInfo adj = new MediaCodec.BufferInfo();
                long pts = src.presentationTimeUs - savePtsOffsetUs;
                if (pts < 0) pts = 0;
                adj.set(src.offset, src.size, pts, src.flags);
                try {
                    muxer.writeSampleData(track, buf, adj);
                } catch (Exception e) {
                    Log.w(TAG, "muxer write failed", e);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Save / stop
    // -------------------------------------------------------------------------
    public synchronized void requestStartSaving() {
        if (state != STATE_BUFFERING) return;
        if (videoFormat == null) {
            Toast.makeText(this, "稍候，编码器未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        state = STATE_TRANSITION;
        notifyState();

        try {
            currentOutputFile = new File(getOutputDir(),
                    "FlashRecorder_" + new SimpleDateFormat(
                            "yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4");
            muxer = new MediaMuxer(currentOutputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            videoTrack = muxer.addTrack(videoFormat);
            synchronized (audioFormatLock) {
                audioTrack = (audioEnabled && audioFormat != null)
                        ? muxer.addTrack(audioFormat) : -1;
            }
            muxer.start();
            muxerStarted = true;

            // PTS offset = earliest frame timestamp across both buffers.
            long v0 = videoBuffer.peekFirstPts();
            long a0 = audioBuffer.peekFirstPts();
            long base = Long.MAX_VALUE;
            if (v0 >= 0) base = Math.min(base, v0);
            if (a0 >= 0) base = Math.min(base, a0);
            if (base == Long.MAX_VALUE) base = 0;
            savePtsOffsetUs = base;

            // Flush the pre-recorded 30 seconds to the file.
            for (Frame f : videoBuffer.drainAll()) {
                writeFrame(videoTrack, f, savePtsOffsetUs);
            }
            if (audioTrack >= 0) {
                for (Frame f : audioBuffer.drainAll()) {
                    writeFrame(audioTrack, f, savePtsOffsetUs);
                }
            }

            state = STATE_SAVING;
            updateNotification(true);
            Toast.makeText(this, "已保存前30秒，正在继续录制…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "startSaving failed", e);
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            try {
                if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); }
            } catch (Exception ignored) {}
            muxer = null; muxerStarted = false;
            state = STATE_BUFFERING;
        }
        notifyState();
    }

    public synchronized void requestStopSaving() {
        if (state != STATE_SAVING) return;
        state = STATE_TRANSITION;
        String savedPath = currentOutputFile != null ? currentOutputFile.getAbsolutePath() : null;
        try {
            if (muxerStarted) muxer.stop();
            muxer.release();
        } catch (Exception e) {
            Log.w(TAG, "muxer stop failed", e);
        }
        muxer = null; muxerStarted = false;
        savePtsOffsetUs = 0;
        videoTrack = audioTrack = -1;
        state = STATE_BUFFERING;
        updateNotification(false);
        notifyState();
        if (savedPath != null) {
            // Scan into MediaStore so gallery apps see it.
            android.media.MediaScannerConnection.scanFile(this,
                    new String[]{savedPath}, new String[]{"video/mp4"}, null);
            Toast.makeText(this, "视频已保存：" + new File(savedPath).getName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void writeFrame(int track, Frame f, long offset) {
        try {
            MediaCodec.BufferInfo adj = new MediaCodec.BufferInfo();
            long pts = f.info.presentationTimeUs - offset;
            if (pts < 0) pts = 0;
            adj.set(f.info.offset, f.info.size, pts, f.info.flags);
            muxer.writeSampleData(track, f.buffer, adj);
        } catch (Exception e) {
            Log.w(TAG, "writeFrame failed", e);
        }
    }

    private void notifyState() {
        int s = state;
        String path = currentOutputFile != null ? currentOutputFile.getAbsolutePath() : null;
        StateListener l = stateListener;
        if (l != null) l.onStateChanged(s, path);
        StateListener g = globalListener;
        if (g != null) g.onStateChanged(s, path);
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------
    private synchronized void shutdown() {
        running = false;
        try {
            if (videoThread != null) videoThread.interrupt();
            if (audioThread != null) audioThread.interrupt();
        } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) {}
        try { if (videoEncoder != null) { videoEncoder.stop(); videoEncoder.release(); } } catch (Exception ignored) {}
        try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); } } catch (Exception ignored) {}
        try { if (audioRecord != null) audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
        try { if (mediaProjection != null) mediaProjection.stop(); } catch (Exception ignored) {}
        try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
        muxer = null; muxerStarted = false;
        videoEncoder = null; audioEncoder = null;
        audioRecord = null; mediaProjection = null; virtualDisplay = null;
        state = STATE_IDLE;
        notifyState();
        stopForeground(true);
        stopSelf();
    }

    private File getOutputDir() {
        return VideoStore.getOutputDir(this);
    }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------
    private Notification buildRecordingNotification(boolean saving) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.recording_notif_title))
                .setContentText(saving
                        ? getString(R.string.recording_notif_saving)
                        : getString(R.string.recording_notif_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void updateNotification(boolean saving) {
        try {
            Notification n = buildRecordingNotification(saving);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "updateNotification failed", e);
        }
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private static final class Frame {
        final ByteBuffer buffer;
        final MediaCodec.BufferInfo info;
        Frame(ByteBuffer b, MediaCodec.BufferInfo src) {
            buffer = b;
            info = new MediaCodec.BufferInfo();
            info.set(src.offset, src.size, src.presentationTimeUs, src.flags);
        }
    }

    /** Time-bounded FIFO of encoded frames; keeps only the last {@code maxAgeUs} microseconds. */
    private static final class FrameBuffer {
        private final long maxAgeUs;
        private final Deque<Frame> deque = new ArrayDeque<>();
        FrameBuffer(long maxAgeUs) { this.maxAgeUs = maxAgeUs; }

        synchronized void add(Frame f) {
            deque.addLast(f);
            long newest = f.info.presentationTimeUs;
            while (!deque.isEmpty() && (newest - deque.peekFirst().info.presentationTimeUs) > maxAgeUs) {
                deque.pollFirst();
            }
            // Hard cap on count to avoid runaway memory if timestamps misbehave.
            while (deque.size() > 1800) deque.pollFirst();
        }

        synchronized List<Frame> drainAll() {
            List<Frame> out = new ArrayList<>(deque);
            deque.clear();
            return out;
        }

        synchronized long peekFirstPts() {
            Frame f = deque.peekFirst();
            return f == null ? -1 : f.info.presentationTimeUs;
        }
    }
}
