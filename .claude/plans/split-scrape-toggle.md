# Plan: 卡片刮削开关 → 刮削按钮

> ⚠️ **已过时** —— 这是当时的实施计划，保留作历史记录。有两处与**现在的实现相反**，
> 以 [CLAUDE.md](../../CLAUDE.md) 为准：
> - 「刮削按钮」**不只是动作** —— 点它会先把这张卡的 `scrapeEnabled` 置 true，再入队。
> - `scrapeEnabled` **在卡片和详情页上都展示**：它由「清除」按钮的显隐表达
>   （看得见「清除」= 刮削开着），关掉刮削就走「清除」按钮。不是"不在卡片上展示"。

## 问题
SwitchCompat 身兼二职（状态 + 动作），关闭=清数据这个设计反直觉。

## 方案

卡片底部行改为：

```
[跳转]              [刮削] [清除]
```

- **刮削按钮** — ~~纯动作，点击即用默认源刮削，永远覆盖旧数据~~
  → 现在：点它 = 打开这张卡的刮削状态 + 加入当前队列
- **清除按钮** — 关掉刮削状态 + 清掉标题和图片。它的显隐就是这张卡的刮削状态
- 去掉 SwitchCompat + "刮削"标签文字
- ~~`CardItem.scrapeEnabled` 字段保留（导入/添加流程用），不在卡片上展示~~
  → 现在：它就是这两颗按钮控制的状态，卡片上看得见

## 涉及文件

| 文件 | 改动 |
|------|------|
| `item_card.xml` | 去掉 swScrapeCard 和 label，加 btnScrape |
| `CardAdapter.java` | 去掉 switch 绑定，加 btnScrape 点击回调；去掉 onScrapeToggle，改 onItemScrape |
| `MainActivity.java` | onScrapeToggle → onItemScrape（纯刮削，无开关逻辑）；删除 switch 相关 |
| `DetailActivity.java` | 详情页 toggle 保持不变（那是另一个场景），只把 OFF→clear 的自动清数据逻辑去掉 |
