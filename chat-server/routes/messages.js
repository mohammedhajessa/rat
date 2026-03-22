const express = require('express');
const router = express.Router();
const { getDb, stmt } = require('../db/database');
const { asyncHandler } = require('../middleware/errorHandler');
const { validateInt } = require('../middleware/validate');
const redis = require('../services/redisService');

router.get('/shared-media/:conversationId', asyncHandler(function(req, res) {
    var conversationId = validateInt(req.params.conversationId);
    if (!conversationId) {
        return res.status(400).json({ success: false, error: 'Invalid conversationId' });
    }

    var limit = parseInt(req.query.limit) || 50;
    var db = getDb();

    var media = stmt('getSharedMedia').all(conversationId, limit);
    var counts = stmt('getMediaCounts').all(conversationId);

    var summary = { images: 0, audio: 0, documents: 0, total: 0 };
    for (var i = 0; i < counts.length; i++) {
        if (counts[i].message_type === 'image') summary.images = counts[i].count;
        else if (counts[i].message_type === 'audio') summary.audio = counts[i].count;
        else if (counts[i].message_type === 'document') summary.documents = counts[i].count;
        summary.total += counts[i].count;
    }

    res.json({ success: true, media: media, summary: summary });
}));

router.get('/:conversationId', asyncHandler(function(req, res) {
    var conversationId = validateInt(req.params.conversationId);
    if (!conversationId) {
        return res.status(400).json({ success: false, error: 'Invalid conversationId' });
    }

    var limit = parseInt(req.query.limit) || 50;
    var beforeId = req.query.before_id;
    var maxAgeHours = parseInt(req.query.max_age_hours) || 0;
    var maxAgeSeconds = parseInt(req.query.max_age_seconds) || 0;
    var db = getDb();

    if (maxAgeSeconds > 0) {
        try {
            stmt('deleteOldMessagesBySeconds').run(conversationId, maxAgeSeconds);
        } catch (e) { /* ignore */ }
    } else if (maxAgeHours > 0) {
        try {
            stmt('deleteOldMessages').run(conversationId, maxAgeHours);
        } catch (e) { /* ignore */ }
    }

    var messages;
    if (beforeId) {
        messages = stmt('getMessagesBeforeId').all(conversationId, beforeId, limit);
    } else {
        messages = stmt('getMessagesPaginated').all(conversationId, limit);
    }

    messages.reverse();
    res.json({ success: true, messages: messages });
}));

router.get('/conversations/:userId', asyncHandler(async function(req, res) {
    var userId = validateInt(req.params.userId);
    if (!userId) {
        return res.status(400).json({ success: false, error: 'Invalid userId' });
    }

    var cacheKey = 'convlist:' + userId;
    var cached = await redis.safeGet(cacheKey);
    if (cached) {
        try {
            var parsed = JSON.parse(cached);
            return res.json({ success: true, conversations: parsed });
        } catch (e) { /* cache corrupt, fall through */ }
    }

    var uid = String(userId);

    var conversations = stmt('getConvForUser').all(userId, userId, userId, userId, userId, userId, userId, userId, userId, userId);

    conversations = conversations.filter(function(c) {
        if (!c.deleted_by) return true;
        var ids = c.deleted_by.split(',');
        return ids.indexOf(uid) === -1;
    });

    conversations.forEach(function(c) { delete c.deleted_by; });

    redis.safeSet(cacheKey, JSON.stringify(conversations), 30);

    res.json({ success: true, conversations: conversations });
}));

router.get('/conversation-with/:userId/:otherUserId', asyncHandler(function(req, res) {
    var userId = validateInt(req.params.userId);
    var otherUserId = validateInt(req.params.otherUserId);
    if (!userId || !otherUserId) {
        return res.status(400).json({ success: false, error: 'Invalid userId or otherUserId' });
    }

    var minId = Math.min(userId, otherUserId);
    var maxId = Math.max(userId, otherUserId);

    var conv = stmt('findConversation').get(minId, maxId);

    if (!conv) {
        var result = stmt('createConversation').run(minId, maxId);
        conv = { id: result.lastInsertRowid };
    }

    res.json({ success: true, conversation_id: conv.id });
}));

router.delete('/conversation/:conversationId/:userId', asyncHandler(function(req, res) {
    var conversationId = validateInt(req.params.conversationId);
    var userId = validateInt(req.params.userId);
    if (!conversationId || !userId) {
        return res.status(400).json({ success: false, error: 'Invalid conversationId or userId' });
    }

    var conv = stmt('getConvDeletedBy').get(conversationId);
    if (!conv) {
        return res.status(404).json({ success: false, error: 'Conversation not found' });
    }

    var deletedBy = conv.deleted_by || '';
    var ids = deletedBy ? deletedBy.split(',').filter(function(s) { return s.length > 0; }) : [];
    var uid = String(userId);
    if (ids.indexOf(uid) === -1) {
        ids.push(uid);
    }

    stmt('updateConvDeletedBy').run(ids.join(','), conversationId);

    redis.safeDel('convlist:' + userId);

    res.json({ success: true });
    console.log('[DELETE] User #' + userId + ' deleted conversation #' + conversationId);
}));

router.delete('/clear/:conversationId/:userId', asyncHandler(function(req, res) {
    var conversationId = validateInt(req.params.conversationId);
    var userId = validateInt(req.params.userId);
    if (!conversationId || !userId) {
        return res.status(400).json({ success: false, error: 'Invalid parameters' });
    }

    stmt('clearConvMessages').run(conversationId);
    stmt('clearConvLastMessage').run(conversationId);

    redis.safeDel('convlist:' + userId);

    res.json({ success: true });
    console.log('[CLEAR] User #' + userId + ' cleared messages in conversation #' + conversationId);
}));

module.exports = router;
