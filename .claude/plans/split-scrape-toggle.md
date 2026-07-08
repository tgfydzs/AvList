# Plan: 卡片刮削开关 → 刮削按钮

## 问题
SwitchCompat 身兼二职（状态 + 动作），关闭=清数据这个设计反直觉。

## 方案

卡片底部行改为：

```
[跳转]                    [刮削]
```

- **刮削按钮** — 纯动作，点击即用默认源刮削，永远覆盖旧数据
- 去掉 SwitchCompat + "刮削"标签文字
- `CardItem.scrapeEnabled` 字段保留（导入/添加流程用），不在卡片上展示

## 涉及文件

| 文件 | 改动 |
|------|------|
| `item_card.xml` | 去掉 swScrapeCard 和 label，加 btnScrape |
| `CardAdapter.java` | 去掉 switch 绑定，加 btnScrape 点击回调；去掉 onScrapeToggle，改 onItemScrape |
| `MainActivity.java` | onScrapeToggle → onItemScrape（纯刮削，无开关逻辑）；删除 switch 相关 |
| `DetailActivity.java` | 详情页 toggle 保持不变（那是另一个场景），只把 OFF→clear 的自动清数据逻辑去掉 |
