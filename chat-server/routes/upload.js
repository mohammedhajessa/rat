const express = require('express');
const router = express.Router();
const multer = require('multer');
const { asyncHandler } = require('../middleware/errorHandler');
const { uploadLimiter } = require('../middleware/rateLimit');
const s3Service = require('../services/s3Service');

// Use memory storage - file buffer goes directly to S3, never touches disk
var upload = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: 25 * 1024 * 1024 }
});

// POST /api/upload
router.post('/', uploadLimiter, upload.single('file'), asyncHandler(async function(req, res) {
    if (!req.file) {
        return res.status(400).json({ success: false, error: 'No file uploaded' });
    }

    var type = req.body.type || 'document';
    var senderId = req.body.sender_id;

    // Validate MIME type to prevent spoofing
    if (!s3Service.validateMimeType(type, req.file.mimetype)) {
        console.warn('[UPLOAD] Rejected: invalid MIME ' + req.file.mimetype + ' for type ' + type);
        return res.status(400).json({
            success: false,
            error: 'Invalid file type. Expected ' + type + ' but got ' + req.file.mimetype
        });
    }

    // Check size limit per type
    var sizeLimit = s3Service.getSizeLimit(type);
    if (req.file.size > sizeLimit) {
        return res.status(413).json({
            success: false,
            error: 'File too large. Max ' + Math.floor(sizeLimit / 1024 / 1024) + 'MB for ' + type
        });
    }

    // Generate S3 key and upload
    var key = s3Service.generateS3Key(type, req.file.originalname);
    var fileUrl = await s3Service.uploadToS3(req.file.buffer, key, req.file.mimetype);

    res.json({
        success: true,
        fileUrl: fileUrl,
        fileName: req.file.originalname,
        fileSize: req.file.size,
        mimeType: req.file.mimetype
    });

    console.log('[UPLOAD] ' + type + ' uploaded by user#' + senderId + ': ' + fileUrl + ' (' + req.file.size + ' bytes)');
}));

module.exports = router;
