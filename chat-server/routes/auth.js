const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { v4: uuidv4 } = require('uuid');
const { getDb, stmt } = require('../db/database');
const { asyncHandler } = require('../middleware/errorHandler');
const { authLimiter } = require('../middleware/rateLimit');
const { sanitize, validateRequired } = require('../middleware/validate');

function generatePin() {
    return String(crypto.randomInt(100000, 999999));
}

function generateToken() {
    return uuidv4().replace(/-/g, '');
}

// POST /api/auth/generate-pin
router.post('/generate-pin', asyncHandler(function(req, res) {
    var body = req.body || {};
    var display_name = sanitize(body.display_name);
    var initials = sanitize(body.initials);
    var avatar_color = body.avatar_color || '#3B82F6';
    var rank = sanitize(body.rank || '');
    var job_title = sanitize(body.job_title || '');

    var db = getDb();
    var pin = generatePin();
    var name = display_name || '\u0645\u0633\u062A\u062E\u062F\u0645 ' + pin.substring(0, 3);
    var init = initials || name.substring(0, 2);
    var color = /^#[0-9A-Fa-f]{6}$/.test(avatar_color) ? avatar_color : '#3B82F6';
    var placeholder = 'pending_' + pin;

    var result = stmt('insertUser').run(placeholder, pin, name, init, color, rank, job_title);

    res.json({
        success: true,
        user_id: result.lastInsertRowid,
        pin: pin,
        display_name: name,
        rank: rank,
        job_title: job_title
    });

    console.log('[AUTH] Generated PIN ' + pin + ' for user "' + name + '" (user #' + result.lastInsertRowid + ')');
}));

// POST /api/auth/verify-pin
router.post('/verify-pin', authLimiter, asyncHandler(function(req, res) {
    var body = req.body || {};
    var pin = body.pin;
    var device_id = body.device_id;
    var device_name = sanitize(body.device_name || '');

    if (!pin || !device_id) {
        return res.status(400).json({ error: 'pin and device_id are required' });
    }

    var db = getDb();
    var user = stmt('getUserByPin').get(String(pin));

    if (!user) {
        return res.status(401).json({ error: '\u0631\u0645\u0632 \u063A\u064A\u0631 \u0635\u0627\u0644\u062D', success: false });
    }

    try {
        stmt('updateUserDeviceOnline').run(device_id, user.id);
    } catch (e) {
        stmt('setUserOnlineNow').run(user.id);
    }

    var isNewDevice = false;
    var existing = stmt('getDeviceByUserAndDevice').get(user.id, device_id);
    if (!existing) {
        stmt('insertDevice').run(user.id, device_id, device_name);
        isNewDevice = true;
    } else {
        stmt('updateDeviceActivity').run(device_name || existing.device_name, user.id, device_id);
    }

    var deviceCount = stmt('countDevicesByUser').get(user.id).cnt;

    var token = generateToken();

    res.json({
        success: true,
        token: token,
        is_new_device: isNewDevice,
        device_count: deviceCount,
        user: {
            id: user.id,
            device_id: device_id,
            display_name: user.display_name,
            initials: user.initials,
            avatar_color: user.avatar_color,
            avatar_url: user.avatar_url || '',
            username: user.username || '',
            rank: user.rank || '',
            job_title: user.job_title || ''
        }
    });

    // Notify other devices of this user about the new login via Socket.IO
    if (isNewDevice && req.app.get('io')) {
        var io = req.app.get('io');
        io.to('user_' + user.id).emit('device:new_login', {
            device_id: device_id,
            device_name: device_name,
            device_count: deviceCount,
            timestamp: new Date().toISOString()
        });
    }

    console.log('[AUTH] User #' + user.id + ' (' + user.display_name + ') verified PIN from device ' + device_id + (isNewDevice ? ' [NEW DEVICE]' : ''));
}));

// POST /api/auth/update-profile
router.post('/update-profile', asyncHandler(function(req, res) {
    var body = req.body || {};
    var user_id = body.user_id;

    if (!user_id) {
        return res.status(400).json({ error: 'user_id is required' });
    }

    var db = getDb();
    var updates = [];
    var values = [];

    if (body.display_name) {
        var newName = sanitize(body.display_name);
        updates.push('display_name = ?');
        values.push(newName);
        updates.push('initials = ?');
        values.push(newName.substring(0, 2));
    }
    if (body.initials) { updates.push('initials = ?'); values.push(sanitize(body.initials)); }
    if (body.avatar_color) {
        var color = /^#[0-9A-Fa-f]{6}$/.test(body.avatar_color) ? body.avatar_color : '#3B82F6';
        updates.push('avatar_color = ?');
        values.push(color);
    }
    if (body.avatar_url !== undefined) {
        updates.push('avatar_url = ?');
        values.push(sanitize(body.avatar_url));
    }
    if (body.username !== undefined) {
        var uname = String(body.username || '').trim().toLowerCase().replace(/^@/, '');
        if (uname && !/^[a-z0-9_]{3,30}$/.test(uname)) {
            return res.status(400).json({ success: false, error: '\u0627\u0633\u0645 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645 \u064A\u062C\u0628 \u0623\u0646 \u064A\u0643\u0648\u0646 3-30 \u062D\u0631\u0641 (a-z, 0-9, _)' });
        }
        if (uname) {
            var existing = stmt('checkUsernameConflict').get(uname, user_id);
            if (existing) {
                return res.status(409).json({ success: false, error: '\u0627\u0633\u0645 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645 \u0645\u0633\u062A\u062E\u062F\u0645 \u0628\u0627\u0644\u0641\u0639\u0644' });
            }
        }
        updates.push('username = ?');
        values.push(uname);
    }
    if (body.rank !== undefined) { updates.push('rank = ?'); values.push(sanitize(body.rank)); }
    if (body.job_title !== undefined) { updates.push('job_title = ?'); values.push(sanitize(body.job_title)); }

    if (updates.length === 0) {
        return res.status(400).json({ error: 'No fields to update' });
    }

    values.push(user_id);
    var s = db.prepare('UPDATE users SET ' + updates.join(', ') + ' WHERE id = ?');
    s.run.apply(s, values);

    var updated = stmt('getUserProfileById').get(user_id);

    res.json({ success: true, user: updated });
    console.log('[AUTH] Updated profile for user#' + user_id + ' fields: ' + updates.join(', '));
}));

// GET /api/auth/user/:userId - look up a single user by ID (public info)
router.get('/user/:userId', asyncHandler(function(req, res) {
    var userId = parseInt(req.params.userId, 10);
    if (!userId || isNaN(userId)) {
        return res.status(400).json({ success: false, error: 'Invalid user ID' });
    }

    var user = stmt('getUserById').get(userId);
    if (!user) {
        return res.status(404).json({ success: false, error: '\u0644\u0645 \u064A\u062A\u0645 \u0627\u0644\u0639\u062B\u0648\u0631 \u0639\u0644\u0649 \u0645\u0633\u062A\u062E\u062F\u0645 \u0628\u0647\u0630\u0627 \u0627\u0644\u0645\u0639\u0631\u0641' });
    }

    res.json({ success: true, user: user });
}));

// GET /api/auth/search-username/:username - look up a user by username
router.get('/search-username/:username', asyncHandler(function(req, res) {
    var username = (req.params.username || '').trim().toLowerCase().replace(/^@/, '');
    if (!username || username.length < 3) {
        return res.status(400).json({ success: false, error: '\u0627\u0633\u0645 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645 \u064A\u062C\u0628 \u0623\u0646 \u064A\u0643\u0648\u0646 3 \u062D\u0631\u0648\u0641 \u0639\u0644\u0649 \u0627\u0644\u0623\u0642\u0644' });
    }

    var user = stmt('getUserByUsername').get(username);
    if (!user) {
        return res.status(404).json({ success: false, error: '\u0644\u0645 \u064A\u062A\u0645 \u0627\u0644\u0639\u062B\u0648\u0631 \u0639\u0644\u0649 \u0645\u0633\u062A\u062E\u062F\u0645 \u0628\u0647\u0630\u0627 \u0627\u0644\u0627\u0633\u0645' });
    }

    res.json({ success: true, user: user });
}));

// GET /api/auth/devices/:userId - list all devices for a user
router.get('/devices/:userId', asyncHandler(function(req, res) {
    var userId = parseInt(req.params.userId, 10);
    if (!userId || isNaN(userId)) {
        return res.status(400).json({ success: false, error: 'Invalid userId' });
    }

    var devices = stmt('getDevicesByUser').all(userId);
    res.json({ success: true, devices: devices, count: devices.length });
}));

// DELETE /api/auth/devices/:userId/:deviceRowId - remove a device (logout)
router.delete('/devices/:userId/:deviceRowId', asyncHandler(function(req, res) {
    var userId = parseInt(req.params.userId, 10);
    var deviceRowId = parseInt(req.params.deviceRowId, 10);
    if (!userId || !deviceRowId) {
        return res.status(400).json({ success: false, error: 'Invalid parameters' });
    }

    var device = stmt('getDeviceByIdAndUser').get(deviceRowId, userId);
    if (!device) {
        return res.status(404).json({ success: false, error: 'Device not found' });
    }

    stmt('deleteDeviceById').run(deviceRowId);

    // Notify the removed device to logout via Socket.IO
    if (req.app.get('io')) {
        var io = req.app.get('io');
        io.to('user_' + userId).emit('device:force_logout', {
            device_id: device.device_id,
            reason: 'removed_by_user'
        });
    }

    var remaining = stmt('countDevicesByUser').get(userId).cnt;
    res.json({ success: true, remaining_devices: remaining });
    console.log('[AUTH] Device removed for user #' + userId + ': ' + device.device_id);
}));

// POST /api/auth/change-pin - change user PIN
router.post('/change-pin', asyncHandler(function(req, res) {
    var body = req.body || {};
    var user_id = parseInt(body.user_id, 10);
    var current_pin = body.current_pin;
    var new_pin = body.new_pin;

    if (!user_id || !current_pin || !new_pin) {
        return res.status(400).json({ success: false, error: 'user_id, current_pin, and new_pin are required' });
    }

    if (!/^\d{6}$/.test(new_pin)) {
        return res.status(400).json({ success: false, error: '\u0627\u0644\u0631\u0645\u0632 \u0627\u0644\u062C\u062F\u064A\u062F \u064A\u062C\u0628 \u0623\u0646 \u064A\u0643\u0648\u0646 6 \u0623\u0631\u0642\u0627\u0645' });
    }

    var user = stmt('getUserPinById').get(user_id);
    if (!user) {
        return res.status(404).json({ success: false, error: '\u0645\u0633\u062A\u062E\u062F\u0645 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F' });
    }

    if (user.pin !== String(current_pin)) {
        return res.status(401).json({ success: false, error: '\u0627\u0644\u0631\u0645\u0632 \u0627\u0644\u062D\u0627\u0644\u064A \u063A\u064A\u0631 \u0635\u062D\u064A\u062D' });
    }

    // Check new PIN isn't already used by another user
    var pinExists = stmt('checkPinConflict').get(String(new_pin), user_id);
    if (pinExists) {
        return res.status(409).json({ success: false, error: '\u0647\u0630\u0627 \u0627\u0644\u0631\u0645\u0632 \u0645\u0633\u062A\u062E\u062F\u0645 \u0628\u0627\u0644\u0641\u0639\u0644' });
    }

    stmt('updateUserPin').run(String(new_pin), user_id);

    res.json({ success: true, message: '\u062A\u0645 \u062A\u063A\u064A\u064A\u0631 \u0627\u0644\u0631\u0645\u0632 \u0628\u0646\u062C\u0627\u062D' });
    console.log('[AUTH] User #' + user_id + ' changed PIN');
}));

// GET /api/auth/users (admin, capped at 100)
router.get('/users', asyncHandler(function(req, res) {
    var limit = Math.min(parseInt(req.query.limit) || 100, 100);
    var offset = parseInt(req.query.offset) || 0;
    var users = stmt('getAllUsers').all();
    var paged = users.slice(offset, offset + limit);
    res.json({ success: true, users: paged, total: users.length });
}));

router.post('/reset', asyncHandler(function(req, res) {
    var db = getDb();
    var resetAll = db.transaction(function() {
        db.exec('DELETE FROM messages');
        db.exec('DELETE FROM contacts');
        db.exec('DELETE FROM conversations');
        db.exec('DELETE FROM call_logs');
        db.exec('DELETE FROM devices');
        db.exec('DELETE FROM blocked_users');
        db.exec('DELETE FROM muted_conversations');
        db.exec('DELETE FROM users');
    });
    resetAll();
    res.json({ success: true, message: 'All data deleted successfully' });
    console.log('[AUTH] Database reset - all data deleted');
}));

module.exports = router;
