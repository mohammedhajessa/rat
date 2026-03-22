const { getDb, stmt } = require('../db/database');
const messageService = require('../services/messageService');
const redis = require('../services/redisService');

var onlineUsers = new Map();
var awayUsers = new Set();
var typingTimers = new Map();
var typingLastSent = new Map(); // Rate limiting: conversationId_userId -> timestamp
var presenceSyncTimer = null;
var clientMessageIds = new Map(); // In-memory cache for clientMessageId deduplication (fallback if Redis unavailable)

var TYPING_TIMEOUT_MS = 4000;
var TYPING_RATE_LIMIT_MS = 2000; // Max 1 typing event per 2 seconds
var PRESENCE_SYNC_INTERVAL_MS = 60000;
var PRESENCE_HEARTBEAT_TTL = 60; // Redis TTL for presence heartbeat
var CLIENT_MSG_ID_TTL = 300; // Keep clientMessageId in Redis for 5 minutes

function parseData(data) {
    if (!data) return {};
    if (typeof data === 'string') {
        try { return JSON.parse(data); } catch (e) { return {}; }
    }
    return data;
}

function logEvent(event, data) {
    var logData = {
        event: event,
        timestamp: messageService.nowISO(),
        userId: data.userId || data.senderId || null,
        conversationId: data.conversationId || null,
        messageId: data.messageId || null
    };
    console.log('[LOG]', JSON.stringify(logData));
}

async function getContactIds(userId) {
    if (redis.available()) {
        var cached = await redis.safeGet('contacts:' + userId);
        if (cached) {
            try { return JSON.parse(cached); } catch (e) { /* fall through */ }
        }
    }

    var rows = stmt('getContactUserIds').all(userId, userId, userId);
    var ids = rows.map(function(r) { return r.uid; });

    redis.safeSet('contacts:' + userId, JSON.stringify(ids), 300);

    return ids;
}

async function broadcastPresence(io, userId, isOnline, lastSeen) {
    var payload = { userId: userId, isOnline: isOnline, lastSeen: lastSeen };

    var contactIds = await getContactIds(userId);

    for (var i = 0; i < contactIds.length; i++) {
        io.to('user_' + contactIds[i]).emit('chat:online', payload);
    }
    io.to('user_' + userId).emit('chat:online', payload);
}

async function setPresence(userId, isOnline, lastSeen) {
    if (redis.available()) {
        if (isOnline) {
            await redis.safeHset('presence:' + userId, 'online', '1');
            await redis.safeHset('presence:' + userId, 'lastSeen', lastSeen);
            await redis.safeExpire('presence:' + userId, PRESENCE_HEARTBEAT_TTL);
            await redis.safeSadd('online_users', String(userId));
        } else {
            await redis.safeHset('presence:' + userId, 'online', '0');
            await redis.safeHset('presence:' + userId, 'lastSeen', lastSeen);
            await redis.safeExpire('presence:' + userId, 86400);
            await redis.safeSrem('online_users', String(userId));
        }
    }

    try {
        if (isOnline) {
            stmt('setUserOnline').run(lastSeen, userId);
        } else {
            stmt('setUserOffline').run(lastSeen, userId);
        }
    } catch (e) {
        console.error('[SOCKET] setPresence DB error:', e.message);
    }
}

async function getPresence(userId) {
    if (redis.available()) {
        var data = await redis.safeHgetall('presence:' + userId);
        if (data) {
            return {
                isOnline: data.online === '1',
                lastSeen: data.lastSeen || ''
            };
        }
        // Check SET for online status
        var isOnlineSet = await redis.safeSismember('online_users', String(userId));
        if (isOnlineSet) {
            return { isOnline: true, lastSeen: '' };
        }
    }

    var isOnline = onlineUsers.has(userId) && !awayUsers.has(userId);
    var user = stmt('getUserLastSeen').get(userId);
    return {
        isOnline: isOnline,
        lastSeen: user ? (user.last_seen || '') : ''
    };
}

async function checkDuplicateMessage(clientMessageId, senderId) {
    if (!clientMessageId) return null;

    var cacheKey = 'client_msg:' + senderId + ':' + clientMessageId;

    // Check Redis first
    if (redis.available()) {
        var existing = await redis.safeGet(cacheKey);
        if (existing) {
            try {
                return JSON.parse(existing); // Returns { messageId, timestamp }
            } catch (e) { /* fall through */ }
        }
    }

    // Fallback to in-memory cache
    if (clientMessageIds.has(cacheKey)) {
        return clientMessageIds.get(cacheKey);
    }

    return null;
}

async function storeClientMessageId(clientMessageId, senderId, messageId) {
    if (!clientMessageId) return;

    var cacheKey = 'client_msg:' + senderId + ':' + clientMessageId;
    var data = { messageId: messageId, timestamp: messageService.nowISO() };

    // Store in Redis
    if (redis.available()) {
        await redis.safeSet(cacheKey, JSON.stringify(data), CLIENT_MSG_ID_TTL);
    }

    // Also store in memory (fallback)
    clientMessageIds.set(cacheKey, data);
    setTimeout(function() {
        clientMessageIds.delete(cacheKey);
    }, CLIENT_MSG_ID_TTL * 1000);
}

function validateMediaMessage(data) {
    var messageType = data.messageType || 'text';
    if (messageType === 'text') return true;

    // Media messages must have fileUrl, fileName, fileSize
    if (!data.fileUrl || !data.fileName || !data.fileSize) {
        return false;
    }

    // Validate fileSize is a positive number
    var fileSize = parseInt(data.fileSize);
    if (isNaN(fileSize) || fileSize <= 0) {
        return false;
    }

    return true;
}

function setupChatSocket(io) {

    startPresenceSync();

    io.on('connection', function(socket) {

        var socketTypingTimers = new Map(); // Per-socket typing timers for cleanup

        socket.on('chat:join', function(rawData) {
            try {
                var data = parseData(rawData);
                var userId = parseInt(data.userId);
                if (!userId || isNaN(userId)) return;

                socket.userId = userId;
                socket.join('user_' + userId);

                if (!onlineUsers.has(userId)) onlineUsers.set(userId, new Set());
                onlineUsers.get(userId).add(socket.id);
                awayUsers.delete(userId);

                var now = messageService.nowISO();
                setPresence(userId, true, now);
                broadcastPresence(io, userId, true, now);

                var onlineList = Array.from(onlineUsers.keys());
                socket.emit('chat:online_list', { users: onlineList });

                logEvent('chat:join', { userId: userId });
            } catch (err) {
                console.error('[SOCKET] chat:join error:', err.message);
            }
        });

        socket.on('chat:heartbeat', async function(rawData) {
            try {
                var userId = socket.userId;
                if (!userId) return;

                var now = messageService.nowISO();
                // Update Redis presence with TTL (keeps user online)
                if (redis.available()) {
                    await redis.safeHset('presence:' + userId, 'online', '1');
                    await redis.safeHset('presence:' + userId, 'lastSeen', now);
                    await redis.safeExpire('presence:' + userId, PRESENCE_HEARTBEAT_TTL);
                    await redis.safeSadd('online_users', String(userId));
                }
                // Also update in-memory and DB
                if (!onlineUsers.has(userId)) onlineUsers.set(userId, new Set());
                onlineUsers.get(userId).add(socket.id);
                awayUsers.delete(userId);
                stmt('setUserOnline').run(now, userId);
                
                // Broadcast presence to contacts so they know user is still online
                // This ensures both users see each other as online when in conversation
                broadcastPresence(io, userId, true, now);
            } catch (err) {
                console.error('[SOCKET] chat:heartbeat error:', err.message);
            }
        });

        socket.on('chat:request_status', function(rawData) {
            try {
                var data = parseData(rawData);
                var targetId = parseInt(data.userId);
                if (!targetId || isNaN(targetId)) return;

                var requesterId = socket.userId;
                if (requesterId) {
                    var blocked = stmt('checkBlock').get(targetId, requesterId, requesterId, targetId);
                    if (blocked) {
                        socket.emit('chat:online', { userId: targetId, isOnline: false, lastSeen: '' });
                        return;
                    }
                }

                getPresence(targetId).then(function(status) {
                    socket.emit('chat:online', {
                        userId: targetId,
                        isOnline: status.isOnline,
                        lastSeen: status.lastSeen
                    });
                });
            } catch (err) {
                console.error('[SOCKET] request_status error:', err.message);
            }
        });

        socket.on('chat:set_status', function(rawData) {
            try {
                var data = parseData(rawData);
                var userId = socket.userId;
                if (!userId) return;
                var active = data.isOnline === true;
                var now = messageService.nowISO();

                if (active) {
                    awayUsers.delete(userId);
                    setPresence(userId, true, now);
                    broadcastPresence(io, userId, true, now);
                } else {
                    awayUsers.add(userId);
                    setPresence(userId, false, now);
                    broadcastPresence(io, userId, false, now);
                }

                logEvent('chat:set_status', { userId: userId, isOnline: active });
            } catch (err) {
                console.error('[SOCKET] chat:set_status error:', err.message);
            }
        });

        socket.on('chat:send', async function(rawData, ack) {
            try {
                var data = parseData(rawData);
                var conversationId = parseInt(data.conversationId);
                var senderId = parseInt(data.senderId);
                var clientMessageId = data.clientMessageId || null;

                if (!conversationId || !senderId || isNaN(conversationId) || isNaN(senderId)) {
                    if (ack && typeof ack === 'function') {
                        ack({ status: 'error', error: 'Invalid conversationId or senderId' });
                    }
                    return;
                }

                // Check for duplicate clientMessageId
                if (clientMessageId) {
                    var duplicate = await checkDuplicateMessage(clientMessageId, senderId);
                    if (duplicate) {
                        // Duplicate detected - return ACK with existing messageId
                        if (ack && typeof ack === 'function') {
                            ack({
                                status: 'ok',
                                messageId: duplicate.messageId,
                                duplicate: true
                            });
                        }
                        logEvent('chat:send_duplicate', { senderId: senderId, conversationId: conversationId, clientMessageId: clientMessageId });
                        return;
                    }
                }

                var conv = stmt('getConversation').get(conversationId);
                if (!conv) {
                    if (ack && typeof ack === 'function') {
                        ack({ status: 'error', error: 'Conversation not found' });
                    }
                    return;
                }

                var recipientId = conv.user1_id === senderId ? conv.user2_id : conv.user1_id;
                var blocked = stmt('checkBlock').get(recipientId, senderId, senderId, recipientId);
                if (blocked) {
                    if (ack && typeof ack === 'function') {
                        ack({ status: 'error', error: 'User blocked' });
                    }
                    return;
                }

                var messageType = data.messageType || 'text';
                if (messageType === 'text' && !data.text) {
                    if (ack && typeof ack === 'function') {
                        ack({ status: 'error', error: 'Text message requires text field' });
                    }
                    return;
                }

                // Validate media messages
                if (messageType !== 'text' && !validateMediaMessage(data)) {
                    if (ack && typeof ack === 'function') {
                        ack({ status: 'error', error: 'Media message requires fileUrl, fileName, and fileSize' });
                    }
                    return;
                }

                var result = messageService.insertMessage({
                    conversationId: conversationId,
                    senderId: senderId,
                    text: data.text || '',
                    messageType: messageType,
                    fileUrl: data.fileUrl || null,
                    fileName: data.fileName || null,
                    fileSize: data.fileSize || 0,
                    duration: data.duration || 0
                });

                // Store clientMessageId to prevent duplicates
                if (clientMessageId) {
                    await storeClientMessageId(clientMessageId, senderId, result.message.id);
                }

                // Send ACK with messageId
                if (ack && typeof ack === 'function') {
                    ack({
                        status: 'ok',
                        messageId: result.message.id
                    });
                }

                io.to('user_' + recipientId).emit('chat:receive', {
                    message: result.message,
                    conversationId: conversationId
                });
                io.to('user_' + senderId).emit('chat:sent', {
                    message: result.message,
                    conversationId: conversationId
                });

                var convUpdate = {
                    conversationId: conversationId,
                    lastMessage: result.preview,
                    lastMessageTime: result.message.timestamp,
                    senderId: senderId
                };
                io.to('user_' + recipientId).emit('chat:conversation_update', convUpdate);
                io.to('user_' + senderId).emit('chat:conversation_update', convUpdate);

                redis.safeDel('convlist:' + senderId, 'convlist:' + recipientId);
                redis.safeHincrby('unread:' + recipientId, String(conversationId), 1);

                clearTyping(io, conversationId, senderId);

                logEvent('chat:send', {
                    senderId: senderId,
                    conversationId: conversationId,
                    messageId: result.message.id,
                    messageType: messageType
                });
            } catch (err) {
                console.error('[SOCKET] chat:send error:', err.message);
                if (ack && typeof ack === 'function') {
                    ack({ status: 'error', error: err.message });
                }
            }
        });

        socket.on('chat:typing', function(rawData) {
            try {
                var data = parseData(rawData);
                var conversationId = parseInt(data.conversationId);
                var userId = parseInt(data.userId);
                if (!conversationId || !userId || isNaN(conversationId) || isNaN(userId)) return;

                // Rate limiting: check if typing was sent recently
                var rateLimitKey = conversationId + '_' + userId;
                var lastSent = typingLastSent.get(rateLimitKey);
                var now = Date.now();
                if (lastSent && (now - lastSent) < TYPING_RATE_LIMIT_MS) {
                    return; // Ignore - too soon
                }
                typingLastSent.set(rateLimitKey, now);

                var conv = stmt('getConversation').get(conversationId);
                if (!conv) return;

                var recipientId = conv.user1_id === userId ? conv.user2_id : conv.user1_id;
                io.to('user_' + recipientId).emit('chat:typing', { conversationId: conversationId, userId: userId });

                var key = conversationId + '_' + userId;
                if (typingTimers.has(key)) clearTimeout(typingTimers.get(key));
                var timer = setTimeout(function() {
                    io.to('user_' + recipientId).emit('chat:stop_typing', { conversationId: conversationId, userId: userId });
                    typingTimers.delete(key);
                    typingLastSent.delete(rateLimitKey);
                }, TYPING_TIMEOUT_MS);
                typingTimers.set(key, timer);
                socketTypingTimers.set(key, timer); // Track for cleanup

                logEvent('chat:typing', { userId: userId, conversationId: conversationId });
            } catch (err) {
                console.error('[SOCKET] chat:typing error:', err.message);
            }
        });

        socket.on('chat:stop_typing', function(rawData) {
            try {
                var data = parseData(rawData);
                var conversationId = parseInt(data.conversationId);
                var userId = parseInt(data.userId);
                if (!conversationId || !userId || isNaN(conversationId) || isNaN(userId)) return;
                clearTyping(io, conversationId, userId);
                var rateLimitKey = conversationId + '_' + userId;
                typingLastSent.delete(rateLimitKey);
            } catch (err) {
                console.error('[SOCKET] chat:stop_typing error:', err.message);
            }
        });

        socket.on('chat:recording', function(rawData) {
            try {
                var data = parseData(rawData);
                var conversationId = parseInt(data.conversationId);
                var userId = parseInt(data.userId);
                if (!conversationId || !userId || isNaN(conversationId) || isNaN(userId)) return;

                var conv = messageService.getConversation(conversationId);
                if (!conv) return;

                var recipientId = conv.user1_id === userId ? conv.user2_id : conv.user1_id;
                io.to('user_' + recipientId).emit('chat:recording', { conversationId: conversationId, userId: userId });
            } catch (err) {
                console.error('[SOCKET] chat:recording error:', err.message);
            }
        });

        socket.on('chat:stop_recording', function(rawData) {
            try {
                var data = parseData(rawData);
                var conversationId = parseInt(data.conversationId);
                var userId = parseInt(data.userId);
                if (!conversationId || !userId || isNaN(conversationId) || isNaN(userId)) return;

                var conv = messageService.getConversation(conversationId);
                if (!conv) return;

                var recipientId = conv.user1_id === userId ? conv.user2_id : conv.user1_id;
                io.to('user_' + recipientId).emit('chat:stop_recording', { conversationId: conversationId, userId: userId });
            } catch (err) {
                console.error('[SOCKET] chat:stop_recording error:', err.message);
            }
        });

        socket.on('chat:read', function(rawData) {
            try {
                var data = parseData(rawData);
                var conversationId = parseInt(data.conversationId);
                var userId = parseInt(data.userId);
                if (!conversationId || !userId || isNaN(conversationId) || isNaN(userId)) return;

                messageService.markMessagesRead(conversationId, userId);

                var conv = messageService.getConversation(conversationId);
                if (!conv) return;

                var senderId = conv.user1_id === userId ? conv.user2_id : conv.user1_id;
                io.to('user_' + senderId).emit('chat:read', { conversationId: conversationId, readBy: userId });

                redis.safeHdel('unread:' + userId, String(conversationId));
                redis.safeDel('convlist:' + userId, 'convlist:' + senderId);

                logEvent('chat:read', { userId: userId, conversationId: conversationId });
            } catch (err) {
                console.error('[SOCKET] chat:read error:', err.message);
            }
        });

        socket.on('chat:delete', function(rawData) {
            try {
                var data = parseData(rawData);
                var messageId = parseInt(data.messageId);
                var userId = parseInt(data.userId);
                var deleteForAll = data.deleteForAll === true;
                if (!messageId || !userId || isNaN(messageId) || isNaN(userId)) return;

                if (deleteForAll) {
                    var result = messageService.deleteMessageForAll(messageId, userId);
                    if (!result) return;

                    io.to('user_' + userId).emit('chat:deleted', {
                        messageId: messageId, conversationId: result.conversationId, deleteForAll: true
                    });
                    io.to('user_' + result.otherId).emit('chat:deleted', {
                        messageId: messageId, conversationId: result.conversationId, deleteForAll: true
                    });

                    redis.safeDel('convlist:' + userId, 'convlist:' + result.otherId);

                    logEvent('chat:delete', { userId: userId, messageId: messageId, conversationId: result.conversationId, deleteForAll: true });
                } else {
                    var msg = messageService.getMessageById(messageId);
                    io.to('user_' + userId).emit('chat:deleted', {
                        messageId: messageId,
                        conversationId: msg ? msg.conversation_id : 0,
                        deleteForAll: false
                    });

                    logEvent('chat:delete', { userId: userId, messageId: messageId, deleteForAll: false });
                }
            } catch (err) {
                console.error('[SOCKET] chat:delete error:', err.message);
            }
        });

        socket.on('disconnect', function(reason) {
            try {
                var userId = socket.userId;
                if (!userId) return;

                // Clean up typing timers for this socket
                socketTypingTimers.forEach(function(timer) {
                    clearTimeout(timer);
                });
                socketTypingTimers.clear();

                if (onlineUsers.has(userId)) {
                    onlineUsers.get(userId).delete(socket.id);
                    if (onlineUsers.get(userId).size === 0) {
                        onlineUsers.delete(userId);
                        awayUsers.delete(userId);

                        var lastSeen = messageService.nowISO();
                        setPresence(userId, false, lastSeen);
                        broadcastPresence(io, userId, false, lastSeen);

                        logEvent('chat:disconnect', { userId: userId });
                    }
                }
            } catch (err) {
                console.error('[SOCKET] disconnect error:', err.message);
            }
        });
    });

    return io;
}

function clearTyping(io, conversationId, userId) {
    var key = conversationId + '_' + userId;
    if (typingTimers.has(key)) {
        clearTimeout(typingTimers.get(key));
        typingTimers.delete(key);
    }
    var rateLimitKey = conversationId + '_' + userId;
    typingLastSent.delete(rateLimitKey);
    try {
        var conv = messageService.getConversation(conversationId);
        if (conv) {
            var recipientId = conv.user1_id === userId ? conv.user2_id : conv.user1_id;
            io.to('user_' + recipientId).emit('chat:stop_typing', { conversationId: conversationId, userId: userId });
        }
    } catch (e) { /* ignore */ }
}

function startPresenceSync() {
    if (presenceSyncTimer) return;
    presenceSyncTimer = setInterval(function() {
        try {
            var now = messageService.nowISO();
            onlineUsers.forEach(function(sockets, userId) {
                if (sockets.size > 0 && !awayUsers.has(userId)) {
                    stmt('setUserOnline').run(now, userId);
                }
            });
        } catch (e) {
            console.error('[SOCKET] presenceSync error:', e.message);
        }
    }, PRESENCE_SYNC_INTERVAL_MS);
}

module.exports = { setupChatSocket: setupChatSocket };
