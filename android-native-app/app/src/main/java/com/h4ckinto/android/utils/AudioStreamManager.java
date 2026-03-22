package com.h4ckinto.android.utils;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.util.Base64;
import android.util.Log;

public class AudioStreamManager {

    private static final String TAG = "AudioStreamManager";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SIZE = 1280; // 40ms frames for smoother audio

    private AudioRecord recorder;
    private AudioTrack player;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl gainControl;

    private volatile boolean isCapturing = false;
    private volatile boolean isPlaying = false;
    private volatile boolean isMuted = false;
    private Thread captureThread;
    private Thread playbackThread;

    private OnAudioChunkListener chunkListener;

    private final java.util.concurrent.LinkedBlockingQueue<byte[]> playbackQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(60);

    public interface OnAudioChunkListener {
        void onAudioChunk(String base64Audio);
    }

    public void setOnAudioChunkListener(OnAudioChunkListener listener) {
        this.chunkListener = listener;
    }

    public void startCapture() {
        if (isCapturing) return;

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT);
        int bufferSize = Math.max(minBuf * 4, FRAME_SIZE * 8);

        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT, bufferSize);

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                return;
            }

            int sessionId = recorder.getAudioSessionId();

            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(sessionId);
                    if (echoCanceler != null) echoCanceler.setEnabled(true);
                } catch (Exception e) { /* not critical */ }
            }

            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionId);
                    if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
                } catch (Exception e) { /* not critical */ }
            }

            if (AutomaticGainControl.isAvailable()) {
                try {
                    gainControl = AutomaticGainControl.create(sessionId);
                    if (gainControl != null) gainControl.setEnabled(true);
                } catch (Exception e) { /* not critical */ }
            }

            isCapturing = true;
            recorder.startRecording();

            captureThread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                byte[] buffer = new byte[FRAME_SIZE];
                while (isCapturing) {
                    int read = recorder.read(buffer, 0, FRAME_SIZE);
                    if (read > 0 && !isMuted && chunkListener != null) {
                        byte[] copy = new byte[read];
                        System.arraycopy(buffer, 0, copy, 0, read);
                        String base64 = Base64.encodeToString(copy, 0, read, Base64.NO_WRAP);
                        chunkListener.onAudioChunk(base64);
                    }
                }
            }, "AudioCapture");
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
            Log.d(TAG, "Audio capture started");
        } catch (SecurityException e) {
            Log.e(TAG, "No permission: " + e.getMessage());
        }
    }

    public void stopCapture() {
        isCapturing = false;
        if (captureThread != null) {
            try { captureThread.join(500); } catch (InterruptedException e) { /* ignore */ }
            captureThread = null;
        }
        if (echoCanceler != null) { try { echoCanceler.release(); } catch (Exception e) {} echoCanceler = null; }
        if (noiseSuppressor != null) { try { noiseSuppressor.release(); } catch (Exception e) {} noiseSuppressor = null; }
        if (gainControl != null) { try { gainControl.release(); } catch (Exception e) {} gainControl = null; }
        if (recorder != null) {
            try { recorder.stop(); recorder.release(); } catch (Exception e) { /* ignore */ }
            recorder = null;
        }
    }

    private volatile boolean speakerMode = false;

    public void startPlayback() {
        if (isPlaying) return;
        createAndStartPlayer();
    }

    private void createAndStartPlayer() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT);
        int bufferSize = Math.max(minBuf * 4, FRAME_SIZE * 16);

        AudioAttributes.Builder attrBuilder = new AudioAttributes.Builder();
        if (speakerMode) {
            attrBuilder.setUsage(AudioAttributes.USAGE_MEDIA)
                       .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
        } else {
            attrBuilder.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                       .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
        }

        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .setEncoding(AUDIO_FORMAT)
                .build();
        player = new AudioTrack(attrBuilder.build(), fmt, bufferSize, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);

        if (player.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize");
            return;
        }

        player.setVolume(AudioTrack.getMaxVolume());
        player.play();
        isPlaying = true;

        playbackThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            while (isPlaying) {
                try {
                    byte[] pcm = playbackQueue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (pcm != null && player != null) {
                        player.write(pcm, 0, pcm.length);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "AudioPlayback");
        playbackThread.setPriority(Thread.MAX_PRIORITY);
        playbackThread.start();
        Log.d(TAG, "Audio playback started (speaker=" + speakerMode + ")");
    }

    public void setSpeakerMode(boolean enabled) {
        if (this.speakerMode == enabled) return;
        this.speakerMode = enabled;
        if (isPlaying) {
            stopPlayback();
            playbackQueue.clear();
            createAndStartPlayer();
        }
    }

    public void stopPlayback() {
        isPlaying = false;
        playbackQueue.clear();
        if (playbackThread != null) {
            try { playbackThread.join(500); } catch (InterruptedException e) { /* ignore */ }
            playbackThread = null;
        }
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception e) { /* ignore */ }
            player = null;
        }
    }

    public void playChunk(String base64Audio) {
        if (!isPlaying || base64Audio == null) return;
        try {
            byte[] pcm = Base64.decode(base64Audio, Base64.NO_WRAP);
            if (!playbackQueue.offer(pcm)) {
                playbackQueue.poll();
                playbackQueue.offer(pcm);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    public void setPlaybackVolume(float vol) {
        if (player != null) {
            player.setVolume(Math.max(0f, Math.min(vol, AudioTrack.getMaxVolume())));
        }
    }

    public void setMuted(boolean muted) { this.isMuted = muted; }
    public boolean isMuted() { return isMuted; }

    public void release() {
        stopCapture();
        stopPlayback();
    }
}
