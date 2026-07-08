# Plan: 刮削源配置功能

## 概述

将当前硬编码的 javbus.com 刮削逻辑改造为可配置的多源刮削系统。用户可在"更多"菜单中管理刮削源（增删改、设默认），刮削时使用当前默认源的配置（基础URL + XPath）。

## 涉及文件

### 新建文件
| 文件 | 说明 |
|------|------|
| `ScrapeSource.java` | 刮削源数据模型 (Serializable) |
| `app/src/main/res/layout/dialog_scrape_source_list.xml` | 刮削源列表对话框布局 |
| `app/src/main/res/layout/dialog_scrape_source_edit.xml` | 添加/编辑刮削源对话框布局 |

### 修改文件
| 文件 | 改动 |
|------|------|
| `JavResolver.java` | 接受 `ScrapeSource` 参数，从 XPath 动态生成 JS |
| `MainActivity.java` | 加载默认源并传递给 JavResolver；添加菜单项处理；刮削源管理对话框 |
| `DetailActivity.java` | 加载默认源并传递给 JavResolver；修正图片URL解析逻辑 |
| `menu_more.xml` | 添加 "刮削源" 菜单项 |

## 数据模型: ScrapeSource

```java
public class ScrapeSource implements Serializable {
    String id;          // UUID
    String name;        // 显示名称，如 "JavBus"
    String baseUrl;     // 搜索URL，用 {keyword} 占位
    String titleXPath;  // 标题提取 XPath
    String imageXPath;  // 图片提取 XPath
    boolean isDefault;  // 是否为默认源
}
```

XPath 示例（可直接选中属性节点）:
- 标题: `//div[contains(@class,'movie-box')]//img/@title`
- 图片: `//div[contains(@class,'movie-box')]//img/@src`

## 存储

`ScrapeSourceManager` 工具类（静态方法，放在 `ScrapeSource.java` 同文件或独立文件），通过 `SharedPreferences` 读写：
- Key: `"scrape_sources"`  
- 值: Gson 序列化的 `List<ScrapeSource>` JSON
- 首次启动时无数据 → 自动创建默认 JavBus 源

## JavResolver 改造

### 构造函数
```java
JavResolver(X5WebView webView, ScrapeSource source, ResolveCallback callback)
```

### startScrape(keyword)
- `url = source.baseUrl.replace("{keyword}", keyword)`
- 保留 stealth script 注入 + 1.5s 延迟

### parse(view)
- 动态生成 JS，用 `document.evaluate(xpath, document, null, XPathResult.STRING_TYPE, null)` 执行配置的 XPath
- 图片URL若以 `/` 开头，从 source.baseUrl 解析域名补全

## 默认 JavBus 源配置

首次启动时自动创建的默认源：
- name: `"JavBus"`
- baseUrl: `"https://www.javbus.com/search/{keyword}?type=&parent=ce"`
- titleXPath: `//div[contains(@class,'movie-box')]//div[contains(@class,'photo-frame')]/img/@title`
- imageXPath: `//div[contains(@class,'movie-box')]//div[contains(@class,'photo-frame')]/img/@src`

## UI: 刮削源管理对话框

两级对话框：
1. **列表对话框** (`dialog_scrape_source_list.xml`): 
   - RecyclerView 展示所有源，每项显示名称、URL、默认标记
   - 每项有编辑和删除按钮
   - 底部 "添加新源" 按钮
2. **编辑对话框** (`dialog_scrape_source_edit.xml`):
   - EditText: 名称、基础地址、标题XPath、图片XPath
   - 提示文案中说明 `{keyword}` 占位符
   - 确定/取消按钮

## 调用点适配

MainActivity 中有 3 处创建 JavResolver:
1. `showAddDialog()` — 添加卡片时自动刮削
2. `importCodeListFromText()` — 导入番号列表后逐条刮削
3. `onScrapeToggle()` — 卡片刮削开关

DetailActivity 中有 1 处:
4. `triggerScrape()` — 详情页触发刮削

每处改为先通过 `ScrapeSourceManager.getDefault()` 获取默认源，再创建 JavResolver。

## 实现顺序

1. 创建 `ScrapeSource.java`（数据模型 + ScrapeSourceManager 存储工具）
2. 改造 `JavResolver.java`（接受 ScrapeSource，动态生成 JS）
3. 创建两个对话框布局 XML
4. 修改 `menu_more.xml` 添加菜单项
5. 修改 `MainActivity.java`（菜单处理 + 管理对话框 + 各调用点适配）
6. 修改 `DetailActivity.java`（调用点适配 + 图片URL解析修正）
