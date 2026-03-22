var xssFilters = require('xss-filters');

function validateRequired(fields, body) {
    var missing = [];
    for (var i = 0; i < fields.length; i++) {
        if (body[fields[i]] === undefined || body[fields[i]] === null || body[fields[i]] === '') {
            missing.push(fields[i]);
        }
    }
    return missing;
}

function sanitize(text) {
    if (!text) return '';
    if (typeof text !== 'string') return String(text);
    return xssFilters.inHTMLData(text);
}

function validateInt(value) {
    var n = parseInt(value);
    if (isNaN(n) || n <= 0) return null;
    return n;
}

function validateParamId(paramName) {
    return function(req, res, next) {
        var val = parseInt(req.params[paramName]);
        if (isNaN(val) || val <= 0) {
            return res.status(400).json({ success: false, error: 'Invalid ' + paramName });
        }
        req.params[paramName] = val;
        next();
    };
}

module.exports = {
    validateRequired: validateRequired,
    sanitize: sanitize,
    validateInt: validateInt,
    validateParamId: validateParamId
};
