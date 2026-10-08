# 手机端 Root 采集脚本

将 `collect-bright-hooks.sh` 保存到手机“下载”目录，在支持 Root 的终端执行：

```sh
su -c 'sh /sdcard/Download/collect-bright-hooks.sh'
```

已进入 Root Shell 时直接执行 `sh /sdcard/Download/collect-bright-hooks.sh`。请保持手机解锁、终端在前台运行。

已有框架包、仅补采集系统 APK 时，重新下载最新版脚本并运行：

```sh
su -c 'sh /sdcard/Download/collect-bright-hooks.sh --apks-only'
```

输出名称为 `bright-2.0-apks-*.tar.gz`。即使包管理器因 Binder 错误不可用，也会按系统分区路径查找背屏、SystemUI、AOD、设置等相关 APK，不会扫描个人应用数据。

完成后将下载目录里的 `bright-2.0-hook-info-*.tar.gz` 发回，并附 **机型、HyperOS 完整版本号、LSPosed 版本截图**。寄生管理器的版本可能无法自动取得。

脚本收集两个背屏息屏策略可能涉及的系统服务 JAR、背屏/AOD/SystemUI/设置等系统 APK、版本信息与两个背屏设置的当前值。它不会修改设置、安装模块、重启，也不收集个人应用数据、照片、聊天记录或完整日志。

文件可能有数百 MB，建议至少预留 2 GB 空间。临时副本位于 Root 私有随机目录；打包、导出及校验成功后仅清理本次临时目录。失败时保留目录并打印位置，便于排查。分享前可查看压缩包的 `metadata`、`manifest.tsv` 和 `errors.txt`；请勿公开分发厂商固件。

这是静态资料采集，并不能保证一次取得所有 Hook 所需信息；如固件不含 DEX 或实现不同，后续可能需要定向补采集。采集脚本本身不会改动 App、模块或系统状态，也不代表采集到的设备已获得兼容保证。
