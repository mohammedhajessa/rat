const { getDb, stmt } = require('../db/database');

// Active calls: callId -> { callerId, receiverId, status, startTime, timeoutHandle }
const activeCalls = new Map();

const CALL_TIMEOUT_MS = 45000;

function generateCallId() {
    return Date.now().toString(36) + Math.random().toString(36).substring(2, 8);
}

function saveCallLog(callerId, receiverId, status, duration) {
    try {
        stmt('insertCallLog').run(callerId, receiverId, status, duration || 0);
        console.log('[CALL-SVC] Saved call log: caller=' + callerId + ' receiver=' + receiverId + ' status=' + status + ' duration=' + (duration || 0));
    } catch (e) {
        console.error('[CALL-SVC] Failed to save call log:', e.message);
    }
}

function createCall(callerId, receiverId) {
    var callId = generateCallId();

    activeCalls.set(callId, {
        callerId: callerId,
        receiverId: receiverId,
        status: 'ringing',
        startTime: null,
        timeoutHandle: null
    });

    return callId;
}

function getCall(callId) {
    return activeCalls.get(callId) || null;
}

function answerCall(callId) {
    var call = activeCalls.get(callId);
    if (!call) return null;

    call.status = 'active';
    call.startTime = Date.now();

    // Clear the ringing timeout
    if (call.timeoutHandle) {
        clearTimeout(call.timeoutHandle);
        call.timeoutHandle = null;
    }

    return call;
}

function endCall(callId) {
    var call = activeCalls.get(callId);
    if (!call) return null;

    var duration = call.startTime ? Math.floor((Date.now() - call.startTime) / 1000) : 0;
    var status = duration > 0 ? 'completed' : 'cancelled';

    // Clear any pending timeout
    if (call.timeoutHandle) {
        clearTimeout(call.timeoutHandle);
        call.timeoutHandle = null;
    }

    activeCalls.delete(callId);

    saveCallLog(call.callerId, call.receiverId, status, duration);

    return { call: call, duration: duration, status: status };
}

function rejectCall(callId) {
    var call = activeCalls.get(callId);
    if (!call) return null;

    if (call.timeoutHandle) {
        clearTimeout(call.timeoutHandle);
        call.timeoutHandle = null;
    }

    activeCalls.delete(callId);

    saveCallLog(call.callerId, call.receiverId, 'rejected', 0);

    return call;
}

function setCallTimeout(callId, onTimeout) {
    var call = activeCalls.get(callId);
    if (!call) return;

    call.timeoutHandle = setTimeout(function() {
        var c = activeCalls.get(callId);
        if (c && c.status === 'ringing') {
            activeCalls.delete(callId);
            saveCallLog(c.callerId, c.receiverId, 'missed', 0);
            if (onTimeout) onTimeout(c);
        }
    }, CALL_TIMEOUT_MS);
}

function cleanupUserCalls(userId) {
    var cleaned = [];

    for (var entry of activeCalls) {
        var callId = entry[0];
        var call = entry[1];

        if (call.callerId === userId || call.receiverId === userId) {
            var duration = call.startTime ? Math.floor((Date.now() - call.startTime) / 1000) : 0;

            if (call.timeoutHandle) {
                clearTimeout(call.timeoutHandle);
                call.timeoutHandle = null;
            }

            activeCalls.delete(callId);

            var status = duration > 0 ? 'completed' : 'missed';
            saveCallLog(call.callerId, call.receiverId, status, duration);

            var otherId = call.callerId === userId ? call.receiverId : call.callerId;
            cleaned.push({ callId: callId, otherId: otherId, duration: duration });
        }
    }

    return cleaned;
}

function getCallerInfo(callerId) {
    try {
        return stmt('getCallerInfo').get(callerId);
    } catch (e) {
        return null;
    }
}

function getActiveCallCount() {
    return activeCalls.size;
}

module.exports = {
    createCall: createCall,
    getCall: getCall,
    answerCall: answerCall,
    endCall: endCall,
    rejectCall: rejectCall,
    setCallTimeout: setCallTimeout,
    cleanupUserCalls: cleanupUserCalls,
    getCallerInfo: getCallerInfo,
    saveCallLog: saveCallLog,
    getActiveCallCount: getActiveCallCount
};
