# 背屏控制

面向小米 17 / 18 系列 Pro、Pro Max 背屏机型的亮度与 AOD 策略工具。源码继续维护在 [RongShangs/bright](https://github.com/RongShangs/bright)，本仓库仅用于模块介绍与发布。

![面板与策略页示意，非真机截图](https://raw.githubusercontent.com/RongShangs/bright/master/docs/panel-preview-2.0.svg)

## 功能

- Root：背屏亮度、AOD、永不息屏；支持控制中心“背屏控制”磁贴，点击快捷呼出面板。
- 可选 LSPosed：独立阻止背屏 AOD 的暗光、遮挡自动息屏；不干涉主屏通话感应、电源键、双击、热保护或其他超时。
- 紧凑双页界面、双向切页动画、深浅色与横屏适配；系统允许时保留跨窗口背景模糊。
- 面板关闭且操作完成后退出 App 主进程；独立 C 守护按需保留，恢复系统控制后停止。LSP 复用现有背屏中心，不新增线程、轮询或唤醒锁。

## 控制中心磁贴

下拉控制中心，进入磁贴编辑界面，将“背屏控制”添加到常用区域。点击太阳图标即可快捷呼出亮度面板，再从黄色“背屏策略”入口设置相关开关；磁贴本身不要求安装 LSP。不同系统版本的编辑入口可能略有不同。

<img src="https://raw.githubusercontent.com/RongShangs/bright/master/docs/control-center-tile.jpg" width="180" alt="控制中心中的背屏控制太阳图标磁贴">

2.1.0 更换包名后，请移除旧包磁贴并添加新包的“背屏控制”。

## 安装与作用域

1. 下载签名 APK 并安装；Root 基础功能需要 Root 授权，桌面面板需要悬浮窗权限。
2. 需要 AOD 策略拦截时，使用支持 libxposed API 101 和远程配置的 LSPosed，启用“背屏控制”。
3. 作用域仅选“背屏中心” `com.xiaomi.subscreencenter`，不要勾选系统框架、SystemUI 或全部应用。
4. 首次启用或更新后重启手机，从黄色“背屏策略”入口开启所需功能。仅切换开关无需重启。

## 2.1.0 迁移提醒

新包名为 `top.rongshangs.brightnesslock`，旧包为 `brightnesslock.rongshangs.top`。证书没有变更，但不同包名不能覆盖安装。先在旧版关闭两个 LSP 开关并恢复系统控制，在 LSPosed 停用旧模块，再卸载旧包、安装新包。重新授权、添加磁贴、启用新模块并重启；配置不会自动迁移，不要同时启用新旧模块。

## 兼容与风险

机型范围不等于全固件实测保证。旧固件 OS4.0.0.41.XBLCNXM 的两项 LSP 功能已由用户确认；新版背屏中心的结构依据用户报告，2.1.0 本轮仅完成自动化验证，没有独立真机与长期功耗测试。实现类型和结构校验不匹配时保留系统行为，连接状态也不代表 Hook 已在所有固件生效。

Root 永不息屏与 LSP AOD 两项独立，前者复用 C 检测并尝试唤醒，可能短暂黑屏。提高亮度、减少息屏都会增加屏幕耗电与烧屏风险，按需使用。卸载前先关闭策略并恢复系统控制。

[官网](https://bright.rongshangs.top/) · [源码与问题反馈](https://github.com/RongShangs/bright) · [完整使用说明](https://github.com/RongShangs/bright/blob/master/README.md)
