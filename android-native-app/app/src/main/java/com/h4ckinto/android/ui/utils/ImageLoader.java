package com.h4ckinto.android.ui.utils;

/**
 * @deprecated Use {@link com.h4ckinto.android.utils.ImageLoader} instead.
 * This class is kept only for backward compatibility and delegates to the unified loader.
 */
@Deprecated
public class ImageLoader {

    private static final com.h4ckinto.android.utils.ImageLoader delegate =
            com.h4ckinto.android.utils.ImageLoader.getInstance();

    private static ImageLoader instance;

    public static synchronized ImageLoader getInstance() {
        if (instance == null) instance = new ImageLoader();
        return instance;
    }

    public void load(String url, android.widget.ImageView imageView, int placeholder) {
        delegate.load(url, imageView, placeholder);
    }

    public void load(String url, android.widget.ImageView imageView) {
        delegate.load(url, imageView);
    }

    public void clearCache() {
        delegate.clearCache();
    }
}
