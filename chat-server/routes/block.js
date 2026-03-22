var express = require('express');
var router = express.Router();
var { getDb, stmt } = require('../db/database');
var { asyncHandler } = require('../middleware/errorHandler');
var { validateInt } = require('../middleware/validate');
var redis = require('../services/redisService');

router.post('/', asyncHandler(function(req, res) {
    var body = req.body || {};
    var blockerId = validateInt(body.blocker_id);
    var blockedId = validateInt(body.blocked_id);
    if (!blockerId || !blockedId) {
        return res.status(400).json({ success: false, error: 'blocker_id and blocked_id are required' });
    }
    if (blockerId === blockedId) {
        return res.status(400).json({ success: false, error: 'Cannot block yourself' });
    }
    stmt('insertBlock').run(blockerId, blockedId);
    redis.safeDel('convlist:' + blockerId, 'convlist:' + blockedId);
    res.json({ success: true });
    console.log('[BLOCK] User #' + blockerId + ' blocked user #' + blockedId);
}));

router.delete('/:blockerId/:blockedId', asyncHandler(function(req, res) {
    var blockerId = validateInt(req.params.blockerId);
    var blockedId = validateInt(req.params.blockedId);
    if (!blockerId || !blockedId) {
        return res.status(400).json({ success: false, error: 'Invalid parameters' });
    }
    stmt('deleteBlock').run(blockerId, blockedId);
    redis.safeDel('convlist:' + blockerId, 'convlist:' + blockedId);
    res.json({ success: true });
    console.log('[BLOCK] User #' + blockerId + ' unblocked user #' + blockedId);
}));

router.get('/check/:blockerId/:blockedId', asyncHandler(function(req, res) {
    var blockerId = validateInt(req.params.blockerId);
    var blockedId = validateInt(req.params.blockedId);
    if (!blockerId || !blockedId) {
        return res.status(400).json({ success: false, error: 'Invalid parameters' });
    }
    var row = stmt('checkBlockOneWay').get(blockerId, blockedId);
    res.json({ success: true, is_blocked: !!row });
}));

router.get('/:userId', asyncHandler(function(req, res) {
    var userId = validateInt(req.params.userId);
    if (!userId) {
        return res.status(400).json({ success: false, error: 'Invalid userId' });
    }
    var rows = stmt('getBlockedIds').all(userId);
    var blockedIds = rows.map(function(r) { return r.blocked_id; });
    res.json({ success: true, blocked_ids: blockedIds });
}));

module.exports = router;
