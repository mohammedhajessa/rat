package com.h4ckinto.android.ui.adapters;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.h4ckinto.android.R;

import java.util.List;
import java.util.Locale;

public class CallLogAdapter extends RecyclerView.Adapter<CallLogAdapter.ViewHolder> {

    public static class CallLogItem {
        public int otherUserId;
        public String otherName;
        public String otherInitials;
        public String otherAvatarColor;
        public boolean isOutgoing;
        public String status; // completed, missed, rejected, cancelled
        public int duration; // seconds
        public String timestamp;

        public CallLogItem(int otherUserId, String otherName, String otherInitials,
                           String otherAvatarColor, boolean isOutgoing, String status,
                           int duration, String timestamp) {
            this.otherUserId = otherUserId;
            this.otherName = otherName;
            this.otherInitials = otherInitials;
            this.otherAvatarColor = otherAvatarColor;
            this.isOutgoing = isOutgoing;
            this.status = status;
            this.duration = duration;
            this.timestamp = timestamp;
        }
    }

    public interface OnCallClickListener {
        void onCallClick(CallLogItem item);
    }

    private final List<CallLogItem> items;
    private OnCallClickListener clickListener;

    public CallLogAdapter(List<CallLogItem> items) {
        this.items = items;
    }

    public void setOnCallClickListener(OnCallClickListener l) {
        this.clickListener = l;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_call_log, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        CallLogItem item = items.get(position);

        h.nameText.setText(item.otherName);
        h.avatarText.setText(item.otherInitials);
        try { h.avatarText.getBackground().setTint(Color.parseColor(item.otherAvatarColor)); }
        catch (Exception e) { h.avatarText.getBackground().setTint(Color.parseColor("#3B82F6")); }

        // Status text
        String statusText;
        int statusColor;
        boolean isMissed = "missed".equals(item.status) || "rejected".equals(item.status);

        if ("completed".equals(item.status)) {
            String dur = formatDuration(item.duration);
            statusText = (item.isOutgoing ? "صادرة" : "واردة") + " • " + dur;
            statusColor = 0xFF8B9DC3;
        } else if ("missed".equals(item.status)) {
            statusText = item.isOutgoing ? "لم يرد" : "مكالمة فائتة";
            statusColor = 0xFFEF4444;
        } else if ("rejected".equals(item.status)) {
            statusText = item.isOutgoing ? "مرفوضة" : "مرفوضة";
            statusColor = 0xFFEF4444;
        } else {
            statusText = "ملغاة";
            statusColor = 0xFF8B9DC3;
        }

        h.statusText.setText(statusText);
        h.statusText.setTextColor(statusColor);

        // Direction icon
        h.directionIcon.setImageResource(item.isOutgoing ? R.drawable.ic_call_outgoing : R.drawable.ic_call_incoming);
        h.directionIcon.setColorFilter(isMissed ? 0xFFEF4444 : 0xFF10B981);

        // Time
        h.timeText.setText(formatTime(item.timestamp));

        // Call back on click
        h.callButton.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onCallClick(item);
        });

        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onCallClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String formatDuration(int seconds) {
        if (seconds < 60) return seconds + " ث";
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    private String formatTime(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return "";
        try {
            java.text.SimpleDateFormat inputFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            java.util.Date date = inputFormat.parse(timestamp);
            if (date == null) return timestamp;
            long diff = System.currentTimeMillis() - date.getTime();
            long dayMs = 86400000;
            if (diff < dayMs) {
                return new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US).format(date);
            } else if (diff < 2 * dayMs) {
                return "أمس";
            } else {
                return new java.text.SimpleDateFormat("dd/MM", Locale.getDefault()).format(date);
            }
        } catch (Exception e) { return timestamp; }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView avatarText, nameText, statusText, timeText;
        ImageView directionIcon, callButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatarText = itemView.findViewById(R.id.call_avatar);
            nameText = itemView.findViewById(R.id.call_name);
            statusText = itemView.findViewById(R.id.call_status_text);
            timeText = itemView.findViewById(R.id.call_time);
            directionIcon = itemView.findViewById(R.id.call_direction_icon);
            callButton = itemView.findViewById(R.id.btn_call_back);
        }
    }
}
