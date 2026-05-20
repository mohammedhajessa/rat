package com.h4ckinto.android.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.h4ckinto.android.R;

/**
 * Settings Fragment - App settings and preferences
 * Shows reading settings, notifications, storage options, and about section
 */
public class SettingsFragment extends Fragment {

    private SwitchMaterial switchNotifications;
    private SwitchMaterial switchReminders;
    private Button btnLogout;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initViews(view);
        setupListeners();
    }
    
    private void initViews(View view) {
        switchNotifications = view.findViewById(R.id.switch_notifications);
        switchReminders = view.findViewById(R.id.switch_reminders);
        btnLogout = view.findViewById(R.id.btn_logout);
    }
    
    private void setupListeners() {
        switchNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String message = isChecked ? "تم تفعيل الإشعارات" : "تم إيقاف الإشعارات";
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        });
        
        switchReminders.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String message = isChecked ? "تم تفعيل التذكير اليومي" : "تم إيقاف التذكير اليومي";
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        });
        
        btnLogout.setOnClickListener(v -> {
            Toast.makeText(getContext(), R.string.logging_out, Toast.LENGTH_SHORT).show();
        });
    }
}




