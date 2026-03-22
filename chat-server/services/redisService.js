const Redis = require('ioredis');

var client = null;
var isReady = false;

function getRedis() {
    if (client) return client;

    var url = process.env.REDIS_URL || 'redis://127.0.0.1:6379';

    client = new Redis(url, {
        maxRetriesPerRequest: 1,
        retryStrategy: function(times) {
            if (times > 3) return null;
            return Math.min(times * 500, 2000);
        },
        lazyConnect: false,
        enableOfflineQueue: false
    });

    client.on('ready', function() {
        isReady = true;
        console.log('[REDIS] Connected');
    });

    client.on('error', function(err) {
        isReady = false;
        if (err.code !== 'ECONNREFUSED') {
            console.error('[REDIS] Error:', err.message);
        }
    });

    client.on('close', function() {
        isReady = false;
    });

    return client;
}

function available() {
    return isReady && client !== null;
}

async function safeGet(key) {
    if (!available()) return null;
    try { return await client.get(key); }
    catch (e) { return null; }
}

async function safeSet(key, value, exSeconds) {
    if (!available()) return;
    try {
        if (exSeconds) {
            await client.setex(key, exSeconds, value);
        } else {
            await client.set(key, value);
        }
    } catch (e) { /* fallback to no-cache */ }
}

async function safeDel() {
    if (!available()) return;
    var keys = Array.prototype.slice.call(arguments);
    if (keys.length === 0) return;
    try { await client.del.apply(client, keys); }
    catch (e) { /* ignore */ }
}

async function safeHget(key, field) {
    if (!available()) return null;
    try { return await client.hget(key, field); }
    catch (e) { return null; }
}

async function safeHgetall(key) {
    if (!available()) return null;
    try {
        var result = await client.hgetall(key);
        if (result && Object.keys(result).length === 0) return null;
        return result;
    } catch (e) { return null; }
}

async function safeHset(key, field, value) {
    if (!available()) return;
    try { await client.hset(key, field, value); }
    catch (e) { /* ignore */ }
}

async function safeHincrby(key, field, increment) {
    if (!available()) return;
    try { await client.hincrby(key, field, increment); }
    catch (e) { /* ignore */ }
}

async function safeHdel(key, field) {
    if (!available()) return;
    try { await client.hdel(key, field); }
    catch (e) { /* ignore */ }
}

async function safeExpire(key, seconds) {
    if (!available()) return;
    try { await client.expire(key, seconds); }
    catch (e) { /* ignore */ }
}

// SET operations for online users
async function safeSadd(key, member) {
    if (!available()) return;
    try { await client.sadd(key, member); }
    catch (e) { /* ignore */ }
}

async function safeSrem(key, member) {
    if (!available()) return;
    try { await client.srem(key, member); }
    catch (e) { /* ignore */ }
}

async function safeSismember(key, member) {
    if (!available()) return false;
    try { return await client.sismember(key, member) === 1; }
    catch (e) { return false; }
}

module.exports = {
    getRedis: getRedis,
    available: available,
    safeGet: safeGet,
    safeSet: safeSet,
    safeDel: safeDel,
    safeHget: safeHget,
    safeHgetall: safeHgetall,
    safeHset: safeHset,
    safeHincrby: safeHincrby,
    safeHdel: safeHdel,
    safeExpire: safeExpire,
    safeSadd: safeSadd,
    safeSrem: safeSrem,
    safeSismember: safeSismember
};
