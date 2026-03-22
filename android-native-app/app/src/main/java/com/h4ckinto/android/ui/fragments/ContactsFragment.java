package com.h4ckinto.android.ui.fragments;
import android.graphics.Bitmap;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.ImageView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.h4ckinto.android.R;
import com.h4ckinto.android.network.ChatApiService;
import com.h4ckinto.android.ui.ConversationActivity;
import com.h4ckinto.android.utils.QrCodeHelper;
import com.h4ckinto.android.ui.QrCodeActivity;
import com.h4ckinto.android.ui.QrScannerActivity;
import com.h4ckinto.android.ui.UserProfileActivity;
import com.h4ckinto.android.ui.adapters.ContactsAdapter;
import com.h4ckinto.android.utils.ChatPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ContactsFragment extends Fragment {

    private static final String TAG = "ContactsFragment";
    private static final int REQUEST_QR_SCAN = 2001;

    private RecyclerView recyclerView;
    private ContactsAdapter adapter;
    private List<ContactsAdapter.ContactItem> contacts;
    private List<ContactsAdapter.ContactItem> allContacts;
    private int myUserId = -1;
    private String myUsername = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_contacts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ChatPrefs chatPrefs = new ChatPrefs(requireContext());
        myUserId = chatPrefs.getUserId();
        myUsername = chatPrefs.getUsername();

        recyclerView = view.findViewById(R.id.contacts_recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        contacts = new ArrayList<>();
        allContacts = new ArrayList<>();
        adapter = new ContactsAdapter(contacts);
        adapter.setOnContactClickListener((item, position) -> {
            if (item.userId > 0) {
                ChatApiService.getInstance().getOrCreateConversation(myUserId, item.userId,
                    new ChatApiService.ApiCallback<Integer>() {
                        @Override
                        public void onSuccess(Integer conversationId) {
                            if (!isAdded()) return;
                            Intent intent = new Intent(getContext(), ConversationActivity.class);
                            intent.putExtra("conversation_id", conversationId);
                            intent.putExtra("other_user_id", item.userId);
                            intent.putExtra("contact_name", item.name);
                            intent.putExtra("contact_initials", item.initials);
                            intent.putExtra("avatar_color", item.avatarColorStr);
                            intent.putExtra("avatar_url", item.avatarUrl != null ? item.avatarUrl : "");
                            startActivity(intent);
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "Error creating conversation: " + error);
                        }
                    });
            }
        });
        adapter.setOnContactLongClickListener((item, position) -> {
            if (item.userId > 0) {
                Intent profileIntent = UserProfileActivity.create(
                        requireContext(), item.userId, item.name, item.initials,
                        item.avatarColorStr, item.avatarUrl, -1);
                startActivity(profileIntent);
            }
        });
        recyclerView.setAdapter(adapter);

        // Add contact button
        View btnAdd = view.findViewById(R.id.btn_add_contact);
        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> showAddContactDialog());
        }

        // QR code button
        View btnQr = view.findViewById(R.id.btn_qr_code);
        if (btnQr != null) {
            btnQr.setOnClickListener(v -> showQrOptionsDialog());
        }

        // Search filter
        EditText searchInput = view.findViewById(R.id.search_contacts);
        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterContacts(s.toString().trim());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        loadContacts();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (myUserId > 0) {
            loadContacts();
        }
    }

    private void showAddContactDialog() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * density);
        int padTop = (int) (20 * density);
        int inputPadH = (int) (16 * density);
        int inputPadV = (int) (12 * density);
        int cornerRadius = (int) (14 * density);

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, padTop, pad, (int) (8 * density));

        TextView label = new TextView(requireContext());
        label.setText("\u0623\u062F\u062E\u0644 \u0627\u0633\u0645 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645");
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        label.setPadding(0, 0, 0, (int) (12 * density));
        layout.addView(label);

        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setHint("@username");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#80FFFFFF"));
        input.setTextSize(15);
        input.setBackgroundResource(R.drawable.selector_modern_input);
        input.setPadding(inputPadH, inputPadV, inputPadH, inputPadV);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = (int) (4 * density);
        input.setLayoutParams(inputParams);
        layout.addView(input);

        final TextView resultView = new TextView(requireContext());
        resultView.setPadding(0, (int) (12 * density), 0, 0);
        resultView.setTextColor(Color.parseColor("#C4A265"));
        resultView.setTextSize(13);
        resultView.setVisibility(View.GONE);
        layout.addView(resultView);

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle("\u0625\u0636\u0627\u0641\u0629 \u062C\u0647\u0629 \u0627\u062A\u0635\u0627\u0644")
                .setView(layout)
                .setPositiveButton("\u0628\u062D\u062B \u0648\u0625\u0636\u0627\u0641\u0629", null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String searchText = input.getText().toString().trim();
                if (searchText.isEmpty()) {
                    input.setError("\u0623\u062F\u062E\u0644 \u0627\u0633\u0645 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645");
                    return;
                }

                if (searchText.startsWith("@")) searchText = searchText.substring(1);
                final String query = searchText.toLowerCase();

                if (query.length() < 3) {
                    input.setError("\u0627\u0633\u0645 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645 \u064A\u062C\u0628 \u0623\u0646 \u064A\u0643\u0648\u0646 3 \u062D\u0631\u0648\u0641 \u0639\u0644\u0649 \u0627\u0644\u0623\u0642\u0644");
                    return;
                }

                resultView.setText("\u062C\u0627\u0631\u064D \u0627\u0644\u0628\u062D\u062B...");
                resultView.setVisibility(View.VISIBLE);

                ChatApiService.getInstance().searchByUsername(query, new ChatApiService.ApiCallback<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject user) {
                        int targetId = user.optInt("id", -1);
                        if (targetId == myUserId) {
                            resultView.setText("\u0644\u0627 \u064A\u0645\u0643\u0646 \u0625\u0636\u0627\u0641\u0629 \u0646\u0641\u0633\u0643");
                            return;
                        }
                        handleFoundUser(user, targetId, dialog, resultView);
                    }
                    @Override
                    public void onError(String error) {
                        resultView.setText(error);
                    }
                });
            });
        });

        dialog.show();
    }

    private void handleFoundUser(JSONObject user, int targetId, AlertDialog dialog, TextView resultView) {
        String name = user.optString("display_name", "\u0645\u0633\u062A\u062E\u062F\u0645");
        String rank = user.optString("rank", "");
        String jobTitle = user.optString("job_title", "");
        String uname = user.optString("username", "");

        StringBuilder info = new StringBuilder("\u062A\u0645 \u0627\u0644\u0639\u062B\u0648\u0631 \u0639\u0644\u0649: " + name);
        if (rank != null && !rank.isEmpty()) info.append("\n\u0627\u0644\u0631\u062A\u0628\u0629: ").append(rank);
        if (jobTitle != null && !jobTitle.isEmpty()) info.append("\n\u0627\u0644\u0645\u0633\u0645\u0649: ").append(jobTitle);
        if (uname != null && !uname.isEmpty()) info.append("\n@").append(uname);
        info.append("\n\u062C\u0627\u0631\u064D \u0627\u0644\u0625\u0636\u0627\u0641\u0629...");
        resultView.setText(info.toString());

        if (uname != null && !uname.isEmpty()) {
            ChatApiService.getInstance().addContactByUsername(myUserId, uname, new ChatApiService.ApiCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject result) {
                    dialog.dismiss();
                    if (getContext() != null) Toast.makeText(getContext(),
                            "\u062A\u0645 \u0625\u0636\u0627\u0641\u0629 " + name, Toast.LENGTH_SHORT).show();
                    loadContacts();
                }
                @Override
                public void onError(String error) {
                    resultView.setText("\u0641\u0634\u0644: " + error);
                }
            });
        } else {
            ChatApiService.getInstance().addContactByUserId(myUserId, targetId, new ChatApiService.ApiCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject result) {
                    dialog.dismiss();
                    if (getContext() != null) Toast.makeText(getContext(),
                            "\u062A\u0645 \u0625\u0636\u0627\u0641\u0629 " + name, Toast.LENGTH_SHORT).show();
                    loadContacts();
                }
                @Override
                public void onError(String error) {
                    resultView.setText("\u0641\u0634\u0644: " + error);
                }
            });
        }
    }

    private void filterContacts(String query) {
        contacts.clear();
        if (query.isEmpty()) {
            contacts.addAll(allContacts);
        } else {
            String lowerQuery = query.toLowerCase();
            for (ContactsAdapter.ContactItem c : allContacts) {
                if (c.isHeader) continue;
                if (c.name.toLowerCase().contains(lowerQuery)
                        || String.valueOf(c.userId).contains(query)
                        || (c.username != null && c.username.toLowerCase().contains(lowerQuery))) {
                    contacts.add(c);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void loadContacts() {
        if (myUserId <= 0) return;

        ChatApiService.getInstance().getContacts(myUserId, new ChatApiService.ApiCallback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray result) {
                try {
                    List<ContactsAdapter.ContactItem> rawContacts = new ArrayList<>();
                    for (int i = 0; i < result.length(); i++) {
                        JSONObject c = result.getJSONObject(i);
                        String name = c.getString("display_name");
                        String initials = c.getString("initials");
                        String avatarColor = c.optString("avatar_color", "#3B82F6");
                        String avatarUrl = c.optString("avatar_url", "");
                        int userId = c.getInt("user_id");
                        boolean isOnline = c.optInt("is_online", 0) == 1;

                        int color;
                        try {
                            color = Color.parseColor(avatarColor);
                        } catch (Exception e) {
                            color = Color.parseColor("#3B82F6");
                        }

                        String username = c.optString("username", "");
                        String rank = c.optString("rank", "");
                        String jobTitleStr = c.optString("job_title", "");

                        ContactsAdapter.ContactItem item = ContactsAdapter.ContactItem.contact(
                                name, initials, isOnline ? "\u0645\u062A\u0635\u0644" : "", color
                        );
                        item.userId = userId;
                        item.avatarColorStr = avatarColor;
                        item.avatarUrl = avatarUrl;
                        item.username = username;
                        item.rank = rank;
                        item.jobTitle = jobTitleStr;
                        rawContacts.add(item);
                    }

                    Collections.sort(rawContacts, (a, b) -> a.name.compareTo(b.name));

                    allContacts.clear();
                    contacts.clear();
                    String currentLetter = "";
                    for (ContactsAdapter.ContactItem c : rawContacts) {
                        String firstLetter = c.name.substring(0, 1);
                        if (!firstLetter.equals(currentLetter)) {
                            currentLetter = firstLetter;
                            ContactsAdapter.ContactItem header = ContactsAdapter.ContactItem.header(currentLetter);
                            allContacts.add(header);
                            contacts.add(header);
                        }
                        allContacts.add(c);
                        contacts.add(c);
                    }

                    adapter.notifyDataSetChanged();
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing contacts: " + e.getMessage());
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error loading contacts: " + error);
            }
        });
    }

    private void showQrOptionsDialog() {
        String[] options = {"رمز QR الخاص بي", "مسح رمز QR"};
        new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle("رمز QR")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showMyQrCode();
                    } else {
                        openQrScanner();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showMyQrCode() {
        if (myUserId <= 0) return;

        ChatPrefs prefs = new ChatPrefs(requireContext());
        Intent intent = QrCodeActivity.create(
                requireContext(),
                myUserId,
                myUsername,
                prefs.getDisplayName(),
                prefs.getInitials(),
                prefs.getAvatarColor(),
                prefs.getAvatarUrl()
        );
        startActivity(intent);
    }

    private void openQrScanner() {
        Intent intent = new Intent(requireContext(), QrScannerActivity.class);
        startActivityForResult(intent, REQUEST_QR_SCAN);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_QR_SCAN && resultCode == Activity.RESULT_OK && data != null) {
            int scannedUserId = data.getIntExtra(QrScannerActivity.EXTRA_SCANNED_USER_ID, -1);
            String scannedUsername = data.getStringExtra(QrScannerActivity.EXTRA_SCANNED_USERNAME);

            if (scannedUserId <= 0) return;

            if (scannedUserId == myUserId) {
                if (getContext() != null) Toast.makeText(getContext(), "\u0644\u0627 \u064A\u0645\u0643\u0646 \u0625\u0636\u0627\u0641\u0629 \u0646\u0641\u0633\u0643", Toast.LENGTH_SHORT).show();
                return;
            }

            ChatApiService.getInstance().addContactByUserId(myUserId, scannedUserId,
                    new ChatApiService.ApiCallback<JSONObject>() {
                        @Override
                        public void onSuccess(JSONObject result) {
                            if (!isAdded()) return;
                            String name = scannedUsername != null ? scannedUsername : "\u062C\u0647\u0629 \u0627\u062A\u0635\u0627\u0644";
                            if (getContext() != null) Toast.makeText(getContext(),
                                    "\u062A\u0645 \u0625\u0636\u0627\u0641\u0629 " + name, Toast.LENGTH_SHORT).show();
                            loadContacts();
                        }

                        @Override
                        public void onError(String error) {
                            if (getContext() != null) Toast.makeText(getContext(),
                                    "\u0641\u0634\u0644: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }
}
