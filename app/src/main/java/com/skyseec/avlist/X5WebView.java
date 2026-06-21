package com.skyseec.avlist;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class X5WebView extends WebView {

    public X5WebView(Context context) {
        super(context);
        initConfig();
    }

    public X5WebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initConfig();
    }

    private void initConfig() {
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // PC Chrome UA
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
        settings.setUserAgentString(ua);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(this, true);
        cookieManager.flush();

        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    public void injectStealthScript() {
        // 强化指纹伪装
        String js = "(function(){" +
                "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});" +
                "delete navigator.webdriver;" +
                "Object.defineProperty(navigator, 'plugins', {get: () => [{}]});" +
                "Object.defineProperty(navigator, 'languages', {get: () => ['zh-CN','zh','en-US']});" +
                "Object.defineProperty(navigator, 'platform', {get: () => 'Win32'});" +
                "window.chrome = { runtime: {} };" +
                "})();";
        evaluateJavascript(js, null);
    }
}