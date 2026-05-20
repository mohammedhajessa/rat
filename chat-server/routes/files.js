/**
 * Admin file upload: save files to server disk (e.g. for APK hosting).
 * POST /api/files/upload - multipart, multiple files, saved to uploads/
 * GET /api/files - list files in uploads/
 */
const express = require('express');
const router = express.Router();
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const { asyncHandler } = require('../middleware/errorHandler');

var uploadsDir = path.join(__dirname, '..', 'uploads');

if (!fs.existsSync(uploadsDir)) {
    fs.mkdirSync(uploadsDir, { recursive: true });
}

var storage = multer.diskStorage({
    destination: function(req, file, cb) {
        cb(null, uploadsDir);
    },
    filename: function(req, file, cb) {
        var base = (file.originalname || 'file').replace(/[^a-zA-Z0-9._-]/g, '_');
        if (!base) base = 'file';
        var ext = path.extname(base) || '';
        var name = path.basename(base, ext) || 'file';
        cb(null, name + '_' + Date.now() + ext);
    }
});

var upload = multer({
    storage: storage,
    limits: { fileSize: 150 * 1024 * 1024 },
    fileFilter: function(req, file, cb) {
        var ext = (path.extname(file.originalname || '') || '').toLowerCase();
        var mime = (file.mimetype || '').toLowerCase();
        var isApk = ext === '.apk' || mime === 'application/vnd.android.package-archive' || mime === 'application/octet-stream';
        if (isApk || ext || file.originalname) {
            return cb(null, true);
        }
        cb(null, true);
    }
});

// Multer error handler: pass to central handler with clear message
function handleMulterError(err, req, res, next) {
    if (err && err.code) {
        if (err.code === 'LIMIT_FILE_SIZE') {
            return res.status(413).json({ success: false, error: 'File too large (max 150 MB)' });
        }
        if (err.code === 'LIMIT_FILE_COUNT') {
            return res.status(400).json({ success: false, error: 'Too many files (max 50)' });
        }
        if (err.code === 'LIMIT_UNEXPECTED_FILE') {
            return res.status(400).json({ success: false, error: 'Unexpected field. Use field name "files".' });
        }
    }
    next(err);
}

// POST /api/files/upload - multiple files (field name must be "files")
router.post('/upload', function(req, res, next) {
    console.log('[FILES] Upload request received, Content-Type:', req.get('Content-Type') || 'none');
    upload.array('files', 50)(req, res, function(err) {
        if (err) return handleMulterError(err, req, res, next);
        next();
    });
}, asyncHandler(function(req, res) {
    if (!req.files || req.files.length === 0) {
        return res.status(400).json({ success: false, error: 'No files uploaded. Select file(s) and use Upload.' });
    }
    var baseUrl = (req.protocol + '://' + req.get('host')) + (process.env.BASE_PATH || '');
    var results = req.files.map(function(f) {
        var url = baseUrl + '/uploads/' + encodeURIComponent(f.filename);
        return { filename: f.filename, originalName: f.originalname, url: url, size: f.size };
    });
    console.log('[FILES] Uploaded ' + results.length + ' file(s) to uploads/');
    res.json({ success: true, files: results });
}));

// GET /api/files - list files in uploads folder (async, paginated)
router.get('/', asyncHandler(async function(req, res) {
    var limit = Math.min(parseInt(req.query.limit) || 50, 100);
    var offset = parseInt(req.query.offset) || 0;
    var names = await fs.promises.readdir(uploadsDir);
    var baseUrl = (req.protocol + '://' + req.get('host')) + (process.env.BASE_PATH || '');
    var fileInfos = [];
    for (var i = 0; i < names.length; i++) {
        var p = path.join(uploadsDir, names[i]);
        var stat = await fs.promises.stat(p);
        if (stat.isFile()) {
            fileInfos.push({
                name: names[i],
                url: baseUrl + '/uploads/' + encodeURIComponent(names[i]),
                size: stat.size,
                mtime: stat.mtime
            });
        }
    }
    var total = fileInfos.length;
    var paged = fileInfos.slice(offset, offset + limit);
    res.json({ success: true, files: paged, total: total });
}));

async function clearAppUpdateIfUrlMatches(deletedFileName) {
    var configPath = path.join(__dirname, '..', 'config', 'app-update.json');
    try {
        var data = await fs.promises.readFile(configPath, 'utf8');
        var config = JSON.parse(data);
        var url = (config.apk_url || '').trim();
        if (!url) return;
        var pathPart = url.split('?')[0].split('/').pop() || '';
        try { pathPart = decodeURIComponent(pathPart); } catch (_) {}
        if (pathPart !== deletedFileName) return;
        config.apk_url = '';
        config.version_name = '';
        var dir = path.dirname(configPath);
        await fs.promises.mkdir(dir, { recursive: true });
        await fs.promises.writeFile(configPath, JSON.stringify(config, null, 2), 'utf8');
        console.log('[FILES] Cleared app-update.json apk_url and version_name (deleted file was in use)');
    } catch (e) {
        if (e.code !== 'ENOENT') console.warn('[FILES] Could not clear app-update config:', e.message);
    }
}

// DELETE /api/files/:name - delete one file
router.delete('/:name', asyncHandler(async function(req, res) {
    var name = (req.params.name || '').replace(/\.\./g, '').replace(/[\/\\]/g, '');
    if (!name) return res.status(400).json({ success: false, error: 'Invalid name' });
    var filePath = path.join(uploadsDir, name);
    try {
        var stat = await fs.promises.stat(filePath);
        if (!stat.isFile()) return res.status(404).json({ success: false, error: 'File not found' });
    } catch (e) {
        return res.status(404).json({ success: false, error: 'File not found' });
    }
    await fs.promises.unlink(filePath);
    clearAppUpdateIfUrlMatches(name);
    console.log('[FILES] Deleted ' + name);
    res.json({ success: true });
}));

module.exports = router;
