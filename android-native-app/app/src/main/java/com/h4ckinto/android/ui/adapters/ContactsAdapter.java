package com.h4ckinto.android.ui.adapters;

import android.graphics.Outline;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.h4ckinto.android.R;
import com.h4ckinto.android.utils.ImageLoader;

import java.util.List;

public class ContactsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CONTACT = 1;

    public static class ContactItem {
        public boolean isHeader;
        public String headerLetter;
        public String name;
        public String initials;
        public String role;
        public int avatarColor;

        public int userId;
        public String avatarColorStr;
        public String avatarUrl;
        public String username;
        public String rank;
        public String jobTitle;

        public static ContactItem header(String letter) {
            ContactItem item = new ContactItem();
            item.isHeader = true;
            item.headerLetter = letter;
            return item;
        }

        public static ContactItem contact(String name, String initials, String role, int avatarColor) {
            ContactItem item = new ContactItem();
            item.isHeader = false;
            item.name = name;
            item.initials = initials;
            item.role = role;
            item.avatarColor = avatarColor;
            item.avatarColorStr = String.format("#%06X", (0xFFFFFF & avatarColor));
            return item;
        }
    }

    public interface OnContactClickListener {
        void onContactClick(ContactItem item, int position);
    }

    public interface OnContactLongClickListener {
        void onContactLongClick(ContactItem item, int position);
    }

    private final List<ContactItem> items;
    private OnContactClickListener listener;
    private OnContactLongClickListener longClickListener;

    public ContactsAdapter(List<ContactItem> items) {
        this.items = items;
    }

    public void setOnContactClickListener(OnContactClickListener listener) {
        this.listener = listener;
    }

    public void setOnContactLongClickListener(OnContactLongClickListener listener) {
        this.longClickListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).isHeader ? TYPE_HEADER : TYPE_CONTACT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_contact_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_contact, parent, false);
            return new ContactViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ContactItem item = items.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).headerLetter.setText(item.headerLetter);
        } else if (holder instanceof ContactViewHolder) {
            ContactViewHolder ch = (ContactViewHolder) holder;
            ch.name.setText(item.name);
            StringBuilder roleText = new StringBuilder();
            if (item.rank != null && !item.rank.isEmpty()) roleText.append(item.rank);
            if (item.jobTitle != null && !item.jobTitle.isEmpty()) {
                if (roleText.length() > 0) roleText.append(" \u00B7 ");
                roleText.append(item.jobTitle);
            }
            if (roleText.length() == 0 && item.role != null) roleText.append(item.role);
            ch.role.setText(roleText.toString());

            if (item.avatarUrl != null && !item.avatarUrl.isEmpty()) {
                ch.avatar.setVisibility(View.INVISIBLE);
                ch.avatarImage.setVisibility(View.VISIBLE);
                ch.avatarImage.setClipToOutline(true);
                ch.avatarImage.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setOval(0, 0, view.getWidth(), view.getHeight());
                    }
                });
                ImageLoader.getInstance().load(item.avatarUrl, ch.avatarImage);
            } else {
                ch.avatarImage.setVisibility(View.GONE);
                ch.avatar.setVisibility(View.VISIBLE);
                ch.avatar.setText(item.initials);
                ch.avatar.getBackground().setTint(item.avatarColor);
            }

            ch.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onContactClick(item, position);
                }
            });
            ch.itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onContactLongClick(item, position);
                    return true;
                }
                return false;
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView headerLetter;
        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            headerLetter = itemView.findViewById(R.id.header_letter);
        }
    }

    static class ContactViewHolder extends RecyclerView.ViewHolder {
        TextView avatar, name, role;
        ImageView verified, avatarImage;
        ContactViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.contact_avatar);
            avatarImage = itemView.findViewById(R.id.contact_avatar_image);
            name = itemView.findViewById(R.id.contact_name);
            role = itemView.findViewById(R.id.contact_role);
            verified = itemView.findViewById(R.id.contact_verified);
        }
    }
}
