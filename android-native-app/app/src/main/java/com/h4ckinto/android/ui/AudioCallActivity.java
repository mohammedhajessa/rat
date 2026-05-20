package com.h4ckinto.android.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
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
import com.h4ckinto.android.network.ChatApiService;
import com.h4ckinto.android.network.ChatSocketManager;
import com.h4ckinto.android.services.CallService;
import com.h4ckinto.android.utils.ChatPrefs;
import com.h4ckinto.android.webrtc.WebRTCCallManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AudioCallActivity extends AppCompatActivity {

    private static final String TAG = "AudioCallActivity";
    private static final int PERMISSION_REQUEST_MIC = 200;
    /** Mic before WebRTC / microphone foreground service (Android 14+). */
    private static final int PERMISSION_REQUEST_MIC_FOR_CALL = 201;
    private static final long CALL_TIMEOUT_MS = 60_000;

    private boolean awaitingMicForOutgoing;
    private boolean awaitingMicForAnswer;
    private boolean callFgServiceStarted;

    // Extras
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_OTHER_USER_ID = "other_user_id";
    public static final String EXTRA_OTHER_NAME = "other_name";
    public static final String EXTRA_OTHER_INITIALS = "other_initials";
    public static final String EXTRA_AVATAR_COLOR = "avatar_color";
    public static final String EXTRA_IS_INCOMING = "is_incoming";
    public static final String EXTRA_OFFER_SDP = "offer_sdp";

    private String callId;
    private int myUserId;
    private int otherUserId;
    private String otherName;
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
    private Runnable connectionTimeoutRunnable;

    // ICE candidate queue — candidates arrive before callId is known
    private final List<JSONObject> pendingIceCandidates = new ArrayList<>();

    // Socket listeners
    private ChatSocketManager.OnCallListener callListener;

    // Foreground service binding
    private CallService callService;
    private boolean serviceBound = false;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            CallService.LocalBinder localBinder = (CallService.LocalBinder) binder;
            callService = localBinder.getService();
            serviceBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            callService = null;
        }
    };

    public static Intent createOutgoing(Context ctx, int otherUserId, String name, String initials, String avatarColor) {
        Intent intent = new Intent(ctx, AudioCallActivity.class);
        intent.putExtra(EXTRA_OTHER_USER_ID, otherUserId);
        intent.putExtra(EXTRA_OTHER_NAME, name);
        intent.putExtra(EXTRA_OTHER_INITIALS, initials);
        intent.putExtra(EXTRA_AVATAR_COLOR, avatarColor);
        intent.putExtra(EXTRA_IS_INCOMING, false);
        return intent;
    }

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

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().setStatusBarColor(getResources().getColor(R.color.chat_background, getTheme()));
        getWindow().setNavigationBarColor(getResources().getColor(R.color.chat_background, getTheme()));

        setContentView(R.layout.activity_audio_call);

        mainHandler = new Handler(Looper.getMainLooper());
        timerHandler = new Handler(Looper.getMainLooper());

        chatSocket = ChatSocketManager.getInstance();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        try {
            webrtcManager = new WebRTCCallManager(this);
            webrtcManager.initialize();
        } catch (Throwable t) {
            Log.e(TAG, "WebRTC initialization failed", t);
            finish();
            return;
        }
        if (!webrtcManager.isReady()) {
            Log.e(TAG, "WebRTC factory not created; closing call screen");
            finish();
            return;
        }

        ChatPrefs prefs = new ChatPrefs(this);
        myUserId = prefs.getUserId();

        callId = getIntent().getStringExtra(EXTRA_CALL_ID);
        otherUserId = getIntent().getIntExtra(EXTRA_OTHER_USER_ID, -1);
        isIncoming = getIntent().getBooleanExtra(EXTRA_IS_INCOMING, false);
        otherName = getIntent().getStringExtra(EXTRA_OTHER_NAME);
        String otherInitials = getIntent().getStringExtra(EXTRA_OTHER_INITIALS);
        String avatarColor = getIntent().getStringExtra(EXTRA_AVATAR_COLOR);

        if (otherName == null) otherName = "\u0645\u0633\u062A\u062E\u062F\u0645";
        if (otherInitials == null) otherInitials = otherName.substring(0, Math.min(2, otherName.length()));
        if (avatarColor == null) avatarColor = "#3B82F6";

        callName = findViewById(R.id.call_name);
        callStatus = findViewById(R.id.call_status);
        muteIndicator = findViewById(R.id.call_mute_indicator);
        TextView avatar = findViewById(R.id.call_avatar);
        btnMute = findViewById(R.id.btn_mute);
        btnSpeaker = findViewById(R.id.btn_speaker);
        btnEndCall = findViewById(R.id.btn_end_call);
        incomingButtons = findViewById(R.id.incoming_buttons);
        controlButtons = findViewById(R.id.control_buttons);

        callName.setText(otherName);
        avatar.setText(otherInitials);
        try { avatar.getBackground().setTint(Color.parseColor(avatarColor)); }
        catch (Exception e) { avatar.getBackground().setTint(Color.parseColor("#3B82F6")); }

        audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(false);
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0);

        setupCallListener();
        setupButtons();

        CallService.cancelIncomingCallNotification(this);

        if (isIncoming) {
            callStatus.setText(getString(R.string.call_incoming));
            incomingButtons.setVisibility(View.VISIBLE);
            controlButtons.setVisibility(View.GONE);
            // Do not start microphone FGS until user answers and RECORD_AUDIO is granted (Android 14+).
        } else {
            callStatus.setText(getString(R.string.call_connecting));
            incomingButtons.setVisibility(View.GONE);
            beginOutgoingCallAfterMicCheck();
        }
    }

    private void ensureCallForegroundService() {
        if (callFgServiceStarted) return;
        callFgServiceStarted = true;
        try {
            Intent svcIntent = CallService.createIntent(this, otherName);
            ContextCompat.startForegroundService(this, svcIntent);
            bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start CallService", e);
            callFgServiceStarted = false;
        }
    }

    private void beginOutgoingCallAfterMicCheck() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            awaitingMicForOutgoing = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_MIC_FOR_CALL);
            return;
        }
        ensureCallForegroundService();
        initiateCall();
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

        ImageView btnAnswer = findViewById(R.id.btn_answer);
        ImageView btnReject = findViewById(R.id.btn_reject);

        btnAnswer.setOnClickListener(v -> answerCall());
        btnReject.setOnClickListener(v -> rejectCall());
    }

    private void setupCallListener() {
        callListener = new ChatSocketManager.OnCallListener() {
            @Override
            public void onCallRinging(String rCallId, int receiverId) {
                mainHandler.post(() -> {
                    callId = rCallId;
                    callStatus.setText(getString(R.string.call_ringing));
                    flushPendingIceCandidates();
                });
            }

            @Override
            public void onCallAnswered(String aCallId, org.json.JSONObject answerSdp) {
                mainHandler.post(() -> {
                    if (webrtcManager != null && answerSdp != null) webrtcManager.setRemoteAnswer(answerSdp);
                });
            }

            @Override
            public void onCallEnded(String eCallId, String reason, int duration) {
                callId = null;
                mainHandler.post(() -> {
                    cancelConnectionTimeout();
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
            public void onCallAudio(String aCallId, String base64Audio) {}

            @Override
            public void onCallIceCandidate(String cid, org.json.JSONObject candidate) {
                if (webrtcManager != null && candidate != null) webrtcManager.addIceCandidate(candidate);
            }

            @Override
            public void onCallMute(String mCallId, int userId, boolean muted) {
                mainHandler.post(() -> muteIndicator.setVisibility(muted ? View.VISIBLE : View.GONE));
            }

            @Override
            public void onIncomingCall(String iCallId, int callerId, String callerName, String callerInitials, String callerAvatarColor, org.json.JSONObject offer) {
                // Handled by global listener
            }
        };
        chatSocket.addCallListener(callListener);
    }

    private WebRTCCallManager.Callback createWebRtcCallback(boolean isCaller) {
        return new WebRTCCallManager.Callback() {
            @Override
            public void onOfferCreated(JSONObject offer) {
                if (isCaller) mainHandler.post(() -> chatSocket.emitCallOffer(myUserId, otherUserId, offer));
            }
            @Override
            public void onAnswerCreated(JSONObject answer) {
                if (!isCaller) mainHandler.post(() -> chatSocket.emitCallAnswer(callId, answer));
            }
            @Override
            public void onIceCandidate(JSONObject candidate) {
                if (callId != null) {
                    chatSocket.emitCallIceCandidate(callId, myUserId, candidate);
                } else {
                    synchronized (pendingIceCandidates) {
                        pendingIceCandidates.add(candidate);
                    }
                }
            }
            @Override
            public void onConnected() {
                mainHandler.post(() -> {
                    cancelConnectionTimeout();
                    startActiveCall();
                });
            }
            @Override
            public void onDisconnected() {
                mainHandler.post(() -> {
                    if (isCallActive) {
                        callStatus.setText(getString(R.string.call_disconnected));
                    }
                });
            }
            @Override
            public void onError(String message) {
                mainHandler.post(() -> callStatus.setText("Error: " + message));
            }
        };
    }

    private void flushPendingIceCandidates() {
        if (callId == null) return;
        synchronized (pendingIceCandidates) {
            for (JSONObject c : pendingIceCandidates) {
                chatSocket.emitCallIceCandidate(callId, myUserId, c);
            }
            pendingIceCandidates.clear();
        }
    }

    private void fetchIceServersAndRun(Runnable afterFetch) {
        ChatApiService.getInstance().getIceServers(new ChatApiService.ApiCallback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray result) {
                if (result != null && result.length() > 0) {
                    List<PeerConnection.IceServer> servers = new ArrayList<>();
                    for (int i = 0; i < result.length(); i++) {
                        try {
                            JSONObject s = result.getJSONObject(i);
                            String urls = s.optString("urls", "");
                            if (urls.isEmpty()) continue;
                            PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urls);
                            if (s.has("username")) builder.setUsername(s.getString("username"));
                            if (s.has("credential")) builder.setPassword(s.getString("credential"));
                            servers.add(builder.createIceServer());
                        } catch (Exception e) {
                            Log.w(TAG, "Parse ICE server: " + e.getMessage());
                        }
                    }
                    if (!servers.isEmpty()) webrtcManager.setIceServers(servers);
                }
                afterFetch.run();
            }
            @Override
            public void onError(String error) {
                Log.w(TAG, "Failed to fetch ICE servers, using defaults: " + error);
                afterFetch.run();
            }
        });
    }

    private void initiateCall() {
        webrtcManager.setCallback(createWebRtcCallback(true));
        startConnectionTimeout();
        fetchIceServersAndRun(() -> {
            webrtcManager.createPeerConnection();
            webrtcManager.createOffer();
        });
    }

    private void answerCall() {
        if (awaitingMicForAnswer) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            awaitingMicForAnswer = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_MIC_FOR_CALL);
            return;
        }
        performAnswerCall();
    }

    private void performAnswerCall() {
        incomingButtons.setVisibility(View.GONE);
        controlButtons.setVisibility(View.VISIBLE);
        callStatus.setText(getString(R.string.call_connecting));

        ensureCallForegroundService();

        String offerStr = getIntent().getStringExtra(EXTRA_OFFER_SDP);
        org.json.JSONObject offerSdp = null;
        if (offerStr != null) {
            try {
                offerSdp = new org.json.JSONObject(offerStr);
            } catch (Exception e) {
                Log.e(TAG, "Parse offer SDP: " + e.getMessage());
            }
        }

        webrtcManager.setCallback(createWebRtcCallback(false));
        startConnectionTimeout();

        final org.json.JSONObject finalOffer = offerSdp;
        fetchIceServersAndRun(() -> {
            webrtcManager.createPeerConnection();
            webrtcManager.setRemoteOfferAndCreateAnswer(finalOffer);
        });
    }

    private void rejectCall() {
        awaitingMicForOutgoing = false;
        awaitingMicForAnswer = false;
        try {
            JSONObject data = new JSONObject();
            data.put("callId", callId);
            chatSocket.emitRaw("call:reject", data);
        } catch (Exception e) {
            Log.e(TAG, "Error rejecting call: " + e.getMessage());
        }
        finish();
    }

    private void startConnectionTimeout() {
        cancelConnectionTimeout();
        connectionTimeoutRunnable = () -> {
            if (!isCallActive && !isFinishing()) {
                Log.w(TAG, "Connection timeout after " + CALL_TIMEOUT_MS + "ms");
                callStatus.setText(getString(R.string.call_no_answer));
                endCall();
            }
        };
        mainHandler.postDelayed(connectionTimeoutRunnable, CALL_TIMEOUT_MS);
    }

    private void cancelConnectionTimeout() {
        if (connectionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(connectionTimeoutRunnable);
            connectionTimeoutRunnable = null;
        }
    }

    private void startActiveCall() {
        if (isCallActive) return;
        isCallActive = true;
        cancelConnectionTimeout();
        callStartTime = System.currentTimeMillis();
        callStatus.setText("00:00");

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
                    String timeStr = String.format(Locale.getDefault(), "%02d:%02d", min, sec);
                    callStatus.setText(timeStr);
                    if (serviceBound && callService != null) callService.updateDuration(timeStr);
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_MIC_FOR_CALL) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) {
                if (awaitingMicForOutgoing) {
                    awaitingMicForOutgoing = false;
                    ensureCallForegroundService();
                    initiateCall();
                } else if (awaitingMicForAnswer) {
                    awaitingMicForAnswer = false;
                    performAnswerCall();
                }
            } else {
                if (awaitingMicForOutgoing) {
                    awaitingMicForOutgoing = false;
                    callStatus.setText(getString(R.string.call_no_answer));
                    finish();
                } else if (awaitingMicForAnswer) {
                    awaitingMicForAnswer = false;
                    incomingButtons.setVisibility(View.VISIBLE);
                    controlButtons.setVisibility(View.GONE);
                    callStatus.setText(getString(R.string.call_incoming));
                }
            }
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
        cancelConnectionTimeout();
        if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
        if (webrtcManager != null) webrtcManager.release();
        if (serviceBound) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            serviceBound = false;
        }
        stopService(new Intent(this, CallService.class));
    }

    @Override
    public void onBackPressed() {
        endCall();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
            audioManager.abandonAudioFocus(null);
        } catch (Exception ignored) {}
    }
}
