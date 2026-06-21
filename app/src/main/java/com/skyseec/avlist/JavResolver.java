package com.skyseec.avlist;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class JavResolver {

    private final X5WebView webView;
    private final ScrapeSource source;
    private final ResolveCallback callback;

    private boolean parseDone;
    private int retryCount;
    private static final int MAX_RETRIES = 2;
    private String scrapeKeyword;
    private long stageStartTime;
    private long overallStartTime;

    public interface ResolveCallback {
        void onResult(String title, String imgUrl, String detailUrl);
    }

    public JavResolver(X5WebView webView, ScrapeSource source, ResolveCallback callback) {
        this.webView = webView;
        this.source = source;
        this.callback = callback;
    }

    // =====================================================================
    // 入口
    // =====================================================================
    public void startScrape(String keyword) {
        if (source == null) {
            LogUtil.w("【刮削】源为空，无法刮削");
            callback.onResult(null, null, null);
            return;
        }
        this.scrapeKeyword = keyword;
        this.retryCount = 0;
        this.overallStartTime = System.currentTimeMillis();

        LogUtil.i("══════ 刮削开始 ══════");
        LogUtil.i("【源】" + source.getName());
        LogUtil.i("【番号】" + keyword);
        LogUtil.i("【搜索URL】" + source.buildSearchUrl(keyword));
        LogUtil.i("【预热】" + source.isNeedsPrewarm() + " 等待=" + source.getWaitSeconds() + "s");
        LogUtil.i("【标题XPath】" + source.getTitleXPath());
        LogUtil.i("【图片XPath】" + source.getImageXPath());

        if (source.isNeedsPrewarm()) {
            prewarmThenSearch();
        } else {
            loadSearchPage();
        }
    }

    // =====================================================================
    // 预热
    // =====================================================================
    private void prewarmThenSearch() {
        String prewarmUrl = source.getPrewarmUrl();
        if (prewarmUrl.isEmpty()) {
            LogUtil.w("【预热】预热URL为空，跳过");
            loadSearchPage();
            return;
        }

        LogUtil.i("【预热】开始 " + prewarmUrl);
        stageStartTime = System.currentTimeMillis();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                LogUtil.i("【预热·onPageStarted】+" + (System.currentTimeMillis() - stageStartTime) + "ms url=" + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                long elapsed = System.currentTimeMillis() - stageStartTime;
                LogUtil.i("【预热·onPageFinished】+" + elapsed + "ms url=" + url);
                if (!url.equals(prewarmUrl)) {
                    LogUtil.i("【预热】被重定向: " + prewarmUrl + " → " + url);
                }

                view.evaluateJavascript("document.title", titleResult -> {
                    String t = unquote(titleResult);
                    LogUtil.i("【预热·title】" + t);

                    view.evaluateJavascript(
                        "JSON.stringify({" +
                        "cfForm:!!document.querySelector('#challenge-form')," +
                        "cfRunning:!!document.querySelector('.cf-challenge-running')," +
                        "cfVerify:!!document.querySelector('.cf-browser-verification')," +
                        "cfIframe:!!document.querySelector('iframe[src*=\"cloudflare\"]')" +
                        "})",
                        cfJson -> {
                            boolean cfDetected = t.contains("Just a moment") || t.toLowerCase().contains("attention required");
                            LogUtil.i("【预热·CF检测】" + cfJson + " cfDetect=" + cfDetected);

                            if (cfDetected) {
                                LogUtil.i("【预热】CF挑战，等5秒复查...");
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    view.evaluateJavascript("document.title", title2 -> {
                                        String t2 = unquote(title2);
                                        LogUtil.i("【预热·title复查】" + t2 + " stillCF=" + (t2.contains("Just a moment") || t2.contains("cf-")));
                                        loadSearchPage();
                                    });
                                }, 5000);
                            } else {
                                LogUtil.i("【预热】完成，2秒后搜索");
                                new Handler(Looper.getMainLooper()).postDelayed(
                                        JavResolver.this::loadSearchPage, 2000);
                            }
                        });
                });
            }
        });

        webView.loadUrl(prewarmUrl);
    }

    // =====================================================================
    // 搜索页
    // =====================================================================
    private void loadSearchPage() {
        String url = source.buildSearchUrl(scrapeKeyword);
        parseDone = false;
        stageStartTime = System.currentTimeMillis();

        LogUtil.i("── 搜索 [" + (retryCount + 1) + "/" + (MAX_RETRIES + 1) + "] ──");
        LogUtil.i("【搜索·URL】" + url);

        webView.injectStealthScript();

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                LogUtil.e("【搜索·网络错误】code=" + errorCode + " " + description + " url=" + failingUrl, null);
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                LogUtil.w("【搜索·HTTP错误】" + errorResponse.getStatusCode() + " " + request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                LogUtil.i("【搜索·onPageStarted】+" + (System.currentTimeMillis() - stageStartTime) + "ms url=" + url);
                webView.injectStealthScript();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                long elapsed = System.currentTimeMillis() - stageStartTime;
                LogUtil.i("【搜索·onPageFinished】+" + elapsed + "ms url=" + url);

                // 页面诊断
                view.evaluateJavascript(
                    "(function(){" +
                    "var t=document.title||'';" +
                    "var bodyLen=document.body?document.body.innerHTML.length:0;" +
                    "var thumbCnt=document.querySelectorAll('.thumbnail').length;" +
                    "var boxCnt=document.querySelectorAll('.movie-box').length;" +
                    "var cfForm=!!document.querySelector('#challenge-form');" +
                    "return JSON.stringify({title:t,bodyLen:bodyLen,thumbCnt:thumbCnt,boxCnt:boxCnt,cf:cfForm});" +
                    "})()",
                    diag -> LogUtil.i("【搜索·诊断】" + diag)
                );

                // XPath 命中测试
                String titleXPath = source.getTitleXPath();
                String imageXPath = source.getImageXPath();
                view.evaluateJavascript(
                    "(function(){" +
                    "var tiCnt=0,tiVal='',imCnt=0,imVal='';" +
                    "try{var ts=document.evaluate('" + escapeJsString(titleXPath) + "',document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);" +
                    "  tiCnt=ts.snapshotLength;" +
                    "  for(var ti=0;ti<tiCnt;ti++){" +
                    "    var tn=ts.snapshotItem(ti);" +
                    "    var tv=(tn.textContent||tn.nodeValue||'').trim();" +
                    "    if(tv){tiVal=tv.substring(0,80);break;}" +
                    "  }}" +
                    "}catch(e){tiCnt=-1;tiVal=e.message;}" +
                    "try{var is=document.evaluate('" + escapeJsString(imageXPath) + "',document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);" +
                    "  imCnt=is.snapshotLength;" +
                    "  for(var ii=0;ii<imCnt;ii++){" +
                    "    var im=is.snapshotItem(ii);" +
                    "    var iv=im.nodeType===2?im.nodeValue:(im.getAttribute('data-src')||im.getAttribute('data-original')||im.getAttribute('data-lazy')||im.src||'');" +
                    "    if(iv&&!iv.startsWith('data:')){imVal=iv.substring(0,120);break;}" +
                    "  }}" +
                    "}catch(e){imCnt=-1;imVal=e.message;}" +
                    "return JSON.stringify({titleHit:tiCnt,titleVal:tiVal,imgHit:imCnt,imgVal:imVal});" +
                    "})()",
                    xpathDiag -> LogUtil.i("【搜索·XPath命中】" + xpathDiag)
                );

                // DOM就绪轮询
                int baseWait = source.getWaitSeconds();
                if (baseWait < 1) baseWait = 3;
                final int waitSec = baseWait * (retryCount + 1);
                LogUtil.i("【搜索·等待】最长" + waitSec + "s (base=" + baseWait + " retry=" + retryCount + ")");

                webView.injectReadyCheck(titleXPath, imageXPath, waitSec);

                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    int elapsed = 0;
                    final int interval = 200;
                    final int maxWait = (waitSec + 3) * 1000;
                    int lastLogAt = 0;

                    @Override
                    public void run() {
                        if (parseDone) return;
                        elapsed += interval;
                        if (elapsed - lastLogAt >= 2000) {
                            lastLogAt = elapsed;
                            webView.evaluateJavascript(
                                "JSON.stringify({ready:window.__scrapeReady,count:window.__scrapeCheckCount||0,max:window.__scrapeMaxCheck||0,diag:window.__scrapeDiag||{}})",
                                pollState -> LogUtil.i("【轮询·" + (elapsed/1000) + "s】" + pollState)
                            );
                        }
                        webView.evaluateJavascript("window.__scrapeReady", ready -> {
                            if (parseDone) return;
                            if ("true".equals(ready) || elapsed >= maxWait) {
                                parseDone = true;
                                webView.stopReadyCheck();
                                LogUtil.i("【轮询】" + ("true".equals(ready) ? "DOM就绪(" + elapsed + "ms)" : "超时(" + elapsed + "ms)") );
                                parseAndHandle(webView);
                            } else {
                                new Handler(Looper.getMainLooper()).postDelayed(this, interval);
                            }
                        });
                    }
                }, 200);
            }
        });

        webView.loadUrl(url);
    }

    // =====================================================================
    // 解析 + 空结果重试
    // =====================================================================
    private void parseAndHandle(WebView view) {
        LogUtil.i("── 解析 ──");

        String titleXPath = escapeJsString(source.getTitleXPath());
        String imageXPath = escapeJsString(source.getImageXPath());

        // 核心解析 JS：用 ORDERED_NODE_SNAPSHOT 遍历所有匹配，跳过空值
        String js =
                "(function(){" +
                "try{" +
                "var title='';" +
                "var img='';" +
                // 标题：遍历匹配，取第一个有实质文本的
                "if('" + titleXPath + "'){" +
                "  try{" +
                "    var ts=document.evaluate('" + titleXPath + "',document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);" +
                "    for(var ti=0;ti<ts.snapshotLength;ti++){" +
                "      var tn=ts.snapshotItem(ti);" +
                "      var tv=(tn.textContent||tn.nodeValue||'').trim();" +
                "      if(tv){title=tv;break;}" +
                "    }" +
                "  }catch(e){}" +
                "}" +
                // 图片：遍历匹配，取第一个有效 HTTP URL
                "if('" + imageXPath + "'){" +
                "  try{" +
                "    var isSnap=document.evaluate('" + imageXPath + "',document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);" +
                "    for(var ii=0;ii<isSnap.snapshotLength;ii++){" +
                "      var im=isSnap.snapshotItem(ii);" +
                "      var iv='';" +
                "      if(im.nodeType===2){" +  // ATTRIBUTE_NODE
                "        iv=im.nodeValue||'';" +
                "      }else{" +  // ELEMENT_NODE
                "        iv=im.getAttribute('data-src')||im.getAttribute('data-original')||im.getAttribute('data-lazy')||im.src||'';" +
                "      }" +
                "      iv=iv.trim();" +
                "      if(iv&&!iv.startsWith('data:')&&!iv.startsWith('blob:')){img=iv;break;}" +
                "    }" +
                "  }catch(e){}" +
                "}" +
                // 截断过长标题
                "if(title&&title.length>200)title=title.substring(0,200);" +
                "return title+'|||'+img+'|||';" +
                "}catch(e){return 'ERROR|||ERROR|||'+e.message;}" +
                "})();";

        view.evaluateJavascript(js, result -> {
            // 记录原始返回值（诊断用）
            LogUtil.i("【解析·rawResult】" + result);

            String raw = result;
            if (raw == null) {
                LogUtil.e("【解析】evaluateJavascript返回null", null);
                callback.onResult(null, null, view.getUrl());
                return;
            }
            // 去 JSON 引号
            if (raw.startsWith("\"") && raw.endsWith("\""))
                raw = raw.substring(1, raw.length() - 1);
            raw = raw.replace("\\\"", "\"");
            raw = raw.replace("\\\\", "\\");

            LogUtil.i("【解析·afterUnescape】" + raw);

            String[] arr = raw.split("\\|\\|\\|");
            String title = arr.length > 0 ? arr[0] : "";
            String img = arr.length > 1 ? arr[1] : "";

            // 过滤
            if (img.startsWith("data:") || img.startsWith("blob:")) {
                LogUtil.i("【解析】占位图，丢弃");
                img = "";
            }
            if ("null".equalsIgnoreCase(title) || "undefined".equalsIgnoreCase(title) || "error".equalsIgnoreCase(title)) title = "";
            if ("null".equalsIgnoreCase(img) || "undefined".equalsIgnoreCase(img) || "error".equalsIgnoreCase(img)) img = "";

            // 清理 nbsp
            title = title.replace(" ", " ").replace("　", " ").trim();
            img = img.replace(" ", "").trim();

            LogUtil.i("【解析·结果】title=[" + title + "] img=[" + (img.isEmpty() ? "(空)" : img) + "]");

            // 空结果重试
            if (title.isEmpty() && img.isEmpty() && retryCount < MAX_RETRIES) {
                retryCount++;
                LogUtil.i("【解析】空，第" + retryCount + "次重试（延迟" + (retryCount * 3) + "s...");
                webView.clearCache(true);
                new Handler(Looper.getMainLooper()).postDelayed(
                        this::loadSearchPage, retryCount * 3000L);
                return;
            }

            if (title.isEmpty() && img.isEmpty()) {
                LogUtil.w("【解析】空（已重试" + MAX_RETRIES + "次）");
            }

            long total = System.currentTimeMillis() - overallStartTime;
            LogUtil.i("══════ 刮削结束 总耗时=" + total + "ms ══════");

            callback.onResult(title, img, view.getUrl());
        });
    }

    // =====================================================================
    // 工具
    // =====================================================================
    private static String unquote(String s) {
        if (s == null) return "";
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }

    private static String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
