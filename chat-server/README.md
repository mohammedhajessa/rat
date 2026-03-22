# Secure Chat Server v2.0

A production-ready, real-time chat backend built with **Node.js**, **Express**, **Socket.IO**, **SQLite**, and **AWS S3**. Full Telegram-style messaging with media sharing, audio calls (WebRTC), user blocking/muting, multi-device support, and an admin panel.

---

## Features

- **Real-time messaging** — Socket.IO with per-message compression
- **Media sharing** — Photos, voice messages, documents via AWS S3
- **Audio calls** — WebRTC signaling with ICE relay, 45s auto-timeout
- **Online presence** — Targeted broadcasts to contacts only
- **Typing indicators** — With 4-second auto-clear
- **Read receipts** — Single check (sent) → Double check (read)
- **Message deletion** — Delete for me / Delete for all
- **User blocking** — Bidirectional enforcement (messages, calls, presence)
- **Conversation muting** — Suppress notifications per conversation
- **Chat deletion** — Soft-delete with `deleted_by` column
- **Multi-device** — Login notifications, remote logout, device listing
- **Profile management** — Display name, avatar URL, PIN change
- **Contact management** — Add by user ID or device ID
- **Shared media** — Query media by conversation with counts
- **Admin panel** — Generate PINs, manage users, link contacts
- **Security** — `crypto.randomInt` PINs, Helmet, rate limiting, XSS sanitization
- **Performance** — Prepared statement caching, DB transactions, gzip compression, WAL mode

---

## Architecture

```
chat-server/
├── index.js                    # Entry point — Express, Socket.IO, admin panel
├── .env                        # Configuration (PORT, AWS, CORS)
├── package.json                # Dependencies and scripts
│
├── db/
│   ├── database.js             # SQLite schema, migrations, prepared statements
│   └── chat.db                 # Database file (auto-created)
│
├── routes/
│   ├── auth.js                 # PIN generation, login, profile, devices, PIN change
│   ├── contacts.js             # Add/list/delete contacts
│   ├── messages.js             # Conversations, message history, shared media, delete
│   ├── calls.js                # Call history and deletion
│   ├── upload.js               # S3 file upload with MIME validation
│   ├── block.js                # Block/unblock users
│   └── mute.js                 # Mute/unmute conversations
│
├── socket/
│   ├── chatHandler.js          # Messaging, typing, presence, read receipts
│   └── callHandler.js          # WebRTC signaling, call state
│
├── services/
│   ├── messageService.js       # Message CRUD with transactions
│   ├── callService.js          # In-memory call state + DB logs
│   └── s3Service.js            # AWS S3 upload, validation, signed URLs
│
└── middleware/
    ├── rateLimit.js             # API, auth, upload rate limiters
    ├── validate.js              # Input validation + XSS sanitization
    └── errorHandler.js          # Async error handler + central error middleware
```

---

## Quick Start

### 1. Install Dependencies

```bash
cd chat-server
npm install
```

### 2. Configure Environment

Create a `.env` file:

```env
PORT=3500
NODE_ENV=production

# AWS S3 (required for media uploads)
AWS_ACCESS_KEY_ID=your_access_key
AWS_SECRET_ACCESS_KEY=your_secret_key
AWS_REGION=us-east-1
AWS_BUCKET_NAME=your-bucket-name

# CORS (comma-separated origins, or * for all)
CORS_ORIGINS=*
```

### 3. Start the Server

```bash
# Production
npm start

# Development
npm run dev
```

Server starts on `http://0.0.0.0:3500`

### 4. Access Admin Panel

Open `http://localhost:3500/admin` to generate PINs, view users, and link contacts.

---

## Database Schema

**Engine**: SQLite via `better-sqlite3` — WAL mode, 256MB mmap, 32MB cache, prepared statement caching.

### Tables

| Table | Key Columns | Purpose |
|---|---|---|
| **users** | id, device_id, pin, display_name, avatar_url, is_online, last_seen | User accounts |
| **conversations** | id, user1_id, user2_id, last_message, last_message_time, deleted_by | Chat threads |
| **messages** | id, conversation_id, sender_id, text, message_type, file_url, file_name, file_size, duration, is_read | All messages |
| **contacts** | owner_id, contact_user_id | Bidirectional contacts |
| **call_logs** | caller_id, receiver_id, status, duration, timestamp | Call history |
| **devices** | user_id, device_id, device_name, last_active | Multi-device tracking |
| **blocked_users** | blocker_id, blocked_id | User blocking |
| **muted_conversations** | user_id, conversation_id | Notification muting |

### Performance Pragmas

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA cache_size = -32000;        -- 32MB
PRAGMA busy_timeout = 5000;
PRAGMA temp_store = MEMORY;
PRAGMA mmap_size = 268435456;      -- 256MB
PRAGMA foreign_keys = ON;
```

### Prepared Statement Cache

All frequent queries are pre-compiled at startup for zero parsing overhead:

`insertMessage`, `getMessageWithSender`, `updateConvLastMessage`, `markRead`, `checkBlock`, `setUserOnline`, `setUserOffline`, `getUserLastSeen`, `getMessagesPaginated`, `getMessagesBeforeId`, `getUserByPin`, `getUserById`, `insertBlock`, `deleteBlock`, `insertMute`, `deleteMute`, `getConvForUser`, `findConversation`, `createConversation`

---

## REST API Reference

### Authentication (`/api/auth`)

#### Generate PIN (Admin)

```http
POST /api/auth/generate-pin
Content-Type: application/json

{
  "display_name": "Ahmed"
}
```

**Response:**

```json
{
  "success": true,
  "user_id": 1,
  "pin": "482917",
  "display_name": "Ahmed"
}
```

> PIN is generated using `crypto.randomInt(100000, 999999)` for cryptographic security.

#### Verify PIN (App Login)

```http
POST /api/auth/verify-pin
Content-Type: application/json

{
  "pin": "482917",
  "device_id": "fe9bb524e5ad3864",
  "device_name": "Samsung Galaxy S24"
}
```

**Response:**

```json
{
  "success": true,
  "token": "550eed5a57de447db0b2ffd51d451479",
  "user": {
    "id": 1,
    "device_id": "fe9bb524e5ad3864",
    "display_name": "Ahmed"
  }
}
```

> If a new device logs in, the server emits `device:new_login` to all existing sessions.

#### Update Profile

```http
POST /api/auth/update-profile
Content-Type: application/json

{
  "user_id": 1,
  "display_name": "Ahmed K.",
  "avatar_url": "https://s3.amazonaws.com/bucket/avatars/user_1.jpg"
}
```

#### Change PIN

```http
POST /api/auth/change-pin
Content-Type: application/json

{
  "user_id": 1,
  "current_pin": "482917",
  "new_pin": "731205"
}
```

#### Get User Profile

```http
GET /api/auth/user/:userId
```

#### List Devices

```http
GET /api/auth/devices/:userId
```

#### Remove Device (Force Logout)

```http
DELETE /api/auth/devices/:userId/:deviceRowId
```

> Emits `device:force_logout` to the removed device's socket.

#### List All Users (Admin)

```http
GET /api/auth/users
```

#### Reset All Data (Admin)

```http
POST /api/auth/reset
```

> Wraps all DELETEs in a database transaction for atomicity.

---

### Contacts (`/api/contacts`)

#### List Contacts

```http
GET /api/contacts/:userId
```

**Response:**

```json
{
  "success": true,
  "contacts": [
    {
      "contact_id": 1,
      "user_id": 2,
      "display_name": "Sara",
      "is_online": 1,
      "last_seen": "2026-02-14T10:30:00Z"
    }
  ]
}
```

#### Add Contact

```http
POST /api/contacts
Content-Type: application/json

{
  "owner_id": 1,
  "contact_user_id": 2
}
```

> Automatically creates bidirectional contact entries and a conversation if one doesn't exist. Also supports `contact_device_id` to find a user by their device ID.

#### Delete Contact

```http
DELETE /api/contacts/:contactId
```

---

### Messages (`/api/messages`)

#### Get Message History (Paginated)

```http
GET /api/messages/:conversationId?limit=50&before_id=123
```

- `limit` — Number of messages (default 50)
- `before_id` — For infinite scroll pagination
- `max_age_hours` — Auto-cleanup old messages (optional)

#### List Conversations

```http
GET /api/messages/conversations/:userId
```

Returns all conversations with last message, unread count, other user info, `is_muted`, and `is_blocked` flags. Conversations soft-deleted by the user are filtered out in JavaScript.

#### Get or Create Conversation

```http
GET /api/messages/conversation-with/:userId/:otherUserId
```

#### Delete Conversation (Soft Delete)

```http
DELETE /api/messages/conversation/:conversationId/:userId
```

> Appends the user ID to `deleted_by` column. Conversation reappears if a new message arrives.

#### Shared Media

```http
GET /api/messages/shared-media/:conversationId?limit=50
```

**Response:**

```json
{
  "success": true,
  "media": [ ... ],
  "summary": {
    "images": 12,
    "voice": 5,
    "documents": 3,
    "total": 20
  }
}
```

---

### File Upload (`/api/upload`)

```http
POST /api/upload
Content-Type: multipart/form-data

file: [binary]
type: "image" | "voice" | "document"
sender_id: 1
```

**Response:**

```json
{
  "success": true,
  "fileUrl": "https://s3.amazonaws.com/bucket/images/image_1708000000_abc123.jpg",
  "fileName": "photo.jpg",
  "fileSize": 245678,
  "mimeType": "image/jpeg"
}
```

**Size Limits:**

| Type | Max Size | Allowed MIME Types |
|---|---|---|
| Image | 10 MB | jpeg, png, gif, webp, bmp |
| Voice | 5 MB | mp3, m4a, ogg, wav, aac, amr, 3gp |
| Document | 25 MB | pdf, doc/docx, xls/xlsx, ppt/pptx, txt, csv, zip, rar |

> Files are uploaded to AWS S3 with AES-256 encryption. MIME type is validated before upload.

---

### Calls (`/api/calls`)

#### Get Call History

```http
GET /api/calls/:userId
```

Returns last 50 calls with other user info and `is_outgoing` flag.

#### Delete All Call Logs

```http
DELETE /api/calls/:userId
```

---

### Block (`/api/block`)

#### Block User

```http
POST /api/block
Content-Type: application/json

{ "blocker_id": 1, "blocked_id": 2 }
```

#### Unblock User

```http
DELETE /api/block/:blockerId/:blockedId
```

#### Check Block Status

```http
GET /api/block/check/:blockerId/:blockedId
```

#### List Blocked Users

```http
GET /api/block/:userId
```

**Block Enforcement (bidirectional):**
- Messages from/to blocked user are silently dropped
- Calls from/to blocked user are rejected with "unavailable"
- Blocked user appears as "offline" in status requests
- Existing messages are preserved; new messages while blocked are lost

---

### Mute (`/api/mute`)

#### Mute Conversation

```http
POST /api/mute
Content-Type: application/json

{ "user_id": 1, "conversation_id": 5 }
```

#### Unmute Conversation

```http
DELETE /api/mute/:userId/:conversationId
```

> Muted conversations still receive messages but the Android app suppresses notifications.

---

## Socket.IO Events

### Connection & Presence

| Event | Direction | Payload | Description |
|---|---|---|---|
| `chat:join` | Client → Server | `{ userId }` | Join room, go online, broadcast presence |
| `chat:online` | Server → Client | `{ userId, isOnline, lastSeen }` | Presence update (sent to contacts only) |
| `chat:online_list` | Server → Client | `{ users: [...] }` | Full online user list on join |
| `chat:request_status` | Client → Server | `{ userId }` | Request specific user's status |
| `chat:set_status` | Client → Server | `{ isOnline }` | Manually set online/away |

### Messaging

| Event | Direction | Payload | Description |
|---|---|---|---|
| `chat:send` | Client → Server | `{ conversationId, senderId, text, messageType, fileUrl, fileName, fileSize, duration }` | Send a message |
| `chat:sent` | Server → Sender | `{ message, conversationId }` | Confirmation with server-assigned ID |
| `chat:receive` | Server → Recipient | `{ message, conversationId }` | Deliver message to recipient |
| `chat:conversation_update` | Server → Both | `{ conversationId, lastMessage, lastMessageTime, senderId }` | Update chat list preview |
| `chat:typing` | Bidirectional | `{ conversationId, userId }` | Typing indicator (4s auto-stop) |
| `chat:stop_typing` | Bidirectional | `{ conversationId, userId }` | Stop typing |
| `chat:read` | Bidirectional | `{ conversationId, userId }` | Mark messages as read |
| `chat:delete` | Client → Server | `{ messageId, userId, deleteForAll }` | Delete message |
| `chat:deleted` | Server → Client | `{ messageId, conversationId, deleteForAll }` | Notify deletion |

### Audio Calls (WebRTC Signaling)

| Event | Direction | Payload | Description |
|---|---|---|---|
| `call:offer` | Client → Server | `{ callerId, receiverId }` | Initiate call |
| `call:incoming` | Server → Receiver | `{ callId, callerId, callerName, callerInitials, callerAvatarColor }` | Ring receiver |
| `call:ringing` | Server → Caller | `{ callId, receiverId }` | Confirm ringing |
| `call:answer` | Client → Server | `{ callId, sdp? }` | Accept call |
| `call:answered` | Server → Both | `{ callId, sdp? }` | Call connected |
| `call:ice-candidate` | Bidirectional | `{ callId, senderId, candidate }` | ICE candidate relay |
| `call:reject` | Client → Server | `{ callId }` | Reject call |
| `call:end` | Client → Server | `{ callId }` | End call |
| `call:ended` | Server → Both | `{ callId, reason, duration? }` | Call terminated |
| `call:audio` | Bidirectional | `{ callId, senderId, audio }` | Audio relay (fallback) |
| `call:mute` | Bidirectional | `{ callId, userId, isMuted }` | Mute toggle |

### Device Management

| Event | Direction | Payload | Description |
|---|---|---|---|
| `device:new_login` | Server → Client | `{ device_name, device_id, timestamp }` | New device logged in |
| `device:force_logout` | Server → Client | `{ }` | Remote logout triggered |

**Call timeout**: Unanswered calls end after 45 seconds with `reason: "no_answer"` (caller) / `"missed"` (receiver).

**Call end reasons**: `"ended"`, `"rejected"`, `"no_answer"`, `"missed"`, `"disconnected"`, `"unavailable"` (blocked).

---

## Security

| Layer | Implementation |
|---|---|
| **PIN Generation** | `crypto.randomInt(100000, 999999)` — cryptographically secure |
| **HTTP Headers** | Helmet (CSP disabled for admin panel) |
| **Rate Limiting** | 1000 req/15min (API), 100/15min (auth), 100/5min (upload) |
| **Input Sanitization** | `xss-filters` on all text inputs |
| **MIME Validation** | Whitelist of allowed file types per category |
| **File Size Limits** | Enforced by multer before S3 upload |
| **S3 Encryption** | AES-256 server-side encryption |
| **CORS** | Configurable origins via `.env` |
| **Block Enforcement** | Bidirectional — checked in socket handlers before send/call |
| **DB Transactions** | Atomic operations for message insert, user reset, message delete |

---

## Performance Optimizations

| Optimization | Detail |
|---|---|
| **Prepared Statements** | All frequent queries pre-compiled and cached at startup |
| **DB Transactions** | Message insert + conversation update in single transaction |
| **WAL Mode** | Concurrent reads during writes |
| **Memory-mapped I/O** | 256MB mmap for fast DB access |
| **Response Compression** | gzip level 6, threshold 1KB |
| **Socket.IO Compression** | `perMessageDeflate` with 2KB threshold |
| **Targeted Broadcasts** | Presence updates only sent to contacts, not all users |
| **Pagination** | All list endpoints support `limit` and cursor-based paging |
| **Graceful Shutdown** | SIGTERM/SIGINT → close IO → close server → close DB |

---

## Deployment (VPS)

See [`DEPLOY.md`](./DEPLOY.md) for a full VPS deployment guide covering:

- Node.js installation
- PM2 process manager
- Nginx reverse proxy with `/chat` prefix
- Let's Encrypt SSL certificates
- UFW firewall configuration
- Database backups
- Monitoring and troubleshooting

### Quick Deploy

```bash
# On VPS
cd /home/ubuntu/chat-server
npm install --production
cp .env.example .env       # Edit with your values

# Start with PM2
pm2 start index.js --name chat-server
pm2 save
pm2 startup
```

### Nginx Config (with /chat prefix)

```nginx
location /chat/ {
    proxy_pass http://127.0.0.1:3500/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 86400;
}
```

---

## Health Check

```http
GET /health
```

**Response:**

```json
{
  "status": "ok",
  "uptime": 86400,
  "activeCalls": 0,
  "connections": 12,
  "memory": {
    "rss": "45 MB",
    "heapUsed": "22 MB",
    "heapTotal": "35 MB"
  },
  "storage": "s3",
  "timestamp": "2026-02-14T12:00:00.000Z"
}
```

---

## Message Types

| Type | Fields | Description |
|---|---|---|
| `text` | `text` | Plain text message |
| `image` | `file_url`, `file_name`, `file_size`, `text` (caption) | Photo message |
| `voice` | `file_url`, `file_name`, `file_size`, `duration` | Voice clip |
| `document` | `file_url`, `file_name`, `file_size` | File attachment |

---

## Troubleshooting

### Port Already in Use

```bash
# Windows
Get-NetTCPConnection -LocalPort 3500 | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }

# Linux
lsof -ti:3500 | xargs kill -9
```

### Database Locked

1. Ensure only one server instance is running
2. Delete `chat.db-wal` and `chat.db-shm` files (auto-recreated)
3. Check `busy_timeout` pragma (default 5000ms)

### S3 Upload Failures

1. Verify AWS credentials in `.env`
2. Check bucket name and region
3. Ensure bucket policy allows `PutObject` and `GetObject`
4. Check MIME type is in the allowed list

### Socket.IO Connection Behind Proxy

When running behind Nginx at `/chat`, the Android app must set:

```java
options.path = "/chat/socket.io/";
socket = IO.socket("http://your-server-ip", options);
```

### Admin Panel API Calls Failing

The admin panel auto-detects the base path from the URL. If `/chat/admin` is showing but buttons don't work, ensure Nginx proxies all paths under `/chat/` to the Node.js server.

---

## API Response Format

**Success:**

```json
{ "success": true, "data": { ... } }
```

**Error:**

```json
{ "success": false, "error": "Error message" }
```

**HTTP Status Codes**: 200 (OK), 400 (Bad Request), 404 (Not Found), 429 (Rate Limited), 500 (Server Error)

---

**Server Version:** 2.0.0
**Last Updated:** February 2026
