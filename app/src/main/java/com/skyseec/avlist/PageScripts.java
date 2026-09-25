package com.skyseec.avlist;

import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * 所有注入页面的 JS 集中在这里。
 *
 * 存在的意义：以前同一段"XPath 提取"逻辑写了三遍 ——
 * X5WebView 的就绪轮询、JavResolver 的诊断、JavResolver 的最终解析各一份，
 * 三份还演化到了不一致（诊断那份多了个 } 导致 JS 解析期 SyntaxError，
 * evaluateJavascript 一直返回 null，从写出来就没工作过）。
 * 现在只保留 extractJs 一份，就绪判定 / 诊断 / 解析全部复用它。
 */
public class PageScripts {

    private PageScripts() {}

    /**
     * 自动化痕迹清理脚本。**只清痕迹，不做任何指纹伪装。**
     *
     * 这份脚本原来是一整套"伪装成 Windows 桌面 Chrome"的补丁（platform 硬改 Win32、
     * plugins 返回 [1,2,3,4,5]、maxTouchPoints 补 0、outerWidth 补 1920……）。
     * 实测指纹之后整批删掉了，原因是 UA 已经改成设备真实值（见 X5WebView.buildDefaultUa）：
     * UA 报 Android，而 platform 报 Win32、maxTouchPoints 报 0，那是**新的**矛盾，
     * 比"它就是 Android"更可疑。
     *
     * 而且这些补丁本来也盖不住：Chromium 自己报的 navigator.userAgentData.platform
     * 和 WebGL renderer 是引擎按真实平台给的，脚本改不掉 —— 一个客户端在三处互相打架。
     *
     * 留下的只有真正必要的：WebView 的自动化暴露点，以及几个上古自动化框架的残留。
     */
    public static final String STEALTH_JS =
            "(function(){" +
                    "try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});}catch(e){}" +
                    "try{delete Object.getPrototypeOf(navigator).webdriver;}catch(e){}" +
                    "try{delete window.callPhantom;}catch(e){}" +
                    "try{delete window._phantom;}catch(e){}" +
                    "try{delete window.__nightmare;}catch(e){}" +
                    "try{delete window.emit;}catch(e){}" +
                    "try{delete window.spawn;}catch(e){}" +
                    "})();";

    /**
     * 修补退化的窗口尺寸。
     *
     * 服务里的 WebView 没挂到任何窗口上，window.outerWidth/outerHeight 会报 0x0
     * —— 真实浏览器不会这样。只在值是 0（或取不到）时才补，有真实值就不碰。
     * 这是修 bug，不是伪装：值本身来自真实屏幕尺寸，只是把它放到本该有的地方。
     */
    public static String windowPatchJs() {
        return "(function(){" +
                "function fix(name,fallback){" +
                "  try{" +
                "    var v=window[name];" +
                "    if(typeof v!=='number'||v<=0){" +
                "      Object.defineProperty(window,name,{get:function(){return fallback;},configurable:true});" +
                "    }" +
                "  }catch(e){}" +
                "}" +
                "fix('outerWidth',screen.width);" +
                "fix('outerHeight',screen.height);" +
                "fix('innerWidth',screen.width);" +
                "fix('innerHeight',screen.height);" +
                "})();";
    }

    /**
     * 探测当前页面是不是人机验证的过渡页，并把"在等什么"一并带回来。
     * 用结构化信号，不看语言、不看 CF 版本 —— 标题只作为补充。
     */
    public static String detectChallengeJs() {
        return "(function(){" +
                "var t=(document.title||'');" +
                // CF 内联注入的配置对象，新版挑战页都有
                "var opt=window._cf_chl_opt||null;" +
                "var plat=!!document.querySelector('script[src*=\"/cdn-cgi/challenge-platform/\"]');" +
                "var scripts=document.querySelectorAll('script[src*=\"challenge-platform\"]').length;" +
                // 挑战脚本的地址：日志里那个 401 到底是不是它，靠这个对
                "var sc=document.querySelector('script[src*=\"challenge-platform\"]');" +
                "var src=sc?(sc.src||''):'';" +
                "if(src.length>160)src=src.substring(0,160)+'...';" +
                // _cf_chl_opt 有哪些键 —— cType 空着很反常，得知道它到底装了什么
                "var keys='';" +
                "if(opt){try{keys=Object.keys(opt).join(',');}catch(e){keys='(读不出)';}}" +
                "return JSON.stringify({" +
                "  title:t," +
                "  chlOpt:!!opt," +
                "  platformScript:plat," +
                // 注意：CF 已经把 _cf_chl_opt 的键名混淆成随机串了
                // （实测 optKeys=cITFT5,VvsrP6,DqqU0,...），所以 cType/cvId
                // 现在**读不到**，这两项会一直是空。留着是为了万一将来又变回来。
                "  cType:(opt&&opt.cType)||''," +
                "  cvId:(opt&&opt.cvId)||''," +
                "  optKeys:keys," +
                "  scriptSrc:src," +
                "  scriptCount:scripts," +
                "  bodyLen:(document.body?document.body.innerHTML.length:0)" +
                "});" +
                "})();";
    }

    /**
     * 提取标题与图片。**这是全项目唯一的 XPath 提取实现。**
     *
     * XPath 用 {@link JSONObject#quote} 编码后内插，任何字符都安全 ——
     * 不再依赖手写的 escapeJsString（那个只处理 \ 和 '，是上一批 bug 的来源）。
     *
     * 返回 JSON：{title, img, titleHits, imgHits, titleErr, imgErr}
     */
    public static String extractJs(String titleXPath, String imageXPath) {
        return "(function(){" +
                "var T=" + JSONObject.quote(titleXPath == null ? "" : titleXPath) + ";" +
                "var I=" + JSONObject.quote(imageXPath == null ? "" : imageXPath) + ";" +
                "var out={title:'',img:'',titleHits:0,imgHits:0,titleErr:'',imgErr:''};" +
                "extractTitle();" +
                "extractImage();" +
                "if(out.title.length>200)out.title=out.title.substring(0,200);" +
                "return JSON.stringify(out);" +

                // 标题：第一个有实质文本的匹配（元素节点或属性节点都能取）
                "function extractTitle(){" +
                "  if(!T)return;" +
                "  try{" +
                "    var s=document.evaluate(T,document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);" +
                "    out.titleHits=s.snapshotLength;" +
                "    for(var i=0;i<s.snapshotLength;i++){" +
                "      var n=s.snapshotItem(i);" +
                "      var v=(n.textContent||n.nodeValue||'').trim();" +
                "      if(v){out.title=v;return;}" +
                "    }" +
                "  }catch(e){out.titleErr=String(e.message||e);}" +
                "}" +

                // 图片：第一个有效 HTTP URL，跳过占位图
                "function extractImage(){" +
                "  if(!I)return;" +
                "  try{" +
                "    var s=document.evaluate(I,document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);" +
                "    out.imgHits=s.snapshotLength;" +
                "    for(var i=0;i<s.snapshotLength;i++){" +
                "      var n=s.snapshotItem(i);" +
                "      var v=(n.nodeType===2)?(n.nodeValue||''):" +
                "        (n.getAttribute('data-src')||n.getAttribute('data-original')||n.getAttribute('data-lazy')||n.src||'');" +
                "      v=v.trim();" +
                "      if(v&&v.indexOf('data:')!==0&&v.indexOf('blob:')!==0){out.img=v;return;}" +
                "    }" +
                "  }catch(e){out.imgErr=String(e.message||e);}" +
                "}" +
                "})();";
    }

    /**
     * 把"我们对外声称自己是谁"一次性打出来。
     *
     * 用途是抓自相矛盾。我们的 UA 写着 Windows 桌面 Chrome，但跑它的是 Android WebView；
     * 只要 CF 的挑战脚本去交叉验证（userAgentData / WebGL / 触摸点数 / 屏幕尺寸），
     * 任何一个都能戳穿。真机 Chrome 无痕在同一个 IP 上能顺利打开 missav.ws，
     * 说明问题不在网络，就在这些信号上 —— 那就把它们打出来对一遍。
     *
     * 注意：预热页不注入 STEALTH_JS（见 WebScrapeEngine.loadPrewarm），
     * 所以这份指纹正是 CF 在挑战页上看到的那个。
     */
    public static String fingerprintJs() {
        return "(function(){" +
                "function safe(f){try{return f();}catch(e){return '(异常)';}}" +
                // WebGL 渲染器是硬件级的，UA 能改、它改不了
                "var glRenderer='(无 WebGL)';" +
                "try{" +
                "  var c=document.createElement('canvas');" +
                "  var gl=c.getContext('webgl')||c.getContext('experimental-webgl');" +
                "  if(gl){" +
                "    var dbg=gl.getExtension('WEBGL_debug_renderer_info');" +
                "    glRenderer=dbg?String(gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL)):'(无 debug 扩展)';" +
                "  }" +
                "}catch(e){glRenderer='(异常)';}" +
                "return JSON.stringify({" +
                "  ua:safe(function(){return navigator.userAgent;})," +
                // 这一项要是和 UA 对不上，就是最直接的破绽
                "  uaDataPlatform:safe(function(){return navigator.userAgentData" +
                "    ?navigator.userAgentData.platform:'(无 userAgentData)';})," +
                "  platform:safe(function(){return navigator.platform;})," +
                "  vendor:safe(function(){return navigator.vendor;})," +
                "  webdriver:safe(function(){return String(navigator.webdriver);})," +
                // 声称桌面、却报 0 触摸点，而 ontouchstart 又在 —— 三份信号打架
                "  maxTouchPoints:safe(function(){return navigator.maxTouchPoints;})," +
                "  hasTouchEvent:safe(function(){return 'ontouchstart' in window;})," +
                "  plugins:safe(function(){return navigator.plugins?navigator.plugins.length:-1;})," +
                "  languages:safe(function(){return (navigator.languages||[]).join('/');})," +
                "  hardwareConcurrency:safe(function(){return navigator.hardwareConcurrency;})," +
                "  deviceMemory:safe(function(){return navigator.deviceMemory;})," +
                "  glRenderer:glRenderer," +
                "  screen:safe(function(){return screen.width+'x'+screen.height;})," +
                "  outer:safe(function(){return window.outerWidth+'x'+window.outerHeight;})," +
                "  dpr:safe(function(){return window.devicePixelRatio;})," +
                "  chromeKeys:safe(function(){return Object.keys(window.chrome||{}).join(',');})" +
                "});" +
                "})();";
    }

    /**
     * 解码 evaluateJavascript 的返回值。
     * 它把 JS 的结果再包一层 JSON 字符串字面量（含转义），这里还原成原始字符串。
     * 返回 null 表示求值失败（页面/渲染进程已经没了）。
     */
    public static String decode(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty() || "null".equals(t)) return null;
        if (t.charAt(0) == '"') {
            try {
                Object v = new JSONTokener(t).nextValue();
                if (v instanceof String) return (String) v;
            } catch (Exception ignored) {
                // 不是合法 JSON 字面量就当原样处理
            }
        }
        return t;
    }

    /** extractJs 的返回结构 */
    public static class ExtractResult {

        public final String title;
        public final String img;
        public final String titleErr;
        public final String imgErr;
        public final int titleHits;
        public final int imgHits;

        public ExtractResult(String title, String img, String titleErr, String imgErr,
                             int titleHits, int imgHits) {
            this.title = title == null ? "" : title;
            this.img = img == null ? "" : img;
            this.titleErr = titleErr == null ? "" : titleErr;
            this.imgErr = imgErr == null ? "" : imgErr;
            this.titleHits = titleHits;
            this.imgHits = imgHits;
        }

        public boolean isEmpty() {
            return title.isEmpty() && img.isEmpty();
        }

        /** @return 解析失败时返回 null */
        public static ExtractResult parse(String json) {
            if (json == null) return null;
            try {
                JSONObject o = new JSONObject(json);
                return new ExtractResult(
                        o.optString("title", ""),
                        o.optString("img", ""),
                        o.optString("titleErr", ""),
                        o.optString("imgErr", ""),
                        o.optInt("titleHits", -1),
                        o.optInt("imgHits", -1));
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * 诊断用的紧凑描述。
         * 光看 hits 不够 —— 有可能命中了节点但节点里没有可用值
         * （titleHits=1 而 title 为空就是这种情况），所以把提取结果也带上。
         */
        public String describe() {
            return "hits(t=" + titleHits + ",i=" + imgHits + ")"
                    + " 提取(t=" + (title.isEmpty() ? "空" : title.length() + "字")
                    + ",i=" + (img.isEmpty() ? "空" : "有") + ")"
                    + (titleErr.isEmpty() ? "" : " titleErr=" + titleErr)
                    + (imgErr.isEmpty() ? "" : " imgErr=" + imgErr);
        }
    }
}
