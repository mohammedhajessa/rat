const callService = require('../services/callService');
const { getDb, stmt } = require('../db/database');

/**
 * WebRTC signaling-only call handler.
 * Server does NOT relay audio; clients use P2P via WebRTC (offer/answer/ICE).
 */

function parseData(data) {
    if (!data) return {};
    if (typeof data === 'string') {
        try { return JSON.parse(data); } catch (e) { return {}; }
    }
    return data;
}

function setupCallSocket(io) {

    io.on('connection', function(socket) {

        // ── call:offer ── Caller sends SDP offer. Server forwards to receiver in call:incoming.
        socket.on('call:offer', function(rawData) {
            try {
                var data = parseData(rawData);
                var callerId = parseInt(data.callerId);
                var receiverId = parseInt(data.receiverId);
                var offer = data.offer || null; // WebRTC SDP offer (object or string)
                if (!callerId || !receiverId) return;

                var blocked = stmt('checkBlock').get(receiverId, callerId, callerId, receiverId);
                if (blocked) {
                    socket.emit('call:ended', { callId: 'blocked', reason: 'unavailable' });
                    return;
                }

                var callId = callService.createCall(callerId, receiverId);
                var caller = callService.getCallerInfo(callerId);

                // Send incoming call to receiver WITH offer SDP so they can setRemoteDescription(offer)
                io.to('user_' + receiverId).emit('call:incoming', {
                    callId: callId,
                    callerId: callerId,
                    callerName: caller ? caller.display_name : '\u0645\u0633\u062A\u062E\u062F\u0645',
                    callerInitials: caller ? caller.initials : '\u061F',
                    callerAvatarColor: caller ? caller.avatar_color : '#3B82F6',
                    offer: offer
                });

                // Confirm ringing to caller
                socket.emit('call:ringing', { callId: callId, receiverId: receiverId });

                // Auto-timeout for unanswered calls
                callService.setCallTimeout(callId, function(call) {
                    io.to('user_' + call.callerId).emit('call:ended', { callId: callId, reason: 'no_answer' });
                    io.to('user_' + call.receiverId).emit('call:ended', { callId: callId, reason: 'missed' });
                    console.log('[CALL] ' + callId + ': missed (timeout)');
                });

                console.log('[CALL] ' + callId + ': user#' + callerId + ' -> user#' + receiverId + ' (WebRTC signaling)');
            } catch (err) {
                console.error('[CALL] offer error:', err.message);
            }
        });

        // ── call:answer ── Receiver sends SDP answer. Server forwards to caller (and receiver) for setRemoteDescription.
        socket.on('call:answer', function(rawData) {
            try {
                var data = parseData(rawData);
                var callId = data.callId;
                var sdp = data.sdp || null; // WebRTC SDP answer
                var call = callService.answerCall(callId);
                if (!call) return;

                io.to('user_' + call.callerId).emit('call:answered', { callId: callId, sdp: sdp });
                io.to('user_' + call.receiverId).emit('call:answered', { callId: callId, sdp: sdp });
                console.log('[CALL] ' + callId + ': answered (WebRTC)');
            } catch (err) {
                console.error('[CALL] answer error:', err.message);
            }
        });

        // ICE CANDIDATE (WebRTC)
        socket.on('call:ice-candidate', function(rawData) {
            try {
                var data = parseData(rawData);
                var callId = data.callId;
                var senderId = parseInt(data.senderId);
                var candidate = data.candidate;
                var call = callService.getCall(callId);
                if (!call || !candidate) return;

                var targetId = call.callerId === senderId ? call.receiverId : call.callerId;
                io.to('user_' + targetId).emit('call:ice-candidate', {
                    callId: callId,
                    candidate: candidate
                });
            } catch (err) {
                console.error('[CALL] ice-candidate error:', err.message);
            }
        });

        // REJECT CALL
        socket.on('call:reject', function(rawData) {
            try {
                var data = parseData(rawData);
                var callId = data.callId;
                var call = callService.rejectCall(callId);
                if (!call) return;

                io.to('user_' + call.callerId).emit('call:ended', { callId: callId, reason: 'rejected' });
                io.to('user_' + call.receiverId).emit('call:ended', { callId: callId, reason: 'rejected' });
                console.log('[CALL] ' + callId + ': rejected');
            } catch (err) {
                console.error('[CALL] reject error:', err.message);
            }
        });

        // END CALL
        socket.on('call:end', function(rawData) {
            try {
                var data = parseData(rawData);
                var callId = data.callId;
                var result = callService.endCall(callId);
                if (!result) return;

                io.to('user_' + result.call.callerId).emit('call:ended', {
                    callId: callId, reason: 'ended', duration: result.duration
                });
                io.to('user_' + result.call.receiverId).emit('call:ended', {
                    callId: callId, reason: 'ended', duration: result.duration
                });
                console.log('[CALL] ' + callId + ': ended (' + result.duration + 's)');
            } catch (err) {
                console.error('[CALL] end error:', err.message);
            }
        });

        // Audio is no longer relayed by the server. WebRTC P2P handles media.
        // Ignore call:audio if sent by legacy clients (no-op).
        socket.on('call:audio', function() { /* no-op: WebRTC P2P only */ });

        // ── call:mute ── Signaling only (UI state); no media relayed.
        socket.on('call:mute', function(rawData) {
            try {
                var data = parseData(rawData);
                var callId = data.callId;
                var userId = parseInt(data.userId);
                var call = callService.getCall(callId);
                if (!call) return;

                var targetId = call.callerId === userId ? call.receiverId : call.callerId;
                io.to('user_' + targetId).emit('call:mute', {
                    callId: callId,
                    userId: userId,
                    isMuted: data.isMuted
                });
            } catch (err) {
                console.error('[CALL] mute error:', err.message);
            }
        });

        // CLEANUP ON DISCONNECT
        socket.on('disconnect', function() {
            var userId = socket.userId;
            if (!userId) return;

            var cleaned = callService.cleanupUserCalls(userId);
            for (var i = 0; i < cleaned.length; i++) {
                var item = cleaned[i];
                io.to('user_' + item.otherId).emit('call:ended', {
                    callId: item.callId,
                    reason: 'disconnected',
                    duration: item.duration
                });
                console.log('[CALL] ' + item.callId + ': ended (disconnect, ' + item.duration + 's)');
            }
        });
    });
}

module.exports = { setupCallSocket: setupCallSocket };
