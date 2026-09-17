#!/bin/bash
# deploy-checkline.sh
# 通过adb将checkline推送到Android设备的Termux环境

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_FILE="$SCRIPT_DIR/checkline-android.sh"

echo "=== checkline 部署脚本 ==="
echo ""

# 检查源文件
if [ ! -f "$SOURCE_FILE" ]; then
    echo "错误: 找不到 $SOURCE_FILE"
    exit 1
fi

# 检查adb
if ! command -v adb &> /dev/null; then
    echo "错误: 找不到adb命令"
    echo "请确保Android SDK platform-tools已安装并在PATH中"
    exit 1
fi

# 检查设备连接（只统计在线设备）
echo "检查Android设备连接..."
DEVICES=$(adb devices | grep -E "device$" | grep -v "offline" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "错误: 未检测到在线的Android设备"
    echo "当前设备状态："
    adb devices -l
    echo ""
    echo "请确保："
    echo "1. 设备已通过USB连接"
    echo "2. 已启用USB调试"
    echo "3. 已授权此计算机进行调试"
    exit 1
fi

# 如果有多个在线设备，让用户选择
if [ "$DEVICES" -gt 1 ]; then
    echo "检测到多个在线设备："
    echo ""
    adb devices -l | grep -E "device$" | grep -v "offline"
    echo ""
    echo "请输入设备序列号："
    read -r SERIAL
    
    if [ -z "$SERIAL" ]; then
        echo "错误: 未输入设备序列号"
        exit 1
    fi
    
    # 验证序列号
    if ! adb devices | grep -q "^$SERIAL.*device$"; then
        echo "错误: 设备 $SERIAL 不存在或不在线"
        exit 1
    fi
    
    ADB_DEVICE="-s $SERIAL"
    echo "使用设备: $SERIAL"
else
    # 获取唯一的在线设备序列号
    SERIAL=$(adb devices | grep -E "device$" | grep -v "offline" | awk '{print $1}')
    ADB_DEVICE="-s $SERIAL"
    echo "使用设备: $SERIAL"
fi

echo ""

# 推送到设备
echo "推送checkline到设备..."
if adb $ADB_DEVICE push "$SOURCE_FILE" /sdcard/checkline-android.sh; then
    echo "推送成功！"
else
    echo "错误: 推送失败"
    exit 1
fi

echo ""
echo "请在Termux中执行以下命令完成安装："
echo ""
echo "  mkdir -p ~/bin"
echo "  cp /sdcard/checkline-android.sh ~/bin/checkline"
echo "  chmod +x ~/bin/checkline"
echo "  echo 'export PATH=\$HOME/bin:\$PATH' >> ~/.bashrc"
echo "  source ~/.bashrc"
echo "  checkline"
echo ""
echo "部署完成！"
