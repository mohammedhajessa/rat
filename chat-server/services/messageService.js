const { getDb, stmt } = require('../db/database');
const xssFilters = require('xss-filters');

function nowISO() {
    return new Date().toISOString().replace('T', ' ').substring(0, 19);
}

function sanitizeText(text) {
    if (!text) return '';
    return xssFilters.inHTMLData(text);
}

function buildPreview(messageType, text, fileName) {
    if (messageType === 'image') return '\uD83D\uDCF7 \u0635\u0648\u0631\u0629';
    if (messageType === 'voice') return '\uD83C\uDFA4 \u0631\u0633\u0627\u0644\u0629 \u0635\u0648\u062A\u064A\u0629';
    if (messageType === 'document') return '\uD83D\uDCC4 ' + (fileName || '\u0645\u0633\u062A\u0646\u062F');
    return text || '';
}

function insertMessage(data) {
    var db = getDb();
    var now = nowISO();

    var text = sanitizeText(data.text || '');
    var messageType = data.messageType || 'text';
    var fileUrl = data.fileUrl || null;
    var fileName = data.fileName || null;
    var fileSize = parseInt(data.fileSize) || 0;
    var duration = parseInt(data.duration) || 0;

    // Validate media messages have required fields
    if (messageType !== 'text') {
        if (!fileUrl || !fileName || fileSize <= 0) {
            throw new Error('Media message requires fileUrl, fileName, and fileSize > 0');
        }
    }

    var preview = buildPreview(messageType, text, fileName);

    var insertMsg = db.transaction(function() {
        var result = stmt('insertMessage').run(
            data.conversationId, data.senderId, text, messageType, fileUrl, fileName, fileSize, duration, now
        );
        var message = stmt('getMessageWithSender').get(result.lastInsertRowid);
        stmt('updateConvLastMessage').run(preview, now, data.conversationId);
        return message;
    });

    var message = insertMsg();
    return { message: message, preview: preview };
}

function getConversation(conversationId) {
    return stmt('getConversation').get(conversationId);
}

function markMessagesRead(conversationId, readByUserId) {
    stmt('markRead').run(conversationId, readByUserId);
}

function deleteMessageForAll(messageId, userId) {
    var db = getDb();
    var msg = stmt('getMessageById').get(messageId);
    if (!msg) return null;
    if (msg.sender_id !== userId) return null;

    var conv = stmt('getConversation').get(msg.conversation_id);
    if (!conv) return null;

    var doDelete = db.transaction(function() {
        stmt('deleteMessage').run(messageId);
        var lastMsg = stmt('getLastMessage').get(msg.conversation_id);
        if (lastMsg) {
            var preview = buildPreview(lastMsg.message_type, lastMsg.text, lastMsg.file_name);
            stmt('updateConvLastMessageOnly').run(preview, lastMsg.timestamp, msg.conversation_id);
        } else {
            stmt('clearConvLastMessageNow').run(msg.conversation_id);
        }
    });
    doDelete();

    var otherId = conv.user1_id === userId ? conv.user2_id : conv.user1_id;
    return { conversationId: msg.conversation_id, otherId: otherId, fileUrl: msg.file_url };
}

function getMessageById(messageId) {
    return stmt('getMessageById').get(messageId);
}

module.exports = {
    nowISO: nowISO,
    sanitizeText: sanitizeText,
    buildPreview: buildPreview,
    insertMessage: insertMessage,
    getConversation: getConversation,
    markMessagesRead: markMessagesRead,
    deleteMessageForAll: deleteMessageForAll,
    getMessageById: getMessageById
};
