# 背屏激发

小米17 Pro / 17 Pro Max 背屏亮度一键控制工具。Root 权限下，一键锁定背屏最大亮度或恢复系统控制。

## ⚡ 功能

- **自动 Root 检测** — 启动时验证 Root 权限，未 Root 则提示退出
- **一键亮度锁定** — 将背屏亮度写入 `/sys/class/backlight/` 并 `chmod 444` 锁定
- **一键恢复系统控制** — `chmod 644` 恢复权限，系统重新接管
- **状态智能识别** — 通过文件权限位判断当前状态，避免误操作
- **控制中心磁贴**（v1.1） — 下拉通知栏一键切换，无需打开 App

## 📋 适用环境

| 项目 | 说明 |
|------|------|
| 机型 | 小米17 Pro / 17 Pro Max |
| 系统 | HyperOS 3.0.313.0（已测试） |
| 权限 | Root（Magisk / KernelSU） |
| 架构 | `panel1-backlight` 背光路径 |

## 🔧 原理

直接操作内核背光接口：

```
/sys/class/backlight/panel1-backlight/brightness
/sys/class/backlight/panel1-backlight/max_brightness
```

- **锁定**：写入 `max_brightness` → `chmod 444` 只读
- **恢复**：`chmod 644` → 写入 `500`

## 🚀 构建

```bash
./gradlew assembleRelease
```

输出：`app/build/outputs/apk/release/app-release-unsigned.apk`

## 📦 下载

| v1.1 | [app-release-v1.1.apk](https://github.com/RongShangs/bright/releases/download/v1.1/app-release-v1.1.apk) | 新增控制中心磁贴 |
| v1.0 | [app-release.apk](https://github.com/RongShangs/bright/releases/download/v1.0/app-release.apk) | 首个发布版本 |

推荐签名后安装

## 📝 许可

MIT License

## 🔗 链接

- 开发者博客：[rongshangs.top](http://rongshangs.top)
- 官网：[bright.rongshangs.top](http://bright.rongshangs.top)
