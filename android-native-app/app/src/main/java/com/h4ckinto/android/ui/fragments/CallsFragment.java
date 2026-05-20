package com.h4ckinto.android.ui.fragments;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.h4ckinto.android.R;
import com.h4ckinto.android.network.ChatApiService;
import com.h4ckinto.android.ui.AudioCallActivity;
import com.h4ckinto.android.ui.adapters.CallLogAdapter;
import com.h4ckinto.android.utils.ChatPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class CallsFragment extends Fragment {

    private static final String TAG = "CallsFragment";
    private RecyclerView recyclerView;
    private LinearLayout emptyState;
    private ImageView btnDeleteCalls;
    private CallLogAdapter adapter;
    private List<CallLogAdapter.CallLogItem> callLogs;
    private int myUserId = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_calls, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ChatPrefs prefs = new ChatPrefs(requireContext());
        myUserId = prefs.getUserId();

        recyclerView = view.findViewById(R.id.calls_recycler);
        emptyState = view.findViewById(R.id.empty_state);
        btnDeleteCalls = view.findViewById(R.id.btn_delete_calls);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        if (btnDeleteCalls != null) {
            btnDeleteCalls.setOnClickListener(v -> showDeleteCallsDialog());
        }

        callLogs = new ArrayList<>();
        adapter = new CallLogAdapter(callLogs);
        adapter.setOnCallClickListener(item -> {
            Intent intent = AudioCallActivity.createOutgoing(
                    requireContext(), item.otherUserId, item.otherName,
                    item.otherInitials, item.otherAvatarColor);
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);

        loadCallLogs();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadCallLogs();
    }

    private void loadCallLogs() {
        if (myUserId <= 0) return;

        ChatApiService.getInstance().getCallLogs(myUserId, new ChatApiService.ApiCallback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray result) {
                try {
                    List<CallLogAdapter.CallLogItem> oldLogs = new ArrayList<>(callLogs);
                    callLogs.clear();
                    for (int i = 0; i < result.length(); i++) {
                        JSONObject obj = result.getJSONObject(i);
                        callLogs.add(new CallLogAdapter.CallLogItem(
                                obj.getInt("other_user_id"),
                                obj.getString("other_name"),
                                obj.getString("other_initials"),
                                obj.optString("other_avatar_color", "#3B82F6"),
                                obj.getInt("is_outgoing") == 1,
                                obj.getString("status"),
                                obj.getInt("duration"),
                                obj.optString("timestamp", "")
                        ));
                    }
                    dispatchCallLogsDiff(oldLogs, callLogs);

                    if (callLogs.isEmpty()) {
                        recyclerView.setVisibility(View.GONE);
                        emptyState.setVisibility(View.VISIBLE);
                        if (btnDeleteCalls != null) btnDeleteCalls.setVisibility(View.GONE);
                    } else {
                        recyclerView.setVisibility(View.VISIBLE);
                        emptyState.setVisibility(View.GONE);
                        if (btnDeleteCalls != null) btnDeleteCalls.setVisibility(View.VISIBLE);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing call logs: " + e.getMessage());
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error loading call logs: " + error);
            }
        });
    }

    private void dispatchCallLogsDiff(List<CallLogAdapter.CallLogItem> oldList,
                                       List<CallLogAdapter.CallLogItem> newList) {
        androidx.recyclerview.widget.DiffUtil.DiffResult diff =
                androidx.recyclerview.widget.DiffUtil.calculateDiff(
                        new androidx.recyclerview.widget.DiffUtil.Callback() {
                            @Override public int getOldListSize() { return oldList.size(); }
                            @Override public int getNewListSize() { return newList.size(); }
                            @Override public boolean areItemsTheSame(int o, int n) {
                                return oldList.get(o).otherUserId == newList.get(n).otherUserId
                                        && java.util.Objects.equals(oldList.get(o).timestamp, newList.get(n).timestamp);
                            }
                            @Override public boolean areContentsTheSame(int o, int n) {
                                return areItemsTheSame(o, n)
                                        && java.util.Objects.equals(oldList.get(o).status, newList.get(n).status);
                            }
                        });
        diff.dispatchUpdatesTo(adapter);
    }

    private void showDeleteCallsDialog() {
        new AlertDialog.Builder(requireContext(), R.style.DarkAlertDialog)
                .setTitle("\u062D\u0630\u0641 \u0633\u062C\u0644 \u0627\u0644\u0645\u0643\u0627\u0644\u0645\u0627\u062A")
                .setMessage("\u0647\u0644 \u062A\u0631\u064A\u062F \u062D\u0630\u0641 \u062C\u0645\u064A\u0639 \u0633\u062C\u0644\u0627\u062A \u0627\u0644\u0645\u0643\u0627\u0644\u0645\u0627\u062A\u061F")
                .setPositiveButton("\u062D\u0630\u0641", (dialog, which) -> {
                    ChatApiService.getInstance().deleteCallLogs(myUserId, new ChatApiService.ApiCallback<JSONObject>() {
                        @Override
                        public void onSuccess(JSONObject result) {
                            List<CallLogAdapter.CallLogItem> old = new ArrayList<>(callLogs);
                            callLogs.clear();
                            dispatchCallLogsDiff(old, callLogs);
                            recyclerView.setVisibility(View.GONE);
                            emptyState.setVisibility(View.VISIBLE);
                            if (btnDeleteCalls != null) btnDeleteCalls.setVisibility(View.GONE);
                            Toast.makeText(requireContext(), "\u062A\u0645 \u062D\u0630\u0641 \u0633\u062C\u0644 \u0627\u0644\u0645\u0643\u0627\u0644\u0645\u0627\u062A", Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String error) {
                            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
                .show();
    }
}
