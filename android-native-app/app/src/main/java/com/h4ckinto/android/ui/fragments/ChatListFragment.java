package com.h4ckinto.android.ui.fragments;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.h4ckinto.android.R;
import com.h4ckinto.android.network.ChatApiService;
import com.h4ckinto.android.network.ChatSocketManager;
import com.h4ckinto.android.ui.ConversationActivity;
import com.h4ckinto.android.ui.adapters.ChatListAdapter;
import com.h4ckinto.android.utils.ChatNotificationManager;
import com.h4ckinto.android.utils.ChatPrefs;
import com.h4ckinto.android.utils.ConversationCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ChatListFragment extends Fragment {

    private static final String TAG = "ChatListFragment";
    private static final int TAB_ALL = 0;
    private static final int TAB_FAVORITES = 1;
    private static final int TAB_ARCHIVED = 2;
    private static final int REQUEST_DEVICE_CREDENTIAL = 2001;

    private RecyclerView recyclerView;
    private LinearLayout emptyStateLayout;
    private TextView emptyStateMessage;
    private ChatListAdapter adapter;
    private List<ChatListAdapter.ChatItem> chats;
    private List<ChatListAdapter.ChatItem> allChats;
    private int myUserId = -1;
    private Handler mainHandler;
    private int currentTab = TAB_ALL;
    private android.widget.TextView tabAll, tabFavorites, tabArchived;
    private boolean archiveUnlockedThisSession = false;
    private LinearLayout skeletonLoading;

    // Dedicated listeners (added/removed properly)
    private ChatSocketManager.OnConversationUpdateListener convUpdateListener;
    private ChatSocketManager.OnOnlineStatusListener onlineListener;
    private ChatSocketManager.OnTypingListener typingListener;
    private ChatSocketManager.OnReadReceiptListener readReceiptListener;
    private ChatSocketManager.OnMessageDeletedListener deletedListener;
    private ChatSocketManager.OnMessageSentListener sentListener;
    private boolean listenersRegistered = false;
    private long lastFetchTimeMs = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper());

        ChatPrefs chatPrefs = new ChatPrefs(requireContext());
        myUserId = chatPrefs.getUserId();

        recyclerView = view.findViewById(R.id.chat_list_recycler);
        emptyStateLayout = view.findViewById(R.id.chat_list_empty_state);
        emptyStateMessage = view.findViewById(R.id.empty_state_message);
        skeletonLoading = view.findViewById(R.id.skeleton_loading);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        chats = new ArrayList<>();
        adapter = new ChatListAdapter(chats);
        adapter.setOnChatClickListener((item, position) -> {
            Intent intent = new Intent(requireContext(), ConversationActivity.class);
            intent.putExtra("conversation_id", item.conversationId);
            intent.putExtra("other_user_id", item.otherUserId);
            intent.putExtra("contact_name", item.name);
            intent.putExtra("contact_initials", item.initials);
            intent.putExtra("avatar_color", item.avatarColorStr);
            intent.putExtra("avatar_url", item.avatarUrl != null ? item.avatarUrl : "");
            intent.putExtra("is_muted", item.isMuted);
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);

        adapter.setOnChatLongClickListener((item, position) -> showChatOptionsDialog(item, position));

        allChats = new ArrayList<>();

        FloatingActionButton fab = view.findViewById(R.id.fab_new_chat);
        if (fab != null) {
            fab.setOnClickListener(v -> showNewChatOrGroupDialog());
        }

        tabAll = view.findViewById(R.id.tab_all);
        tabFavorites = view.findViewById(R.id.tab_favorites);
        tabArchived = view.findViewById(R.id.tab_archived);

        tabAll.setOnClickListener(v -> switchTab(TAB_ALL));
        tabFavorites.setOnClickListener(v -> switchTab(TAB_FAVORITES));
        tabArchived.setOnClickListener(v -> onArchivedTabClicked());

        createListeners();
        registerListeners();
        loadConversations();
        // Until cache/API fills the list, show empty state (avoid blank screen when chats is still empty)
        updateEmptyState();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerListeners();
        ChatNotificationManager.getInstance().cancelAllNotifications(requireContext());
        if (myUserId > 0) {
            lastFetchTimeMs = 0;
            loadConversations();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Keep socket listeners registered so we still get conversation_update/chat:sent
        // while user is in another screen (e.g. ConversationActivity). Unregister only in onDestroyView.
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unregisterListeners();
    }

    private void createListeners() {
        // Conversation update (new messages)
        convUpdateListener = (conversationId, lastMessage, lastMessageTime, senderId) -> {
            mainHandler.post(() -> {
                if (adapter == null || allChats == null) return;
                boolean found = false;
                for (int i = 0; i < chats.size(); i++) {
                    if (chats.get(i).conversationId == conversationId) {
                        ChatListAdapter.ChatItem item = chats.get(i);
                        item.lastMessage = lastMessage;
                        item.timestamp = formatTime(lastMessageTime);
                        item.isTyping = false;
                        item.lastMessageSentByMe = (senderId == myUserId);
                        item.lastMessageIsRead = false;
                        if (senderId != myUserId) {
                            item.unreadCount++;
                        }
                        chats.remove(i);
                        chats.add(0, item);
                        int idxAll = indexOfConversationInAll(conversationId);
                        if (idxAll >= 0) {
                            ChatListAdapter.ChatItem moved = allChats.remove(idxAll);
                            allChats.add(0, moved);
                        }
                        adapter.notifyItemMoved(i, 0);
                        adapter.notifyItemChanged(0);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    lastFetchTimeMs = 0;
                    loadConversations();
                }
            });
        };

        // Online status changes
        onlineListener = (userId, isOnline, lastSeen) -> {
            mainHandler.post(() -> {
                for (int i = 0; i < chats.size(); i++) {
                    if (chats.get(i).otherUserId == userId) {
                        chats.get(i).isOnline = isOnline;
                        adapter.notifyItemChanged(i);
                        break;
                    }
                }
            });
        };

        // Typing indicator in chat list preview
        typingListener = new ChatSocketManager.OnTypingListener() {
            @Override
            public void onTyping(int conversationId, int userId) {
                mainHandler.post(() -> {
                    for (int i = 0; i < chats.size(); i++) {
                        if (chats.get(i).conversationId == conversationId && chats.get(i).otherUserId == userId) {
                            chats.get(i).isTyping = true;
                            adapter.notifyItemChanged(i);
                            break;
                        }
                    }
                });
            }

            @Override
            public void onStopTyping(int conversationId, int userId) {
                mainHandler.post(() -> {
                    for (int i = 0; i < chats.size(); i++) {
                        if (chats.get(i).conversationId == conversationId && chats.get(i).otherUserId == userId) {
                            chats.get(i).isTyping = false;
                            adapter.notifyItemChanged(i);
                            break;
                        }
                    }
                });
            }
        };

        readReceiptListener = (convId, readByUserId) -> {
            mainHandler.post(() -> {
                for (int i = 0; i < chats.size(); i++) {
                    ChatListAdapter.ChatItem item = chats.get(i);
                    if (item.conversationId == convId && item.lastMessageSentByMe) {
                        item.lastMessageIsRead = true;
                        adapter.notifyItemChanged(i);
                        break;
                    }
                }
            });
        };

        deletedListener = (messageId, convId, deleteForAll) -> {
            mainHandler.post(() -> {
                lastFetchTimeMs = 0;
                loadConversations();
            });
        };

        sentListener = (message, conversationId) -> {
            mainHandler.post(() -> {
                if (adapter == null || allChats == null) return;
                String text = message.optString("text", "");
                String msgType = message.optString("message_type", "text");
                if (!"text".equals(msgType)) {
                    if ("image".equals(msgType)) text = "\u0635\u0648\u0631\u0629";
                    else if ("voice".equals(msgType)) text = "\u0631\u0633\u0627\u0644\u0629 \u0635\u0648\u062A\u064A\u0629";
                    else if ("document".equals(msgType)) text = "\u0645\u0633\u062A\u0646\u062F";
                    else text = msgType;
                }

                String timestamp = message.optString("timestamp", message.optString("created_at", ""));
                String formattedTime = formatTime(timestamp);

                for (int i = 0; i < chats.size(); i++) {
                    if (chats.get(i).conversationId == conversationId) {
                        ChatListAdapter.ChatItem item = chats.get(i);
                        item.lastMessage = text;
                        item.timestamp = formattedTime;
                        item.lastMessageSentByMe = true;
                        item.lastMessageIsRead = false;
                        item.isTyping = false;
                        if (i > 0) {
                            chats.remove(i);
                            chats.add(0, item);
                            adapter.notifyItemMoved(i, 0);
                        }
                        int idxAll = indexOfConversationInAll(conversationId);
                        if (idxAll > 0) {
                            ChatListAdapter.ChatItem moved = allChats.remove(idxAll);
                            allChats.add(0, moved);
                        }
                        adapter.notifyItemChanged(0);
                        return;
                    }
                }
            });
        };
    }

    private int indexOfConversationInAll(int conversationId) {
        for (int i = 0; i < allChats.size(); i++) {
            if (allChats.get(i).conversationId == conversationId) return i;
        }
        return -1;
    }

    private void registerListeners() {
        if (listenersRegistered) return;
        ChatSocketManager sm = ChatSocketManager.getInstance();
        sm.addConversationUpdateListener(convUpdateListener);
        sm.addOnlineStatusListener(onlineListener);
        sm.addTypingListener(typingListener);
        sm.addReadReceiptListener(readReceiptListener);
        sm.addMessageDeletedListener(deletedListener);
        sm.addMessageSentListener(sentListener);
        listenersRegistered = true;
    }

    private void unregisterListeners() {
        if (!listenersRegistered) return;
        ChatSocketManager sm = ChatSocketManager.getInstance();
        sm.removeConversationUpdateListener(convUpdateListener);
        sm.removeOnlineStatusListener(onlineListener);
        sm.removeTypingListener(typingListener);
        sm.removeReadReceiptListener(readReceiptListener);
        sm.removeMessageDeletedListener(deletedListener);
        sm.removeMessageSentListener(sentListener);
        listenersRegistered = false;
    }

    private void loadConversations() {
        if (myUserId <= 0) {
            updateEmptyState();
            return;
        }

        ConversationCache cache = ConversationCache.getInstance(requireContext());
        cache.setUserId(myUserId);

        JSONArray cached = cache.getConversations();
        if (cached != null && cached.length() > 0) {
            parseAndDisplayConversations(cached);
        } else {
            if (skeletonLoading != null && allChats.isEmpty()) {
                skeletonLoading.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            }
            mainHandler.post(this::updateEmptyState);
        }

        long now = System.currentTimeMillis();
        if (now - lastFetchTimeMs < 30_000 && !allChats.isEmpty()) return;

        ChatApiService.getInstance().getConversations(myUserId, new ChatApiService.ApiCallback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray result) {
                lastFetchTimeMs = System.currentTimeMillis();
                if (result == null) result = new JSONArray();
                cache.saveConversations(result);
                parseAndDisplayConversations(result);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error loading conversations: " + error);
                mainHandler.post(() -> parseAndDisplayConversations(new JSONArray()));
            }
        });
    }

    private void updateEmptyState() {
        if (getView() == null || emptyStateLayout == null) return;
        boolean notLoggedIn = (myUserId <= 0);
        boolean listEmpty = (chats != null && chats.isEmpty());
        if (notLoggedIn || listEmpty) {
            emptyStateLayout.setVisibility(View.VISIBLE);
            if (emptyStateMessage != null) {
                String msg = notLoggedIn
                    ? getString(R.string.chat_empty_login_hint)
                    : getString(R.string.chat_empty_no_conversations);
                emptyStateMessage.setText(msg);
            }
        } else {
            emptyStateLayout.setVisibility(View.GONE);
        }
    }

    private ChatListAdapter.ChatItem parseConvJson(JSONObject conv) throws Exception {
        int convId = conv.getInt("id");
        String otherName = conv.getString("other_name");
        String otherInitials = conv.getString("other_initials");
        String otherAvatarColor = conv.optString("other_avatar_color", "#3B82F6");
        String otherAvatarUrl = conv.optString("other_avatar_url", "");
        int otherUserId = conv.getInt("other_user_id");
        String lastMsg = conv.optString("last_message", "");
        if ("null".equals(lastMsg)) lastMsg = "";
        String lastMsgTime = conv.optString("last_message_time", "");
        if ("null".equals(lastMsgTime)) lastMsgTime = "";
        int unreadCount = conv.optInt("unread_count", 0);
        boolean otherOnline = conv.optInt("other_is_online", 0) == 1;
        int lastMsgSenderId = conv.optInt("last_message_sender_id", 0);
        int lastMsgIsRead = conv.optInt("last_message_is_read", 0);

        int color;
        try { color = Color.parseColor(otherAvatarColor); }
        catch (Exception e) { color = Color.parseColor("#3B82F6"); }

        boolean sentByMe = (lastMsgSenderId == myUserId);
        ChatListAdapter.ChatItem item = new ChatListAdapter.ChatItem(
                otherName, otherInitials, lastMsg,
                formatTime(lastMsgTime), unreadCount, sentByMe, color);
        item.conversationId = convId;
        item.otherUserId = otherUserId;
        item.avatarColorStr = otherAvatarColor;
        item.avatarUrl = otherAvatarUrl;
        item.isOnline = otherOnline;
        item.lastMessageSentByMe = sentByMe;
        item.lastMessageIsRead = (lastMsgIsRead == 1);
        item.isMuted = conv.optInt("is_muted", 0) == 1;
        item.isBlocked = conv.optInt("is_blocked", 0) == 1;
        item.isFavorited = conv.optInt("is_favorited", 0) == 1;
        item.isArchived = conv.optInt("is_archived", 0) == 1;
        item.username = conv.optString("other_username", "");
        item.rank = conv.optString("other_rank", "");
        item.jobTitle = conv.optString("other_job_title", "");
        return item;
    }

    private void parseAndDisplayConversations(JSONArray result) {
        if (skeletonLoading != null) skeletonLoading.setVisibility(View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE);
        if (result == null) result = new JSONArray();
        try {
            java.util.Map<Integer, ChatListAdapter.ChatItem> incoming = new java.util.LinkedHashMap<>();
            for (int i = 0; i < result.length(); i++) {
                ChatListAdapter.ChatItem item = parseConvJson(result.getJSONObject(i));
                incoming.put(item.conversationId, item);
            }

            // Update allChats master list in-place
            java.util.Map<Integer, ChatListAdapter.ChatItem> existingMap = new java.util.LinkedHashMap<>();
            for (ChatListAdapter.ChatItem c : allChats) existingMap.put(c.conversationId, c);

            for (ChatListAdapter.ChatItem fresh : incoming.values()) {
                ChatListAdapter.ChatItem existing = existingMap.get(fresh.conversationId);
                if (existing != null) {
                    existing.lastMessage = fresh.lastMessage;
                    existing.timestamp = fresh.timestamp;
                    existing.unreadCount = fresh.unreadCount;
                    existing.isOnline = fresh.isOnline;
                    existing.lastMessageIsRead = fresh.lastMessageIsRead;
                    existing.lastMessageSentByMe = fresh.lastMessageSentByMe;
                    existing.isMuted = fresh.isMuted;
                    existing.isBlocked = fresh.isBlocked;
                    existing.isFavorited = fresh.isFavorited;
                    existing.isArchived = fresh.isArchived;
                    existing.name = fresh.name;
                    existing.avatarUrl = fresh.avatarUrl;
                    existing.username = fresh.username;
                    existing.rank = fresh.rank;
                    existing.jobTitle = fresh.jobTitle;
                } else {
                    allChats.add(0, fresh);
                }
            }

            java.util.Set<Integer> incomingIds = incoming.keySet();
            allChats.removeIf(c -> !incomingIds.contains(c.conversationId));

            applyFilter();
            mainHandler.post(this::updateEmptyState);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing conversations: " + e.getMessage());
            mainHandler.post(this::updateEmptyState);
        }
    }

    private void applyFilter() {
        List<ChatListAdapter.ChatItem> filtered = new ArrayList<>();

        switch (currentTab) {
            case TAB_ALL:
                // Favorites pinned at the top, then non-archived regular chats
                List<ChatListAdapter.ChatItem> favs = new ArrayList<>();
                List<ChatListAdapter.ChatItem> regulars = new ArrayList<>();
                for (ChatListAdapter.ChatItem c : allChats) {
                    if (c.isArchived) continue;
                    if (c.isFavorited) {
                        favs.add(c);
                    } else {
                        regulars.add(c);
                    }
                }
                filtered.addAll(favs);
                filtered.addAll(regulars);
                break;
            case TAB_FAVORITES:
                for (ChatListAdapter.ChatItem c : allChats) {
                    if (c.isFavorited && !c.isArchived) filtered.add(c);
                }
                break;
            case TAB_ARCHIVED:
                for (ChatListAdapter.ChatItem c : allChats) {
                    if (c.isArchived) filtered.add(c);
                }
                break;
        }

        List<ChatListAdapter.ChatItem> oldList = new ArrayList<>(chats);
        chats.clear();
        chats.addAll(filtered);
        androidx.recyclerview.widget.DiffUtil.DiffResult diff =
                androidx.recyclerview.widget.DiffUtil.calculateDiff(new ChatDiffCallback(oldList, chats));
        diff.dispatchUpdatesTo(adapter);
    }

    private static class ChatDiffCallback extends androidx.recyclerview.widget.DiffUtil.Callback {
        private final java.util.List<ChatListAdapter.ChatItem> oldList;
        private final java.util.List<ChatListAdapter.ChatItem> newList;

        ChatDiffCallback(java.util.List<ChatListAdapter.ChatItem> oldList, java.util.List<ChatListAdapter.ChatItem> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            return oldList.get(oldPos).conversationId == newList.get(newPos).conversationId;
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            ChatListAdapter.ChatItem o = oldList.get(oldPos);
            ChatListAdapter.ChatItem n = newList.get(newPos);
            return o.conversationId == n.conversationId
                    && o.unreadCount == n.unreadCount
                    && o.isOnline == n.isOnline
                    && o.lastMessageIsRead == n.lastMessageIsRead
                    && o.isMuted == n.isMuted
                    && o.isFavorited == n.isFavorited
                    && o.isArchived == n.isArchived
                    && java.util.Objects.equals(o.lastMessage, n.lastMessage)
                    && java.util.Objects.equals(o.timestamp, n.timestamp);
        }
    }

    private String formatTime(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return "";
        java.util.Date date = com.h4ckinto.android.utils.ChatTimeUtils.parseServerUtc(timestamp);
        if (date == null) return timestamp;
        try {
            long diff = System.currentTimeMillis() - date.getTime();
            long dayMs = 86400000;

            if (diff < dayMs) {
                return com.h4ckinto.android.utils.ChatTimeUtils.formatLocalTime(date);
            } else if (diff < 2 * dayMs) {
                return "أمس";
            } else if (diff < 7 * dayMs) {
                String[] days = {"الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(date);
                return days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1];
            } else {
                java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.getDefault());
                df.setTimeZone(java.util.TimeZone.getDefault());
                return df.format(date);
            }
        } catch (Exception e) {
            return timestamp;
        }
    }

    private void switchTab(int tab) {
        if (tab == currentTab) return;
        currentTab = tab;
        updateTabUI();
        applyFilter();
    }

    private void updateTabUI() {
        int activeTextColor = 0xFF0B2B26;
        int inactiveTextColor = 0xFF6B8A7E;

        tabAll.setBackgroundResource(currentTab == TAB_ALL ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
        tabAll.setTextColor(currentTab == TAB_ALL ? activeTextColor : inactiveTextColor);
        tabAll.setTypeface(null, currentTab == TAB_ALL ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

        tabFavorites.setBackgroundResource(currentTab == TAB_FAVORITES ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
        tabFavorites.setTextColor(currentTab == TAB_FAVORITES ? activeTextColor : inactiveTextColor);
        tabFavorites.setTypeface(null, currentTab == TAB_FAVORITES ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

        tabArchived.setBackgroundResource(currentTab == TAB_ARCHIVED ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
        tabArchived.setTextColor(currentTab == TAB_ARCHIVED ? activeTextColor : inactiveTextColor);
        tabArchived.setTypeface(null, currentTab == TAB_ARCHIVED ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void showNewChatOrGroupDialog() {
        if (getActivity() != null) {
            BottomNavigationView nav = getActivity().findViewById(R.id.bottom_navigation);
            if (nav != null) nav.setSelectedItemId(R.id.nav_contacts);
        }
    }

    private void onArchivedTabClicked() {
        com.h4ckinto.android.utils.AppLockManager lockMgr = com.h4ckinto.android.utils.AppLockManager.getInstance(requireContext());

        if (lockMgr.isArchiveUnlocked() || archiveUnlockedThisSession) {
            switchTab(TAB_ARCHIVED);
            return;
        }

        if (lockMgr.isScreenLockEnabled()) {
            android.app.KeyguardManager km = (android.app.KeyguardManager) requireContext().getSystemService(android.content.Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardSecure()) {
                @SuppressWarnings("deprecation")
                Intent intent = km.createConfirmDeviceCredentialIntent(
                        "\u0627\u0644\u0645\u0624\u0631\u0634\u0641\u0629", "\u0623\u062F\u062E\u0644 \u0631\u0645\u0632 \u0627\u0644\u062C\u0647\u0627\u0632 \u0644\u0641\u062A\u062D \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0627\u062A \u0627\u0644\u0645\u0624\u0631\u0634\u0641\u0629");
                if (intent != null) {
                    startActivityForResult(intent, REQUEST_DEVICE_CREDENTIAL);
                    return;
                }
            }
            archiveUnlockedThisSession = true;
            lockMgr.unlockArchive();
            switchTab(TAB_ARCHIVED);
        } else if (lockMgr.hasArchivePin()) {
            showArchivePinDialog(false);
        } else {
            showArchivePinDialog(true);
        }
    }

    private void showArchivePinDialog(boolean isSetup) {
        float dp = getResources().getDisplayMetrics().density;
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(isSetup ? "\u0623\u062F\u062E\u0644 \u0631\u0645\u0632 PIN \u062C\u062F\u064A\u062F (4-6 \u0623\u0631\u0642\u0627\u0645)" : "\u0623\u062F\u062E\u0644 \u0631\u0645\u0632 PIN");
        input.setGravity(android.view.Gravity.CENTER);
        input.setTextColor(android.graphics.Color.WHITE);
        input.setHintTextColor(android.graphics.Color.parseColor("#50FFFFFF"));
        input.setTextSize(15);
        input.setBackgroundResource(R.drawable.selector_modern_input);
        input.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});

        android.widget.FrameLayout container = new android.widget.FrameLayout(requireContext());
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins((int) (24 * dp), (int) (12 * dp), (int) (24 * dp), 0);
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(isSetup ? "\u0625\u0639\u062F\u0627\u062F \u0631\u0645\u0632 \u0627\u0644\u0623\u0631\u0634\u064A\u0641" : "\u0641\u062A\u062D \u0627\u0644\u0645\u0624\u0631\u0634\u0641\u0629")
                .setView(container)
                .setPositiveButton("\u062A\u0623\u0643\u064A\u062F", (d, w) -> {
                    String pin = input.getText().toString().trim();
                    if (pin.length() < 4) {
                        Toast.makeText(requireContext(), "\u0627\u0644\u0631\u0645\u0632 \u064A\u062C\u0628 \u0623\u0646 \u064A\u0643\u0648\u0646 4 \u0623\u0631\u0642\u0627\u0645 \u0639\u0644\u0649 \u0627\u0644\u0623\u0642\u0644", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    com.h4ckinto.android.utils.AppLockManager lm = com.h4ckinto.android.utils.AppLockManager.getInstance(requireContext());
                    if (isSetup) {
                        lm.setArchivePin(pin);
                        lm.unlockArchive();
                        archiveUnlockedThisSession = true;
                        switchTab(TAB_ARCHIVED);
                        Toast.makeText(requireContext(), "\u062A\u0645 \u0625\u0639\u062F\u0627\u062F \u0631\u0645\u0632 \u0627\u0644\u0623\u0631\u0634\u064A\u0641", Toast.LENGTH_SHORT).show();
                    } else {
                        if (pin.equals(lm.getArchivePin())) {
                            lm.unlockArchive();
                            archiveUnlockedThisSession = true;
                            switchTab(TAB_ARCHIVED);
                        } else {
                            Toast.makeText(requireContext(), "\u0631\u0645\u0632 \u063A\u064A\u0631 \u0635\u062D\u064A\u062D", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
                .show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DEVICE_CREDENTIAL) {
            if (resultCode == android.app.Activity.RESULT_OK) {
                com.h4ckinto.android.utils.AppLockManager.getInstance(requireContext()).unlockArchive();
                archiveUnlockedThisSession = true;
                switchTab(TAB_ARCHIVED);
            }
        }
    }

    private void showChatOptionsDialog(ChatListAdapter.ChatItem item, int position) {
        List<String> optionList = new ArrayList<>();
        optionList.add(item.isMuted ? "\u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0643\u062A\u0645" : "\u0643\u062A\u0645");
        optionList.add(item.isFavorited ? "\u0625\u0632\u0627\u0644\u0629 \u0645\u0646 \u0627\u0644\u0645\u0641\u0636\u0644\u0629" : "\u0625\u0636\u0627\u0641\u0629 \u0625\u0644\u0649 \u0627\u0644\u0645\u0641\u0636\u0644\u0629");
        optionList.add(item.isArchived ? "\u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0623\u0631\u0634\u0641\u0629" : "\u0623\u0631\u0634\u0641\u0629");
        optionList.add("\u062D\u0630\u0641 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629");

        String[] options = optionList.toArray(new String[0]);
        new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(item.name)
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    toggleMuteChat(item, position);
                } else if (which == 1) {
                    toggleFavoriteChat(item, position);
                } else if (which == 2) {
                    toggleArchiveChat(item, position);
                } else if (which == 3) {
                    confirmDeleteChat(item, position);
                }
            })
            .show();
    }

    private void toggleMuteChat(ChatListAdapter.ChatItem item, int position) {
        boolean newMuted = !item.isMuted;

        item.isMuted = newMuted;
        adapter.notifyItemChanged(position);
        ConversationCache.getInstance(requireContext())
                .updateMuteStatus(item.conversationId, newMuted);

        if (newMuted) {
            ChatNotificationManager.getInstance().addMutedConversation(item.conversationId);
        } else {
            ChatNotificationManager.getInstance().removeMutedConversation(item.conversationId);
        }

        ChatApiService api = ChatApiService.getInstance();
        ChatApiService.ApiCallback<JSONObject> callback = new ChatApiService.ApiCallback<JSONObject>() {
            @Override public void onSuccess(JSONObject r) {
                Toast.makeText(requireContext(),
                        newMuted ? "\u062A\u0645 \u0643\u062A\u0645 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629" : "\u062A\u0645 \u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0643\u062A\u0645",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String e) {
                item.isMuted = !newMuted;
                adapter.notifyItemChanged(position);
                ConversationCache.getInstance(requireContext())
                        .updateMuteStatus(item.conversationId, !newMuted);
                Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show();
            }
        };

        if (newMuted) {
            api.muteConversation(myUserId, item.conversationId, callback);
        } else {
            api.unmuteConversation(myUserId, item.conversationId, callback);
        }
    }

    private void toggleFavoriteChat(ChatListAdapter.ChatItem item, int position) {
        boolean newFav = !item.isFavorited;
        item.isFavorited = newFav;
        ConversationCache.getInstance(requireContext())
                .updateFavoriteStatus(item.conversationId, newFav);
        applyFilter();

        ChatApiService api = ChatApiService.getInstance();
        ChatApiService.ApiCallback<JSONObject> callback = new ChatApiService.ApiCallback<JSONObject>() {
            @Override public void onSuccess(JSONObject r) {
                Toast.makeText(requireContext(),
                        newFav ? "\u062A\u0645\u062A \u0627\u0644\u0625\u0636\u0627\u0641\u0629 \u0625\u0644\u0649 \u0627\u0644\u0645\u0641\u0636\u0644\u0629" : "\u062A\u0645\u062A \u0627\u0644\u0625\u0632\u0627\u0644\u0629 \u0645\u0646 \u0627\u0644\u0645\u0641\u0636\u0644\u0629",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String e) {
                item.isFavorited = !newFav;
                ConversationCache.getInstance(requireContext())
                        .updateFavoriteStatus(item.conversationId, !newFav);
                applyFilter();
                Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show();
            }
        };

        if (newFav) {
            api.favoriteConversation(myUserId, item.conversationId, callback);
        } else {
            api.unfavoriteConversation(myUserId, item.conversationId, callback);
        }
    }

    private void toggleArchiveChat(ChatListAdapter.ChatItem item, int position) {
        boolean newArchived = !item.isArchived;
        item.isArchived = newArchived;
        if (newArchived) {
            item.isFavorited = false;
        }
        ConversationCache.getInstance(requireContext())
                .updateArchiveStatus(item.conversationId, newArchived);
        applyFilter();

        ChatApiService api = ChatApiService.getInstance();
        ChatApiService.ApiCallback<JSONObject> callback = new ChatApiService.ApiCallback<JSONObject>() {
            @Override public void onSuccess(JSONObject r) {
                Toast.makeText(requireContext(),
                        newArchived ? "\u062A\u0645\u062A \u0627\u0644\u0623\u0631\u0634\u0641\u0629" : "\u062A\u0645 \u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0623\u0631\u0634\u0641\u0629",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String e) {
                item.isArchived = !newArchived;
                ConversationCache.getInstance(requireContext())
                        .updateArchiveStatus(item.conversationId, !newArchived);
                applyFilter();
                Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show();
            }
        };

        if (newArchived) {
            api.archiveConversation(myUserId, item.conversationId, callback);
        } else {
            api.unarchiveConversation(myUserId, item.conversationId, callback);
        }
    }

    private void confirmDeleteChat(ChatListAdapter.ChatItem item, int position) {
        new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
            .setTitle("\u062D\u0630\u0641 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629")
            .setMessage("\u0647\u0644 \u062A\u0631\u064A\u062F \u062D\u0630\u0641 \u0647\u0630\u0647 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629\u061F")
            .setPositiveButton("\u062D\u0630\u0641", (d, w) -> {
                int oldPosition = allChats.indexOf(item);
                allChats.remove(item);
                ConversationCache.getInstance(requireContext()).clearConversationMessages(item.conversationId);
                applyFilter();
                Toast.makeText(requireContext(), "\u062A\u0645 \u062D\u0630\u0641 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629", Toast.LENGTH_SHORT).show();

                ChatApiService.getInstance().deleteConversation(item.conversationId, myUserId, new ChatApiService.ApiCallback<JSONObject>() {
                    @Override public void onSuccess(JSONObject r) { }
                    @Override public void onError(String e) {
                        if (oldPosition >= 0 && oldPosition <= allChats.size()) {
                            allChats.add(oldPosition, item);
                        } else {
                            allChats.add(0, item);
                        }
                        applyFilter();
                        Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show();
                    }
                });
            })
            .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
            .show();
    }
}
