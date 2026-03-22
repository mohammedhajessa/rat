const rateLimit = require('express-rate-limit');

var apiLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 1000,
    standardHeaders: true,
    legacyHeaders: false,
    validate: { xForwardedForHeader: false },
    message: { success: false, error: 'Too many requests, please try again later' }
});

var authLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 100,
    standardHeaders: true,
    legacyHeaders: false,
    validate: { xForwardedForHeader: false },
    message: { success: false, error: 'Too many auth attempts, please try again later' }
});

var uploadLimiter = rateLimit({
    windowMs: 5 * 60 * 1000,
    max: 100,
    standardHeaders: true,
    legacyHeaders: false,
    validate: { xForwardedForHeader: false },
    message: { success: false, error: 'Too many uploads, please slow down' }
});

module.exports = {
    apiLimiter: apiLimiter,
    authLimiter: authLimiter,
    uploadLimiter: uploadLimiter
};
