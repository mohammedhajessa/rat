package com.h4ckinto.android.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

public class MediaCacheManager {

    private static final String TAG = "MediaCacheManager";
    private static final String PREFS_NAME = "media_cache_index";
    private static final String CACHE_DIR = "media_cache";

    private static MediaCacheManager instance;
    private final File cacheRoot;
    private final SharedPreferences index;

    private MediaCacheManager(Context ctx) {
        cacheRoot = new File(ctx.getFilesDir(), CACHE_DIR);
        if (!cacheRoot.exists()) cacheRoot.mkdirs();
        new File(cacheRoot, "images").mkdirs();
        new File(cacheRoot, "voice").mkdirs();
        new File(cacheRoot, "documents").mkdirs();
        index = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MediaCacheManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new MediaCacheManager(ctx.getApplicationContext());
        }
        return instance;
    }

    public File getLocalFile(String url) {
        if (url == null || url.isEmpty()) return null;
        String hash = hashUrl(url);
        String path = index.getString(hash, null);
        if (path == null) return null;
        File f = new File(path);
        if (f.exists() && f.length() > 0) return f;
        index.edit().remove(hash).apply();
        return null;
    }

    public File cacheFile(String url, byte[] data, String type) {
        if (url == null || data == null || data.length == 0) return null;
        try {
            String hash = hashUrl(url);
            String ext = getExtFromUrl(url);
            String subdir = getSubdir(type);
            File outFile = new File(new File(cacheRoot, subdir), hash + ext);

            FileOutputStream fos = new FileOutputStream(outFile);
            fos.write(data);
            fos.close();

            index.edit().putString(hash, outFile.getAbsolutePath()).apply();
            Log.d(TAG, "Cached " + data.length + " bytes -> " + outFile.getName());
            return outFile;
        } catch (Exception e) {
            Log.e(TAG, "cacheFile error: " + e.getMessage());
            return null;
        }
    }

    public File cacheFromFile(String url, File source, String type) {
        if (url == null || source == null || !source.exists()) return null;
        try {
            String hash = hashUrl(url);
            String ext = getExtFromUrl(url);
            if (ext.isEmpty()) {
                String srcName = source.getName();
                int dot = srcName.lastIndexOf('.');
                if (dot >= 0) ext = srcName.substring(dot);
            }
            String subdir = getSubdir(type);
            File outFile = new File(new File(cacheRoot, subdir), hash + ext);

            if (outFile.getAbsolutePath().equals(source.getAbsolutePath())) {
                index.edit().putString(hash, outFile.getAbsolutePath()).apply();
                return outFile;
            }

            FileInputStream fis = new FileInputStream(source);
            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) fos.write(buf, 0, len);
            fos.close();
            fis.close();

            index.edit().putString(hash, outFile.getAbsolutePath()).apply();
            Log.d(TAG, "Cached from file -> " + outFile.getName());
            return outFile;
        } catch (Exception e) {
            Log.e(TAG, "cacheFromFile error: " + e.getMessage());
            return null;
        }
    }

    public File cacheFromStream(String url, InputStream is, String type) {
        if (url == null || is == null) return null;
        try {
            String hash = hashUrl(url);
            String ext = getExtFromUrl(url);
            String subdir = getSubdir(type);
            File outFile = new File(new File(cacheRoot, subdir), hash + ext);

            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            fos.close();

            index.edit().putString(hash, outFile.getAbsolutePath()).apply();
            return outFile;
        } catch (Exception e) {
            Log.e(TAG, "cacheFromStream error: " + e.getMessage());
            return null;
        }
    }

    public long getCacheSize() {
        return dirSize(cacheRoot);
    }

    public void clearCache() {
        deleteDir(cacheRoot);
        cacheRoot.mkdirs();
        new File(cacheRoot, "images").mkdirs();
        new File(cacheRoot, "voice").mkdirs();
        new File(cacheRoot, "documents").mkdirs();
        index.edit().clear().apply();
        Log.d(TAG, "Cache cleared");
    }

    private long dirSize(File dir) {
        long size = 0;
        if (dir == null || !dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) size += dirSize(f);
            else size += f.length();
        }
        return size;
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private String getSubdir(String type) {
        if (type == null) return "documents";
        switch (type) {
            case "image": return "images";
            case "voice": return "voice";
            default: return "documents";
        }
    }

    private String getExtFromUrl(String url) {
        try {
            String path = url;
            int q = path.indexOf('?');
            if (q > 0) path = path.substring(0, q);
            int dot = path.lastIndexOf('.');
            if (dot > 0 && dot > path.lastIndexOf('/')) {
                return path.substring(dot);
            }
        } catch (Exception e) { /* ignore */ }
        return ".bin";
    }

    private String hashUrl(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(16, digest.length); i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }
}
