# Plan: 更多按钮 → 右侧滑动侧边栏

## 变更概述

将右上角 `btnMore` + PopupMenu 替换为 DrawerLayout 右侧滑出抽屉菜单，向左滑动或点击按钮呼出。

## 涉及文件

| 文件 | 操作 |
|------|------|
| `app/src/main/res/layout/activity_main.xml` | 改写 — 根标签改为 DrawerLayout，包裹原 CoordinatorLayout；添加右侧 drawer 布局 |
| `MainActivity.java` | 修改 — 替换 PopupMenu 为 DrawerLayout 操作；添加 back-press 关抽屉逻辑 |

## 布局改造 (`activity_main.xml`)

```
DrawerLayout
├── CoordinatorLayout  (原有全部内容，保持不变)
│   ├── llHeader (搜索栏 + btnMore 改为汉堡图标)
│   ├── FrameLayout (RecyclerView)
│   ├── llBottomAction
│   └── FAB
└── LinearLayout (drawer, android:layout_gravity="end", width=260dp)
    ├── TextView "菜单" 标题 (16sp bold)
    ├── 分隔线
    ├── 导出JSON
    ├── 导入JSON
    ├── 导入番号列表
    ├── 分隔线
    ├── 多选模式
    ├── 刮削源
    ├── 运行日志
```

每个菜单项：`TextView` + 底部细分隔线，padding 16dp，带 ripple 点击效果。

## 代码改动 (`MainActivity.java`)

1. **新增成员**：`DrawerLayout drawerLayout`
2. **onCreate 中**：获取 DrawerLayout 引用，`btnMore` 点击 → `drawerLayout.openDrawer(GravityCompat.END)`
3. **抽屉各菜单项 **：在 onCreate 中 findViewById + setOnClickListener，调用已有的对应方法
4. **Back-press 优先级调整**：
   1. 多选模式 → exitMultiMode
   2. **Drawer 打开 → 关抽屉**
   3. WebView 可见 → 隐藏
   4. 默认返回
5. **移除**：`showPopupMenu()` 方法、`PopupMenu` 相关 import
6. **新增 import**：`DrawerLayout`、`GravityCompat`

## 不涉及

- 菜单功能逻辑完全不变，仅改变触发方式
- `menu_more.xml` 可保留（不再被引用，但也无害）
