package com.h4ckinto.android.ui.adapters;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.h4ckinto.android.R;
import com.h4ckinto.android.utils.ImageLoader;

import java.util.List;

public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.ViewHolder> {

    public static class ChatItem {
        public String name;
        public String initials;
        public String lastMessage;
        public String timestamp;
        public int unreadCount;
        public boolean hasReadReceipt;
        public boolean lastMessageIsRead;
        public boolean lastMessageSentByMe;
        public int avatarColor;

        public int conversationId;
        public int otherUserId;
        public String avatarColorStr;
        public String avatarUrl;
        public boolean isOnline;
        public boolean isTyping;
        public boolean isMuted;
        public boolean isBlocked;
        public boolean isFavorited;
        public boolean isArchived;
        public String username;
        public String rank;
        public String jobTitle;

        public ChatItem(String name, String initials, String lastMessage, String timestamp,
                        int unreadCount, boolean hasReadReceipt, int avatarColor) {
            this.name = name;
            this.initials = initials;
            this.lastMessage = lastMessage;
            this.timestamp = timestamp;
            this.unreadCount = unreadCount;
            this.hasReadReceipt = hasReadReceipt;
            this.avatarColor = avatarColor;
            this.avatarColorStr = String.format("#%06X", (0xFFFFFF & avatarColor));
        }
    }

    public interface OnChatLongClickListener {
        void onChatLongClick(ChatItem item, int position);
    }

    public interface OnChatClickListener {
        void onChatClick(ChatItem item, int position);
    }

    private final List<ChatItem> items;
    private OnChatClickListener listener;
    private OnChatLongClickListener longClickListener;

    public ChatListAdapter(List<ChatItem> items) {
        this.items = items;
    }

    public void setOnChatLongClickListener(OnChatLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnChatClickListener(OnChatClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_conversation, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChatItem item = items.get(position);

        holder.contactName.setText(item.name);
        holder.timestamp.setText(item.timestamp);

        if (item.avatarUrl != null && !item.avatarUrl.isEmpty()) {
            holder.avatarText.setVisibility(View.INVISIBLE);
            holder.avatarImage.setVisibility(View.VISIBLE);
            holder.avatarImage.setClipToOutline(true);
            holder.avatarImage.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            ImageLoader.getInstance().load(item.avatarUrl, holder.avatarImage);
        } else {
            holder.avatarImage.setVisibility(View.GONE);
            holder.avatarText.setVisibility(View.VISIBLE);
            holder.avatarText.setText(item.initials);
            if (holder.avatarText.getBackground() != null) {
                holder.avatarText.getBackground().setTint(item.avatarColor);
            }
        }

        // Online indicator
        if (item.isOnline) {
            holder.onlineIndicator.setVisibility(View.VISIBLE);
        } else {
            holder.onlineIndicator.setVisibility(View.GONE);
        }

        // Favorite icon
        holder.favoriteIcon.setVisibility(item.isFavorited ? View.VISIBLE : View.GONE);

        // Mute icon
        holder.muteIcon.setVisibility(item.isMuted ? View.VISIBLE : View.GONE);

        boolean hasMessage = item.lastMessage != null
                && !item.lastMessage.isEmpty()
                && !"null".equals(item.lastMessage);

        // Typing or last message
        if (item.isTyping) {
            holder.messagePreview.setVisibility(View.VISIBLE);
            holder.messagePreview.setText("\u064A\u0643\u062A\u0628...");
            holder.messagePreview.setTextColor(Color.parseColor("#3B82F6"));
            holder.messagePreview.setTypeface(null, Typeface.ITALIC);
            holder.messagePreview.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        } else if (hasMessage) {
            holder.messagePreview.setVisibility(View.VISIBLE);
            applyMediaPreview(holder, item.lastMessage);
        } else {
            holder.messagePreview.setVisibility(View.GONE);
            holder.messagePreview.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        }

        // Hide timestamp for empty conversations
        holder.timestamp.setVisibility(hasMessage || item.isTyping ? View.VISIBLE : View.GONE);

        // Unread badge
        if (item.unreadCount > 0) {
            holder.unreadBadge.setVisibility(View.VISIBLE);
            holder.unreadBadge.setText(String.valueOf(item.unreadCount));
            holder.contactName.setTextColor(Color.WHITE);
            holder.messagePreview.setTypeface(null, item.isTyping ? Typeface.BOLD_ITALIC : Typeface.BOLD);
        } else {
            holder.unreadBadge.setVisibility(View.GONE);
            holder.contactName.setTextColor(holder.itemView.getContext()
                    .getResources().getColor(R.color.chat_text_primary, holder.itemView.getContext().getTheme()));
        }

        // Read receipt: only show for messages I sent
        if (item.lastMessageSentByMe && !item.isTyping && hasMessage) {
            holder.readReceipt.setVisibility(View.VISIBLE);
            if (item.lastMessageIsRead) {
                holder.readReceipt.setImageResource(R.drawable.ic_done_all);
                holder.readReceipt.setColorFilter(
                    holder.itemView.getContext().getResources().getColor(R.color.chat_accent_light, holder.itemView.getContext().getTheme()));
            } else {
                holder.readReceipt.setImageResource(R.drawable.ic_done);
                holder.readReceipt.setColorFilter(
                    holder.itemView.getContext().getResources().getColor(R.color.chat_text_tertiary, holder.itemView.getContext().getTheme()));
            }
        } else {
            holder.readReceipt.setVisibility(View.GONE);
        }

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onChatLongClick(item, position);
            }
            return true;
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChatClick(item, position);
            }
        });
    }

    private void applyMediaPreview(ViewHolder holder, String message) {
        android.content.Context ctx = holder.itemView.getContext();
        int iconRes = 0;
        String displayText = message;

        if (message != null) {
            String m = message.trim();
            if ("\u0635\u0648\u0631\u0629".equals(m) || m.contains("\uD83D\uDCF7") || m.contains("\uD83D\uDDBC")) {
                iconRes = R.drawable.ic_media_image;
                displayText = "\u0635\u0648\u0631\u0629";
            } else if ("\u0631\u0633\u0627\u0644\u0629 \u0635\u0648\u062A\u064A\u0629".equals(m) || m.contains("\uD83C\uDFA4")) {
                iconRes = R.drawable.ic_media_audio;
                displayText = "\u0631\u0633\u0627\u0644\u0629 \u0635\u0648\u062A\u064A\u0629";
            } else if ("\u0645\u0633\u062A\u0646\u062F".equals(m) || m.contains("\uD83D\uDCC4") || m.contains("\uD83D\uDCCE")) {
                iconRes = R.drawable.ic_media_document;
                displayText = "\u0645\u0633\u062A\u0646\u062F";
            }
        }

        holder.messagePreview.setText(displayText);
        holder.messagePreview.setTextColor(ctx.getResources().getColor(R.color.chat_text_secondary, ctx.getTheme()));
        holder.messagePreview.setTypeface(null, Typeface.NORMAL);

        if (iconRes != 0) {
            Drawable icon = ContextCompat.getDrawable(ctx, iconRes);
            if (icon != null) {
                icon = DrawableCompat.wrap(icon.mutate());
                int accentColor = ctx.getResources().getColor(R.color.chat_accent, ctx.getTheme());
                DrawableCompat.setTint(icon, accentColor);
                int size = (int) (holder.messagePreview.getTextSize() * 1.15f);
                icon.setBounds(0, 0, size, size);
            }
            holder.messagePreview.setCompoundDrawablesRelative(null, null, icon, null);
            holder.messagePreview.setCompoundDrawablePadding((int) (4 * ctx.getResources().getDisplayMetrics().density));
            holder.messagePreview.setTextColor(ctx.getResources().getColor(R.color.chat_accent, ctx.getTheme()));
        } else {
            holder.messagePreview.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView avatarText, contactName, messagePreview, timestamp, unreadBadge;
        ImageView readReceipt, avatarImage, muteIcon, favoriteIcon;
        View onlineIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatarText = itemView.findViewById(R.id.avatar_text);
            avatarImage = itemView.findViewById(R.id.avatar_image);
            contactName = itemView.findViewById(R.id.contact_name);
            messagePreview = itemView.findViewById(R.id.message_preview);
            timestamp = itemView.findViewById(R.id.timestamp);
            unreadBadge = itemView.findViewById(R.id.unread_badge);
            readReceipt = itemView.findViewById(R.id.read_receipt);
            onlineIndicator = itemView.findViewById(R.id.online_indicator);
            muteIcon = itemView.findViewById(R.id.mute_icon);
            favoriteIcon = itemView.findViewById(R.id.favorite_icon);
        }
    }
}
