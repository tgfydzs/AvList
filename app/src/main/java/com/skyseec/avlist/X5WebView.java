package com.skyseec.avlist;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
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
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        defaultUa = buildDefaultUa();
        settings.setUserAgentString(defaultUa);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(this, true);
        cookieManager.flush();

        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 渲染进程在应用不可见时默认会被"放弃"（waivedWhenNotVisible=true），
        // 系统可以随时回收它 —— 一回收，所有 evaluateJavascript 回调就永远不来了。
        // 刮削要在后台跑，所以这里不让它被放弃。
        setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false);

        // CF 的挑战脚本出错只会出现在这里 —— 页面上、网络里都看不出来。
        // 之前排查 CF 全程没有这份输出，等于闭着眼调。
        setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                String msg = cm.message();
                if (msg != null && msg.length() > 300) msg = msg.substring(0, 300) + "...";
                LogUtil.w("【页面控制台】" + cm.messageLevel() + " " + msg
                        + " @" + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }
        });

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    /** 取不到系统默认 UA 时的兜底，形状是标准 Android Chrome */
    private static final String FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Mobile Safari/537.36";

    /** 本设备的默认 UA，initConfig 里算一次 */
    private String defaultUa;

    /**
     * 默认 UA —— **设备真实 UA，不再伪装 Windows 桌面**。
     *
     * 改的原因（有实测数据，不是推理）：指纹打出来是这个样子 ——
     *   ua              = Windows NT 10.0 … Chrome/131     ← 我们声称的
     *   uaDataPlatform  = Android                          ← Chromium 按真实平台报的
     *   glRenderer      = 手机 GPU（模拟器上直接写着 "Emulator"）
     * 后两项是引擎按真实平台报的，改不掉。一个客户端在三处互相打架，
     * 比"它就是 Android"本身可疑得多。真机 Chrome 无痕在同一个 IP 上能顺利打开
     * missav.ws，差别就在它没撒谎。
     *
     * 取系统默认 UA，再抹掉 WebView 的两处暴露点：
     *   "; wv)"        —— WebView 独有标记
     *   "Version/4.0 " —— Chrome for Android 没有这一段
     * 抹完就是标准 Android Chrome 的 UA 形状。
     *
     * ⚠️ 这个 UA 和 {@link PageScripts#STEALTH_JS} 是一套的：既然 UA 报 Android，
     * 就不能再把 platform 补成 Win32、maxTouchPoints 补成 0 —— 那是新的矛盾。
     *
     * 个别站点如果对移动端返回不同 HTML 导致 XPath 失配，在「刮削源管理」里
     * 给该源单独填 UA 即可（留空 = 用这个）。
     */
    private String buildDefaultUa() {
        try {
            String ua = WebSettings.getDefaultUserAgent(getContext());
            if (ua != null && !ua.trim().isEmpty()) {
                return ua.replace("; wv)", ")").replace("Version/4.0 ", "").trim();
            }
        } catch (Exception e) {
            LogUtil.w("取系统默认 UA 失败，用兜底: " + e);
        }
        return FALLBACK_UA;
    }

    /**
     * 应用 UA。传空则回到设备默认。
     * 这是给个别站点留的兜底 —— 万一默认 UA 拿到不同 HTML 导致 XPath 失配，
     * 可以在「刮削源管理」里给该源单独填一个（比如填回桌面版 UA）。
     */
    public void applyUserAgent(String custom) {
        String ua = (custom == null || custom.trim().isEmpty()) ? defaultUa : custom.trim();
        getSettings().setUserAgentString(ua);
    }

    /**
     * 注入脚本，只清理自动化痕迹，不做任何指纹伪装。
     * 由 WebScrapeEngine 在 loadUrl 前和每个 onPageStarted 各调一次。
     */
    public void injectStealthScript() {
        evaluateJavascript(PageScripts.STEALTH_JS, null);
    }

    /**
     * 修补退化的窗口尺寸，预热页也要调。
     *
     * 服务里的 WebView 从来没挂到任何窗口上，window.outerWidth/outerHeight 报 0x0
     * —— 真实浏览器不会这样，是个很干净、很好用的破绽。挑战是在预热页上跑的，
     * 所以那个页面同样需要修。
     */
    public void patchWindowSize() {
        evaluateJavascript(PageScripts.windowPatchJs(), null);
    }
}
