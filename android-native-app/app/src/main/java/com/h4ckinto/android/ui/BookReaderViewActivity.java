package com.h4ckinto.android.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;

import com.h4ckinto.android.R;

/**
 * Activity to read/view book files (PDF, EPUB) using WebView
 */
public class BookReaderViewActivity extends BaseActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private ImageView btnBack;
    private TextView titleText;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Setup fullscreen
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );
        View decorView = getWindow().getDecorView();
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), decorView);
        controller.setAppearanceLightStatusBars(false);
        
        setContentView(R.layout.activity_book_reader_view);
        
        webView = findViewById(R.id.book_webview);
        progressBar = findViewById(R.id.progress_bar);
        btnBack = findViewById(R.id.btn_back);
        titleText = findViewById(R.id.book_title_text);
        
        String bookTitle = getIntent().getStringExtra("book_title");
        String bookFileUrl = getIntent().getStringExtra("book_file_url");
        
        if (bookTitle != null) {
            titleText.setText(bookTitle);
        }
        
        btnBack.setOnClickListener(v -> finish());
        
        // Configure WebView
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setSupportZoom(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }
        });
        
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
        
        // Load book
        if (bookFileUrl != null && !bookFileUrl.isEmpty()) {
            if (bookFileUrl.toLowerCase().endsWith(".pdf")) {
                // Use Google Docs Viewer for PDF
                String pdfViewerUrl = "https://docs.google.com/gview?embedded=true&url=" + bookFileUrl;
                webView.loadUrl(pdfViewerUrl);
            } else {
                // Load directly for other formats
                webView.loadUrl(bookFileUrl);
            }
        }
    }
    
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
    
    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}


