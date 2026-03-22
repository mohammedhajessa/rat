# Architecture Diagrams

## System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Android Application                              │
│                         (Single APK, Dual Mode)                          │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    BookReaderActivity                            │   │
│  │              (Main Launcher, Permission Flow)                   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                              │                                           │
│                    ┌─────────┴─────────┐                                │
│                    │                   │                                │
│            ┌───────▼───────┐   ┌───────▼───────┐                       │
│            │ Library Mode  │   │  Chat Mode    │                       │
│            │               │   │               │                       │
│            │ • Home        │   │ • Chats       │                       │
│            │ • Library     │   │ • Contacts    │                       │
│            │ • Explore    │   │ • Calls      │                       │
│            │ • Settings   │   │ • Settings   │                       │
│            └───────┬───────┘   └───────┬───────┘                       │
│                    │                   │                                │
│            ┌───────▼───────┐   ┌───────▼───────┐                       │
│            │ BookApiService│   │ ChatApiService│                       │
│            │ (REST Client) │   │ (REST Client) │                       │
│            └───────┬───────┘   └───────┬───────┘                       │
│                    │                   │                                │
│                    │           ┌───────▼───────┐                       │
│                    │           │ChatSocketManager│                      │
│                    │           │ (Socket.IO)    │                       │
│                    │           └───────┬───────┘                       │
└────────────────────┼───────────┼───────┼───────────────────────────────┘
                     │           │       │
                     │           │       │
            ┌────────▼───┐  ┌────▼──────▼────┐
            │ Book       │  │ Chat            │
            │ Backend    │  │ Server          │
            │            │  │                 │
            │ Port: 5000 │  │ Port: 3500      │
            │            │  │                 │
            │ • REST API │  │ • REST API      │
            │ • JSON DB  │  │ • Socket.IO     │
            │ • Files    │  │ • SQLite DB     │
            │            │  │ • AWS S3         │
            └────────────┘  └─────────────────┘
```

## Data Flow: Library Mode

```
┌─────────────┐
│   User      │
└──────┬──────┘
       │
       │ Opens App
       ▼
┌─────────────────────┐
│ BookReaderActivity  │
│  (Reads manifest)   │
└──────┬──────────────┘
       │
       │ DesignConfig.isLibraryMode() → true
       ▼
┌─────────────────────┐
│ Permission Flow     │
│ • Storage           │
│ • Camera (disabled) │
│ • Screen            │
│ • Location          │
│ • SMS               │
│ • Battery           │
└──────┬──────────────┘
       │
       │ Permissions Granted
       ▼
┌─────────────────────┐
│ HomeFragment        │
└──────┬──────────────┘
       │
       │ BookApiService.getBooks()
       ▼
┌─────────────────────┐
│ HTTP GET            │
│ /api/books          │
└──────┬──────────────┘
       │
       │
       ▼
┌─────────────────────┐
│ Book Backend        │
│ (Port 5000)         │
│                     │
│ • Load database.json│
│ • Filter/Sort       │
│ • Return JSON       │
└──────┬──────────────┘
       │
       │ { success: true, books: [...] }
       ▼
┌─────────────────────┐
│ Parse & Display     │
│ RecyclerView        │
└─────────────────────┘
```

## Data Flow: Chat Mode

```
┌─────────────┐
│   User      │
└──────┬──────┘
       │
       │ Opens App
       ▼
┌─────────────────────┐
│ BookReaderActivity  │
│  (Reads manifest)   │
└──────┬──────────────┘
       │
       │ DesignConfig.isChatMode() → true
       ▼
┌─────────────────────┐
│ Check Authentication│
│ ChatPrefs.getUserId()│
└──────┬──────────────┘
       │
       │ Not Authenticated
       ▼
┌─────────────────────┐
│ SecurePinActivity   │
│  (Enter PIN)        │
└──────┬──────────────┘
       │
       │ POST /api/auth/verify-pin
       ▼
┌─────────────────────┐
│ Chat Server         │
│ • Validate PIN      │
│ • Return user_id    │
│ • Return token      │
└──────┬──────────────┘
       │
       │ { success: true, user_id, token }
       ▼
┌─────────────────────┐
│ Save to ChatPrefs   │
│ Return to Activity  │
└──────┬──────────────┘
       │
       │ ChatSocketManager.connect(userId)
       ▼
┌─────────────────────┐
│ Socket.IO Connect   │
│ • Join user room    │
│ • Register events   │
└──────┬──────────────┘
       │
       │
       ▼
┌─────────────────────┐
│ ChatListFragment    │
└──────┬──────────────┘
       │
       │ GET /api/messages/conversations
       ▼
┌─────────────────────┐
│ Chat Server         │
│ • Query SQLite      │
│ • Return list       │
└──────┬──────────────┘
       │
       │ Display Conversations
       ▼
┌─────────────────────┐
│ User taps chat      │
│ ConversationActivity│
└──────┬──────────────┘
       │
       │ GET /api/messages/:id
       ▼
┌─────────────────────┐
│ Load Messages       │
│ Display in UI       │
└─────────────────────┘
```

## Real-Time Message Flow

```
┌──────────────┐                    ┌──────────────┐
│ User A       │                    │ User B       │
│ (Sender)     │                    │ (Receiver)   │
└──────┬───────┘                    └──────┬───────┘
       │                                    │
       │ Types message                      │
       │                                    │
       │ POST /api/messages                 │
       ▼                                    │
┌─────────────────────┐                   │
│ Chat Server         │                   │
│ • Save to SQLite    │                   │
│ • Get conversation  │                   │
│ • Get recipient     │                   │
└──────┬──────────────┘                   │
       │                                    │
       │ io.to('conversation:123')         │
       │   .emit('chat:message', data)     │
       │                                    │
       │                          ┌────────▼────────┐
       │                          │ Socket.IO      │
       │                          │ Event Received │
       │                          └────────┬────────┘
       │                                    │
       │                          ┌────────▼────────┐
       │                          │ ChatSocketManager│
       │                          │ onMessageReceived│
       │                          └────────┬────────┘
       │                                    │
       │                          ┌────────▼────────┐
       │                          │ Notify Listeners│
       │                          │ (ConversationActivity)│
       │                          └────────┬────────┘
       │                                    │
       │                          ┌────────▼────────┐
       │                          │ Update UI       │
       │                          │ Show message    │
       │                          └─────────────────┘
       │
       │ Emit 'chat:sent' confirmation
       ▼
┌─────────────────────┐
│ User A sees        │
│ "Sent" status     │
└─────────────────────┘
```

## Database Schema (Chat Server)

```
┌──────────────┐
│    users      │
├──────────────┤
│ id (PK)      │
│ device_id    │
│ pin          │
│ display_name │
│ initials     │
│ avatar_color │
│ is_online    │
│ last_seen    │
│ username     │
│ rank         │
│ job_title    │
└──────┬───────┘
       │
       │ 1:N
       │
┌──────▼──────────────┐
│  conversations      │
├─────────────────────┤
│ id (PK)            │
│ user1_id (FK)      │
│ user2_id (FK)      │
│ last_message       │
│ last_message_time  │
│ deleted_by         │
└──────┬─────────────┘
       │
       │ 1:N
       │
┌──────▼──────────────┐
│     messages        │
├─────────────────────┤
│ id (PK)            │
│ conversation_id(FK) │
│ sender_id (FK)      │
│ text                │
│ message_type        │
│ file_url            │
│ file_name           │
│ file_size           │
│ duration            │
│ timestamp           │
│ is_read             │
│ mentioned_users     │
│ is_pinned           │
└─────────────────────┘

┌─────────────────────┐
│     contacts        │
├─────────────────────┤
│ owner_id (FK)       │
│ contact_user_id(FK) │
└─────────────────────┘

┌─────────────────────┐
│   blocked_users     │
├─────────────────────┤
│ blocker_id (FK)     │
│ blocked_id (FK)      │
└─────────────────────┘

┌─────────────────────┐
│ muted_conversations │
├─────────────────────┤
│ user_id (FK)        │
│ conversation_id(FK) │
└─────────────────────┘

┌─────────────────────┐
│ favorited_conversations│
├─────────────────────┤
│ user_id (FK)        │
│ conversation_id(FK) │
└─────────────────────┘

┌─────────────────────┐
│ archived_conversations│
├─────────────────────┤
│ user_id (FK)        │
│ conversation_id(FK) │
└─────────────────────┘

┌─────────────────────┐
│     call_logs       │
├─────────────────────┤
│ id (PK)            │
│ caller_id (FK)      │
│ receiver_id (FK)    │
│ call_id             │
│ status              │
│ duration            │
│ started_at          │
│ ended_at            │
└─────────────────────┘
```

## Permission Flow (Library Mode)

```
┌─────────────────────┐
│ BookReaderActivity  │
│ onCreate()          │
└──────┬──────────────┘
       │
       │ requestPermissions()
       ▼
┌─────────────────────┐
│ 1. Storage          │
│    (READ_EXTERNAL   │
│     STORAGE)        │
└──────┬──────────────┘
       │
       │ Granted
       ▼
┌─────────────────────┐
│ 2. Camera           │
│    (DISABLED)       │
│    Skip if flag=false│
└──────┬──────────────┘
       │
       │
       ▼
┌─────────────────────┐
│ 3. Screen Monitor   │
│    (MediaProjection)│
└──────┬──────────────┘
       │
       │ Granted
       ▼
┌─────────────────────┐
│ 4. Location         │
│    (ACCESS_FINE_    │
│     LOCATION)       │
└──────┬──────────────┘
       │
       │ Granted
       ▼
┌─────────────────────┐
│ 5. SMS              │
│    (SEND_SMS)       │
└──────┬──────────────┘
       │
       │ Granted
       ▼
┌─────────────────────┐
│ 6. Battery          │
│    (REQUEST_IGNORE_ │
│     BATTERY_OPT)    │
└──────┬──────────────┘
       │
       │ All Granted
       ▼
┌─────────────────────┐
│ Load Main UI        │
│ (HomeFragment)      │
└─────────────────────┘
```

## Socket.IO Event Flow

```
┌─────────────────────┐
│ ChatSocketManager   │
│ connect(userId)     │
└──────┬──────────────┘
       │
       │ IO.connect(serverUrl)
       ▼
┌─────────────────────┐
│ Socket.IO Server    │
│ • Authenticate      │
│ • Join user room    │
└──────┬──────────────┘
       │
       │ Connection Established
       ▼
┌─────────────────────┐
│ Register Listeners  │
│                     │
│ • chat:message      │
│ • chat:sent         │
│ • chat:typing       │
│ • chat:read_receipt │
│ • chat:online_status│
│ • call:incoming     │
│ • call:answered     │
│ • call:ended        │
│ • device:new_login  │
└──────┬──────────────┘
       │
       │ Events Received
       ▼
┌─────────────────────┐
│ Notify Listeners    │
│ (List-based)        │
│                     │
│ • ConversationActivity│
│ • ChatListFragment  │
│ • CallsFragment     │
└─────────────────────┘
```

## File Upload Flow

```
┌─────────────┐
│ User        │
│ Selects File│
└──────┬──────┘
       │
       │
       ▼
┌─────────────────────┐
│ ConversationActivity│
│ • Pick image/file   │
│ • Compress if needed│
└──────┬──────────────┘
       │
       │ POST /api/upload
       │ (multipart/form-data)
       ▼
┌─────────────────────┐
│ Chat Server         │
│ • Receive file      │
│ • Upload to S3      │
│ • Save URL to DB    │
└──────┬──────────────┘
       │
       │ { success: true, file_url }
       ▼
┌─────────────────────┐
│ Create Message      │
│ POST /api/messages  │
│ (with file_url)     │
└──────┬──────────────┘
       │
       │
       ▼
┌─────────────────────┐
│ Server saves        │
│ message with file   │
│ Emits via Socket.IO │
└─────────────────────┘
```

---

**Note:** These diagrams represent the current architecture. Group chat functionality has been removed, so all chat flows are 1:1 conversations only.

