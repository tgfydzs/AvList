package com.skyseec.avlist;

import android.webkit.CookieManager;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 人机验证（Cloudflare 等）过渡页的识别。
 *
 * 独立出来的原因：这段判断以前写死在 JavResolver.prewarmThenSearch 里，
 * 只对"预热"这一个入口生效 —— 搜索页撞到验证就没有对应的处理路径，
 * 只能靠空结果重试去撞。
 *
 * 主判据用结构化信号，不看语言也不看 CF 版本：
 *   window._cf_chl_opt 存在 / 加载了 /cdn-cgi/challenge-platform/ 脚本
 * 标题只作补充。之前只认英文 "Just a moment"，而实际返回的是中文「请稍候…」，
 * 导致检测一直返回 false，代码误以为验证已通过就直接去搜索（然后必然 403）。
 */
public class ChallengeDetector {

    private ChallengeDetector() {}

    /** 已知过渡页标题片段（多语言），小写比较 */
    private static final String[] INTERSTITIAL_TITLES = {
            "just a moment",
            "attention required",
            "checking your browser",
            "请稍候",
            "稍候",
            "verification",
            "vérification",
            "überprüfung",
            "comprobando",
    };

    /** @param detectJson PageScripts.detectChallengeJs() 的返回值（已 decode） */
    public static boolean isChallenge(String detectJson) {
        if (detectJson == null) return false;
        try {
            JSONObject o = new JSONObject(detectJson);
            if (o.optBoolean("chlOpt", false)) return true;
            if (o.optBoolean("platformScript", false)) return true;
            return isChallengeTitle(o.optString("title", ""));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isChallengeTitle(String title) {
        if (title == null || title.isEmpty()) return false;
        String t = title.toLowerCase(Locale.ROOT);
        for (String needle : INTERSTITIAL_TITLES) {
            if (t.contains(needle)) return true;
        }
        return false;
    }

    /**
     * CookieManager 对**没有路径**的 URL（比如 getPrewarmUrl() 返回的 "https://missav.ws"）
     * 匹配 Path=/ 的 cookie 并不可靠，统一补成根路径再查。
     * 这个坑曾经让 hasClearance() 一直返回 false —— 判断"验证过没过"的地基就是错的。
     */
    private static String withRootPath(String url) {
        if (url == null || url.isEmpty()) return url;
        int schemeEnd = url.indexOf("://");
        int from = schemeEnd >= 0 ? schemeEnd + 3 : 0;
        return url.indexOf('/', from) < 0 ? url + "/" : url;
    }

    /**
     * cf_clearance 是否已经种下 —— 这才是"验证过了"的**真正判据**。
     *
     * 为什么不能只看页面：CF 的挑战是在后台把 clearance cookie 种下的，
     * 页面上那个「请稍候…」和 window._cf_chl_opt 会一直挂着直到它自己决定跳转。
     * 实测抓到过：cookie 早就生效了（之后的请求 403 消失），而页面依然每秒钟
     * 都报 chlOpt=true title=[请稍候…]，白白多等了几十秒。
     */
    public static boolean hasClearance(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String cookie = CookieManager.getInstance().getCookie(withRootPath(url));
            return cookie != null && cookie.contains("cf_clearance");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 页面上的挑战是不是"要人来点"的那种。
     *
     * ⚠️ 现在**基本不会命中**：CF 把 _cf_chl_opt 的键名混淆成随机串了
     * （见 PageScripts.detectChallengeJs 的注释），cType 读不出来。
     * 保留是为了万一它哪天又变回可读的键名。
     */
    public static boolean needsInteraction(String detectJson) {
        if (detectJson == null) return false;
        try {
            String cType = new JSONObject(detectJson).optString("cType", "");
            return "interactive".equalsIgnoreCase(cType) || "managed".equalsIgnoreCase(cType);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 当前域下有哪些 cookie（只列名字，值又长又没必要）。
     * 用来判断"是不是在等一个早就该到的东西"。
     */
    public static String cookieNames(String url) {
        if (url == null || url.isEmpty()) return "(无url)";
        try {
            String cookie = CookieManager.getInstance().getCookie(withRootPath(url));
            if (cookie == null || cookie.trim().isEmpty()) return "(无)";
            StringBuilder sb = new StringBuilder();
            for (String part : cookie.split(";")) {
                String name = part.split("=", 2)[0].trim();
                if (name.isEmpty()) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(name);
            }
            return sb.length() == 0 ? "(无)" : sb.toString();
        } catch (Exception e) {
            return "(读取失败)";
        }
    }

    /** 日志用：把检测结果说清楚，方便排查 */
    public static String describe(String detectJson) {
        if (detectJson == null) return "检测失败(求值无返回)";
        try {
            JSONObject o = new JSONObject(detectJson);
            String cType = o.optString("cType", "");
            String keys = o.optString("optKeys", "");
            return "chlOpt=" + o.optBoolean("chlOpt", false)
                    + " platformScript=" + o.optBoolean("platformScript", false)
                    + " cType=" + (cType.isEmpty() ? "(空)" : cType)   // 空着本身就是线索，别藏起来
                    + " script=" + o.optInt("scriptCount", 0)
                    + " body=" + o.optInt("bodyLen", 0)
                    + " title=[" + o.optString("title", "") + "]"
                    + (keys.isEmpty() ? "" : " optKeys=" + keys)
                    + " → " + (isChallenge(detectJson) ? "验证中" : "已通过");
        } catch (Exception e) {
            return "检测结果无法解析: " + detectJson;
        }
    }

    /** 挑战脚本的地址（首次轮询时单独打一行，太长不适合塞进每秒一行里） */
    public static String scriptSrc(String detectJson) {
        if (detectJson == null) return "";
        try {
            return new JSONObject(detectJson).optString("scriptSrc", "");
        } catch (Exception e) {
            return "";
        }
    }
}
