# CHANGELOG · 连接发现弹窗误杀修复（2026-10-09）

## 现象
「图片早期 hook（零闪帧 FASTIMG）」改动上线后，连接发现弹窗（DiscoveryDialog）功能失效：
弹窗内图片/内容被清空隐藏，用户端表现为弹窗空白/功能不可用。

## 根因（LSPosed 日志实锤）
1. `hookFastProductImage` / `hookDiscoveryPopupMainImage` / onResume walk 三处「宁缺勿错」策略过度：
   - 档案解析失败或模块图未就绪时：`setArg(0,null)` + `INVISIBLE` / `suppress` → 弹窗图全灭；
   - onResume walk 还会把弹窗内所有 ≥200x200 带 drawable 的 view（含正常 UI）清空隐藏。
2. 另发现 `hookOneSpaceConnectStateUi()` 为半成品重构残留（全库无定义）→ 编译门禁被破。

## 修复
| 位置 | 旧行为 | 新行为 |
|---|---|---|
| `hookFastProductImage` (MelodyCompatImageView before) | 无档案/无图 → setArg(null)+INVISIBLE | 放行宿主图（FASTPASS 日志），延迟覆盖任务兜底 |
| `hookDiscoveryPopupMainImage` M() after | 无档案 → setImageDrawable(null)+INVISIBLE | 仅清动画，放行宿主加载链（M-pass） |
| onResume walk | 无档案 → 隐藏主图位；applied 时清空其它大图 | 保留宿主图（walk-pass）；删除「清空其它大图」循环 |
| `onHook()` | 调用未定义的 hookOneSpaceConnectStateUi() | 移除调用（编译修复） |

## 验证（LSPosed 日志）
- `POPUP_MAINIMG walk-pass (保留宿主图)` 替代旧 `POPUP_MAIN_UNAVAILABLE suppress`；
- 弹窗链路完整跑通：`DAMS_P_INVOKE` → `show module discovery popup` → `DISCOVERY_DIALOG_ON_RESUME` → `POPUP_MAIN_APPLIED`；
- Provider `Unknown authority` 在重启宿主进程后消失（forceQueryable 生效）；
- 构建 `:app:compileDebugKotlin` + `:app:assembleDebug` 全部 SUCCESSFUL。

## 版本
- 修复 APK：`MelodyPlus-fix.apk`（md5 `a810b1d6df02e2c7b5e91dec3e73fb46`），已安装（2026-10-09 08:24）
