# 背屏激发

小米17 Pro / 17 Pro Max 背屏亮度控制工具。Root 权限下，通过直观的滑块调节背屏亮度，一键锁定或恢复系统控制，支持背屏 AOD 开关。适配 Hyper OS 3 / 4。

## 📱 应用界面

<img width="1220" height="866" alt="6a8fb84a461bb568155e1327994b6302" src="https://github.com/user-attachments/assets/cf4d712e-9daf-4ef4-957c-b84033193057" />


*卡片式界面，左侧状态面板（目标/当前/最大亮度） + 右侧亮度滑块与功能按钮*

## ✨ 核心特性

- **垂直亮度滑块** — 直观的滑块控件，拖动即可实时调节背屏亮度（0 - 4095）
- **实时亮度监控** — 300ms 刷新间隔，实时显示当前亮度值变化
- **渐进式同步** — 松手后自动同步亮度，最多 30 次重试，5 秒超时，渐进式延迟
- **C 守护进程** — 独立后台进程持续守护亮度，App 被杀也能保持，适配 Hyper OS 4
- **一键切换状态** — 软件接管/系统控制，点击按钮即可切换
- **背屏 AOD 开关** — 一键开关背屏息屏显示（Always-On Display）
- **控制中心磁贴** — 下拉通知栏快速打开控制面板
- **自动 Root 检测** — 启动时验证权限，未 Root 则提示退出
- **Su 进程复用** — App 运行期间持久保持 su 进程，操作响应更快
- **指令合并优化** — 单条命令完成写入+锁定，去除无效 sync，执行更高效

## 📋 适用环境

| 项目 | 说明 |
|------|------|
| 机型 | 小米17 Pro / 17 Pro Max |
| 系统 | HyperOS 3.0.313.0（已测试）<br>HyperOS 4（已适配） |
| 权限 | Root（Magisk / KernelSU） |
| 背光路径 | `/sys/class/backlight/panel1-backlight/` |
| 亮度范围 | 0 - 4095 |

## 🔧 工作原理

直接操作内核背光接口：

```
/sys/class/backlight/panel1-backlight/brightness
/sys/class/backlight/panel1-backlight/max_brightness
```

- **软件接管**：通过滑块调节亮度 → 写入 `brightness` 文件 → 实时监控写入结果
- **锁定模式**：`chmod 444` 设为只读，系统无法自动调节
- **系统控制**：`chmod 644` 恢复可写，系统重新接管亮度调节
- **AOD 控制**：通过 `settings put secure rear_doze_always_on` 开关背屏息屏显示

### 底层优化

- **Su 进程复用**：`ShellUtils` 维护持久化的 su 进程，通过 marker 标记命令输出边界，30 秒空闲自动回收，避免频繁创建/销毁进程
- **指令合并**：将 chmod + echo + chmod 合并为单条命令执行，去除无效的 sync 调用
- **渐进式同步**：松手后启动同步循环，1-10 次 100ms 间隔，11-20 次 200ms，21-30 次 300ms，5 秒超时
- **双重验证**：写入成功后间隔 50ms 再次读取验证，确保稳定性
- **EPIPE 自愈**：su 进程意外死亡（EPIPE）时自动重建并重试，避免命令静默失败

### C 守护进程（v1.5，适配 Hyper OS 4）

Hyper OS 4 中系统会持续覆盖背屏亮度值，且 App 进程可能被系统冻结。为此引入**独立 C 守护进程**，静态编译（128KB 内存），即使 App 被杀依然驻留后台守护亮度。

```
app/src/main/cpp/watchdog.c  →  静态编译 ARM64 二进制  →  /data/local/tmp/bright_watchdog
```

- **自适应轮询**：正常 4 秒低频轮询（CPU≈0%），检测异常后切 1 秒短间隔确认
- **二次确认**：50ms 间隔二次读取，防瞬时波动误判
- **恢复流程**：`chmod 644` 解锁 → 写入目标值 → `chmod 444` 重新锁定
- **PID 管理**：`/data/local/tmp/bright_watchdog.pid`，还原系统控制时自动停止

## 📚 澎湃OS4 背屏亮度适配全记录

### 问题背景

**OS3→OS4 变化**：
- OS3：系统直接写 sysfs brightness 节点控制背屏
- OS4：改用 SurfaceControl 链路（`useSurfaceControl=true`），sysfs 节点退化为"镜像"
- 关键行为：OS4 遮盖背屏时通过 SurfaceControl 写 0，拿出时依赖写 sysfs 恢复，被 `chmod 444` 锁住则放弃恢复 → 背屏不亮

### 尝试过的方案（按时间顺序）

| # | 方案 | 原理 | 结果 | 失败原因 |
|---|------|------|------|----------|
| 1 | **chmod 444 锁定** | 锁亮度节点防止系统改写 | ❌ | OS4 系统 root 无视权限写 0，且恢复时写节点失败 |
| 2 | **bind mount + chattr +i** | 镜像文件锁死，系统写不进 | ❌ | 系统控制走 SurfaceControl，不经过 sysfs |
| 3 | **SELinux 策略限制** | deny 系统写节点 | ❌ | Android 不支持运行时 deny，且危险 |
| 4 | **inotifyd 事件监听** | 文件变化触发 | ❌ | 系统不走文件写入，无事件 |
| 5 | **poll (POLLPRI)** | sysfs 通知机制 | ❌ | msm_drm 驱动未实现 sysfs_notify |
| 6 | **广播监听 (SUB_SCREEN_ON)** | 系统广播触发恢复 | ❌ | MIUI 后台限制，第三方 APP 收不到（enqueue 阶段跳过） |
| 7 | **settings 设置项** | `sub_display_screen_brightness` | ⚠️ | 系统遮盖时强制接管改回自己值 |
| 8 | **Shell 轮询守护** | 主动读节点，异常时写回 | ✅ | — |
| 9 | **C 二进制轮询守护** | 同上，C 实现 | ✅ **最终采用** | — |

### 为什么前 7 种都失败

```
根本原因：OS4 的背光控制链路完全变化

OS3: APP直接写sysfs → chmod444 → 系统读不到 → 亮度锁死 ✅
OS4: 系统走SurfaceControl → 绕过sysfs
     遮盖时直接写0（root无视权限）
     拿出时依赖写sysfs恢复（被锁则放弃）

事件类方案（inotify/poll/广播）：
  系统不产生任何可感知的事件 → 全失效

权限类方案（chmod/bind/selinux）：
  拦不住root + 反而阻止系统恢复 → 失效
```

### 最终方案：C 二进制轮询守护

```
用户修改亮度 → 启动C守护（独立root进程）
    ↓
守护每4秒读一次亮度节点
    ↓
发现亮度被系统写0/改写 → chmod644 → 写回目标 → chmod444
    ↓
自适应：正常4s低频，异常后1s快速恢复
```

**为什么可行**：

| 特性 | 说明 |
|------|------|
| **主动轮询** | 不依赖任何系统事件，自己读节点 |
| **root 进程** | 独立于 APP，不受 MIUI 冻结/后台限制 |
| **自适应** | 平时 4s（CPU 0%），异常后 1s（快速恢复） |

**资源占用（实测）**：

| 指标 | 值 |
|------|-----|
| 内存 | **128KB** |
| CPU | **0.0%** |
| 状态 | 纯休眠等待 |

### 当前方案完整逻辑

```
用户修改亮度 1483
    ↓
保存 target=1483 + is_takeover=true
    ↓
startWatchdog(1483):
    ├─ 释放C二进制（assets → /data/local/tmp/bright_watchdog）
    ├─ nohup 启动C守护
    └─ 守护开始4s轮询
    ↓
遮盖背屏 → 系统写0 → 守护检测到 → 1s内写回1483
    ↓
拿出手机 → 背屏亮起时亮度已是1483 ✅
    ↓
用户点"恢复系统控制":
    ├─ stopWatchdog() 杀进程
    ├─ chmod644 + echo500 恢复系统
    └─ 清理状态（is_takeover=false, target=-1）
```

### 适配过程中修复的代码问题

| 问题 | 修复 |
|------|------|
| su 进程 EPIPE 崩溃 | execRoot 捕获 IOException 重建 su 重试 |
| 脚本 PID 多了个 $ | 修正为 `$$` |
| 恢复后状态残留 | 清除 is_takeover / target_brightness |
| assets 占位符 | 替换为真实 C 二进制 + 释放逻辑 |

### 方案对比总结

| 维度 | 广播方案 | Shell 守护 | **C 守护（最终）** |
|------|---------|-----------|-------------------|
| 可靠性 | ❌ 收不到 | ✅ | ✅ |
| 内存 | 0 | 2.3MB | **128KB** |
| CPU | 0 | 0.6% | **0.0%** |
| 轮询 | 无 | 2s | **4s+1s 自适应** |
| 受 MIUI 影响 | 致命 | 不受 | 不受 |
| 复杂度 | 低 | 低 | 中（需编译） |

**结论**：经过 9 种方案探索，最终采用 **C 二进制自适应轮询守护**，以极低资源（128KB / 0%）可靠解决 OS4 背屏遮盖后无法恢复的问题。

## 🚀 构建

```bash
./gradlew assembleRelease
```

输出：`app/build/outputs/apk/release/app-release-unsigned.apk`

建议使用 Android Studio 构建，签名后安装。

## 📦 下载

| 版本 | 文件 | 说明 |
|------|------|------|
| v1.5 | [bright_1.5.apk](website/bright_1.5.apk) | C 守护进程，适配 Hyper OS 4 |
| v1.4 | 见release | 实时亮度监控、渐进式同步循环、指令合并优化 |
| v1.3 | 见release | Su 进程复用、写入验证重试、AOD 开关、界面重构 |
| v1.2 | [app-release-new.apk](website/app-release-new.apk) | 新增亮度滑块，全新界面 |
| v1.1 | [app-release-v1.1.apk](website/app-release-v1.1.apk) | 新增控制中心磁贴 |
| v1.0 | [app-release.apk](website/app-release.apk) | 首个发布版本 |

## 📝 许可

MIT License

## 🔗 链接

- 开发者博客：[rongshangs.top](http://rongshangs.top)
- 官网：[bright.rongshangs.top](http://bright.rongshangs.top)
