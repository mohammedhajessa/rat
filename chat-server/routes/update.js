/**
 * App update API for the Android client.
 * GET /api/app/update - returns current update config (no auth).
 * POST /api/app/update - (admin) save new version config (body: latest_version, min_supported_version, apk_url, message, force_update).
 */
const express = require('express');
const router = express.Router();
const fs = require('fs');
const path = require('path');
const { asyncHandler } = require('../middleware/errorHandler');

var configPath = path.join(__dirname, '..', 'config', 'app-update.json');

function readConfig() {
    try {
        var data = fs.readFileSync(configPath, 'utf8');
        return JSON.parse(data);
    } catch (e) {
        return {
            version_name: '',
            latest_version: 1,
            min_supported_version: 1,
            apk_url: '',
            force_update: false,
            message: ''
        };
    }
}

function writeConfig(obj) {
    var dir = path.dirname(configPath);
    if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
    }
    fs.writeFileSync(configPath, JSON.stringify(obj, null, 2), 'utf8');
}

// GET /api/app/update - Android app calls this to check for update
// version_name = release version from APK filename (e.g. 2.3.1 from 2.3.1.apk). Update only if server version_name > app versionName.
router.get('/update', function(req, res) {
    var config = readConfig();
    res.json({
        version_name: String(config.version_name || '').trim(),
        latest_version: config.latest_version,
        min_supported_version: config.min_supported_version,
        apk_url: config.apk_url || '',
        force_update: !!config.force_update,
        message: config.message || ''
    });
});

// POST /api/app/update - Admin: set version_name (e.g. from 2.3.1.apk), APK URL, etc.
router.post('/update', express.json(), asyncHandler(function(req, res) {
    var body = req.body || {};
    var latest = parseInt(body.latest_version, 10);
    var minSupported = parseInt(body.min_supported_version, 10);
    if (isNaN(latest)) latest = 1;
    if (isNaN(minSupported)) minSupported = 1;
    var versionName = String(body.version_name || '').trim();
    var config = {
        version_name: versionName,
        latest_version: latest,
        min_supported_version: minSupported,
        apk_url: String(body.apk_url || '').trim(),
        force_update: !!body.force_update,
        message: String(body.message || '').trim()
    };
    writeConfig(config);
    console.log('[UPDATE] App update config saved: version_name=' + config.version_name + ', apk=' + (config.apk_url ? 'set' : 'empty'));
    res.json({ success: true, config: config });
}));

module.exports = router;
