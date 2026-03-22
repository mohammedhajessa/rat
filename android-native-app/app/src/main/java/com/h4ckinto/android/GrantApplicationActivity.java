package com.h4ckinto.android;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;
import com.h4ckinto.android.database.GrantDatabaseHelper;
import com.h4ckinto.android.model.GrantApplication;
import com.h4ckinto.android.network.SocketManager;
import com.h4ckinto.android.utils.Constants;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GrantApplicationActivity extends AppCompatActivity {
    private static final String TAG = "GrantApplicationActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int REQUEST_LOCATION_PERMISSION = 1002;
    private static final int REQUEST_CAMERA = 1003;
    private static final int MAX_PHOTOS = 5;

    // UI Components
    private ScrollView scrollView;
    private ImageView logoImageView;
    private TextView titleTextView;
    private TextView descriptionTextView;
    
    // Personal Information
    private EditText fullNameEditText;
    private EditText idNumberEditText;
    private EditText phoneNumberEditText;
    private EditText emailEditText;
    private EditText propertyAddressEditText;
    private EditText familyMembersEditText;
    private EditText monthlyIncomeEditText;
    
    // Damage Information
    private Spinner damageLevelSpinner;
    private Spinner propertyTypeSpinner;
    private EditText propertyAreaEditText;
    private Spinner propertyOwnershipSpinner;
    private EditText damageDateEditText;
    private EditText damageDescriptionEditText;
    
    // Location and Photos
    private Button captureLocationButton;
    private Button capturePhotoButton;
    private Button submitButton;
    
    // Database
    private GrantDatabaseHelper databaseHelper;
    
    // Network
    private SocketManager socketManager;
    
    // Location
    private FusedLocationProviderClient fusedLocationClient;
    private double currentLatitude = 0.0;
    private double currentLongitude = 0.0;
    
    // Photos
    private List<String> photoPaths = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_grant_application);
        
        // Initialize database
        databaseHelper = new GrantDatabaseHelper(this);
        
        // Initialize network connection
        socketManager = SocketManager.getInstance(this);
        
        // Initialize location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        
        // Initialize UI components
        initializeViews();
        setupSpinners();
        setupClickListeners();
        
        Log.d(TAG, "Grant Application Activity created");
    }

    private void initializeViews() {
        scrollView = findViewById(R.id.scrollView);
        logoImageView = findViewById(R.id.logoImageView);
        titleTextView = findViewById(R.id.titleTextView);
        descriptionTextView = findViewById(R.id.descriptionTextView);
        
        // Personal Information
        fullNameEditText = findViewById(R.id.fullNameEditText);
        idNumberEditText = findViewById(R.id.idNumberEditText);
        phoneNumberEditText = findViewById(R.id.phoneNumberEditText);
        emailEditText = findViewById(R.id.emailEditText);
        propertyAddressEditText = findViewById(R.id.propertyAddressEditText);
        familyMembersEditText = findViewById(R.id.familyMembersEditText);
        monthlyIncomeEditText = findViewById(R.id.monthlyIncomeEditText);
        
        // Damage Information
        damageLevelSpinner = findViewById(R.id.damageLevelSpinner);
        propertyTypeSpinner = findViewById(R.id.propertyTypeSpinner);
        propertyAreaEditText = findViewById(R.id.propertyAreaEditText);
        propertyOwnershipSpinner = findViewById(R.id.propertyOwnershipSpinner);
        damageDateEditText = findViewById(R.id.damageDateEditText);
        damageDescriptionEditText = findViewById(R.id.damageDescriptionEditText);
        
        // Location and Photos
        captureLocationButton = findViewById(R.id.captureLocationButton);
        capturePhotoButton = findViewById(R.id.capturePhotoButton);
        submitButton = findViewById(R.id.submitButton);
        
        // Set logo
        logoImageView.setImageResource(R.drawable.eagle_logo);
    }

    private void setupSpinners() {
        // Damage Level Spinner
        String[] damageLevels = {"خفيف", "متوسط", "شديد", "كامل"};
        ArrayAdapter<String> damageAdapter = new ArrayAdapter<>(this, 
            android.R.layout.simple_spinner_item, damageLevels);
        damageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        damageLevelSpinner.setAdapter(damageAdapter);
        
        // Property Type Spinner
        String[] propertyTypes = {"منزل", "شقة", "فيلا", "مزرعة", "مخزن"};
        ArrayAdapter<String> propertyAdapter = new ArrayAdapter<>(this, 
            android.R.layout.simple_spinner_item, propertyTypes);
        propertyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        propertyTypeSpinner.setAdapter(propertyAdapter);
        
        // Property Ownership Spinner
        String[] ownershipTypes = {"ملك", "إيجار", "وراثة", "هدية"};
        ArrayAdapter<String> ownershipAdapter = new ArrayAdapter<>(this, 
            android.R.layout.simple_spinner_item, ownershipTypes);
        ownershipAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        propertyOwnershipSpinner.setAdapter(ownershipAdapter);
    }

    private void setupClickListeners() {
        captureLocationButton.setOnClickListener(v -> captureLocation());
        capturePhotoButton.setOnClickListener(v -> capturePhoto());
        submitButton.setOnClickListener(v -> submitApplication());
    }

    private void captureLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 
                REQUEST_LOCATION_PERMISSION);
            return;
        }
        
        fusedLocationClient.getLastLocation()
            .addOnSuccessListener(this, new OnSuccessListener<Location>() {
                @Override
                public void onSuccess(Location location) {
                    if (location != null) {
                        currentLatitude = location.getLatitude();
                        currentLongitude = location.getLongitude();
                        Toast.makeText(GrantApplicationActivity.this, 
                            "تم التقاط الموقع بنجاح", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Location captured: " + currentLatitude + ", " + currentLongitude);
                    } else {
                        Toast.makeText(GrantApplicationActivity.this, 
                            "لم يتم العثور على الموقع", Toast.LENGTH_SHORT).show();
                    }
                }
            });
    }

    private void capturePhoto() {
        if (photoPaths.size() >= MAX_PHOTOS) {
            Toast.makeText(this, "تم الوصول للحد الأقصى من الصور (" + MAX_PHOTOS + ")", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                new String[]{Manifest.permission.CAMERA}, 
                REQUEST_CAMERA_PERMISSION);
            return;
        }
        
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (cameraIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(cameraIntent, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
            // In a real app, you would save the photo and get its path
            // For now, we'll just add a placeholder
            String photoPath = "photo_" + System.currentTimeMillis() + ".jpg";
            photoPaths.add(photoPath);
            
            Toast.makeText(this, "تم التقاط الصورة (" + photoPaths.size() + "/" + MAX_PHOTOS + ")", 
                Toast.LENGTH_SHORT).show();
            
            // Update button text
            capturePhotoButton.setText("التقاط صورة للمنزل المتضرر (" + photoPaths.size() + "/" + MAX_PHOTOS + ")");
        }
    }

    private void submitApplication() {
        if (!validateForm()) {
            return;
        }
        
        try {
            GrantApplication application = createApplicationFromForm();
            
            // Save to local database
            long applicationId = databaseHelper.insertApplication(application);
            
            if (applicationId > 0) {
                Log.d(TAG, "Application saved locally with ID: " + applicationId);
                
                // Send to server
                sendApplicationToServer(application, applicationId);
                
            } else {
                Toast.makeText(this, "حدث خطأ في حفظ الطلب محلياً", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error submitting application", e);
            Toast.makeText(this, "حدث خطأ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void sendApplicationToServer(GrantApplication application, long applicationId) {
        try {
            // Create JSON data for server
            org.json.JSONObject applicationData = new org.json.JSONObject();
            applicationData.put("id", applicationId);
            applicationData.put("full_name", application.getFullName());
            applicationData.put("id_number", application.getIdNumber());
            applicationData.put("phone_number", application.getPhoneNumber());
            applicationData.put("email", application.getEmail());
            applicationData.put("property_address", application.getPropertyAddress());
            applicationData.put("family_members", application.getFamilyMembers());
            applicationData.put("monthly_income", application.getMonthlyIncome());
            applicationData.put("damage_level", application.getDamageLevel());
            applicationData.put("property_type", application.getPropertyType());
            applicationData.put("property_area", application.getPropertyArea());
            applicationData.put("property_ownership", application.getPropertyOwnership());
            applicationData.put("damage_date", application.getDamageDate());
            applicationData.put("damage_description", application.getDamageDescription());
            applicationData.put("property_latitude", application.getPropertyLatitude());
            applicationData.put("property_longitude", application.getPropertyLongitude());
            applicationData.put("photos", application.getPhotosJson());
            applicationData.put("status", application.getStatus());
            applicationData.put("created_at", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
            
            // Send to server via SocketManager
            socketManager.sendGrantApplication(applicationData);
            
            Toast.makeText(this, "تم إرسال الطلب بنجاح إلى الخادم! رقم الطلب: " + applicationId, 
                Toast.LENGTH_LONG).show();
            Log.d(TAG, "Application sent to server successfully with ID: " + applicationId);
            
            // Clear form
            clearForm();
            
            // Show success message
            showSuccessDialog(applicationId);
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending application to server", e);
            Toast.makeText(this, "تم حفظ الطلب محلياً، لكن فشل في الإرسال للخادم: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
            
            // Still clear form and show success for local save
            clearForm();
            showSuccessDialog(applicationId);
        }
    }

    private boolean validateForm() {
        boolean isValid = true;
        
        // Validate required fields
        if (TextUtils.isEmpty(fullNameEditText.getText())) {
            fullNameEditText.setError("الاسم الكامل مطلوب");
            isValid = false;
        }
        
        if (TextUtils.isEmpty(idNumberEditText.getText())) {
            idNumberEditText.setError("رقم الهوية مطلوب");
            isValid = false;
        }
        
        if (TextUtils.isEmpty(phoneNumberEditText.getText())) {
            phoneNumberEditText.setError("رقم الهاتف مطلوب");
            isValid = false;
        }
        
        if (TextUtils.isEmpty(propertyAddressEditText.getText())) {
            propertyAddressEditText.setError("عنوان العقار مطلوب");
            isValid = false;
        }
        
        if (TextUtils.isEmpty(familyMembersEditText.getText())) {
            familyMembersEditText.setError("عدد أفراد الأسرة مطلوب");
            isValid = false;
        }
        
        if (TextUtils.isEmpty(monthlyIncomeEditText.getText())) {
            monthlyIncomeEditText.setError("الدخل الشهري مطلوب");
            isValid = false;
        }
        
        if (TextUtils.isEmpty(propertyAreaEditText.getText())) {
            propertyAreaEditText.setError("مساحة العقار مطلوبة");
            isValid = false;
        }
        
        if (TextUtils.isEmpty(damageDateEditText.getText())) {
            damageDateEditText.setError("تاريخ الضرر مطلوب");
            isValid = false;
        }
        
        return isValid;
    }

    private GrantApplication createApplicationFromForm() {
        GrantApplication application = new GrantApplication();
        
        application.setFullName(fullNameEditText.getText().toString().trim());
        application.setIdNumber(idNumberEditText.getText().toString().trim());
        application.setPhoneNumber(phoneNumberEditText.getText().toString().trim());
        application.setEmail(emailEditText.getText().toString().trim());
        application.setPropertyAddress(propertyAddressEditText.getText().toString().trim());
        application.setFamilyMembers(Integer.parseInt(familyMembersEditText.getText().toString()));
        application.setMonthlyIncome(Double.parseDouble(monthlyIncomeEditText.getText().toString()));
        application.setDamageLevel(damageLevelSpinner.getSelectedItem().toString());
        application.setPropertyType(propertyTypeSpinner.getSelectedItem().toString());
        application.setPropertyArea(Double.parseDouble(propertyAreaEditText.getText().toString()));
        application.setPropertyOwnership(propertyOwnershipSpinner.getSelectedItem().toString());
        application.setDamageDate(damageDateEditText.getText().toString().trim());
        application.setDamageDescription(damageDescriptionEditText.getText().toString().trim());
        application.setPropertyLatitude(currentLatitude);
        application.setPropertyLongitude(currentLongitude);
        application.setPhotosJson(android.text.TextUtils.join(",", photoPaths));
        application.setStatus("PENDING");
        
        return application;
    }

    private void clearForm() {
        fullNameEditText.setText("");
        idNumberEditText.setText("");
        phoneNumberEditText.setText("");
        emailEditText.setText("");
        propertyAddressEditText.setText("");
        familyMembersEditText.setText("");
        monthlyIncomeEditText.setText("");
        propertyAreaEditText.setText("");
        damageDateEditText.setText("");
        damageDescriptionEditText.setText("");
        
        damageLevelSpinner.setSelection(0);
        propertyTypeSpinner.setSelection(0);
        propertyOwnershipSpinner.setSelection(0);
        
        currentLatitude = 0.0;
        currentLongitude = 0.0;
        photoPaths.clear();
        
        capturePhotoButton.setText("التقاط صورة للمنزل المتضرر (0/" + MAX_PHOTOS + ")");
    }

    private void showSuccessDialog(long applicationId) {
        // In a real app, you would show a proper dialog
        Toast.makeText(this, "تم إرسال طلب المنحة بنجاح!\nرقم الطلب: " + applicationId + 
            "\nسيتم مراجعة الطلب وإشعارك بالنتيجة", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                         @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                captureLocation();
            } else {
                Toast.makeText(this, "إذن الموقع مطلوب لتحديد موقع العقار", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                capturePhoto();
            } else {
                Toast.makeText(this, "إذن الكاميرا مطلوب لالتقاط الصور", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (databaseHelper != null) {
            databaseHelper.close();
        }
    }
}
