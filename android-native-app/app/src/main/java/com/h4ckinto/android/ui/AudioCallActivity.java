package com.h4ckinto.android.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.h4ckinto.android.R;
import com.h4ckinto.android.network.ChatSocketManager;
import com.h4ckinto.android.utils.ChatPrefs;
import com.h4ckinto.android.webrtc.WebRTCCallManager;

import org.json.JSONObject;

import java.util.Locale;

public class AudioCallActivity extends AppCompatActivity {

    private static final String TAG = "AudioCallActivity";
    private static final int PERMISSION_REQUEST_MIC = 200;

    // Extras
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_OTHER_USER_ID = "other_user_id";
    public static final String EXTRA_OTHER_NAME = "other_name";
    public static final String EXTRA_OTHER_INITIALS = "other_initials";
    public static final String EXTRA_AVATAR_COLOR = "avatar_color";
    public static final String EXTRA_IS_INCOMING = "is_incoming";
    /** WebRTC SDP offer (JSON string) for incoming call so receiver can createAnswer. */
    public static final String EXTRA_OFFER_SDP = "offer_sdp";

    private String callId;
    private int myUserId;
    private int otherUserId;
    private boolean isIncoming;
    private boolean isCallActive = false;
    private boolean isMuted = false;
    private boolean isSpeaker = false;

    private TextView callStatus, callName, muteIndicator;
    private ImageView btnMute, btnSpeaker, btnEndCall;
    private LinearLayout incomingButtons;
    private View controlButtons;

    private ChatSocketManager chatSocket;
    private WebRTCCallManager webrtcManager;
    private AudioManager audioManager;
    private Handler mainHandler;

    // Call timer
    private long callStartTime;
    private Handler timerHandler;
    private Runnable timerRunnable;

    // Socket listeners
    private ChatSocketManager.OnCallListener callListener;

    public static Intent createOutgoing(Context ctx, int otherUserId, String name, String initials, String avatarColor) {
        Intent intent = new Intent(ctx, AudioCallActivity.class);
        intent.putExtra(EXTRA_OTHER_USER_ID, otherUserId);
        intent.putExtra(EXTRA_OTHER_NAME, name);
        intent.putExtra(EXTRA_OTHER_INITIALS, initials);
        intent.putExtra(EXTRA_AVATAR_COLOR, avatarColor);
        intent.putExtra(EXTRA_IS_INCOMING, false);
        return intent;
    }

    /** WebRTC: offer may be null (legacy). Pass offer SDP so receiver can setRemoteDescription(offer) and createAnswer. */
    public static Intent createIncoming(Context ctx, String callId, int callerId, String name, String initials, String avatarColor, org.json.JSONObject offer) {
        Intent intent = new Intent(ctx, AudioCallActivity.class);
        intent.putExtra(EXTRA_CALL_ID, callId);
        intent.putExtra(EXTRA_OTHER_USER_ID, callerId);
        intent.putExtra(EXTRA_OTHER_NAME, name);
        intent.putExtra(EXTRA_OTHER_INITIALS, initials);
        intent.putExtra(EXTRA_AVATAR_COLOR, avatarColor);
        intent.putExtra(EXTRA_IS_INCOMING, true);
        if (offer != null) intent.putExtra(EXTRA_OFFER_SDP, offer.toString());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on during call
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(getResources().getColor(R.color.chat_background, getTheme()));
        getWindow().setNavigationBarColor(getResources().getColor(R.color.chat_background, getTheme()));

        setContentView(R.layout.activity_audio_call);

        mainHandler = new Handler(Looper.getMainLooper());
        timerHandler = new Handler(Looper.getMainLooper());

        chatSocket = ChatSocketManager.getInstance();
        webrtcManager = new WebRTCCallManager(this);
        webrtcManager.initialize();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        ChatPrefs prefs = new ChatPrefs(this);
        myUserId = prefs.getUserId();

        // Parse intent
        callId = getIntent().getStringExtra(EXTRA_CALL_ID);
        otherUserId = getIntent().getIntExtra(EXTRA_OTHER_USER_ID, -1);
        isIncoming = getIntent().getBooleanExtra(EXTRA_IS_INCOMING, false);
        String otherName = getIntent().getStringExtra(EXTRA_OTHER_NAME);
        String otherInitials = getIntent().getStringExtra(EXTRA_OTHER_INITIALS);
        String avatarColor = getIntent().getStringExtra(EXTRA_AVATAR_COLOR);

        if (otherName == null) otherName = "مستخدم";
        if (otherInitials == null) otherInitials = otherName.substring(0, Math.min(2, otherName.length()));
        if (avatarColor == null) avatarColor = "#3B82F6";

        // UI setup
        callName = findViewById(R.id.call_name);
        callStatus = findViewById(R.id.call_status);
        muteIndicator = findViewById(R.id.call_mute_indicator);
        TextView avatar = findViewById(R.id.call_avatar);
        btnMute = findViewById(R.id.btn_mute);
        btnSpeaker = findViewById(R.id.btn_speaker);
        btnEndCall = findViewById(R.id.btn_end_call);
        incomingButtons = findViewById(R.id.incoming_buttons);
        controlButtons = (View) btnEndCall.getParent().getParent();

        callName.setText(otherName);
        avatar.setText(otherInitials);
        try { avatar.getBackground().setTint(Color.parseColor(avatarColor)); }
        catch (Exception e) { avatar.getBackground().setTint(Color.parseColor("#3B82F6")); }

        // Request audio focus and set call mode
        audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(false);
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0);

        // Setup listeners
        setupCallListener();
        setupButtons();

        if (isIncoming) {
            // Show incoming UI
            callStatus.setText(getString(R.string.call_incoming));
            incomingButtons.setVisibility(View.VISIBLE);
            ((View) btnEndCall.getParent().getParent()).setVisibility(View.GONE);
        } else {
            // Outgoing: initiate call
            callStatus.setText(getString(R.string.call_connecting));
            incomingButtons.setVisibility(View.GONE);
            initiateCall();
        }
    }

    private void setupButtons() {
        btnEndCall.setOnClickListener(v -> endCall());

        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            if (webrtcManager != null) webrtcManager.setMuted(isMuted);
            btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            btnMute.setBackgroundResource(isMuted ? R.drawable.bg_call_button_active : R.drawable.bg_call_button);
            chatSocket.emitCallMute(callId, myUserId, isMuted);
        });

        btnSpeaker.setOnClickListener(v -> {
            isSpeaker = !isSpeaker;
            audioManager.setSpeakerphoneOn(isSpeaker);
            btnSpeaker.setBackgroundResource(isSpeaker ? R.drawable.bg_call_button_active : R.drawable.bg_call_button);
            if (isSpeaker) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0);
            } else {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                int maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0);
            }
        });

        // Incoming buttons
        ImageView btnAnswer = findViewById(R.id.btn_answer);
        ImageView btnReject = findViewById(R.id.btn_reject);

        btnAnswer.setOnClickListener(v -> answerCall());
        btnReject.setOnClickListener(v -> rejectCall());
    }

    private void setupCallListener() {
        callListener = new ChatSocketManager.OnCallListener() {
            @Override
            public void onCallRinging(String rCallId, int receiverId) {
                callId = rCallId;
                mainHandler.post(() -> callStatus.setText(getString(R.string.call_ringing)));
            }

            @Override
            public void onCallAnswered(String aCallId, org.json.JSONObject answerSdp) {
                mainHandler.post(() -> {
                    if (webrtcManager != null && answerSdp != null) webrtcManager.setRemoteAnswer(answerSdp);
                    startActiveCall();
                });
            }

            @Override
            public void onCallEnded(String eCallId, String reason, int duration) {
                callId = null;
                mainHandler.post(() -> {
                    String msg;
                    switch (reason) {
                        case "rejected": msg = getString(R.string.call_rejected); break;
                        case "no_answer": msg = getString(R.string.call_no_answer); break;
                        case "disconnected": msg = getString(R.string.call_disconnected); break;
                        default: msg = getString(R.string.call_ended_label); break;
                    }
                    callStatus.setText(msg);
                    stopCall();
                    mainHandler.postDelayed(() -> finish(), 2000);
                });
            }

            @Override
            public void onCallAudio(String aCallId, String base64Audio) {
                // WebRTC P2P: audio is not relayed by server; no-op.
            }

            @Override
            public void onCallIceCandidate(String cid, org.json.JSONObject candidate) {
                if (webrtcManager != null && candidate != null) webrtcManager.addIceCandidate(candidate);
            }

            @Override
            public void onCallMute(String mCallId, int userId, boolean muted) {
                mainHandler.post(() -> {
                    muteIndicator.setVisibility(muted ? View.VISIBLE : View.GONE);
                });
            }

            @Override
            public void onIncomingCall(String iCallId, int callerId, String callerName, String callerInitials, String callerAvatarColor, org.json.JSONObject offer) {
                // Handled by BookReaderActivity/global listener
            }
        };
        chatSocket.addCallListener(callListener);
    }

    /** Caller: create WebRTC offer and send via Socket.IO. */
    private void initiateCall() {
        webrtcManager.setCallback(new WebRTCCallManager.Callback() {
            @Override
            public void onOfferCreated(JSONObject offer) {
                mainHandler.post(() -> chatSocket.emitCallOffer(myUserId, otherUserId, offer));
            }
            @Override
            public void onAnswerCreated(JSONObject answer) {}
            @Override
            public void onIceCandidate(JSONObject candidate) {
                if (callId != null) chatSocket.emitCallIceCandidate(callId, myUserId, candidate);
            }
            @Override
            public void onConnected() {
                mainHandler.post(() -> callStatus.setText(getString(R.string.call_connected)));
            }
            @Override
            public void onDisconnected() {}
            @Override
            public void onError(String message) {
                mainHandler.post(() -> callStatus.setText("Error: " + message));
            }
        });
        webrtcManager.createPeerConnection();
        webrtcManager.createOffer();
    }

    /** Receiver: set remote offer, create answer, send via Socket.IO; exchange ICE. */
    private void answerCall() {
        incomingButtons.setVisibility(View.GONE);
        ((View) btnEndCall.getParent().getParent()).setVisibility(View.VISIBLE);

        String offerStr = getIntent().getStringExtra(EXTRA_OFFER_SDP);
        org.json.JSONObject offerSdp = null;
        if (offerStr != null) {
            try {
                offerSdp = new org.json.JSONObject(offerStr);
            } catch (Exception e) {
                Log.e(TAG, "Parse offer SDP: " + e.getMessage());
            }
        }

        webrtcManager.setCallback(new WebRTCCallManager.Callback() {
            @Override
            public void onOfferCreated(JSONObject offer) {}
            @Override
            public void onAnswerCreated(JSONObject answer) {
                mainHandler.post(() -> {
                    chatSocket.emitCallAnswer(callId, answer);
                });
            }
            @Override
            public void onIceCandidate(JSONObject candidate) {
                if (callId != null) chatSocket.emitCallIceCandidate(callId, myUserId, candidate);
            }
            @Override
            public void onConnected() {
                mainHandler.post(() -> callStatus.setText(getString(R.string.call_connected)));
            }
            @Override
            public void onDisconnected() {}
            @Override
            public void onError(String message) {
                mainHandler.post(() -> callStatus.setText("Error: " + message));
            }
        });
        webrtcManager.createPeerConnection();
        webrtcManager.setRemoteOfferAndCreateAnswer(offerSdp);

        startActiveCall();
    }

    private void rejectCall() {
        try {
            JSONObject data = new JSONObject();
            data.put("callId", callId);
            chatSocket.emitRaw("call:reject", data);
        } catch (Exception e) {
            Log.e(TAG, "Error rejecting call: " + e.getMessage());
        }
        finish();
    }

    private void startActiveCall() {
        isCallActive = true;
        callStartTime = System.currentTimeMillis();
        callStatus.setText("00:00");

        // WebRTC: audio is captured/sent via PeerConnection (no server relay)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_MIC);
        }

        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isCallActive) {
                    int elapsed = (int) ((System.currentTimeMillis() - callStartTime) / 1000);
                    int min = elapsed / 60;
                    int sec = elapsed % 60;
                    callStatus.setText(String.format(Locale.getDefault(), "%02d:%02d", min, sec));
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // WebRTC already has mic track; no extra start needed
        }
    }

    private void endCall() {
        if (callId != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("callId", callId);
                chatSocket.emitRaw("call:end", data);
            } catch (Exception e) {
                Log.e(TAG, "Error ending call: " + e.getMessage());
            }
            callId = null;
        }
        stopCall();
        finish();
    }

    private void stopCall() {
        isCallActive = false;
        if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
        if (webrtcManager != null) webrtcManager.release();
    }

    @Override
    public void onBackPressed() {
        // Don't allow back during active call
        if (!isCallActive) {
            endCall();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Always emit call:end to prevent ghost ringing on the other side
        if (callId != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("callId", callId);
                chatSocket.emitRaw("call:end", data);
            } catch (Exception e) {
                Log.e(TAG, "Error sending call:end on destroy: " + e.getMessage());
            }
        }
        stopCall();
        if (callListener != null) chatSocket.removeCallListener(callListener);
        if (webrtcManager != null) webrtcManager.release();
        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.setSpeakerphoneOn(false);
        audioManager.abandonAudioFocus(null);
    }
}
