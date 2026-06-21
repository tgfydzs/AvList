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
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
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
        String js =
                "(function(){" +
                        "Object.defineProperty(navigator,'webdriver',{get:()=>false});" +
                        "delete Object.getPrototypeOf(navigator).webdriver;" +
                        "Object.defineProperty(navigator,'plugins',{" +
                        "  get:()=>[1,2,3,4,5]," +
                        "  configurable:true" +
                        "});" +
                        "Object.defineProperty(navigator,'languages',{get:()=>['zh-CN','zh','en-US','en']});" +
                        "Object.defineProperty(navigator,'language',{get:()=>'zh-CN'});" +
                        "Object.defineProperty(navigator,'platform',{get:()=>'Win32'});" +
                        "Object.defineProperty(navigator,'vendor',{get:()=>'Google Inc.'});" +
                        "Object.defineProperty(navigator,'vendorSub',{get:()=>''});" +
                        "Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>8});" +
                        "Object.defineProperty(navigator,'deviceMemory',{get:()=>8});" +
                        "Object.defineProperty(navigator,'maxTouchPoints',{get:()=>0});" +
                        "window.chrome={runtime:{},loadTimes:function(){},csi:function(){},app:{}};" +
                        "if(navigator.permissions){" +
                        "  var _query=navigator.permissions.query;" +
                        "  navigator.permissions.query=function(p){" +
                        "    return Promise.resolve({state:'prompt',onchange:null});" +
                        "  };" +
                        "}" +
                        "Object.defineProperty(navigator,'mediaDevices',{get:()=>({enumerateDevices:()=>Promise.resolve([])})});" +
                        "Object.defineProperty(screen,'colorDepth',{get:()=>24});" +
                        "Object.defineProperty(screen,'pixelDepth',{get:()=>24});" +
                        "Object.defineProperty(window,'outerWidth',{get:()=>1920});" +
                        "Object.defineProperty(window,'outerHeight',{get:()=>1080});" +
                        "if(navigator.connection){" +
                        "  Object.defineProperty(navigator.connection,'rtt',{get:()=>50});" +
                        "}" +
                        "delete window.callPhantom;" +
                        "delete window._phantom;" +
                        "delete window.__nightmare;" +
                        "delete window.emit;" +
                        "delete window.spawn;" +
                        "})();";
        evaluateJavascript(js, null);
    }

    /**
     * 注入DOM就绪轮询。
     * 关键：这里的XPath可能指向元素节点（如 //div/a）或属性节点（如 //img/@data-src）。
     * 轮询检查时统一处理两种节点类型。
     */
    public void injectReadyCheck(String titleXPath, String imageXPath, int maxSeconds) {
        String pollingJs =
                "(function(){" +
                        "window.__scrapeReady=false;" +
                        "window.__scrapeCheckCount=0;" +
                        "window.__scrapeMaxCheck=" + (maxSeconds * 10) + ";" +
                        "window.__scrapeDiag={tnFound:0,imFound:0,textSample:'',srcSample:'',nodeType:''};" +
                        "window.__scrapeCheckInterval=setInterval(function(){" +
                        "  window.__scrapeCheckCount++;" +
                        "  var textOk=false,imgOk=false;" +
                        // 标题：取第一个有实质文本的匹配
                        "  try{" +
                        "    var tnSnapshot=document.evaluate('" + escapeJsString(titleXPath) + "',document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);" +
                        "    for(var ti=0;ti<tnSnapshot.snapshotLength;ti++){" +
                        "      var tn=tnSnapshot.snapshotItem(ti);" +
                        "      var tc=(tn.textContent||tn.nodeValue||'').trim();" +
                        "      if(tc){textOk=true;window.__scrapeDiag.tnFound=1;window.__scrapeDiag.textSample=tc.substring(0,80);break;}" +
                        "    }" +
                        "  }catch(e){}" +
                        // 图片：统一处理元素节点和属性节点
                        "  try{" +
                        "    var imSnapshot=document.evaluate('" + escapeJsString(imageXPath) + "',document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);" +
                        "    for(var ii=0;ii<imSnapshot.snapshotLength;ii++){" +
                        "      var im=imSnapshot.snapshotItem(ii);" +
                        "      var isrc='';" +
                        "      if(im.nodeType===2){" + // ATTRIBUTE_NODE
                        "        isrc=im.nodeValue||'';" +
                        "        window.__scrapeDiag.nodeType='attr';" +
                        "      }else{" + // ELEMENT_NODE
                        "        isrc=im.getAttribute('data-src')||im.getAttribute('data-original')||im.getAttribute('data-lazy')||im.src||'';" +
                        "        window.__scrapeDiag.nodeType='elem';" +
                        "      }" +
                        "      if(isrc&&!isrc.startsWith('data:')&&!isrc.startsWith('blob:')){" +
                        "        imgOk=true;window.__scrapeDiag.imFound=1;window.__scrapeDiag.srcSample=isrc.substring(0,100);break;" +
                        "      }" +
                        "    }" +
                        "  }catch(e){}" +
                        "  if(textOk||imgOk||window.__scrapeCheckCount>=window.__scrapeMaxCheck){" +
                        "    clearInterval(window.__scrapeCheckInterval);" +
                        "    window.__scrapeReady=true;" +
                        "  }" +
                        "},100);" +
                        "})();";
        evaluateJavascript(pollingJs, null);
    }

    public void stopReadyCheck() {
        evaluateJavascript(
                "if(window.__scrapeCheckInterval){clearInterval(window.__scrapeCheckInterval);window.__scrapeReady=true;}",
                null);
    }

    private static String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
