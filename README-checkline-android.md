# checkline Android/Termux 版本

## 功能特性

### USB设备检测
- 自动检测所有连接的USB设备
- 显示设备名称、VID:PID、序列号
- 自动识别USB协议版本（USB 1.x/2.0/3.0/3.1/3.2/4.0等）
- 显示连接速度（1.5M/12M/480M/5G/10G/20G等）
- 检测设备类别（HID、存储、音频、视频等）

### 充电器检测
- 自动检测充电器类型：
  - USB SDP (标准下游端口 - PC/笔记本)
  - USB DCP (专用充电端口 - 充电器/充电宝)
  - USB CDP (充电下游端口)
  - USB PD (Power Delivery)
  - USB PD DRP (可角色切换)
  - USB PD PPS (可编程电源)
  - 高通QC快充 (QC2.0/3.0/3.5)
- 显示最大充电功率
- 实时显示充电状态（电压、电流、功率）

### 电池健康检测
- 电量百分比
- 充电状态（充电中/放电中/已充满）
- 循环次数
- 电池温度
- 实时电压、电流、功率
- 电池容量（设计容量 vs 实际容量）
- 电池健康度百分比
- 电池技术类型（Li-ion/Li-poly）
- 厂商和型号信息
- 健康状态（良好/过热/损坏/过冷等）

## 安装方法

### 方法1：在Termux中自动安装

1. 将 `checkline-android.sh` 复制到Termux：
   ```bash
   # 在Mac上使用adb推送
   adb push checkline-android.sh /sdcard/
   
   # 在Termux中
   cp /sdcard/checkline-android.sh ~/bin/checkline
   chmod +x ~/bin/checkline
   ```

2. 或者直接在Termux中运行安装脚本：
   ```bash
   bash install-checkline-android.sh
   ```

3. 添加到PATH（如果尚未添加）：
   ```bash
   echo 'export PATH=$HOME/bin:$PATH' >> ~/.bashrc
   source ~/.bashrc
   ```

### 方法2：手动安装

1. 在Termux中创建bin目录：
   ```bash
   mkdir -p ~/bin
   ```

2. 复制checkline-android.sh到~/bin/checkline：
   ```bash
   cp /path/to/checkline-android.sh ~/bin/checkline
   chmod +x ~/bin/checkline
   ```

3. 添加到PATH：
   ```bash
   export PATH=$HOME/bin:$PATH
   ```

## 使用方法

```bash
# 基本用法
checkline

# 显示设备标题信息
checkline title

# 帮助信息
checkline --help
```

## 输出示例

```
=== Xiaomi 13 - ABCD1234 - Android 14 (SDK 34) ===

USB
  USB 2.0 Hub                      USB 2.0   480M
                                    VID:PID 1234:5678  HUB
  Xiaomi 13                        USB 2.0   480M ⚡5W
                                    VID:PID 18D1:FF48  厂商自定义

Charger
  USB DCP  10W  实时: 5000mV 1500mA 7.5W

Battery
  85% 充电中  循环42次  28.5°C
   4.2V 1200mA 5.04W 4500mAh (90%健康)
   健康: 良好  技术: Li-poly  厂商: Xiaomi  型号: BN5A
```

## 依赖要求

- Android系统（需要root权限访问sysfs）
- Termux环境
- KernelSU（可选，用于获取root权限）
- 标准shell工具（awk, grep, sed, bc）

## 技术实现

### USB设备检测
- 通过 `/sys/bus/usb/devices/` 遍历USB设备
- 读取设备属性：idVendor, idProduct, product, speed, version等
- 自动识别USB协议版本（从version字段推断）

### 电池信息获取
- 通过 `/sys/class/power_supply/battery/` 读取电池属性
- 支持的属性：capacity, voltage_now, current_now, temp, cycle_count等
- 自动计算电池健康度（charge_full / charge_full_design）

### 充电器检测
- 遍历 `/sys/class/power_supply/*/` 检测所有电源设备
- 读取type字段识别充电器类型
- 检测usb_type字段识别具体充电协议
- 实时读取current_now和voltage_now计算充电功率

## 注意事项

1. **Root权限**：某些系统属性可能需要root权限才能访问
2. **系统差异**：不同Android设备的sysfs路径可能略有不同
3. **内核支持**：某些电池属性（如cycle_count）可能需要内核支持
4. **权限问题**：如果遇到权限错误，可以尝试使用KernelSU

## 故障排除

### 无法读取USB设备信息
```bash
# 检查sysfs是否可访问
ls -la /sys/bus/usb/devices/

# 使用root权限
su -c "ls /sys/bus/usb/devices/"
```

### 无法读取电池信息
```bash
# 检查电池目录
ls -la /sys/class/power_supply/battery/

# 使用dumpsys作为备选
dumpsys battery
```

### bc命令不可用
```bash
# 安装bc
pkg install bc
```

## 扩展功能

### 添加新的充电协议支持
编辑checkline-android.sh，在charger_type的case语句中添加新的协议：

```bash
case "$usb_type" in
    "NEW_PROTOCOL") charger_type="新协议描述" ;;
    # ... 其他协议
esac
```

### 添加新的USB设备类别
在class_str的case语句中添加新的类别：

```bash
case "$class" in
    "NEW_CLASS") class_str="新类别描述" ;;
    # ... 其他类别
esac
```

## 版本历史

- **v1.0** (2026-09-17): 初始版本
  - USB设备检测
  - 充电器类型识别
  - 电池健康监控
  - 实时充电状态显示
