const express = require('express');
const router = express.Router();
const { getDb, stmt } = require('../db/database');
const { asyncHandler } = require('../middleware/errorHandler');
const { validateInt } = require('../middleware/validate');
const redis = require('../services/redisService');

// GET /api/contacts/:userId
router.get('/:userId', asyncHandler(function(req, res) {
    var userId = validateInt(req.params.userId);
    if (!userId) {
        return res.status(400).json({ success: false, error: 'Invalid userId' });
    }

    var contacts = stmt('getContactsByOwner').all(userId);
    res.json({ success: true, contacts: contacts });
}));

// POST /api/contacts
router.post('/', asyncHandler(function(req, res) {
    var body = req.body || {};
    var owner_id = validateInt(body.owner_id);
    var contact_user_id = validateInt(body.contact_user_id);
    var contact_device_id = body.contact_device_id;
    var contact_username = body.contact_username;

    if (!owner_id || (!contact_user_id && !contact_device_id && !contact_username)) {
        return res.status(400).json({ error: 'owner_id and contact_user_id (or contact_device_id or contact_username) are required' });
    }

    var db = getDb();
    var contactUser;

    if (contact_user_id) {
        contactUser = stmt('getUserNameById').get(contact_user_id);
        if (!contactUser) {
            return res.status(404).json({ error: '\u0644\u0645 \u064A\u062A\u0645 \u0627\u0644\u0639\u062B\u0648\u0631 \u0639\u0644\u0649 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645' });
        }
    } else if (contact_username) {
        var uname = contact_username.trim().toLowerCase().replace(/^@/, '');
        contactUser = stmt('getUserNameByUsername').get(uname);
        if (!contactUser) {
            return res.status(404).json({ error: '\u0644\u0645 \u064A\u062A\u0645 \u0627\u0644\u0639\u062B\u0648\u0631 \u0639\u0644\u0649 \u0645\u0633\u062A\u062E\u062F\u0645 \u0628\u0647\u0630\u0627 \u0627\u0644\u0627\u0633\u0645' });
        }
    } else {
        contactUser = stmt('getUserNameByDevice').get(contact_device_id);
        if (!contactUser) {
            return res.status(404).json({ error: '\u0644\u0645 \u064A\u062A\u0645 \u0627\u0644\u0639\u062B\u0648\u0631 \u0639\u0644\u0649 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645' });
        }
    }

    if (contactUser.id === owner_id) {
        return res.status(400).json({ error: 'Cannot add yourself as a contact' });
    }

    var existing = stmt('checkContactExists').get(owner_id, contactUser.id);
    if (existing) {
        return res.json({ success: true, message: 'Contact already exists', contact_id: existing.id });
    }

    var addBoth = getDb().transaction(function() {
        stmt('insertContact').run(owner_id, contactUser.id);
        stmt('insertContact').run(contactUser.id, owner_id);
    });
    addBoth();

    var minId = Math.min(owner_id, contactUser.id);
    var maxId = Math.max(owner_id, contactUser.id);
    var existingConv = stmt('findConversation').get(minId, maxId);

    var conversationId;
    if (!existingConv) {
        var convResult = stmt('createConversation').run(minId, maxId);
        conversationId = convResult.lastInsertRowid;
    } else {
        conversationId = existingConv.id;
    }

    redis.safeDel('contacts:' + owner_id, 'contacts:' + contactUser.id);
    redis.safeDel('convlist:' + owner_id, 'convlist:' + contactUser.id);

    res.json({
        success: true,
        contact_user_id: contactUser.id,
        contact_name: contactUser.display_name,
        conversation_id: conversationId
    });

    console.log('[CONTACTS] User #' + owner_id + ' added contact #' + contactUser.id + ' (' + contactUser.display_name + ')');
}));

// DELETE /api/contacts/:contactId
router.delete('/:contactId', asyncHandler(function(req, res) {
    var contactId = validateInt(req.params.contactId);
    if (!contactId) {
        return res.status(400).json({ success: false, error: 'Invalid contactId' });
    }

    var contact = stmt('getContactById').get(contactId);
    stmt('deleteContactById').run(contactId);

    if (contact) {
        redis.safeDel('contacts:' + contact.owner_id, 'contacts:' + contact.contact_user_id);
    }

    res.json({ success: true });
}));

module.exports = router;
