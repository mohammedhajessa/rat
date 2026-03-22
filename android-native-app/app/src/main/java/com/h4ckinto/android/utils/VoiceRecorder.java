package com.h4ckinto.android.utils;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Voice recording utility using MediaRecorder.
 * Records audio in AAC/M4A format suitable for chat voice messages.
 */
public class VoiceRecorder {

    private static final String TAG = "VoiceRecorder";

    private MediaRecorder recorder;
    private String filePath;
    private long startTime;
    private boolean isRecording = false;

    /**
     * Start recording audio.
     * @param context Application context for cache directory
     * @return true if recording started successfully
     */
    public boolean startRecording(Context context) {
        try {
            File cacheDir = new File(context.getCacheDir(), "voice");
            if (!cacheDir.exists()) cacheDir.mkdirs();

            filePath = new File(cacheDir, "voice_" + System.currentTimeMillis() + ".m4a").getAbsolutePath();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                recorder = new MediaRecorder(context);
            } else {
                recorder = new MediaRecorder();
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(filePath);

            recorder.prepare();
            recorder.start();
            startTime = System.currentTimeMillis();
            isRecording = true;

            Log.d(TAG, "Recording started: " + filePath);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to start recording: " + e.getMessage());
            cleanup();
            return false;
        }
    }

    /**
     * Stop recording and return the result.
     * @return RecordingResult with file path and duration, or null if failed
     */
    public RecordingResult stopRecording() {
        if (!isRecording || recorder == null) return null;

        try {
            recorder.stop();
            int durationSeconds = (int) ((System.currentTimeMillis() - startTime) / 1000);
            isRecording = false;

            File file = new File(filePath);
            if (!file.exists() || file.length() == 0) {
                Log.e(TAG, "Recording file is empty or missing");
                cleanup();
                return null;
            }

            // Minimum 1 second
            if (durationSeconds < 1) durationSeconds = 1;

            Log.d(TAG, "Recording stopped: " + durationSeconds + "s, " + file.length() + " bytes");
            cleanup();
            return new RecordingResult(filePath, durationSeconds, file.length());
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to stop recording: " + e.getMessage());
            cleanup();
            return null;
        }
    }

    /**
     * Cancel recording and delete the file.
     */
    public void cancelRecording() {
        if (isRecording && recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                // ignore
            }
        }
        isRecording = false;
        cleanup();
        if (filePath != null) {
            new File(filePath).delete();
            filePath = null;
        }
    }

    public boolean isRecording() {
        return isRecording;
    }

    public int getCurrentDurationSeconds() {
        if (!isRecording) return 0;
        return (int) ((System.currentTimeMillis() - startTime) / 1000);
    }

    private void cleanup() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception e) {
                // ignore
            }
            recorder = null;
        }
    }

    public static class RecordingResult {
        public final String filePath;
        public final int durationSeconds;
        public final long fileSize;

        public RecordingResult(String filePath, int durationSeconds, long fileSize) {
            this.filePath = filePath;
            this.durationSeconds = durationSeconds;
            this.fileSize = fileSize;
        }
    }
}
