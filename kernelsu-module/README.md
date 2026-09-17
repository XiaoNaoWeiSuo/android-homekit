# Marionette HomeKit Driver · KernelSU module

这个模块把 Marionette 作为系统启动阶段可恢复的 HomeKit BLE 前台驱动集成到 Android：

- 将 release APK 挂载到 `system/priv-app/Marionette/`；
- 将应用加入系统 `allow-in-power-save` 白名单；
- 用户解锁后由 `service.sh` 一次性请求启动前台服务；
- 运行时由 Android `START_STICKY`、开机/解锁/更新广播和 30 分钟非精确 watchdog 负责恢复。

模块没有常驻 shell 守护循环、没有 WakeLock，也不会周期性重启正常运行的服务。应用内或控制中心关闭开关后，模块的开机脚本和 watchdog 都会尊重该状态。

## 安装

1. 运行项目根目录的 `build-kernelsu-module.sh`。
2. 在 KernelSU Manager 中安装生成的 `marionette-kernelsu-module.zip`。
3. 重启手机，解锁后等待前台通知出现。

模块会尝试幂等授予蓝牙、附近设备和通知运行时权限；不会自动授予相机权限。安装升级时不要清除应用数据，否则 HomeKit 配对身份会丢失。

## 卸载

在 KernelSU Manager 中禁用或卸载模块后重启。若仍保留普通 APK，应用本身的前台服务和开机广播仍按 Android 普通应用规则工作。
