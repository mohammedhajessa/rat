package com.h4ckinto.android;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.Assert.*;

/**
 * Simple test to verify server connectivity
 */
@RunWith(JUnit4.class)
public class ServerConnectionTest {

    @Test
    public void testServerConnection() {
        String serverUrl = "http://localhost:9400"; // Update with your server URL
        
        try {
            URL url = new URL(serverUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            int responseCode = connection.getResponseCode();
            assertTrue("Server should be reachable", responseCode >= 200 && responseCode < 400);
            
            connection.disconnect();
        } catch (IOException e) {
            fail("Failed to connect to server: " + e.getMessage());
        }
    }
}
