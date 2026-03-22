function asyncHandler(fn) {
    return function(req, res, next) {
        Promise.resolve(fn(req, res, next)).catch(next);
    };
}

function centralErrorHandler(err, req, res, next) {
    console.error('[ERROR] ' + req.method + ' ' + req.originalUrl + ':', err.message);
    console.error(err.stack);

    if (err.type === 'entity.too.large') {
        return res.status(413).json({ success: false, error: 'Payload too large' });
    }

    if (err.code === 'LIMIT_FILE_SIZE') {
        return res.status(413).json({ success: false, error: 'File too large' });
    }

    if (err.code === 'LIMIT_UNEXPECTED_FILE') {
        return res.status(400).json({ success: false, error: 'Unexpected file field' });
    }

    var statusCode = err.statusCode || err.status || 500;
    var message = process.env.NODE_ENV === 'production' ? 'Internal server error' : err.message;

    res.status(statusCode).json({
        success: false,
        error: message
    });
}

module.exports = {
    asyncHandler: asyncHandler,
    centralErrorHandler: centralErrorHandler
};
