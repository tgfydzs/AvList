package com.skyseec.avlist;

import android.content.Context;
import android.graphics.Bitmap;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;
import java.util.Map;

/**
 * WebView 的唯一归属方。
 *
 * 存在的意义：以前 ScrapeService 只是 new 一个 WebView 就交给 JavResolver，
 * 而 JavResolver 每任务一个、却在共享 WebView 上每个阶段重设一次 setWebViewClient
 * （上一个任务的回调可能打到下一个任务的 client 上）；渲染进程死了也无人知晓。
 *
 * 现在 WebViewClient 只在本类里 set 一次，业务侧只调 load()/eval() 这类
 * 一次性、可取消的操作，不再接触 WebViewClient。
 */
public class WebScrapeEngine {

    private static final String TAG = "【引擎】";

    /** 页面加载完成（主框架 onPageFinished） */
    public interface LoadCallback {
        void onLoaded(String url);
    }

    public interface OnRendererGoneListener {
        void onRendererGone(boolean crashed);
    }

    private final Context context;
    private X5WebView webView;

    private LoadCallback pendingLoadCallback;
    private OnRendererGoneListener rendererGoneListener;
    private boolean destroyed;
    private boolean pendingIsPrewarm;

    public WebScrapeEngine(Context context) {
        this.context = context.getApplicationContext();
        createWebView();
    }

    private void createWebView() {
        webView = new X5WebView(context);
        webView.setWebViewClient(new EngineClient());
    }

    public void setOnRendererGoneListener(OnRendererGoneListener listener) {
        this.rendererGoneListener = listener;
    }

    /**
     * 设置本次刮削要用的 UA（空 = 系统默认）。必须在 load() 之前调。
     * 底层是同一个 WebView，所以每个任务开始时都要重设一次。
     */
    public void applyUserAgent(String custom) {
        if (webView != null) {
            webView.applyUserAgent(custom);
        }
    }

    /**
     * 加载搜索页等业务页面。会注入 stealth。
     * 一次只有一个在途请求，新的会顶掉上一个的回调。
     */
    public void load(String url, LoadCallback callback) {
        startLoad(url, callback, false);
    }

    /**
     * 加载预热页 —— **故意不注入 stealth**。
     *
     * CF 的挑战就是在预热页上真正执行的，糊上那套假指纹反而会让挑战跑不完：
     * 实测注入之后 45 秒全程卡在「请稍候…」，搜索页必然 403。
     * 原版就是"预热页裸跑、搜索页才注入"，这是能过 CF 的关键组合之一，
     * 别当成"漏注入的 bug"顺手补上（我补过一次，补坏了）。
     */
    public void loadPrewarm(String url, LoadCallback callback) {
        startLoad(url, callback, true);
    }

    private void startLoad(String url, LoadCallback callback, boolean isPrewarm) {
        if (webView == null) {
            LogUtil.w(TAG + "load 被忽略：WebView 不可用");
            return;
        }
        pendingLoadCallback = callback;
        pendingIsPrewarm = isPrewarm;
        // loadUrl 之前先注入一次，onPageStarted 里还会再注入一次，
        // 尽量赶在页面自己的脚本之前。窗口修补对预热页也做（挑战在那页上跑）。
        webView.patchWindowSize();
        if (!isPrewarm) {
            webView.injectStealthScript();
        }
        webView.loadUrl(url);
    }

    /** 清 HTTP 缓存（不清 cookie）。原版每次重试前都会清一次，保持一致。 */
    public void clearCache() {
        if (webView != null) {
            webView.clearCache(true);
        }
    }

    /**
     * 求值一段 JS。evaluateJavascript 是 IPC 求值、不是页面定时器，
     * 因此不受后台页面定时器节流影响 —— 就绪检测靠它才能在后台上作。
     */
    public void eval(String js, ValueCallback<String> callback) {
        if (webView == null) return;
        webView.evaluateJavascript(js, callback);
    }

    public String getUrl() {
        return webView == null ? null : webView.getUrl();
    }

    /** 中止当前加载并丢弃在途回调（任务结束/被取消时调） */
    public void stop() {
        pendingLoadCallback = null;
        if (webView != null) {
            webView.stopLoading();
        }
    }

    /** 渲染进程死亡后重建底层 WebView，引擎本身继续可用 */
    public void rebuild() {
        if (destroyed) return;
        LogUtil.i(TAG + "重建 WebView");
        destroyWebView();
        createWebView();
    }

    public void destroy() {
        destroyed = true;
        pendingLoadCallback = null;
        destroyWebView();
    }

    private void destroyWebView() {
        if (webView == null) return;
        try {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
        } catch (Exception e) {
            LogUtil.e(TAG + "销毁 WebView 异常", e);
        }
        webView = null;
    }

    // =====================================================================
    // 引擎独占的 WebViewClient
    // =====================================================================
    private class EngineClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (view instanceof X5WebView) {
                X5WebView v = (X5WebView) view;
                // 窗口尺寸修补预热页也要做 —— 挑战是在预热页上跑的，而那个
                // 没挂窗口的 WebView 会报 outer=0x0，是给 CF 看的
                v.patchWindowSize();
                // 痕迹清理预热页不注入，理由见 loadPrewarm
                if (!pendingIsPrewarm) {
                    v.injectStealthScript();
                }
            }
            LogUtil.i(TAG + "onPageStarted " + url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            LogUtil.i(TAG + "onPageFinished " + url);
            if (pendingIsPrewarm) {
                // 预热页是挑战真正跑的地方，也是唯一没注入 stealth 的页面 ——
                // CF 看到的就是这份指纹。打一次，用来对"我们声称自己是谁"和"实际是什么"。
                view.evaluateJavascript(PageScripts.fingerprintJs(),
                        raw -> LogUtil.i(TAG + "预热页指纹 " + PageScripts.decode(raw)));
            }
            LoadCallback cb = pendingLoadCallback;
            pendingLoadCallback = null;   // 一次性
            if (cb != null) cb.onLoaded(url);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            boolean crashed = detail != null && detail.didCrash();
            LogUtil.e(TAG + "渲染进程终止 crashed=" + crashed
                    + (detail != null ? " priorityAtExit=" + detail.rendererPriorityAtExit() : ""), null);
            pendingLoadCallback = null;
            if (rendererGoneListener != null) {
                rendererGoneListener.onRendererGone(crashed);
            }
            // 返回 true 表示已处理，避免系统直接杀掉整个应用
            return true;
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            LogUtil.e(TAG + "网络错误 code=" + errorCode + " " + description + " url=" + failingUrl, null);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            // 403 可见性是排查 CF 的关键，保留。
            // 连响应头一起打：只看状态码分不清 CF 是"要考我们"还是"直接拒绝我们"，
            // 这两种情况在响应头里是有区别的（cf-mitigated），而且能看出 Set-Cookie 到底给没给。
            LogUtil.w(TAG + "HTTP错误 " + errorResponse.getStatusCode() + " " + request.getUrl()
                    + " 头=" + describeHeaders(errorResponse));
        }

        /** 只看排查 CF 用得上的那几个响应头，其余（content-type 之类）是噪音 */
        private String describeHeaders(WebResourceResponse resp) {
            try {
                Map<String, String> headers = resp.getResponseHeaders();
                if (headers == null || headers.isEmpty()) return "(无)";
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    String k = e.getKey();
                    if (k == null) continue;
                    String lower = k.toLowerCase(Locale.ROOT);
                    if (!lower.startsWith("cf-") && !lower.equals("set-cookie")
                            && !lower.equals("server") && !lower.equals("location")) {
                        continue;
                    }
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(k).append('=').append(e.getValue());
                }
                return sb.length() == 0 ? "(无相关头)" : sb.toString();
            } catch (Exception e) {
                return "(读取失败)";
            }
        }
    }
}
