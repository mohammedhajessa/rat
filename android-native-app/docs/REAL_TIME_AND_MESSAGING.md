# Real-Time System and Messaging

This document explains how **real-time messaging** and **live updates** work between the **Android app** and the **chat-server**, including the Socket.IO event flow, message persistence, and how it fits with the REST API.

For **voice/video call flows** (WebRTC signaling, offer/answer, ICE), see [CALL_FLOW.md](CALL_FLOW.md).

---

## 1. Overview

| Layer | Role |
|-------|------|
| **Transport** | Single **Socket.IO** connection per user to the chat server (`Constants.CHAT_SERVER_URL`). Same connection is used for chat events and call signaling. |
| **REST API** | Used for conversations list, message history (paginated), auth, contacts, call logs, uploads, block/mute. |
| **Real-time (Socket)** | New messages, typing/recording indicators, read receipts, online status, conversation list updates, and call signaling are delivered over Socket.IO. |

- **Android:** `ChatSocketManager` (singleton) holds the socket, registers listeners, and exposes `emit` helpers and list-based listeners so multiple screens (e.g. chat list and conversation) can react to the same events.
- **Server:** `chat-server/socket/chatHandler.js` handles chat events; `chat-server/socket/callHandler.js` handles call events. Both use the same `io` instance and the same room naming: `user_<userId>`.

---

## 2. Connection and Rooms

### 2.1 Connecting

1. After the user is authenticated (PIN verified), the app calls `ChatSocketManager.getInstance().connect(userId)`.
2. The client connects to the chat server base URL with path `/socket.io/` (or derived from `CHAT_SERVER_URL`).
3. Options include reconnection with backoff, so a dropped connection is restored without the user re-opening the app.

### 2.2 Joining (chat:join)

- On connect, the client emits **`chat:join`** with `{ userId }`.
- The server:
  - Sets `socket.userId = userId`
  - Runs `socket.join('user_' + userId)` so this socket is in the room for that user.
  - Updates presence (online) and broadcasts **`chat:online`** to the user’s contacts and to the user themselves.

All subsequent server-to-client pushes for that user are done with:

- `io.to('user_' + userId).emit(eventName, payload)`

So one user can have multiple devices/sockets; each is in `user_<userId>`, and all receive the same events (e.g. new message, incoming call).

---

## 3. Messaging Flow

### 3.1 Sending a message

```
  ANDROID (Sender)                    CHAT SERVER                     ANDROID (Receiver)
  ─────────────────                  ───────────                     ───────────────────

  User sends text / media
       │
       │  ChatSocketManager.sendMessage() or sendMediaMessage()
       │  socket.emit("chat:send", { conversationId, senderId, text, messageType, ... })
       │─────────────────────────────────►
       │                                  │  chatHandler: chat:send
       │                                  │  - Validate conversation, block
       │                                  │  - messageService.insertMessage() → DB
       │                                  │  - Update conversation last_message, last_message_time
       │                                  │
       │                                  │  io.to('user_'+recipientId).emit('chat:receive', { message, conversationId })
       │                                  │─────────────────────────────────────────────────────────►
       │                                  │                              │  OnMessageReceivedListener
       │                                  │                              │  → ConversationActivity adds message
       │                                  │                              │  → ChatNotificationManager (if background)
       │                                  │
       │  io.to('user_'+senderId).emit('chat:sent', { message, conversationId })
       │◄─────────────────────────────────
       │  OnMessageSentListener → UI: show message as sent (e.g. tick)
       │
       │  io.to(recipient).emit('chat:conversation_update', { conversationId, lastMessage, lastMessageTime, senderId })
       │  io.to(sender).emit('chat:conversation_update', ...)
       │◄─────────────────────────────────  (both clients update list item / unread)
```

- **Persistence:** The server writes the message to the **messages** table and updates the conversation’s `last_message` and `last_message_time`. So history is always in the DB; the socket is for instant delivery and UI sync.
- **Message types:** `text`, `image`, `voice`, `document` (and optional metadata like `fileUrl`, `fileName`, `fileSize`, `duration`). The server builds a short preview for the conversation row (e.g. “📷 صورة” for image).

### 3.2 Receiving a message (Android)

- The app registers **`OnMessageReceivedListener`** (e.g. in `ConversationActivity`). When **`chat:receive`** is received:
  - The new message is appended to the current conversation UI.
  - If the app is in the background and the conversation is not open/muted, **ChatNotificationManager** shows a notification (with launcher icon and optional large icon).
- **`chat:sent`** is used so the sender’s UI can mark the message as delivered (and optionally update local state).

### 3.3 Offline / queuing

- If the socket is not connected when the user sends a message, `ChatSocketManager` enqueues it in **`pendingMessages`**.
- On **EVENT_CONNECT** (and after **chat:join**), **`flushPendingMessages()`** runs and re-sends queued messages. So messages sent while offline are sent once the connection is back.

---

## 4. Real-Time Features (Socket Events)

### 4.1 Typing and recording indicators

| Client → Server | Server → Other peer | Purpose |
|-----------------|---------------------|---------|
| **chat:typing** `{ conversationId, userId }` | **chat:typing** | “User is typing” in this conversation. |
| **chat:stop_typing** | **chat:stop_typing** | Clear typing. Server also clears typing when a message is sent. |
| **chat:recording** | **chat:recording** | “User is recording voice.” |
| **chat:stop_recording** | **chat:stop_recording** | Clear recording indicator. |

- The server derives the **recipient** from the conversation (the other participant) and emits only to `user_<recipientId>`.
- Typing has a **4s timeout** on the server; if no **chat:stop_typing** or new message, the server sends **chat:stop_typing** automatically.

### 4.2 Read receipts

| Client → Server | Server action | Server → Other peer |
|-----------------|---------------|----------------------|
| **chat:read** `{ conversationId, userId }` | Mark all messages in that conversation as read for the other participant; update DB. | **chat:read** `{ conversationId, readBy }` to the **other** user. |

- The reader emits **chat:read** when they open the conversation (or when messages enter the “read” state).
- The other side uses **OnReadReceiptListener** to show “read” (e.g. double tick).

### 4.3 Conversation list updates

- After **chat:send**, the server emits **chat:conversation_update** to **both** sender and recipient with:
  - `conversationId`, `lastMessage`, `lastMessageTime`, `senderId`
- Android **OnConversationUpdateListener** (e.g. chat list screen) can update the row: last message preview, time, and unread state (often combined with local unread logic or REST).

### 4.4 Online status

| Client → Server | Server → Clients |
|-----------------|------------------|
| **chat:join** (on connect) | Server sets user online, then **broadcasts** **chat:online** `{ userId, isOnline, lastSeen }` to all of the user’s **contacts** and to the user. |
| **chat:set_status** `{ isOnline }` | Same broadcast when app goes to foreground/background. |
| **chat:request_status** `{ userId }` | Server replies to **requester** with **chat:online** for that target user (from DB/Redis or in-memory presence). |
| **disconnect** | Server sets user offline and broadcasts **chat:online** `isOnline: false` and `lastSeen`. |

- Presence can be stored in DB and optionally in **Redis** for scalability. Android uses **OnOnlineStatusListener** to show “online” / “last seen” in the UI.

### 4.5 Delete message

| Client → Server | Server action | Server → Clients |
|-----------------|---------------|------------------|
| **chat:delete** `{ messageId, userId, deleteForAll }` | If `deleteForAll`: delete in DB, update conversation last message. Else: only local delete. | **chat:deleted** `{ messageId, conversationId, deleteForAll }` to sender and (if deleteForAll) to the other participant. |

- Android **OnMessageDeletedListener** removes or updates the message in the UI.

---

## 5. Event Summary Tables

### 5.1 Chat events (client → server)

| Event | Payload (main fields) | Handler (server) |
|-------|------------------------|------------------|
| **chat:join** | `userId` | Join room `user_<userId>`, set presence, broadcast **chat:online** |
| **chat:send** | `conversationId`, `senderId`, `text`, `messageType`, optional `fileUrl`, `fileName`, `fileSize`, `duration` | Insert message, emit **chat:receive** to recipient, **chat:sent** to sender, **chat:conversation_update** to both |
| **chat:typing** | `conversationId`, `userId` | Emit **chat:typing** to other participant; set 4s auto **chat:stop_typing** |
| **chat:stop_typing** | `conversationId`, `userId` | Emit **chat:stop_typing** to other participant |
| **chat:recording** / **chat:stop_recording** | `conversationId`, `userId` | Emit same event to other participant |
| **chat:read** | `conversationId`, `userId` | Mark read in DB; emit **chat:read** to other participant |
| **chat:request_status** | `userId` (target) | Reply to sender with **chat:online** for that user |
| **chat:set_status** | `isOnline` | Update presence, broadcast **chat:online** to contacts and self |
| **chat:delete** | `messageId`, `userId`, `deleteForAll` | Delete or update conv; emit **chat:deleted** to concerned clients |

### 5.2 Chat events (server → client)

| Event | Typical payload | Android use |
|-------|------------------|-------------|
| **chat:receive** | `message`, `conversationId` | OnMessageReceivedListener → show message, maybe notification |
| **chat:sent** | `message`, `conversationId` | OnMessageSentListener → mark as sent |
| **chat:typing** / **chat:stop_typing** | `conversationId`, `userId` | OnTypingListener → show “typing…” |
| **chat:recording** / **chat:stop_recording** | `conversationId`, `userId` | OnRecordingListener → show recording indicator |
| **chat:read** | `conversationId`, `readBy` | OnReadReceiptListener → show read receipt |
| **chat:online** | `userId`, `isOnline`, `lastSeen` | OnOnlineStatusListener → update status in UI |
| **chat:conversation_update** | `conversationId`, `lastMessage`, `lastMessageTime`, `senderId` | OnConversationUpdateListener → refresh list row |
| **chat:deleted** | `messageId`, `conversationId`, `deleteForAll` | OnMessageDeletedListener → remove/update message in UI |
| **chat:online_list** | `users` (array of online userIds) | Optional: bulk online list on join |

---

## 6. Android: ChatSocketManager and listeners

- **Single connection:** One Socket.IO connection per app process; `ChatSocketManager` is a singleton.
- **List-based listeners:** Multiple activities/fragments can add the same listener type (e.g. several `OnMessageReceivedListener`). When an event is received, **all** registered listeners for that event are invoked. This allows:
  - **ConversationActivity** to show the new message.
  - **ChatListFragment** to stay in sync if it listens for **chat:conversation_update** or **chat:sent**.
  - **ChatNotificationManager** to be driven from the same **chat:receive** path (e.g. via a listener that calls `showMessageNotification` when app is in background and conversation not open).
- **Connection lifecycle:** Listeners for **EVENT_CONNECT**, **EVENT_DISCONNECT**, **EVENT_CONNECT_ERROR** (`OnConnectionListener`) allow the UI to show connection state and optionally retry or show a “reconnecting” state.

---

## 7. REST API vs Real-Time

- **REST** is used for:
  - **Conversations list:** e.g. `GET /api/messages/conversations/:userId`
  - **Message history:** e.g. `GET /api/messages/:conversationId?limit=...` (paginated)
  - **Auth, contacts, call logs, upload, block, mute, archive, etc.**

- **Real-time (Socket)** is used for:
  - **Instant delivery** of new messages (**chat:receive** / **chat:sent**).
  - **Live UI updates:** typing, recording, read receipts, online status, conversation list updates, delete.
  - **Call signaling:** offer, answer, ICE, end, etc. (see [CALL_FLOW.md](CALL_FLOW.md)).

So: the app loads conversations and history via REST; then Socket.IO keeps the UI up to date and delivers new messages and presence without polling.

---

## 8. Redis Caching (Performance Optimization)

**Redis is optional** — the chat server works without it, but Redis significantly speeds up frequent queries.

### 8.1 What Redis caches

| Cache Key Pattern | TTL | Purpose |
|-------------------|-----|---------|
| **`convlist:<userId>`** | 30s | Cached conversations list (JSON array). Invalidated when a message is sent/received, conversation is deleted, or user blocks/unblocks. |
| **`contacts:<userId>`** | 300s (5 min) | Cached contact user IDs (JSON array). Used to determine who should receive presence broadcasts. |
| **`presence:<userId>`** | 86400s (24h) | Hash: `online` (0/1), `lastSeen` (ISO timestamp). Used for fast online status lookups. |
| **`unread:<userId>`** | No expiry | Hash: `conversationId` → unread count. Incremented on new message, cleared on read. |

### 8.2 How Redis is used

- **Conversations list:** `GET /api/messages/conversations/:userId` checks `convlist:<userId>`. If missing, queries SQLite, caches for 30s, returns. On message send/receive, the cache is deleted (`redis.safeDel('convlist:' + userId)`) so the next request rebuilds from DB.
- **Contacts:** When broadcasting presence (`chat:online`), the server checks `contacts:<userId>`. If missing, queries DB, caches for 5 min.
- **Presence:** On **chat:join** or **chat:set_status**, the server writes `presence:<userId>` with `online` and `lastSeen`. On **chat:request_status**, it reads from Redis (or falls back to DB/in-memory).
- **Unread counts:** On **chat:send**, `redis.safeHincrby('unread:' + recipientId, conversationId, 1)`. On **chat:read**, `redis.safeHdel('unread:' + userId, conversationId)`.

### 8.3 Graceful fallback

- `redisService.available()` checks if Redis is connected. If not, all `safe*` methods return `null` or no-op, and the server falls back to SQLite queries.
- The app works identically with or without Redis; Redis only adds speed for high-traffic scenarios.

### 8.4 Configuration

- Set `REDIS_URL=redis://127.0.0.1:6379` in `chat-server/.env` (or leave unset to disable).
- The server logs `[REDIS] Connected` on startup if Redis is available, or `[REDIS] Not available, running without cache` if not.

---

## 9. AWS S3 for Media Storage

All media files (images, voice messages, documents) are stored in **AWS S3** with server-side encryption (AES-256). The server never stores files on disk; files go from client → memory buffer → S3.

### 9.1 Upload flow (Android → Server → S3)

```
  ANDROID (Sender)                    CHAT SERVER                     AWS S3
  ─────────────────                  ───────────                     ───────

  User selects image/voice/document
       │
       │  ChatApiService.uploadFile(file, type, senderId)
       │  POST /api/upload
       │  Content-Type: multipart/form-data
       │  Body: file, type, sender_id
       │─────────────────────────────────►
       │                                  │  routes/upload.js
       │                                  │  - multer (memory storage, max 25MB)
       │                                  │  - s3Service.validateMimeType(type, mimetype)
       │                                  │  - s3Service.getSizeLimit(type)
       │                                  │  - s3Service.generateS3Key(type, filename)
       │                                  │    → "images/image_1708000000_abc123.jpg"
       │                                  │  - s3Service.uploadToS3(buffer, key, mimetype)
       │                                  │──────────────────────────────────────────────►
       │                                  │                              │  PutObjectCommand
       │                                  │                              │  - Bucket: AWS_BUCKET_NAME
       │                                  │                              │  - Key: images/image_...
       │                                  │                              │  - Body: file buffer
       │                                  │                              │  - ContentType: image/jpeg
       │                                  │                              │  - ServerSideEncryption: AES256
       │                                  │                              │
       │                                  │  Returns: public URL
       │                                  │  "https://bucket.s3.region.amazonaws.com/images/..."
       │                                  │◄──────────────────────────────────────────────
       │                                  │
       │  Response: { success: true, fileUrl, fileName, fileSize, mimeType }
       │◄─────────────────────────────────
       │
       │  Now send message with fileUrl:
       │  ChatSocketManager.sendMediaMessage(convId, senderId, "", "image", fileUrl, fileName, fileSize, 0)
       │  socket.emit("chat:send", { conversationId, senderId, messageType: "image", fileUrl, fileName, fileSize })
       │─────────────────────────────────►
       │                                  │  Server saves message with fileUrl in DB
       │                                  │  Emits chat:receive to recipient
```

### 9.2 File types and limits

| Type | MIME types allowed | Max size | S3 subdirectory |
|------|-------------------|----------|------------------|
| **image** | `image/jpeg`, `image/png`, `image/gif`, `image/webp`, `image/bmp` | 10 MB | `images/` |
| **voice** | `audio/mpeg`, `audio/mp4`, `audio/aac`, `audio/ogg`, `audio/wav`, `audio/x-m4a`, `audio/3gpp`, `audio/amr`, `application/octet-stream` | 5 MB | `voice/` |
| **document** | `application/pdf`, `application/msword`, `.docx`, `.xlsx`, `.pptx`, `text/plain`, `application/zip`, `application/x-rar-compressed`, `application/octet-stream` | 25 MB | `documents/` |

- **MIME validation:** The server checks the actual MIME type sent by the client against the allowed list for the declared `type`. This prevents spoofing (e.g. uploading an executable as an image).
- **Size limits:** Enforced by `multer` (before S3 upload) and `s3Service.getSizeLimit(type)`.

### 9.3 S3 key generation

- Format: `<subdir>/<type>_<timestamp>_<random16hex>.<ext>`
- Example: `images/image_1708000000_a1b2c3d4e5f6.jpg`
- The random suffix ensures uniqueness and prevents collisions.

### 9.4 Security

- **Server-side encryption:** All uploads use `ServerSideEncryption: 'AES256'` (S3-managed keys).
- **Public URLs:** Files are stored with public-read ACL (or bucket policy allows public access). The URL format is: `https://<bucket>.s3.<region>.amazonaws.com/<key>`.
- **No signed URLs needed:** Since files are public, clients can download directly from S3 URLs. If you need private files, you can use `s3Service.getSignedDownloadUrl(key, expiresIn)` to generate temporary signed URLs.

### 9.5 Configuration

Set in `chat-server/.env`:
```
AWS_ACCESS_KEY_ID=your_access_key
AWS_SECRET_ACCESS_KEY=your_secret_key
AWS_REGION=us-east-1
AWS_BUCKET_NAME=your-bucket-name
```

---

## 10. Media Messages: Complete Flow

### 10.1 Sending a media message

1. **User selects media** (camera, gallery, voice recorder, file picker).
2. **Android uploads to S3:**
   - `ChatApiService.uploadFile(file, type, senderId)` → `POST /api/upload`
   - Server validates MIME type and size, uploads to S3, returns `fileUrl`.
3. **Android caches locally** (optional, for faster re-display):
   - `MediaCacheManager.cacheFromFile(fileUrl, tempFile, type)` stores the file in app cache (`media_cache/images/`, `media_cache/voice/`, `media_cache/documents/`) with a hash-based filename.
4. **Android sends message via Socket:**
   - `ChatSocketManager.sendMediaMessage(conversationId, senderId, caption, "image", fileUrl, fileName, fileSize, duration)`
   - Emits **chat:send** with `messageType: "image"`, `fileUrl`, `fileName`, `fileSize`, optional `text` (caption).
5. **Server:**
   - Validates conversation, checks block.
   - `messageService.insertMessage()` saves to DB with `file_url`, `file_name`, `file_size`, `duration`.
   - Builds preview (e.g. "📷 صورة" for image).
   - Emits **chat:receive** to recipient, **chat:sent** to sender, **chat:conversation_update** to both.

### 10.2 Receiving a media message

1. **Android receives chat:receive** with `message.file_url` (S3 URL).
2. **Check local cache:**
   - `MediaCacheManager.getLocalFile(fileUrl)` checks if already cached (by URL hash).
   - If cached, use local file for display.
3. **If not cached, download:**
   - Android downloads from S3 URL (direct HTTP GET, no signed URL needed).
   - `MediaCacheManager.cacheFromStream(url, inputStream, type)` saves to cache.
   - Display the cached file.
4. **Display:**
   - **Images:** Show thumbnail/full image (with `ImageLoader`).
   - **Voice:** Show waveform/player with duration.
   - **Documents:** Show icon, filename, size, download button.

### 10.3 Media caching (Android)

- **Location:** `app.getFilesDir()/media_cache/{images,voice,documents}/`
- **Index:** `SharedPreferences` (`media_cache_index`) maps URL hash → local file path.
- **Benefits:** Faster re-display, works offline for cached media, reduces bandwidth.
- **Cache management:** User can clear cache in Settings (`MediaCacheManager.clearCache()`).

### 10.4 Message types in database

The **messages** table stores:
- `message_type`: `'text'`, `'image'`, `'voice'`, `'document'`
- `file_url`: Full S3 URL (e.g. `https://bucket.s3.region.amazonaws.com/images/...`)
- `file_name`: Original filename (e.g. `photo.jpg`)
- `file_size`: Bytes (e.g. `2048576`)
- `duration`: Seconds (for voice messages, e.g. `15`)

When sending a text message, `file_url` is `NULL` and `message_type` is `'text'`.

---

## 11. Call signaling (short)

Voice/video calls use the **same Socket.IO connection** and the same room names (`user_<userId>`). Events include **call:offer**, **call:answer**, **call:ice-candidate**, **call:incoming**, **call:ringing**, **call:answered**, **call:ended**, **call:mute**. Audio can be WebRTC P2P (recommended) or, in legacy setups, relayed with **call:audio**. Full call flow: [CALL_FLOW.md](CALL_FLOW.md).

---

## 12. Diagram: high-level real-time messaging

```
                    ┌─────────────────────────────────────────────────────────┐
                    │                    CHAT SERVER                          │
                    │  socket/chatHandler.js  │  socket/callHandler.js        │
                    │  messageService.js     │  callService.js                │
                    │  routes/upload.js (S3) │  routes/messages.js (REST)     │
                    │  DB (SQLite: messages, conversations, users)             │
                    │  Redis (optional cache: convlist, contacts, presence)   │
                    └──────────┬──────────────┬───────────────────────────────┘
                               │              │
         Socket.IO (one connection)  │              │  REST (conversations, history, auth)
         Rooms: user_<userId>        │              │
                               │              │
                               │              │  POST /api/upload → AWS S3
                               │              │  (multipart/form-data: file, type, sender_id)
                               │              │
                               │              ▼
                               │      ┌─────────────────┐
                               │      │   AWS S3        │
                               │      │   (Media files) │
                               │      │   AES-256 enc.  │
                               │      └─────────────────┘
                               │
  ┌────────────────────────────┼──────────────────────────────────────────────┐
  │ ANDROID                    │                                              │
  │  ChatSocketManager          │   ChatApiService (OkHttp/Retrofit)           │
  │    • connect(userId)        │     • GET /api/messages/conversations/…     │
  │    • sendMessage /          │     • GET /api/messages/:convId?limit=…      │
  │      sendMediaMessage       │     • POST /api/upload → S3                  │
  │    • sendTyping,            │     • POST /api/auth/…                       │
  │      markAsRead, …          │                                              │
  │    • Listener lists →       │   MediaCacheManager                          │
  │      ConversationActivity, │     • Cache S3 files locally                  │
  │      ChatListFragment,      │     • Hash-based index (URL → local path)    │
  │      ChatNotificationManager│     • Subdirs: images/, voice/, documents/   │
  └────────────────────────────┴──────────────────────────────────────────────┘
```

This file focuses on **real-time messaging and presence**. For **call flows** (WebRTC, offer/answer, ICE, end), use [CALL_FLOW.md](CALL_FLOW.md).
