const Database = require('better-sqlite3');
const path = require('path');

var DB_PATH = path.join(__dirname, 'chat.db');

var db;
var stmtCache = {};

function getDb() {
    if (!db) {
        db = new Database(DB_PATH);

        db.pragma('journal_mode = WAL');
        db.pragma('foreign_keys = ON');
        db.pragma('synchronous = NORMAL');
        db.pragma('cache_size = -32000');
        db.pragma('busy_timeout = 5000');
        db.pragma('temp_store = MEMORY');
        db.pragma('mmap_size = 268435456');
        db.pragma('page_size = 4096');

        initSchema();
        initStatements();
    }
    return db;
}

function stmt(name) {
    return stmtCache[name];
}

function initStatements() {
    // One-time fix for existing DBs: if messages table FK was left pointing at a missing table name
    // (e.g. from an old migration), recreate messages so FK points to "conversations".
    try {
        var msgRow = db.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name='messages'").get();
        var sql = (msgRow && msgRow.sql) ? String(msgRow.sql) : '';
        var badFk = sql.toLowerCase().indexOf('conversations_old') !== -1;
        if (badFk) {
            console.log('[DB MIGRATION] Fixing messages table FK -> conversations');
            db.exec('PRAGMA foreign_keys=OFF');
            var msgCols = [];
            try {
                msgCols = db.prepare('PRAGMA table_info(messages)').all().map(function(c) { return c.name; });
            } catch (e) {
                console.error('[DB MIGRATION] table_info(messages) failed:', e.message);
            }
            var hasMentioned = msgCols.indexOf('mentioned_users') !== -1;
            var hasPinned = msgCols.indexOf('is_pinned') !== -1;
            db.exec('BEGIN TRANSACTION');
            try {
                db.exec([
                    'CREATE TABLE messages_new (',
                    '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
                    '    conversation_id INTEGER NOT NULL,',
                    '    sender_id INTEGER NOT NULL,',
                    '    text TEXT NOT NULL DEFAULT \'\',',
                    "    message_type TEXT DEFAULT 'text',",
                    '    file_url TEXT DEFAULT NULL,',
                    '    file_name TEXT DEFAULT NULL,',
                    '    file_size INTEGER DEFAULT 0,',
                    '    duration INTEGER DEFAULT 0,',
                    "    timestamp TEXT DEFAULT (datetime('now')),",
                    '    is_read INTEGER DEFAULT 0,',
                    '    mentioned_users TEXT DEFAULT NULL,',
                    '    is_pinned INTEGER DEFAULT 0,',
                    '    FOREIGN KEY (conversation_id) REFERENCES conversations(id),',
                    '    FOREIGN KEY (sender_id) REFERENCES users(id)',
                    ')'
                ].join(''));
                var selMentioned = hasMentioned ? 'mentioned_users' : 'NULL';
                var selPinned = hasPinned ? 'is_pinned' : '0';
                db.exec('INSERT INTO messages_new SELECT id, conversation_id, sender_id, text, message_type, file_url, file_name, file_size, duration, timestamp, is_read, ' + selMentioned + ', ' + selPinned + ' FROM messages');
                db.exec('DROP TABLE messages');
                db.exec('ALTER TABLE messages_new RENAME TO messages');
                db.exec('CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id)');
                db.exec('CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender_id)');
                db.exec('CREATE INDEX IF NOT EXISTS idx_messages_conv_read ON messages(conversation_id, is_read)');
                db.exec('CREATE INDEX IF NOT EXISTS idx_messages_conv_id_desc ON messages(conversation_id, id DESC)');
                db.exec('CREATE INDEX IF NOT EXISTS idx_messages_timestamp ON messages(timestamp)');
                db.exec('CREATE INDEX IF NOT EXISTS idx_messages_conv_timestamp ON messages(conversation_id, timestamp)');
                try { db.exec('CREATE INDEX IF NOT EXISTS idx_messages_mentioned ON messages(mentioned_users)'); } catch (e) { }
                db.exec('COMMIT');
                console.log('[DB MIGRATION] messages table recreated with correct FK');
            } catch (fixErr) {
                db.exec('ROLLBACK');
                throw fixErr;
            }
            db.exec('PRAGMA foreign_keys=ON');
        }
    } catch (e) {
        console.error('[DB MIGRATION] Fix FK failed:', e.message);
    }

    // Debug: log existing tables to help diagnose issues
    try {
        var tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").all();
        console.log('[DB] Tables in database:', tables.map(t => t.name).join(', '));
    } catch (e) {
        console.error('[DB] Failed to list tables:', e.message);
    }

    stmtCache.insertMessage = db.prepare(
        'INSERT INTO messages (conversation_id, sender_id, text, message_type, file_url, file_name, file_size, duration, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)'
    );
    stmtCache.getMessageWithSender = db.prepare(
        'SELECT m.*, u.display_name as sender_name, u.initials as sender_initials FROM messages m JOIN users u ON u.id = m.sender_id WHERE m.id = ?'
    );
    stmtCache.updateConvLastMessage = db.prepare(
        "UPDATE conversations SET last_message = ?, last_message_time = ?, deleted_by = '' WHERE id = ?"
    );
    stmtCache.getConversation = db.prepare(
        'SELECT user1_id, user2_id FROM conversations WHERE id = ?'
    );
    stmtCache.markRead = db.prepare(
        'UPDATE messages SET is_read = 1 WHERE conversation_id = ? AND sender_id != ? AND is_read = 0'
    );
    stmtCache.checkBlock = db.prepare(
        'SELECT id FROM blocked_users WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)'
    );
    stmtCache.setUserOnline = db.prepare(
        'UPDATE users SET is_online = 1, last_seen = ? WHERE id = ?'
    );
    stmtCache.setUserOffline = db.prepare(
        'UPDATE users SET is_online = 0, last_seen = ? WHERE id = ?'
    );
    stmtCache.getUserLastSeen = db.prepare(
        'SELECT last_seen FROM users WHERE id = ?'
    );
    stmtCache.getMessageById = db.prepare(
        'SELECT * FROM messages WHERE id = ?'
    );
    stmtCache.deleteMessage = db.prepare(
        'DELETE FROM messages WHERE id = ?'
    );
    stmtCache.getLastMessage = db.prepare(
        'SELECT text, message_type, file_name, timestamp FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 1'
    );
    stmtCache.getMessagesPaginated = db.prepare(
        'SELECT m.*, u.display_name as sender_name, u.initials as sender_initials FROM messages m JOIN users u ON u.id = m.sender_id WHERE m.conversation_id = ? ORDER BY m.id DESC LIMIT ?'
    );
    stmtCache.getMessagesBeforeId = db.prepare(
        'SELECT m.*, u.display_name as sender_name, u.initials as sender_initials FROM messages m JOIN users u ON u.id = m.sender_id WHERE m.conversation_id = ? AND m.id < ? ORDER BY m.id DESC LIMIT ?'
    );
    stmtCache.getUserByPin = db.prepare(
        'SELECT * FROM users WHERE pin = ?'
    );
    stmtCache.getUserById = db.prepare(
        'SELECT id, display_name, initials, avatar_color, avatar_url, is_online, last_seen, username, rank, job_title FROM users WHERE id = ?'
    );
    stmtCache.getUserByUsername = db.prepare(
        'SELECT id, display_name, initials, avatar_color, avatar_url, is_online, last_seen, username, rank, job_title FROM users WHERE username = ?'
    );
    stmtCache.insertBlock = db.prepare(
        'INSERT OR IGNORE INTO blocked_users (blocker_id, blocked_id) VALUES (?, ?)'
    );
    stmtCache.deleteBlock = db.prepare(
        'DELETE FROM blocked_users WHERE blocker_id = ? AND blocked_id = ?'
    );
    stmtCache.insertMute = db.prepare(
        'INSERT OR IGNORE INTO muted_conversations (user_id, conversation_id) VALUES (?, ?)'
    );
    stmtCache.deleteMute = db.prepare(
        'DELETE FROM muted_conversations WHERE user_id = ? AND conversation_id = ?'
    );
    stmtCache.getConvForUser = db.prepare(
        'SELECT c.id, c.last_message, c.last_message_time, c.deleted_by, ' +
        'CASE WHEN c.user1_id = ? THEN c.user2_id ELSE c.user1_id END as other_user_id, ' +
        'u.display_name as other_name, u.initials as other_initials, ' +
        'u.avatar_color as other_avatar_color, u.avatar_url as other_avatar_url, u.is_online as other_is_online, ' +
        'u.username as other_username, u.rank as other_rank, u.job_title as other_job_title, ' +
        '(SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id AND m.sender_id != ? AND m.is_read = 0) as unread_count, ' +
        '(SELECT sender_id FROM messages WHERE conversation_id = c.id ORDER BY id DESC LIMIT 1) as last_message_sender_id, ' +
        '(SELECT is_read FROM messages WHERE conversation_id = c.id ORDER BY id DESC LIMIT 1) as last_message_is_read, ' +
        'CASE WHEN EXISTS(SELECT 1 FROM muted_conversations mc WHERE mc.user_id = ? AND mc.conversation_id = c.id) THEN 1 ELSE 0 END as is_muted, ' +
        'CASE WHEN EXISTS(SELECT 1 FROM blocked_users bu WHERE bu.blocker_id = ? AND bu.blocked_id = CASE WHEN c.user1_id = ? THEN c.user2_id ELSE c.user1_id END) THEN 1 ELSE 0 END as is_blocked, ' +
        'CASE WHEN EXISTS(SELECT 1 FROM favorited_conversations fc WHERE fc.user_id = ? AND fc.conversation_id = c.id) THEN 1 ELSE 0 END as is_favorited, ' +
        'CASE WHEN EXISTS(SELECT 1 FROM archived_conversations ac WHERE ac.user_id = ? AND ac.conversation_id = c.id) THEN 1 ELSE 0 END as is_archived ' +
        'FROM conversations c ' +
        'JOIN users u ON u.id = CASE WHEN c.user1_id = ? THEN c.user2_id ELSE c.user1_id END ' +
        'WHERE (c.user1_id = ? OR c.user2_id = ?) ' +
        'ORDER BY c.last_message_time DESC'
    );
    stmtCache.findConversation = db.prepare(
        'SELECT id FROM conversations WHERE user1_id = ? AND user2_id = ?'
    );
    stmtCache.createConversation = db.prepare(
        'INSERT INTO conversations (user1_id, user2_id) VALUES (?, ?)'
    );

    // auth.js
    stmtCache.insertUser = db.prepare(
        'INSERT INTO users (device_id, pin, display_name, initials, avatar_color, rank, job_title) VALUES (?, ?, ?, ?, ?, ?, ?)'
    );
    stmtCache.updateUserDeviceOnline = db.prepare(
        "UPDATE users SET device_id = ?, is_online = 1, last_seen = datetime('now') WHERE id = ?"
    );
    stmtCache.setUserOnlineNow = db.prepare(
        "UPDATE users SET is_online = 1, last_seen = datetime('now') WHERE id = ?"
    );
    stmtCache.getDeviceByUserAndDevice = db.prepare(
        'SELECT id FROM devices WHERE user_id = ? AND device_id = ?'
    );
    stmtCache.insertDevice = db.prepare(
        'INSERT INTO devices (user_id, device_id, device_name) VALUES (?, ?, ?)'
    );
    stmtCache.updateDeviceActivity = db.prepare(
        "UPDATE devices SET last_active = datetime('now'), device_name = ? WHERE user_id = ? AND device_id = ?"
    );
    stmtCache.countDevicesByUser = db.prepare(
        'SELECT COUNT(*) as cnt FROM devices WHERE user_id = ?'
    );
    stmtCache.checkUsernameConflict = db.prepare(
        'SELECT id FROM users WHERE username = ? AND id != ?'
    );
    stmtCache.getUserProfileById = db.prepare(
        'SELECT id, display_name, initials, avatar_color, avatar_url, username, rank, job_title FROM users WHERE id = ?'
    );
    stmtCache.getDevicesByUser = db.prepare(
        'SELECT id, device_id, device_name, last_active, created_at FROM devices WHERE user_id = ? ORDER BY last_active DESC'
    );
    stmtCache.getDeviceByIdAndUser = db.prepare(
        'SELECT * FROM devices WHERE id = ? AND user_id = ?'
    );
    stmtCache.deleteDeviceById = db.prepare(
        'DELETE FROM devices WHERE id = ?'
    );
    stmtCache.getUserPinById = db.prepare(
        'SELECT id, pin FROM users WHERE id = ?'
    );
    stmtCache.checkPinConflict = db.prepare(
        'SELECT id FROM users WHERE pin = ? AND id != ?'
    );
    stmtCache.updateUserPin = db.prepare(
        'UPDATE users SET pin = ? WHERE id = ?'
    );
    stmtCache.getAllUsers = db.prepare(
        'SELECT id, device_id, pin, display_name, initials, avatar_color, is_online, last_seen, created_at, username, rank, job_title FROM users'
    );

    // contacts.js
    stmtCache.getContactsByOwner = db.prepare(
        'SELECT c.id as contact_id, u.id as user_id, u.device_id, u.display_name, ' +
        'u.initials, u.avatar_color, u.avatar_url, u.is_online, u.last_seen, c.added_at, ' +
        'u.username, u.rank, u.job_title ' +
        'FROM contacts c JOIN users u ON u.id = c.contact_user_id ' +
        'WHERE c.owner_id = ? ORDER BY u.display_name ASC'
    );
    stmtCache.getUserNameById = db.prepare(
        'SELECT id, display_name FROM users WHERE id = ?'
    );
    stmtCache.getUserNameByUsername = db.prepare(
        'SELECT id, display_name FROM users WHERE username = ?'
    );
    stmtCache.getUserNameByDevice = db.prepare(
        'SELECT id, display_name FROM users WHERE device_id = ?'
    );
    stmtCache.checkContactExists = db.prepare(
        'SELECT id FROM contacts WHERE owner_id = ? AND contact_user_id = ?'
    );
    stmtCache.insertContact = db.prepare(
        'INSERT OR IGNORE INTO contacts (owner_id, contact_user_id) VALUES (?, ?)'
    );
    stmtCache.getContactById = db.prepare(
        'SELECT id, owner_id, contact_user_id FROM contacts WHERE id = ?'
    );
    stmtCache.deleteContactById = db.prepare(
        'DELETE FROM contacts WHERE id = ?'
    );

    // messages.js
    stmtCache.getSharedMedia = db.prepare(
        "SELECT id, sender_id, message_type, file_url, file_name, file_size, duration, timestamp " +
        "FROM messages WHERE conversation_id = ? AND message_type IN ('image', 'audio', 'document') " +
        "ORDER BY id DESC LIMIT ?"
    );
    stmtCache.getMediaCounts = db.prepare(
        "SELECT message_type, COUNT(*) as count FROM messages " +
        "WHERE conversation_id = ? AND message_type IN ('image', 'audio', 'document') " +
        "GROUP BY message_type"
    );
    stmtCache.deleteOldMessages = db.prepare(
        "DELETE FROM messages WHERE conversation_id = ? AND timestamp < datetime('now', '-' || ? || ' hours')"
    );
    stmtCache.deleteOldMessagesBySeconds = db.prepare(
        "DELETE FROM messages WHERE conversation_id = ? AND timestamp < datetime('now', '-' || ? || ' seconds')"
    );
    stmtCache.getConvDeletedBy = db.prepare(
        'SELECT id, deleted_by FROM conversations WHERE id = ?'
    );
    stmtCache.updateConvDeletedBy = db.prepare(
        'UPDATE conversations SET deleted_by = ? WHERE id = ?'
    );
    stmtCache.clearConvMessages = db.prepare(
        'DELETE FROM messages WHERE conversation_id = ?'
    );
    stmtCache.clearConvLastMessage = db.prepare(
        "UPDATE conversations SET last_message = '', last_message_time = NULL WHERE id = ?"
    );

    // favorite.js
    stmtCache.insertFavorite = db.prepare(
        'INSERT OR IGNORE INTO favorited_conversations (user_id, conversation_id) VALUES (?, ?)'
    );
    stmtCache.deleteFavorite = db.prepare(
        'DELETE FROM favorited_conversations WHERE user_id = ? AND conversation_id = ?'
    );
    stmtCache.checkFavorite = db.prepare(
        'SELECT 1 FROM favorited_conversations WHERE user_id = ? AND conversation_id = ?'
    );

    // archive.js
    stmtCache.insertArchive = db.prepare(
        'INSERT OR IGNORE INTO archived_conversations (user_id, conversation_id) VALUES (?, ?)'
    );
    stmtCache.deleteArchive = db.prepare(
        'DELETE FROM archived_conversations WHERE user_id = ? AND conversation_id = ?'
    );
    stmtCache.checkArchive = db.prepare(
        'SELECT 1 FROM archived_conversations WHERE user_id = ? AND conversation_id = ?'
    );

    // mute.js
    stmtCache.checkMuted = db.prepare(
        'SELECT 1 FROM muted_conversations WHERE user_id = ? AND conversation_id = ?'
    );

    // block.js
    stmtCache.checkBlockOneWay = db.prepare(
        'SELECT id FROM blocked_users WHERE blocker_id = ? AND blocked_id = ?'
    );
    stmtCache.getBlockedIds = db.prepare(
        'SELECT blocked_id FROM blocked_users WHERE blocker_id = ?'
    );

    // calls.js
    stmtCache.getCallLogs = db.prepare(
        'SELECT cl.id, cl.caller_id, cl.receiver_id, cl.status, cl.duration, cl.timestamp, ' +
        'CASE WHEN cl.caller_id = ? THEN cl.receiver_id ELSE cl.caller_id END as other_user_id, ' +
        'CASE WHEN cl.caller_id = ? THEN 1 ELSE 0 END as is_outgoing, ' +
        'u.display_name as other_name, u.initials as other_initials, u.avatar_color as other_avatar_color ' +
        'FROM call_logs cl ' +
        'JOIN users u ON u.id = CASE WHEN cl.caller_id = ? THEN cl.receiver_id ELSE cl.caller_id END ' +
        'WHERE cl.caller_id = ? OR cl.receiver_id = ? ' +
        'ORDER BY cl.timestamp DESC LIMIT 50'
    );
    stmtCache.deleteCallLogs = db.prepare(
        'DELETE FROM call_logs WHERE caller_id = ? OR receiver_id = ?'
    );

    // callService.js
    stmtCache.insertCallLog = db.prepare(
        'INSERT INTO call_logs (caller_id, receiver_id, status, duration) VALUES (?, ?, ?, ?)'
    );
    stmtCache.getCallerInfo = db.prepare(
        'SELECT display_name, initials, avatar_color FROM users WHERE id = ?'
    );

    // messageService.js (deleteMessageForAll)
    stmtCache.updateConvLastMessageOnly = db.prepare(
        'UPDATE conversations SET last_message = ?, last_message_time = ? WHERE id = ?'
    );
    stmtCache.clearConvLastMessageNow = db.prepare(
        "UPDATE conversations SET last_message = '', last_message_time = datetime('now') WHERE id = ?"
    );

    // chatHandler.js (broadcastPresence)
    stmtCache.getContactUserIds = db.prepare(
        'SELECT CASE WHEN owner_id = ? THEN contact_user_id ELSE owner_id END as uid ' +
        'FROM contacts WHERE owner_id = ? OR contact_user_id = ?'
    );

    console.log('[DB] Schema initialized (WAL mode, indexes optimized)');
}

function initSchema() {
    // Migrate: remove UNIQUE constraint from device_id if old table exists
    try {
        var tableInfo = db.prepare("PRAGMA table_info(users)").all();
        if (tableInfo.length > 0) {
            var indexes = db.prepare(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='users' AND sql LIKE '%device_id%'"
            ).all();
            for (var i = 0; i < indexes.length; i++) {
                try { db.exec('DROP INDEX IF EXISTS "' + indexes[i].name + '"'); } catch (e) { /* ignore */ }
            }
        }
    } catch (e) { /* table does not exist yet */ }

    db.exec([
        'CREATE TABLE IF NOT EXISTS users (',
        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
        '    device_id TEXT NOT NULL DEFAULT \'\',',
        '    pin TEXT NOT NULL,',
        '    display_name TEXT DEFAULT \'\',',
        '    initials TEXT DEFAULT \'\',',
        '    avatar_color TEXT DEFAULT \'#3B82F6\',',
        '    avatar_url TEXT DEFAULT \'\',',
        '    is_online INTEGER DEFAULT 0,',
        '    last_seen TEXT,',
        "    created_at TEXT DEFAULT (datetime('now'))",
        ');',
        '',
        'CREATE TABLE IF NOT EXISTS conversations (',
        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
        '    user1_id INTEGER,',
        '    user2_id INTEGER,',
        '    last_message TEXT DEFAULT \'\',',
        "    last_message_time TEXT DEFAULT (datetime('now')),",
        "    created_at TEXT DEFAULT (datetime('now')),",
        '    FOREIGN KEY (user1_id) REFERENCES users(id),',
        '    FOREIGN KEY (user2_id) REFERENCES users(id)',
        ');',
        '',
        'CREATE TABLE IF NOT EXISTS messages (',
        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
        '    conversation_id INTEGER NOT NULL,',
        '    sender_id INTEGER NOT NULL,',
        '    text TEXT NOT NULL DEFAULT \'\',',
        "    message_type TEXT DEFAULT 'text',",
        '    file_url TEXT DEFAULT NULL,',
        '    file_name TEXT DEFAULT NULL,',
        '    file_size INTEGER DEFAULT 0,',
        '    duration INTEGER DEFAULT 0,',
        "    timestamp TEXT DEFAULT (datetime('now')),",
        '    is_read INTEGER DEFAULT 0,',
        '    mentioned_users TEXT DEFAULT NULL,',
        '    is_pinned INTEGER DEFAULT 0,',
        '    FOREIGN KEY (conversation_id) REFERENCES conversations(id),',
        '    FOREIGN KEY (sender_id) REFERENCES users(id)',
        ');',
        '',
        'CREATE TABLE IF NOT EXISTS contacts (',
        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
        '    owner_id INTEGER NOT NULL,',
        '    contact_user_id INTEGER NOT NULL,',
        "    added_at TEXT DEFAULT (datetime('now')),",
        '    FOREIGN KEY (owner_id) REFERENCES users(id),',
        '    FOREIGN KEY (contact_user_id) REFERENCES users(id),',
        '    UNIQUE(owner_id, contact_user_id)',
        ');',
        '',
        'CREATE TABLE IF NOT EXISTS call_logs (',
        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
        '    caller_id INTEGER NOT NULL,',
        '    receiver_id INTEGER NOT NULL,',
        "    status TEXT DEFAULT 'missed',",
        '    duration INTEGER DEFAULT 0,',
        "    timestamp TEXT DEFAULT (datetime('now')),",
        '    FOREIGN KEY (caller_id) REFERENCES users(id),',
        '    FOREIGN KEY (receiver_id) REFERENCES users(id)',
        ');',
        '',
        '-- Indexes for messages',
        'CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id);',
        'CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender_id);',
        'CREATE INDEX IF NOT EXISTS idx_messages_conv_read ON messages(conversation_id, is_read);',
        'CREATE INDEX IF NOT EXISTS idx_messages_conv_id_desc ON messages(conversation_id, id DESC);',
        'CREATE INDEX IF NOT EXISTS idx_messages_timestamp ON messages(timestamp);',
        'CREATE INDEX IF NOT EXISTS idx_messages_conv_timestamp ON messages(conversation_id, timestamp);',
        '',
        '-- Indexes for contacts',
        'CREATE INDEX IF NOT EXISTS idx_contacts_owner ON contacts(owner_id);',
        'CREATE INDEX IF NOT EXISTS idx_contacts_contact ON contacts(contact_user_id);',
        '',
        '-- Indexes for conversations',
        'CREATE INDEX IF NOT EXISTS idx_conversations_user1 ON conversations(user1_id);',
        'CREATE INDEX IF NOT EXISTS idx_conversations_user2 ON conversations(user2_id);',
        'CREATE INDEX IF NOT EXISTS idx_conversations_last_time ON conversations(last_message_time DESC);',
        'CREATE INDEX IF NOT EXISTS idx_conversations_user1_time ON conversations(user1_id, last_message_time DESC);',
        'CREATE INDEX IF NOT EXISTS idx_conversations_user2_time ON conversations(user2_id, last_message_time DESC);',
        'CREATE INDEX IF NOT EXISTS idx_messages_conv_sender_read ON messages(conversation_id, sender_id, is_read);',
        '',
        '-- Indexes for users',
        'CREATE INDEX IF NOT EXISTS idx_users_pin ON users(pin);',
        'CREATE INDEX IF NOT EXISTS idx_users_device ON users(device_id);',
        '',
        '-- Indexes for call_logs',
        'CREATE INDEX IF NOT EXISTS idx_call_logs_caller ON call_logs(caller_id);',
        'CREATE INDEX IF NOT EXISTS idx_call_logs_receiver ON call_logs(receiver_id);',
        'CREATE INDEX IF NOT EXISTS idx_call_logs_timestamp ON call_logs(timestamp DESC);',
        '',
        'CREATE TABLE IF NOT EXISTS devices (',
        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
        '    user_id INTEGER NOT NULL,',
        '    device_id TEXT NOT NULL,',
        '    device_name TEXT DEFAULT \'\',',
        "    last_active TEXT DEFAULT (datetime('now')),",
        "    created_at TEXT DEFAULT (datetime('now')),",
        '    FOREIGN KEY (user_id) REFERENCES users(id),',
        '    UNIQUE(user_id, device_id)',
        ');',
        '',
        'CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);',
        'CREATE INDEX IF NOT EXISTS idx_devices_device ON devices(device_id);',
        '',
        'CREATE TABLE IF NOT EXISTS blocked_users (',
        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
        '    blocker_id INTEGER NOT NULL,',
        '    blocked_id INTEGER NOT NULL,',
        "    created_at TEXT DEFAULT (datetime('now')),",
        '    FOREIGN KEY (blocker_id) REFERENCES users(id),',
        '    FOREIGN KEY (blocked_id) REFERENCES users(id),',
        '    UNIQUE(blocker_id, blocked_id)',
        ');',
        '',
        'CREATE TABLE IF NOT EXISTS muted_conversations (',
        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
        '    user_id INTEGER NOT NULL,',
        '    conversation_id INTEGER NOT NULL,',
        "    created_at TEXT DEFAULT (datetime('now')),",
        '    FOREIGN KEY (user_id) REFERENCES users(id),',
        '    FOREIGN KEY (conversation_id) REFERENCES conversations(id),',
        '    UNIQUE(user_id, conversation_id)',
        ');',
        '',
        'CREATE INDEX IF NOT EXISTS idx_blocked_blocker ON blocked_users(blocker_id);',
        'CREATE INDEX IF NOT EXISTS idx_blocked_blocked ON blocked_users(blocked_id);',
        'CREATE INDEX IF NOT EXISTS idx_muted_user ON muted_conversations(user_id);',
        'CREATE INDEX IF NOT EXISTS idx_muted_composite ON muted_conversations(user_id, conversation_id);',
        '',
        'CREATE TABLE IF NOT EXISTS favorited_conversations (',
        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
        '    user_id INTEGER NOT NULL,',
        '    conversation_id INTEGER NOT NULL,',
        "    created_at TEXT DEFAULT (datetime('now')),",
        '    FOREIGN KEY (user_id) REFERENCES users(id),',
        '    FOREIGN KEY (conversation_id) REFERENCES conversations(id),',
        '    UNIQUE(user_id, conversation_id)',
        ');',
        '',
        'CREATE TABLE IF NOT EXISTS archived_conversations (',
        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
        '    user_id INTEGER NOT NULL,',
        '    conversation_id INTEGER NOT NULL,',
        "    created_at TEXT DEFAULT (datetime('now')),",
        '    FOREIGN KEY (user_id) REFERENCES users(id),',
        '    FOREIGN KEY (conversation_id) REFERENCES conversations(id),',
        '    UNIQUE(user_id, conversation_id)',
        ');',
        '',
        'CREATE INDEX IF NOT EXISTS idx_favorited_composite ON favorited_conversations(user_id, conversation_id);',
        'CREATE INDEX IF NOT EXISTS idx_archived_composite ON archived_conversations(user_id, conversation_id);'
    ].join('\n'));

    // Migration: add media columns to messages if they do not exist
    var mediaCols = [
        { name: 'message_type', sql: "ALTER TABLE messages ADD COLUMN message_type TEXT DEFAULT 'text'" },
        { name: 'file_url', sql: 'ALTER TABLE messages ADD COLUMN file_url TEXT DEFAULT NULL' },
        { name: 'file_name', sql: 'ALTER TABLE messages ADD COLUMN file_name TEXT DEFAULT NULL' },
        { name: 'file_size', sql: 'ALTER TABLE messages ADD COLUMN file_size INTEGER DEFAULT 0' },
        { name: 'duration', sql: 'ALTER TABLE messages ADD COLUMN duration INTEGER DEFAULT 0' }
    ];

    // Migration: add avatar_url, username, rank, job_title to users if missing
    try {
        var userCols = db.prepare('PRAGMA table_info(users)').all().map(function(c) { return c.name; });
        if (userCols.indexOf('avatar_url') === -1) {
            try { db.exec("ALTER TABLE users ADD COLUMN avatar_url TEXT DEFAULT ''"); } catch (e) { /* already exists */ }
        }
        if (userCols.indexOf('username') === -1) {
            try { db.exec("ALTER TABLE users ADD COLUMN username TEXT DEFAULT ''"); } catch (e) { /* already exists */ }
        }
        if (userCols.indexOf('rank') === -1) {
            try { db.exec("ALTER TABLE users ADD COLUMN rank TEXT DEFAULT ''"); } catch (e) { /* already exists */ }
        }
        if (userCols.indexOf('job_title') === -1) {
            try { db.exec("ALTER TABLE users ADD COLUMN job_title TEXT DEFAULT ''"); } catch (e) { /* already exists */ }
        }
    } catch (e) { /* ignore */ }

    // Unique index on username (only non-empty values)
    try { db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username ON users(username) WHERE username != \'\''); } catch (e) { /* already exists */ }

    try {
        var cols = db.prepare('PRAGMA table_info(messages)').all().map(function(c) { return c.name; });
        for (var i = 0; i < mediaCols.length; i++) {
            if (cols.indexOf(mediaCols[i].name) === -1) {
                try { db.exec(mediaCols[i].sql); } catch (e) { /* already exists */ }
            }
        }
    } catch (e) { /* table does not exist yet */ }

    // Migration: add deleted_by column to conversations if missing
    try {
        var convCols = db.prepare('PRAGMA table_info(conversations)').all().map(function(c) { return c.name; });
        if (convCols.indexOf('deleted_by') === -1) {
            try { db.exec("ALTER TABLE conversations ADD COLUMN deleted_by TEXT DEFAULT ''"); } catch (e) { /* already exists */ }
        }
    } catch (e) { /* ignore */ }

    // Recovery: if a previous run left conversations_tmp (temp table from UNIQUE migration), restore to conversations
    try {
        var tmpExists = db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='conversations_tmp'").get();
        var convExists = db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='conversations'").get();
        if (tmpExists && !convExists) {
            db.exec('PRAGMA foreign_keys=OFF');
            db.exec('ALTER TABLE conversations_tmp RENAME TO conversations');
            db.exec('PRAGMA foreign_keys=ON');
        } else if (tmpExists && convExists) {
            db.exec('PRAGMA foreign_keys=OFF');
            db.exec('DROP TABLE conversations');
            db.exec('ALTER TABLE conversations_tmp RENAME TO conversations');
            db.exec('PRAGMA foreign_keys=ON');
        }
    } catch (e) { /* ignore */ }

    // Migration: remove UNIQUE(user1_id, user2_id) from conversations by recreating table (SQLite cannot drop inline UNIQUE)
    try {
        var tableInfo = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='conversations'").get();
        if (tableInfo) {
            var hasUniqueAutoIndex = db.prepare("SELECT 1 FROM sqlite_master WHERE type='index' AND tbl_name='conversations' AND name LIKE 'sqlite_autoindex_conversations%' LIMIT 1").get();
            if (hasUniqueAutoIndex) {
                console.log('[DB MIGRATION] Found UNIQUE constraint on conversations, recreating table...');
                db.exec('PRAGMA foreign_keys=OFF');
                db.exec('BEGIN TRANSACTION');
                var migrationOk = false;
                try {
                    db.exec('ALTER TABLE conversations RENAME TO conversations_tmp');
                    db.exec([
                        'CREATE TABLE conversations (',
                        '    id INTEGER PRIMARY KEY AUTOINCREMENT,',
                        '    user1_id INTEGER,',
                        '    user2_id INTEGER,',
                        '    last_message TEXT DEFAULT \'\',',
                        "    last_message_time TEXT DEFAULT (datetime('now')),",
                        "    created_at TEXT DEFAULT (datetime('now')),",
                        '    deleted_by TEXT DEFAULT \'\',',
                        '    FOREIGN KEY (user1_id) REFERENCES users(id),',
                        '    FOREIGN KEY (user2_id) REFERENCES users(id)',
                        ')'
                    ].join(''));
                    var tmpCols = db.prepare('PRAGMA table_info(conversations_tmp)').all().map(function(c) { return c.name; });
                    var where1to1 = tmpCols.indexOf('is_group') !== -1 ? ' WHERE COALESCE(is_group,0) = 0' : '';
                    db.exec('INSERT INTO conversations (id, user1_id, user2_id, last_message, last_message_time, created_at, deleted_by) SELECT id, user1_id, user2_id, COALESCE(last_message,\'\'), last_message_time, COALESCE(created_at, datetime(\'now\')), COALESCE(deleted_by,\'\') FROM conversations_tmp' + where1to1);
                    db.exec('DROP TABLE conversations_tmp');
                    db.exec('CREATE INDEX IF NOT EXISTS idx_conversations_user1 ON conversations(user1_id)');
                    db.exec('CREATE INDEX IF NOT EXISTS idx_conversations_user2 ON conversations(user2_id)');
                    db.exec('CREATE INDEX IF NOT EXISTS idx_conversations_last_time ON conversations(last_message_time DESC)');
                    db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_conversations_1to1 ON conversations(user1_id, user2_id)');
                    console.log('[DB MIGRATION] Migration complete, committing...');
                    migrationOk = true;
                } catch (migErr) {
                    console.error('[DB MIGRATION] Migration failed:', migErr.message);
                    throw migErr;
                } finally {
                    if (migrationOk) {
                        db.exec('COMMIT');
                        console.log('[DB MIGRATION] Transaction committed');
                    } else {
                        try { db.exec('ROLLBACK'); console.log('[DB MIGRATION] Transaction rolled back'); } catch (e2) { }
                    }
                    db.exec('PRAGMA foreign_keys=ON');
                }
            }
        }
    } catch (e) {
        console.error('[DB MIGRATION] Outer catch:', e.message);
        try { db.exec('ROLLBACK'); } catch (e2) { }
        try { db.exec('PRAGMA foreign_keys=ON'); } catch (e2) { }
    }

    // Add message columns before FK fix so INSERT during fix has all columns
    try {
        var msgColsPre = db.prepare('PRAGMA table_info(messages)').all().map(function(c) { return c.name; });
        if (msgColsPre.indexOf('mentioned_users') === -1) {
            try { db.exec("ALTER TABLE messages ADD COLUMN mentioned_users TEXT DEFAULT NULL"); } catch (e) { /* already exists */ }
        }
        if (msgColsPre.indexOf('is_pinned') === -1) {
            try { db.exec("ALTER TABLE messages ADD COLUMN is_pinned INTEGER DEFAULT 0"); } catch (e) { /* already exists */ }
        }
    } catch (e) { /* ignore */ }

    // One-time FK fix for messages is done at start of initStatements() when needed.

    try { db.exec('CREATE INDEX IF NOT EXISTS idx_messages_mentioned ON messages(mentioned_users)'); } catch (e) { /* already exists */ }

    console.log('[DB] Schema initialized (WAL mode, indexes optimized)');
}

module.exports = { getDb: getDb, stmt: stmt };
