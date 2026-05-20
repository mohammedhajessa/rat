package com.h4ckinto.android.ui;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.h4ckinto.android.utils.LocaleHelper;

/**
 * Ensures library locale is applied to each screen (Application alone is not enough on many devices).
 */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }
}
