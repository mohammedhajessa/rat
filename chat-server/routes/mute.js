var express = require('express');
var router = express.Router();
var { stmt } = require('../db/database');
var { asyncHandler } = require('../middleware/errorHandler');
var { validateInt } = require('../middleware/validate');
var redis = require('../services/redisService');

router.post('/', asyncHandler(function(req, res) {
    var body = req.body || {};
    var userId = validateInt(body.user_id);
    var conversationId = validateInt(body.conversation_id);
    if (!userId || !conversationId) {
        return res.status(400).json({ success: false, error: 'user_id and conversation_id are required' });
    }
    stmt('insertMute').run(userId, conversationId);
    redis.safeDel('convlist:' + userId);
    res.json({ success: true });
    console.log('[MUTE] User #' + userId + ' muted conversation #' + conversationId);
}));

router.get('/:userId/:conversationId', asyncHandler(function(req, res) {
    var userId = validateInt(req.params.userId);
    var conversationId = validateInt(req.params.conversationId);
    if (!userId || !conversationId) {
        return res.status(400).json({ success: false, error: 'Invalid parameters' });
    }
    var row = stmt('checkMuted').get(userId, conversationId);
    res.json({ success: true, muted: !!row });
}));

router.delete('/:userId/:conversationId', asyncHandler(function(req, res) {
    var userId = validateInt(req.params.userId);
    var conversationId = validateInt(req.params.conversationId);
    if (!userId || !conversationId) {
        return res.status(400).json({ success: false, error: 'Invalid parameters' });
    }
    stmt('deleteMute').run(userId, conversationId);
    redis.safeDel('convlist:' + userId);
    res.json({ success: true });
    console.log('[MUTE] User #' + userId + ' unmuted conversation #' + conversationId);
}));

module.exports = router;
