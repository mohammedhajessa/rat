const express = require('express');
const router = express.Router();
const { getDb, stmt } = require('../db/database');
const { asyncHandler } = require('../middleware/errorHandler');
const { validateInt } = require('../middleware/validate');

// GET /api/calls/:userId
router.get('/:userId', asyncHandler(function(req, res) {
    var userId = validateInt(req.params.userId);
    if (!userId) {
        return res.status(400).json({ success: false, error: 'Invalid userId' });
    }

    var calls = stmt('getCallLogs').all(userId, userId, userId, userId, userId);
    res.json({ success: true, calls: calls });
}));

// DELETE /api/calls/:userId
router.delete('/:userId', asyncHandler(function(req, res) {
    var userId = validateInt(req.params.userId);
    if (!userId) {
        return res.status(400).json({ success: false, error: 'Invalid userId' });
    }

    stmt('deleteCallLogs').run(userId, userId);

    res.json({ success: true, message: 'Call logs deleted' });
    console.log('[CALLS] Deleted call logs for user#' + userId);
}));

module.exports = router;
