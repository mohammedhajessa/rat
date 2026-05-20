package com.h4ckinto.android.utils;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    private final ExecutorService io;
    private final ExecutorService computation;
    private final ScheduledExecutorService scheduled;
    private final Handler mainThread;

    private AppExecutors() {
        io = Executors.newCachedThreadPool();
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        computation = Executors.newFixedThreadPool(cores);
        scheduled = Executors.newScheduledThreadPool(2);
        mainThread = new Handler(Looper.getMainLooper());
    }

    public static AppExecutors get() {
        return INSTANCE;
    }

    public ExecutorService io() {
        return io;
    }

    public ExecutorService computation() {
        return computation;
    }

    public ScheduledExecutorService scheduled() {
        return scheduled;
    }

    public Handler mainThread() {
        return mainThread;
    }

    public void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainThread.post(r);
        }
    }
}
