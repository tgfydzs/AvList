package com.skyseec.avlist;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class JavResolver {

    private final X5WebView webView;
    private final ResolveCallback callback;

    public interface ResolveCallback {
        void onResult(String title, String imgUrl, String detailUrl);
    }

    public JavResolver(X5WebView webView, ResolveCallback callback) {
        this.webView = webView;
        this.callback = callback;
    }

    public void startScrape(String keyword) {

        // =========================
        // URL 修正：& → ?（查询参数起始符）
        // =========================
        String url = "https://www.javbus.com/search/" + keyword + "?type=&parent=ce";

        LogUtil.i("URL=" + url);

        // =========================
        // 注入反爬脚本后再加载页面
        // =========================
        webView.injectStealthScript();

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                LogUtil.i("页面加载完成 url=" + url);

                // 延迟 1.5s 等待 CF 挑战完成后 DOM 渲染
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    parse(view);
                }, 1500);
            }
        });

        webView.loadUrl(url);
    }

    private void parse(WebView view) {

        // =========================
        // 针对真实 DOM 结构精确匹配
        // .movie-box > .photo-frame > img[title] = 标题
        // .movie-box > .photo-frame > img[src]  = 封面缩略图
        // .movie-box > .photo-info > span > date = 番号/日期
        // =========================
        String js =
                "(function(){" +
                        "try{" +

                        "let title = '';" +
                        "let img = '';" +
                        "let code = '';" +

                        // 策略1：搜索结果页——第一个 .movie-box
                        "let box = document.querySelector('.movie-box');" +
                        "if (box) {" +

                        // 标题：取 .photo-frame 下 img 的 title 属性
                        "  let pfImg = box.querySelector('.photo-frame img');" +
                        "  if (pfImg) {" +
                        "    title = (pfImg.getAttribute('title') || '').trim();" +
                        "    img = pfImg.getAttribute('src') || '';" +
                        "  }" +

                        // 番号：取 .photo-info date 第一个
                        "  let dateEl = box.querySelector('.photo-info date');" +
                        "  if (dateEl) code = (dateEl.textContent || '').trim();" +

                        // 如果 img title 没取到，从 .photo-info span 文本提取
                        "  if (!title) {" +
                        "    let piSpan = box.querySelector('.photo-info span');" +
                        "    if (piSpan) {" +
                        "      let txt = piSpan.childNodes[0] ? piSpan.childNodes[0].textContent.trim() : '';" +
                        "      if (txt) title = txt;" +
                        "    }" +
                        "  }" +
                        "}" +

                        // 策略2：详情页（直接跳转到 /jav/XXX 时）
                        "if (!title) {" +
                        "  let h3 = document.querySelector('.container h3');" +
                        "  if (h3) title = h3.textContent.trim();" +
                        "}" +
                        "if (!img) {" +
                        "  let bigImg = document.querySelector('.bigImage img');" +
                        "  if (bigImg) img = bigImg.getAttribute('src') || '';" +
                        "}" +

                        // 策略3：XPath 兜底
                        "if (!title) {" +
                        "  try { title = document.evaluate('/html/body/div[5]/h3', document, null, XPathResult.STRING_TYPE, null).stringValue.trim(); } catch(e) {}" +
                        "}" +
                        "if (!img) {" +
                        "  try { let n = document.evaluate('/html/body/div[5]/div[1]/div[1]/a/img', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; if (n) img = n.getAttribute('src') || ''; } catch(e) {}" +
                        "}" +

                        "return title + '|||' + img + '|||' + code;" +

                        "}catch(e){return 'ERROR|||ERROR|||' + e.message;}" +
                        "})();";

        view.evaluateJavascript(js, result -> {

            try {
                String raw = result.replace("\"", "");
                String[] arr = raw.split("\\|\\|\\|");

                String title = arr.length > 0 ? arr[0] : "";
                String img = arr.length > 1 ? arr[1] : "";
                String scrapedCode = arr.length > 2 ? arr[2] : "";

                LogUtil.i("RAW title=[" + title + "] img=[" + img + "] code=[" + scrapedCode + "]");

                if (title.isEmpty() && img.isEmpty()) {
                    LogUtil.w("刮削结果为空，可能页面结构已变更或CF拦截");
                }

                callback.onResult(title, img, view.getUrl());

            } catch (Exception e) {
                LogUtil.e("解析失败", e);
                callback.onResult(null, null, null);
            }
        });
    }
}