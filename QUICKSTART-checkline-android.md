# checkline Android 快速使用指南

## 1. 部署到Android设备

### 方法A: 使用部署脚本（推荐）
```bash
# 在Mac上执行
./deploy-checkline.sh
```

### 方法B: 手动部署
```bash
# 1. 通过adb推送文件
adb push checkline-android.sh /sdcard/

# 2. 在Termux中安装
mkdir -p ~/bin
cp /sdcard/checkline-android.sh ~/bin/checkline
chmod +x ~/bin/checkline

# 3. 添加到PATH
echo 'export PATH=$HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

## 2. 使用checkline

```bash
# 显示USB、充电器、电池信息
checkline

# 显示设备标题信息
checkline title

# 显示帮助
checkline --help
```

## 3. 输出示例

```
=== Xiaomi 13 - ABCD1234 - Android 14 (SDK 34) ===

USB
  USB 2.0 Hub                      USB 2.0   480M
                                    VID:PID 1234:5678  HUB
  Xiaomi 13                        USB 2.0   480M ⚡5W
                                    VID:PID 18D1:FF48  厂商自定义

Charger
  USB DCP (专用充电端口/充电宝)  10W  实时: 5000mV 1500mA 7.5W

Battery
  85% 充电中  循环42次  28.5°C
   4.2V 1200mA 5.04W 4500mAh (90%健康)
   健康: 良好  技术: Li-poly  厂商: Xiaomi  型号: BN5A
```

## 4. 功能说明

### USB设备检测
- 自动检测所有连接的USB设备
- 显示设备名称、VID:PID、序列号
- 自动识别USB协议版本
- 显示连接速度

### 充电器检测
- 识别充电器类型（SDP/DCP/CDP/PD/QC）
- 显示最大充电功率
- 实时显示充电状态

### 电池健康检测
- 电量百分比和充电状态
- 循环次数和温度
- 实时电压、电流、功率
- 电池容量和健康度
- 电池技术类型和厂商信息

## 5. 故障排除

### 权限问题
```bash
# 使用root权限
su -c "checkline"
```

### bc命令不可用
```bash
# 安装bc
pkg install bc
```

### 无法读取USB信息
```bash
# 检查sysfs权限
ls -la /sys/bus/usb/devices/
```

## 6. 更多信息

详细文档请查看：
- `README-checkline-android.md` - 完整文档
- `CHECKLINE-ANDROID-SUMMARY.md` - 功能总结
