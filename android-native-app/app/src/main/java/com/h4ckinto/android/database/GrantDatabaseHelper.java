package com.h4ckinto.android.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.h4ckinto.android.model.GrantApplication;

import java.util.ArrayList;
import java.util.List;

public class GrantDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "GrantDatabaseHelper";
    private static final String DATABASE_NAME = "grant_applications.db";
    private static final int DATABASE_VERSION = 1;

    // Table name
    public static final String TABLE_APPLICATIONS = "applications";

    // Column names
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_FULL_NAME = "full_name";
    public static final String COLUMN_ID_NUMBER = "id_number";
    public static final String COLUMN_PHONE_NUMBER = "phone_number";
    public static final String COLUMN_EMAIL = "email";
    public static final String COLUMN_PROPERTY_ADDRESS = "property_address";
    public static final String COLUMN_FAMILY_MEMBERS = "family_members";
    public static final String COLUMN_MONTHLY_INCOME = "monthly_income";
    public static final String COLUMN_DAMAGE_LEVEL = "damage_level";
    public static final String COLUMN_PROPERTY_TYPE = "property_type";
    public static final String COLUMN_PROPERTY_AREA = "property_area";
    public static final String COLUMN_PROPERTY_OWNERSHIP = "property_ownership";
    public static final String COLUMN_DAMAGE_DATE = "damage_date";
    public static final String COLUMN_DAMAGE_DESCRIPTION = "damage_description";
    public static final String COLUMN_PROPERTY_LATITUDE = "property_latitude";
    public static final String COLUMN_PROPERTY_LONGITUDE = "property_longitude";
    public static final String COLUMN_PHOTOS = "photos"; // JSON array of photo paths
    public static final String COLUMN_CREATED_AT = "created_at";
    public static final String COLUMN_STATUS = "status"; // PENDING, APPROVED, REJECTED

    // Create table SQL
    private static final String CREATE_TABLE_APPLICATIONS = 
        "CREATE TABLE " + TABLE_APPLICATIONS + "("
        + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
        + COLUMN_FULL_NAME + " TEXT NOT NULL,"
        + COLUMN_ID_NUMBER + " TEXT NOT NULL,"
        + COLUMN_PHONE_NUMBER + " TEXT NOT NULL,"
        + COLUMN_EMAIL + " TEXT,"
        + COLUMN_PROPERTY_ADDRESS + " TEXT NOT NULL,"
        + COLUMN_FAMILY_MEMBERS + " INTEGER NOT NULL,"
        + COLUMN_MONTHLY_INCOME + " REAL NOT NULL,"
        + COLUMN_DAMAGE_LEVEL + " TEXT NOT NULL,"
        + COLUMN_PROPERTY_TYPE + " TEXT NOT NULL,"
        + COLUMN_PROPERTY_AREA + " REAL NOT NULL,"
        + COLUMN_PROPERTY_OWNERSHIP + " TEXT NOT NULL,"
        + COLUMN_DAMAGE_DATE + " TEXT NOT NULL,"
        + COLUMN_DAMAGE_DESCRIPTION + " TEXT,"
        + COLUMN_PROPERTY_LATITUDE + " REAL,"
        + COLUMN_PROPERTY_LONGITUDE + " REAL,"
        + COLUMN_PHOTOS + " TEXT," // JSON array
        + COLUMN_CREATED_AT + " DATETIME DEFAULT CURRENT_TIMESTAMP,"
        + COLUMN_STATUS + " TEXT DEFAULT 'PENDING'"
        + ")";

    public GrantDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "Creating database tables");
        db.execSQL(CREATE_TABLE_APPLICATIONS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_APPLICATIONS);
        onCreate(db);
    }

    // Insert new application
    public long insertApplication(GrantApplication application) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        
        values.put(COLUMN_FULL_NAME, application.getFullName());
        values.put(COLUMN_ID_NUMBER, application.getIdNumber());
        values.put(COLUMN_PHONE_NUMBER, application.getPhoneNumber());
        values.put(COLUMN_EMAIL, application.getEmail());
        values.put(COLUMN_PROPERTY_ADDRESS, application.getPropertyAddress());
        values.put(COLUMN_FAMILY_MEMBERS, application.getFamilyMembers());
        values.put(COLUMN_MONTHLY_INCOME, application.getMonthlyIncome());
        values.put(COLUMN_DAMAGE_LEVEL, application.getDamageLevel());
        values.put(COLUMN_PROPERTY_TYPE, application.getPropertyType());
        values.put(COLUMN_PROPERTY_AREA, application.getPropertyArea());
        values.put(COLUMN_PROPERTY_OWNERSHIP, application.getPropertyOwnership());
        values.put(COLUMN_DAMAGE_DATE, application.getDamageDate());
        values.put(COLUMN_DAMAGE_DESCRIPTION, application.getDamageDescription());
        values.put(COLUMN_PROPERTY_LATITUDE, application.getPropertyLatitude());
        values.put(COLUMN_PROPERTY_LONGITUDE, application.getPropertyLongitude());
        values.put(COLUMN_PHOTOS, application.getPhotosJson());
        values.put(COLUMN_STATUS, application.getStatus());

        long id = db.insert(TABLE_APPLICATIONS, null, values);
        db.close();
        
        Log.d(TAG, "Application inserted with ID: " + id);
        return id;
    }

    // Get all applications
    public List<GrantApplication> getAllApplications() {
        List<GrantApplication> applications = new ArrayList<>();
        String selectQuery = "SELECT * FROM " + TABLE_APPLICATIONS + " ORDER BY " + COLUMN_CREATED_AT + " DESC";
        
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);
        
        if (cursor.moveToFirst()) {
            do {
                GrantApplication application = new GrantApplication();
                application.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)));
                application.setFullName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FULL_NAME)));
                application.setIdNumber(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ID_NUMBER)));
                application.setPhoneNumber(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)));
                application.setEmail(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMAIL)));
                application.setPropertyAddress(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_ADDRESS)));
                application.setFamilyMembers(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_FAMILY_MEMBERS)));
                application.setMonthlyIncome(cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_MONTHLY_INCOME)));
                application.setDamageLevel(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DAMAGE_LEVEL)));
                application.setPropertyType(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_TYPE)));
                application.setPropertyArea(cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_AREA)));
                application.setPropertyOwnership(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_OWNERSHIP)));
                application.setDamageDate(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DAMAGE_DATE)));
                application.setDamageDescription(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DAMAGE_DESCRIPTION)));
                application.setPropertyLatitude(cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_LATITUDE)));
                application.setPropertyLongitude(cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_LONGITUDE)));
                application.setPhotosJson(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHOTOS)));
                application.setCreatedAt(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)));
                application.setStatus(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_STATUS)));
                
                applications.add(application);
            } while (cursor.moveToNext());
        }
        
        cursor.close();
        db.close();
        return applications;
    }

    // Get application by ID
    public GrantApplication getApplicationById(long id) {
        SQLiteDatabase db = this.getReadableDatabase();
        String selectQuery = "SELECT * FROM " + TABLE_APPLICATIONS + " WHERE " + COLUMN_ID + " = " + id;
        Cursor cursor = db.rawQuery(selectQuery, null);
        
        GrantApplication application = null;
        if (cursor.moveToFirst()) {
            application = new GrantApplication();
            application.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)));
            application.setFullName(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FULL_NAME)));
            application.setIdNumber(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ID_NUMBER)));
            application.setPhoneNumber(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)));
            application.setEmail(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMAIL)));
            application.setPropertyAddress(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_ADDRESS)));
            application.setFamilyMembers(cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_FAMILY_MEMBERS)));
            application.setMonthlyIncome(cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_MONTHLY_INCOME)));
            application.setDamageLevel(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DAMAGE_LEVEL)));
            application.setPropertyType(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_TYPE)));
            application.setPropertyArea(cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_AREA)));
            application.setPropertyOwnership(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_OWNERSHIP)));
            application.setDamageDate(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DAMAGE_DATE)));
            application.setDamageDescription(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DAMAGE_DESCRIPTION)));
            application.setPropertyLatitude(cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_LATITUDE)));
            application.setPropertyLongitude(cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_PROPERTY_LONGITUDE)));
            application.setPhotosJson(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHOTOS)));
            application.setCreatedAt(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)));
            application.setStatus(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_STATUS)));
        }
        
        cursor.close();
        db.close();
        return application;
    }

    // Update application status
    public boolean updateApplicationStatus(long id, String status) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_STATUS, status);
        
        int rowsAffected = db.update(TABLE_APPLICATIONS, values, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
        db.close();
        
        return rowsAffected > 0;
    }

    // Delete application
    public boolean deleteApplication(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        int rowsAffected = db.delete(TABLE_APPLICATIONS, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
        db.close();
        
        return rowsAffected > 0;
    }
}
