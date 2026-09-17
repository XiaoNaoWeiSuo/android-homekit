#!/bin/bash
# install-checkline-android.sh
# 将checkline安装到Termux环境

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_FILE="$SCRIPT_DIR/checkline-android.sh"
TERMUX_BIN="/data/data/com.termux/files/home/bin"
TERMUX_FILE="$TERMUX_BIN/checkline"

echo "=== checkline Android 安装脚本 ==="
echo ""

# 检查源文件
if [ ! -f "$SOURCE_FILE" ]; then
    echo "错误: 找不到 $SOURCE_FILE"
    exit 1
fi

# 检查是否在Termux环境中
if [ ! -d "/data/data/com.termux" ]; then
    echo "警告: 当前不在Termux环境中"
    echo "请在Termux中运行此脚本，或手动复制文件"
    echo ""
    echo "手动安装步骤："
    echo "1. 复制 checkline-android.sh 到 Termux"
    echo "2. 在Termux中执行："
    echo "   mkdir -p ~/bin"
    echo "   cp checkline-android.sh ~/bin/checkline"
    echo "   chmod +x ~/bin/checkline"
    echo "   export PATH=\$HOME/bin:\$PATH"
    echo ""
    echo "或者添加到 ~/.bashrc:"
    echo "   echo 'export PATH=\$HOME/bin:\$PATH' >> ~/.bashrc"
    exit 0
fi

# 创建bin目录
echo "创建目录: $TERMUX_BIN"
mkdir -p "$TERMUX_BIN"

# 复制文件
echo "复制 checkline 到 $TERMUX_FILE"
cp "$SOURCE_FILE" "$TERMUX_FILE"

# 设置权限
echo "设置执行权限"
chmod +x "$TERMUX_FILE"

# 检查PATH
if [[ ":$PATH:" != *":$TERMUX_BIN:"* ]]; then
    echo ""
    echo "警告: $TERMUX_BIN 不在PATH中"
    echo "添加到当前shell: export PATH=$TERMUX_BIN:\$PATH"
    echo "永久生效: echo 'export PATH=$TERMUX_BIN:\$PATH' >> ~/.bashrc"
fi

echo ""
echo "安装完成！"
echo ""
echo "使用方法："
echo "  checkline        # 显示USB、充电器、电池信息"
echo "  checkline title  # 显示设备标题信息"
echo ""
echo "示例输出："
echo "=== Xiaomi 13 - ABCD1234 - Android 14 (SDK 34) ==="
echo ""
echo "USB"
echo "  USB 2.0 Hub                      USB 2.0   480M"
echo "  Xiaomi 13                        USB 2.0   480M"
echo ""
echo "Charger"
echo "  USB DCP (专用充电端口/充电宝)  10W"
echo ""
echo "Battery"
echo "  85% 充电中  循环42次  28.5°C"
echo "  4.2V 1200mA 5.04W 4500mAh (90%健康)"
echo "  健康: 良好  技术: Li-poly  厂商: Xiaomi"
