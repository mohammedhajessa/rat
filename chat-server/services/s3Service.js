const { S3Client, PutObjectCommand, DeleteObjectCommand, GetObjectCommand } = require('@aws-sdk/client-s3');
const { getSignedUrl } = require('@aws-sdk/s3-request-presigner');
const crypto = require('crypto');
const path = require('path');

const ALLOWED_MIME_TYPES = {
    image: ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp'],
    voice: [
        'audio/mpeg', 'audio/mp4', 'audio/aac', 'audio/ogg', 'audio/wav',
        'audio/x-m4a', 'audio/m4a', 'audio/3gpp', 'audio/amr',
        'application/octet-stream'
    ],
    document: [
        'application/pdf', 'application/msword',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.ms-excel',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'application/vnd.ms-powerpoint',
        'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        'text/plain', 'application/zip', 'application/x-rar-compressed',
        'application/octet-stream'
    ]
};

const SIZE_LIMITS = {
    image: 10 * 1024 * 1024,
    voice: 5 * 1024 * 1024,
    document: 25 * 1024 * 1024
};

let s3Client = null;
let bucketName = null;

function initS3() {
    if (s3Client) return;

    const region = process.env.AWS_REGION;
    const accessKeyId = process.env.AWS_ACCESS_KEY_ID;
    const secretAccessKey = process.env.AWS_SECRET_ACCESS_KEY;
    bucketName = process.env.AWS_BUCKET_NAME;

    if (!region || !accessKeyId || !secretAccessKey || !bucketName) {
        throw new Error(
            'Missing AWS config. Set AWS_REGION, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_BUCKET_NAME'
        );
    }

    s3Client = new S3Client({
        region: region,
        credentials: { accessKeyId: accessKeyId, secretAccessKey: secretAccessKey }
    });

    console.log('[S3] Initialized - bucket: ' + bucketName + ', region: ' + region);
}

function getSubdir(type) {
    switch (type) {
        case 'image': return 'images';
        case 'voice': return 'voice';
        case 'document': return 'documents';
        default: return 'documents';
    }
}

function generateS3Key(type, originalName) {
    var subdir = getSubdir(type);
    var ext = path.extname(originalName) || '.bin';
    var rand = crypto.randomBytes(16).toString('hex');
    var filename = type + '_' + Date.now() + '_' + rand + ext;
    return subdir + '/' + filename;
}

function validateMimeType(type, mimetype) {
    var allowed = ALLOWED_MIME_TYPES[type] || ALLOWED_MIME_TYPES.document;
    return allowed.includes(mimetype);
}

function getSizeLimit(type) {
    return SIZE_LIMITS[type] || SIZE_LIMITS.document;
}

async function uploadToS3(fileBuffer, key, mimetype) {
    initS3();

    var command = new PutObjectCommand({
        Bucket: bucketName,
        Key: key,
        Body: fileBuffer,
        ContentType: mimetype,
        ServerSideEncryption: 'AES256'
    });

    await s3Client.send(command);

    var url = 'https://' + bucketName + '.s3.' + process.env.AWS_REGION + '.amazonaws.com/' + key;
    return url;
}

async function deleteFromS3(key) {
    initS3();

    var command = new DeleteObjectCommand({
        Bucket: bucketName,
        Key: key
    });

    await s3Client.send(command);
}

async function getSignedDownloadUrl(key, expiresIn) {
    initS3();
    expiresIn = expiresIn || 3600;

    var command = new GetObjectCommand({
        Bucket: bucketName,
        Key: key
    });

    return await getSignedUrl(s3Client, command, { expiresIn: expiresIn });
}

function extractKeyFromUrl(url) {
    if (!url) return null;
    try {
        var parsed = new URL(url);
        return parsed.pathname.substring(1);
    } catch (e) {
        if (url.startsWith('/uploads/')) {
            return url.substring(9);
        }
        return url;
    }
}

module.exports = {
    initS3: initS3,
    uploadToS3: uploadToS3,
    deleteFromS3: deleteFromS3,
    getSignedDownloadUrl: getSignedDownloadUrl,
    generateS3Key: generateS3Key,
    validateMimeType: validateMimeType,
    getSizeLimit: getSizeLimit,
    getSubdir: getSubdir,
    extractKeyFromUrl: extractKeyFromUrl,
    ALLOWED_MIME_TYPES: ALLOWED_MIME_TYPES,
    SIZE_LIMITS: SIZE_LIMITS
};
