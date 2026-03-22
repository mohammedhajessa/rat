package com.h4ckinto.android.ui.adapters;

import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.h4ckinto.android.R;
import com.h4ckinto.android.ui.ImageViewerActivity;
import com.h4ckinto.android.ui.views.VoiceWaveformView;
import com.h4ckinto.android.utils.ImageLoader;
import com.h4ckinto.android.utils.MediaCacheManager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.List;
import java.util.Locale;

public class MessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SENT_TEXT = 0;
    private static final int TYPE_RECEIVED_TEXT = 1;
    private static final int TYPE_SENT_IMAGE = 2;
    private static final int TYPE_RECEIVED_IMAGE = 3;
    private static final int TYPE_SENT_VOICE = 4;
    private static final int TYPE_RECEIVED_VOICE = 5;
    private static final int TYPE_SENT_DOCUMENT = 6;
    private static final int TYPE_RECEIVED_DOCUMENT = 7;
    private static final int TYPE_DATE_SEPARATOR = 8;

    public static class Message {
        public int id = -1;
        public String text;
        public String time;
        public String rawDate;
        public boolean isSent;
        public boolean isRead;
        public String messageType;
        public String fileUrl;
        public String serverFileUrl;
        public String fileName;
        public long fileSize;
        public int duration;
        public boolean isDateSeparator = false;
        public String localFilePath;

        public Message(String text, String time, boolean isSent, boolean isRead) {
            this.text = text;
            this.time = time;
            this.isSent = isSent;
            this.isRead = isRead;
            this.messageType = "text";
        }

        public Message(String text, String time, boolean isSent, boolean isRead,
                       String messageType, String fileUrl, String fileName, long fileSize, int duration) {
            this.text = text;
            this.time = time;
            this.isSent = isSent;
            this.isRead = isRead;
            this.messageType = messageType != null ? messageType : "text";
            this.fileUrl = fileUrl;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.duration = duration;
        }

        public static Message createDateSeparator(String dateLabel) {
            Message m = new Message("", "", false, false);
            m.isDateSeparator = true;
            m.messageType = "date_separator";
            m.text = dateLabel;
            return m;
        }
    }

    public interface OnMessageLongClickListener {
        void onMessageLongClick(Message message, int position);
    }

    private OnMessageLongClickListener longClickListener;

    private static final String TAG = "MessagesAdapter";
    private final List<Message> messages;
    private MediaPlayer currentPlayer;
    private int currentPlayingPos = -1;
    private Handler playHandler = new Handler(Looper.getMainLooper());
    private ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();

    public MessagesAdapter(List<Message> messages) {
        this.messages = messages;
    }

    public void setOnMessageLongClickListener(OnMessageLongClickListener l) {
        this.longClickListener = l;
    }

    @Override
    public int getItemViewType(int position) {
        Message msg = messages.get(position);
        if (msg.isDateSeparator) return TYPE_DATE_SEPARATOR;
        String type = msg.messageType != null ? msg.messageType : "text";
        switch (type) {
            case "image":
                return msg.isSent ? TYPE_SENT_IMAGE : TYPE_RECEIVED_IMAGE;
            case "voice":
                return msg.isSent ? TYPE_SENT_VOICE : TYPE_RECEIVED_VOICE;
            case "document":
                return msg.isSent ? TYPE_SENT_DOCUMENT : TYPE_RECEIVED_DOCUMENT;
            default:
                return msg.isSent ? TYPE_SENT_TEXT : TYPE_RECEIVED_TEXT;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_DATE_SEPARATOR:
                return new DateSeparatorViewHolder(inflater.inflate(R.layout.item_date_separator, parent, false));
            case TYPE_SENT_IMAGE:
                return new ImageViewHolder(inflater.inflate(R.layout.item_message_sent_image, parent, false));
            case TYPE_RECEIVED_IMAGE:
                return new ImageViewHolder(inflater.inflate(R.layout.item_message_received_image, parent, false));
            case TYPE_SENT_VOICE:
                return new VoiceViewHolder(inflater.inflate(R.layout.item_message_sent_voice, parent, false));
            case TYPE_RECEIVED_VOICE:
                return new VoiceViewHolder(inflater.inflate(R.layout.item_message_received_voice, parent, false));
            case TYPE_SENT_DOCUMENT:
                return new DocumentViewHolder(inflater.inflate(R.layout.item_message_sent_document, parent, false));
            case TYPE_RECEIVED_DOCUMENT:
                return new DocumentViewHolder(inflater.inflate(R.layout.item_message_received_document, parent, false));
            case TYPE_SENT_TEXT:
                return new SentViewHolder(inflater.inflate(R.layout.item_message_sent, parent, false));
            default:
                return new ReceivedViewHolder(inflater.inflate(R.layout.item_message_received, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message msg = messages.get(position);

        if (holder instanceof DateSeparatorViewHolder) {
            ((DateSeparatorViewHolder) holder).dateLabel.setText(msg.text);
            return;
        }

        // Long-click for delete on all message types
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null && msg.id > 0) {
                longClickListener.onMessageLongClick(msg, position);
                return true;
            }
            return false;
        });

        if (holder instanceof SentViewHolder) {
            SentViewHolder sh = (SentViewHolder) holder;
            sh.messageText.setText(msg.text);
            sh.messageTime.setText(msg.time);
            updateReadReceipt(sh.readReceipt, msg.isRead);
        } else if (holder instanceof ReceivedViewHolder) {
            ReceivedViewHolder rh = (ReceivedViewHolder) holder;
            rh.messageText.setText(msg.text);
            rh.messageTime.setText(msg.time);
        } else if (holder instanceof ImageViewHolder) {
            bindImage((ImageViewHolder) holder, msg);
        } else if (holder instanceof VoiceViewHolder) {
            bindVoice((VoiceViewHolder) holder, msg, position);
        } else if (holder instanceof DocumentViewHolder) {
            bindDocument((DocumentViewHolder) holder, msg);
        }
    }

    private void loadBitmapInto(ImageView imageView, File file) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            int w = opts.outWidth, ht = opts.outHeight;
            int sampleSize = 1;
            while ((ht / sampleSize) > 720 || (w / sampleSize) > 720) {
                sampleSize *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp != null) {
                imageView.setImageBitmap(bmp);
            } else {
                imageView.setImageResource(R.drawable.bg_image_placeholder);
            }
        } catch (Exception e) {
            Log.e(TAG, "loadBitmapInto error: " + e.getMessage());
            imageView.setImageResource(R.drawable.bg_image_placeholder);
        }
    }

    private File resolveLocalFile(Message msg, Context ctx) {
        if (msg.localFilePath != null && !msg.localFilePath.isEmpty()) {
            File f = new File(msg.localFilePath);
            if (f.exists()) return f;
        }
        if (msg.fileUrl != null && !msg.fileUrl.isEmpty() && msg.fileUrl.startsWith("/")) {
            File f = new File(msg.fileUrl);
            if (f.exists()) return f;
        }
        MediaCacheManager mc = MediaCacheManager.getInstance(ctx);
        if (msg.serverFileUrl != null && !msg.serverFileUrl.isEmpty()) {
            File f = mc.getLocalFile(msg.serverFileUrl);
            if (f != null) return f;
        }
        if (msg.fileUrl != null && !msg.fileUrl.isEmpty()) {
            File f = mc.getLocalFile(msg.fileUrl);
            if (f != null) return f;
        }
        return null;
    }

    private void bindImage(ImageViewHolder h, Message msg) {
        h.messageTime.setText(msg.time);
        if (h.readReceipt != null && msg.isSent) {
            updateReadReceipt(h.readReceipt, msg.isRead);
        } else if (h.readReceipt != null) {
            h.readReceipt.setVisibility(View.GONE);
        }
        if (msg.text != null && !msg.text.isEmpty()) {
            h.caption.setVisibility(View.VISIBLE);
            h.caption.setText(msg.text);
        } else {
            h.caption.setVisibility(View.GONE);
        }

        float radiusDp = 18f;
        float radiusPx = radiusDp * h.imageView.getContext().getResources().getDisplayMetrics().density;
        h.imageView.setClipToOutline(true);
        h.imageView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });

        h.imageView.setImageResource(R.drawable.bg_image_placeholder);
        Context ctx = h.imageView.getContext();

        File localFile = resolveLocalFile(msg, ctx);
        if (localFile != null) {
            h.progressBar.setVisibility(View.GONE);
            loadBitmapInto(h.imageView, localFile);
            h.imageView.setOnClickListener(v ->
                    v.getContext().startActivity(ImageViewerActivity.create(v.getContext(), "file://" + localFile.getAbsolutePath())));
            return;
        }

        String displayUrl = msg.fileUrl;
        if (displayUrl == null || displayUrl.isEmpty()) displayUrl = msg.serverFileUrl;

        if (displayUrl != null && !displayUrl.isEmpty()) {
            h.progressBar.setVisibility(View.VISIBLE);
            final String finalUrl = displayUrl;
            ImageLoader.getInstance().load(displayUrl, h.imageView, () -> h.progressBar.setVisibility(View.GONE));
            h.imageView.setOnClickListener(v -> {
                Context ctx2 = v.getContext();
                String viewUrl = (msg.serverFileUrl != null && !msg.serverFileUrl.isEmpty())
                        ? msg.serverFileUrl : finalUrl;
                MediaCacheManager mc2 = MediaCacheManager.getInstance(ctx2);
                File lf = mc2.getLocalFile(viewUrl);
                if (lf == null) lf = mc2.getLocalFile(finalUrl);
                if (lf != null) {
                    ctx2.startActivity(ImageViewerActivity.create(ctx2, "file://" + lf.getAbsolutePath()));
                } else {
                    ctx2.startActivity(ImageViewerActivity.create(ctx2, viewUrl));
                }
            });
        } else {
            h.progressBar.setVisibility(View.GONE);
        }
    }

    private void bindVoice(VoiceViewHolder h, Message msg, int position) {
        h.messageTime.setText(msg.time);
        if (h.readReceipt != null && msg.isSent) {
            updateReadReceipt(h.readReceipt, msg.isRead);
        } else if (h.readReceipt != null) {
            h.readReceipt.setVisibility(View.GONE);
        }
        h.durationText.setText(formatDuration(msg.duration));
        h.progressBar.setProgress(0);

        if (h.waveformView != null) {
            h.waveformView.setSentStyle(msg.isSent);
            h.waveformView.setSeed(msg.id > 0 ? msg.id : position * 7919L);
            h.waveformView.resetProgress();
        }

        boolean isPlaying = (currentPlayingPos == position && currentPlayer != null && currentPlayer.isPlaying());
        h.btnPlay.setImageResource(isPlaying ? R.drawable.ic_pause_voice : R.drawable.ic_play_voice);

        h.btnPlay.setOnClickListener(v -> {
            if (currentPlayingPos == position && currentPlayer != null) {
                try {
                    if (currentPlayer.isPlaying()) {
                        currentPlayer.pause();
                        h.btnPlay.setImageResource(R.drawable.ic_play_voice);
                    } else {
                        currentPlayer.start();
                        h.btnPlay.setImageResource(R.drawable.ic_pause_voice);
                        startProgressUpdate(h, position);
                    }
                } catch (Exception e) {
                    stopCurrentPlayback();
                }
                return;
            }
            stopCurrentPlayback();

            if (msg.localFilePath != null && !msg.localFilePath.isEmpty()) {
                File lf = new File(msg.localFilePath);
                if (lf.exists()) {
                    playLocalVoice(h, msg, position, lf.getAbsolutePath());
                    return;
                }
            }

            String voiceUrl = msg.fileUrl;
            if ((voiceUrl == null || voiceUrl.isEmpty()) && msg.serverFileUrl != null) {
                voiceUrl = msg.serverFileUrl;
            }
            if (voiceUrl == null || voiceUrl.isEmpty()) return;

            boolean isLocal = !voiceUrl.startsWith("http");
            if (isLocal) {
                playLocalVoice(h, msg, position, voiceUrl);
            } else {
                MediaCacheManager mc = MediaCacheManager.getInstance(v.getContext());
                File cachedVoice = mc.getLocalFile(voiceUrl);
                if (cachedVoice == null && msg.serverFileUrl != null) {
                    cachedVoice = mc.getLocalFile(msg.serverFileUrl);
                }
                if (cachedVoice != null) {
                    playLocalVoice(h, msg, position, cachedVoice.getAbsolutePath());
                    return;
                }
                h.btnPlay.setEnabled(false);
                h.durationText.setText("...");
                final String finalVoiceUrl = voiceUrl;
                downloadExecutor.execute(() -> {
                    File localFile = downloadToCache(v.getContext(), finalVoiceUrl, "voice_" + position + ".m4a");
                    playHandler.post(() -> {
                        h.btnPlay.setEnabled(true);
                        h.durationText.setText(formatDuration(msg.duration));
                        if (localFile == null) {
                            Log.e(TAG, "Failed to download voice: " + msg.fileUrl);
                            Toast.makeText(v.getContext(), "فشل تحميل الرسالة الصوتية", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        playLocalVoice(h, msg, position, localFile.getAbsolutePath());
                    });
                });
            }
        });
    }

    private void playLocalVoice(VoiceViewHolder h, Message msg, int position, String filePath) {
        try {
            currentPlayer = new MediaPlayer();
            currentPlayer.setDataSource(filePath);
            currentPlayer.setOnPreparedListener(mp -> {
                currentPlayingPos = position;
                mp.start();
                h.btnPlay.setImageResource(R.drawable.ic_pause_voice);
                int dur = mp.getDuration();
                h.progressBar.setMax(dur);
                if (h.waveformView != null) {
                    h.waveformView.setMax(dur);
                }
                startProgressUpdate(h, position);
            });
            currentPlayer.setOnCompletionListener(mp -> {
                h.btnPlay.setImageResource(R.drawable.ic_play_voice);
                h.progressBar.setProgress(0);
                if (h.waveformView != null) {
                    h.waveformView.resetProgress();
                }
                currentPlayingPos = -1;
                stopCurrentPlayback();
            });
            currentPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + "/" + extra);
                h.btnPlay.setImageResource(R.drawable.ic_play_voice);
                currentPlayingPos = -1;
                stopCurrentPlayback();
                return true;
            });
            currentPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Voice play error: " + e.getMessage());
        }
    }

    private void startProgressUpdate(VoiceViewHolder h, int position) {
        playHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentPlayer != null && currentPlayingPos == position && currentPlayer.isPlaying()) {
                    int pos = currentPlayer.getCurrentPosition();
                    h.progressBar.setProgress(pos);
                    if (h.waveformView != null) {
                        h.waveformView.setProgress(pos);
                    }
                    playHandler.postDelayed(this, 50);
                }
            }
        }, 50);
    }

    private void stopCurrentPlayback() {
        if (currentPlayer != null) {
            try {
                if (currentPlayer.isPlaying()) currentPlayer.stop();
                currentPlayer.release();
            } catch (Exception e) { /* ignore */ }
            currentPlayer = null;
            currentPlayingPos = -1;
        }
    }

    private void bindDocument(DocumentViewHolder h, Message msg) {
        h.messageTime.setText(msg.time);
        if (h.readReceipt != null && msg.isSent) {
            updateReadReceipt(h.readReceipt, msg.isRead);
        } else if (h.readReceipt != null) {
            h.readReceipt.setVisibility(View.GONE);
        }
        String displayName = msg.fileName != null ? msg.fileName : "مستند";
        h.fileName.setText(displayName);
        h.fileSize.setText(formatFileSize(msg.fileSize));

        h.itemView.setOnClickListener(v -> {
            Context ctx = v.getContext();

            if (msg.localFilePath != null && !msg.localFilePath.isEmpty()) {
                File lf = new File(msg.localFilePath);
                if (lf.exists()) {
                    openDocumentFile(ctx, lf, displayName);
                    return;
                }
            }

            String docUrl = msg.fileUrl;
            if ((docUrl == null || docUrl.isEmpty()) && msg.serverFileUrl != null) {
                docUrl = msg.serverFileUrl;
            }
            if (docUrl == null || docUrl.isEmpty()) return;

            MediaCacheManager mc = MediaCacheManager.getInstance(ctx);
            File quickCached = mc.getLocalFile(docUrl);
            if (quickCached == null && msg.serverFileUrl != null) {
                quickCached = mc.getLocalFile(msg.serverFileUrl);
            }
            if (quickCached != null) {
                openDocumentFile(ctx, quickCached, displayName);
                return;
            }

            Toast.makeText(ctx, "\u062C\u0627\u0631\u064A \u0627\u0644\u062A\u062D\u0645\u064A\u0644...", Toast.LENGTH_SHORT).show();

            final String finalDocUrl = docUrl;
            downloadExecutor.execute(() -> {
                File localFile = downloadToCache(ctx, finalDocUrl, displayName);
                playHandler.post(() -> {
                    if (localFile == null) {
                        Toast.makeText(ctx, "فشل تحميل المستند", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    openDocumentFile(ctx, localFile, displayName);
                });
            });
        });
    }

    private void openDocumentFile(Context ctx, File file, String displayName) {
        try {
            Uri contentUri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".fileprovider", file);
            String mime = getMimeType(displayName);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Open document error: " + e.getMessage());
            Toast.makeText(ctx, "لا يوجد تطبيق لفتح هذا الملف", Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "application/msword";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "application/vnd.ms-excel";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private File downloadToCache(Context ctx, String urlStr, String fileName) {
        try {
            MediaCacheManager mc = MediaCacheManager.getInstance(ctx);
            File existing = mc.getLocalFile(urlStr);
            if (existing != null) return existing;

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Android-Chat/1.0");
            conn.connect();

            if (conn.getResponseCode() != 200) {
                Log.e(TAG, "Download failed: HTTP " + conn.getResponseCode() + " for " + urlStr);
                conn.disconnect();
                return null;
            }

            String type = "document";
            if (urlStr.contains("/voice/") || fileName.endsWith(".m4a")) type = "voice";
            else if (urlStr.contains("/images/") || fileName.endsWith(".jpg") || fileName.endsWith(".png")) type = "image";

            File cached = mc.cacheFromStream(urlStr, conn.getInputStream(), type);
            conn.disconnect();

            if (cached != null) {
                Log.d(TAG, "Downloaded + cached " + cached.length() + " bytes");
            }
            return cached;
        } catch (Exception e) {
            Log.e(TAG, "Download error: " + e.getMessage());
            return null;
        }
    }

    private String formatDuration(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }


    private void updateReadReceipt(ImageView receipt, boolean isRead) {
        if (receipt == null) return;
        receipt.setVisibility(View.VISIBLE);
        if (isRead) {
            receipt.setImageResource(R.drawable.ic_done_all);
            receipt.setColorFilter(receipt.getContext().getResources().getColor(R.color.chat_accent_light, receipt.getContext().getTheme()));
        } else {
            receipt.setImageResource(R.drawable.ic_done);
            receipt.setColorFilter(receipt.getContext().getResources().getColor(R.color.chat_text_tertiary, receipt.getContext().getTheme()));
        }
    }

    public void release() {
        stopCurrentPlayback();
    }

    // ── View Holders ──

    static class SentViewHolder extends RecyclerView.ViewHolder {
        TextView messageText, messageTime;
        ImageView readReceipt;
        SentViewHolder(@NonNull View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.message_text);
            messageTime = itemView.findViewById(R.id.message_time);
            readReceipt = itemView.findViewById(R.id.read_receipt);
        }
    }

    static class ReceivedViewHolder extends RecyclerView.ViewHolder {
        TextView messageText, messageTime;
        ReceivedViewHolder(@NonNull View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.message_text);
            messageTime = itemView.findViewById(R.id.message_time);
        }
    }

    static class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        TextView messageTime, caption;
        ImageView readReceipt;
        ProgressBar progressBar;
        ImageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.message_image);
            messageTime = itemView.findViewById(R.id.message_time);
            caption = itemView.findViewById(R.id.message_caption);
            readReceipt = itemView.findViewById(R.id.read_receipt);
            progressBar = itemView.findViewById(R.id.image_progress);
        }
    }

    static class VoiceViewHolder extends RecyclerView.ViewHolder {
        ImageView btnPlay;
        ProgressBar progressBar;
        VoiceWaveformView waveformView;
        TextView durationText, messageTime;
        ImageView readReceipt;
        VoiceViewHolder(@NonNull View itemView) {
            super(itemView);
            btnPlay = itemView.findViewById(R.id.btn_play);
            progressBar = itemView.findViewById(R.id.voice_progress);
            waveformView = itemView.findViewById(R.id.voice_waveform);
            durationText = itemView.findViewById(R.id.voice_duration);
            messageTime = itemView.findViewById(R.id.message_time);
            readReceipt = itemView.findViewById(R.id.read_receipt);
        }
    }

    static class DocumentViewHolder extends RecyclerView.ViewHolder {
        TextView fileName, fileSize, messageTime;
        ImageView readReceipt;
        DocumentViewHolder(@NonNull View itemView) {
            super(itemView);
            fileName = itemView.findViewById(R.id.doc_file_name);
            fileSize = itemView.findViewById(R.id.doc_file_size);
            messageTime = itemView.findViewById(R.id.message_time);
            readReceipt = itemView.findViewById(R.id.read_receipt);
        }
    }

    static class DateSeparatorViewHolder extends RecyclerView.ViewHolder {
        TextView dateLabel;
        DateSeparatorViewHolder(@NonNull View itemView) {
            super(itemView);
            dateLabel = itemView.findViewById(R.id.date_label);
        }
    }
}
