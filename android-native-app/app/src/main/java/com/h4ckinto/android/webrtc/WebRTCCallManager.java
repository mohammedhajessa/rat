package com.h4ckinto.android.webrtc;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WebRTC P2P audio call manager.
 * Uses PeerConnectionFactory, RTCPeerConnection, AudioTrack.
 * STUN for NAT traversal; signaling (offer/answer/ICE) is done via Socket.IO in the activity.
 */
public class WebRTCCallManager {

    private static final String TAG = "WebRTCCallManager";

    private static final String STUN_URL = "stun:stun.l.google.com:19302";

    private final Context context;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private AudioTrack localAudioTrack;
    private AudioSource audioSource;
    private AudioDeviceModule audioDeviceModule;
    private boolean isMuted;

    private List<PeerConnection.IceServer> iceServers;
    private PeerConnection.Observer pcObserver;
    private Callback callback;

    public interface Callback {
        void onOfferCreated(JSONObject offer);
        void onAnswerCreated(JSONObject answer);
        void onIceCandidate(JSONObject candidate);
        void onConnected();
        void onDisconnected();
        void onError(String message);
    }

    public WebRTCCallManager(Context context) {
        this.context = context.getApplicationContext();
        this.isMuted = false;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /** True after {@link #initialize()} built a factory successfully. */
    public boolean isReady() {
        return peerConnectionFactory != null;
    }

    /**
     * Initialize PeerConnectionFactory (audio only, Opus).
     */
    public void initialize() {
        if (peerConnectionFactory != null) return;

        try {
            WebRtcInitialization.ensureInitialized(context);

            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            audioDeviceModule = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(
                            JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                    .setUseHardwareNoiseSuppressor(
                            JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                    .createAudioDeviceModule();

            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setAudioDeviceModule(audioDeviceModule)
                    .createPeerConnectionFactory();

            iceServers = new ArrayList<>();
            iceServers.add(PeerConnection.IceServer.builder(STUN_URL).createIceServer());

            Log.d(TAG, "PeerConnectionFactory created (audio, JavaAudioDeviceModule)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create PeerConnectionFactory", e);
            disposeFactoryAndAudio();
        }
    }

    private void disposeFactoryAndAudio() {
        if (peerConnectionFactory != null) {
            try {
                peerConnectionFactory.dispose();
            } catch (Exception ignored) { }
            peerConnectionFactory = null;
        }
        if (audioDeviceModule != null) {
            try {
                audioDeviceModule.release();
            } catch (Exception ignored) { }
            audioDeviceModule = null;
        }
    }

    /**
     * Replace the default ICE servers with a list fetched from the signaling server.
     * Must be called before createPeerConnection().
     */
    public void setIceServers(List<PeerConnection.IceServer> servers) {
        if (servers != null && !servers.isEmpty()) {
            iceServers = new ArrayList<>(servers);
            Log.d(TAG, "ICE servers updated: " + servers.size() + " server(s)");
        }
    }

    /**
     * Create peer connection and add local audio track (caller or receiver).
     */
    public void createPeerConnection() {
        if (peerConnectionFactory == null) initialize();
        if (peerConnectionFactory == null) {
            Log.e(TAG, "PeerConnectionFactory is null – cannot create peer connection");
            if (callback != null) callback.onError("WebRTC not available");
            return;
        }
        if (peerConnection != null) return;

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        pcObserver = new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState state) {
                Log.d(TAG, "SignalingState: " + state);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                Log.d(TAG, "IceConnectionState: " + state);
                if (callback == null) return;
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    callback.onConnected();
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED
                        || state == PeerConnection.IceConnectionState.FAILED
                        || state == PeerConnection.IceConnectionState.CLOSED) {
                    callback.onDisconnected();
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                if (callback == null) return;
                try {
                    JSONObject json = new JSONObject();
                    json.put("sdpMid", candidate.sdpMid);
                    json.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    json.put("candidate", candidate.sdp);
                    callback.onIceCandidate(json);
                } catch (Exception e) {
                    Log.e(TAG, "onIceCandidate serialize: " + e.getMessage());
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {}

            @Override
            public void onAddStream(org.webrtc.MediaStream stream) {
                Log.d(TAG, "onAddStream (remote)");
            }

            @Override
            public void onRemoveStream(org.webrtc.MediaStream stream) {}

            @Override
            public void onDataChannel(org.webrtc.DataChannel dataChannel) {}

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, org.webrtc.MediaStream[] mediaStreams) {
                Log.d(TAG, "onAddTrack (remote audio)");
            }
        };

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, pcObserver);
        if (peerConnection == null) {
            if (callback != null) callback.onError("Failed to create PeerConnection");
            return;
        }

        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(!isMuted);
        peerConnection.addTrack(localAudioTrack, Collections.singletonList("stream0"));

        Log.d(TAG, "PeerConnection created, local audio track added");
    }

    /**
     * Caller: create offer and return SDP via callback.
     */
    public void createOffer() {
        if (peerConnection == null) createPeerConnection();
        MediaConstraints offerConstraints = new MediaConstraints();
        offerConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        peerConnection.createOffer(new org.webrtc.SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new org.webrtc.SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}

                    @Override
                    public void onSetSuccess() {
                        if (callback != null) {
                            try {
                                JSONObject offer = new JSONObject();
                                offer.put("type", sessionDescription.type.canonicalForm());
                                offer.put("sdp", sessionDescription.description);
                                callback.onOfferCreated(offer);
                            } catch (Exception e) {
                                if (callback != null) callback.onError("Offer serialize: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        if (callback != null) callback.onError("setLocalDescription: " + s);
                    }

                    @Override
                    public void onSetFailure(String s) {
                        if (callback != null) callback.onError("setLocalDescription: " + s);
                    }
                }, sessionDescription);
            }

            @Override
            public void onCreateFailure(String s) {
                if (callback != null) callback.onError("createOffer: " + s);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onSetFailure(String s) {}
        }, offerConstraints);
    }

    /**
     * Receiver: set remote description (offer) then create answer.
     */
    public void setRemoteOfferAndCreateAnswer(JSONObject offerSdp) {
        if (offerSdp == null || peerConnection == null) return;
        try {
            String type = offerSdp.optString("type", "offer");
            String sdp = offerSdp.optString("sdp", "");
            SessionDescription offer = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp);

            peerConnection.setRemoteDescription(new org.webrtc.SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {}

                @Override
                public void onSetSuccess() {
                    createAnswer();
                }

                @Override
                public void onCreateFailure(String s) {
                    if (callback != null) callback.onError("setRemoteDescription: " + s);
                }

                @Override
                public void onSetFailure(String s) {
                    if (callback != null) callback.onError("setRemoteDescription: " + s);
                }
            }, offer);
        } catch (Exception e) {
            Log.e(TAG, "setRemoteOfferAndCreateAnswer: " + e.getMessage());
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    private void createAnswer() {
        MediaConstraints answerConstraints = new MediaConstraints();

        peerConnection.createAnswer(new org.webrtc.SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new org.webrtc.SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}

                    @Override
                    public void onSetSuccess() {
                        if (callback != null) {
                            try {
                                JSONObject answer = new JSONObject();
                                answer.put("type", sessionDescription.type.canonicalForm());
                                answer.put("sdp", sessionDescription.description);
                                callback.onAnswerCreated(answer);
                            } catch (Exception e) {
                                if (callback != null) callback.onError("Answer serialize: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        if (callback != null) callback.onError("setLocalDescription: " + s);
                    }

                    @Override
                    public void onSetFailure(String s) {
                        if (callback != null) callback.onError("setLocalDescription: " + s);
                    }
                }, sessionDescription);
            }

            @Override
            public void onCreateFailure(String s) {
                if (callback != null) callback.onError("createAnswer: " + s);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onSetFailure(String s) {}
        }, answerConstraints);
    }

    /**
     * Caller: set remote description (answer from receiver).
     */
    public void setRemoteAnswer(JSONObject answerSdp) {
        if (answerSdp == null || peerConnection == null) return;
        try {
            String type = answerSdp.optString("type", "answer");
            String sdp = answerSdp.optString("sdp", "");
            SessionDescription answer = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp);

            peerConnection.setRemoteDescription(new org.webrtc.SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {}

                @Override
                public void onSetSuccess() {
                    Log.d(TAG, "Remote answer set");
                }

                @Override
                public void onCreateFailure(String s) {
                    if (callback != null) callback.onError("setRemoteDescription answer: " + s);
                }

                @Override
                public void onSetFailure(String s) {
                    if (callback != null) callback.onError("setRemoteDescription answer: " + s);
                }
            }, answer);
        } catch (Exception e) {
            Log.e(TAG, "setRemoteAnswer: " + e.getMessage());
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Add ICE candidate from the other peer (signaled via server).
     */
    public void addIceCandidate(JSONObject candidateJson) {
        if (peerConnection == null || candidateJson == null) return;
        try {
            String sdpMid = candidateJson.optString("sdpMid");
            int sdpMLineIndex = candidateJson.optInt("sdpMLineIndex", 0);
            String candidate = candidateJson.optString("candidate");
            IceCandidate c = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
            peerConnection.addIceCandidate(c);
        } catch (Exception e) {
            Log.e(TAG, "addIceCandidate: " + e.getMessage());
        }
    }

    public void setMuted(boolean muted) {
        isMuted = muted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!muted);
    }

    public void release() {
        try {
            if (peerConnection != null) {
                peerConnection.dispose();
                peerConnection = null;
            }
            if (localAudioTrack != null) {
                localAudioTrack.dispose();
                localAudioTrack = null;
            }
            if (audioSource != null) {
                audioSource.dispose();
                audioSource = null;
            }
            if (peerConnectionFactory != null) {
                peerConnectionFactory.dispose();
                peerConnectionFactory = null;
            }
            if (audioDeviceModule != null) {
                audioDeviceModule.release();
                audioDeviceModule = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during WebRTC release", e);
        }
        callback = null;
        Log.d(TAG, "WebRTC released");
    }
}
