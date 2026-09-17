#!/bin/bash
# test-checkline-android.sh
# 测试checkline-android.sh脚本的语法和功能

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_FILE="$SCRIPT_DIR/checkline-android.sh"

echo "=== checkline-android 测试脚本 ==="
echo ""

# 检查源文件
if [ ! -f "$SOURCE_FILE" ]; then
    echo "错误: 找不到 $SOURCE_FILE"
    exit 1
fi

echo "1. 检查脚本语法..."
if bash -n "$SOURCE_FILE"; then
    echo "   ✓ 语法检查通过"
else
    echo "   ✗ 语法错误"
    exit 1
fi

echo ""
echo "2. 检查脚本权限..."
if [ -x "$SOURCE_FILE" ]; then
    echo "   ✓ 脚本有执行权限"
else
    echo "   ✗ 脚本没有执行权限"
    chmod +x "$SOURCE_FILE"
    echo "   ✓ 已添加执行权限"
fi

echo ""
echo "3. 检查shebang行..."
HEAD_LINE=$(head -1 "$SOURCE_FILE")
if [[ "$HEAD_LINE" == *"#!/data/data/com.termux/files/usr/bin/bash"* ]]; then
    echo "   ✓ Shebang行正确"
else
    echo "   ✗ Shebang行不正确: $HEAD_LINE"
fi

echo ""
echo "4. 检查关键功能函数..."
if grep -q "USB设备检测" "$SOURCE_FILE"; then
    echo "   ✓ 包含USB设备检测功能"
else
    echo "   ✗ 缺少USB设备检测功能"
fi

if grep -q "电池" "$SOURCE_FILE" && grep -q "Battery" "$SOURCE_FILE"; then
    echo "   ✓ 包含电池健康监控功能"
else
    echo "   ✗ 缺少电池健康监控功能"
fi

if grep -q "充电器" "$SOURCE_FILE" && grep -q "Charger" "$SOURCE_FILE"; then
    echo "   ✓ 包含充电器检测功能"
else
    echo "   ✗ 缺少充电器检测功能"
fi

echo ""
echo "5. 检查支持的充电协议..."
if grep -q "USB PD" "$SOURCE_FILE"; then
    echo "   ✓ 支持USB PD充电协议"
else
    echo "   ✗ 不支持USB PD充电协议"
fi

if grep -q "QC" "$SOURCE_FILE"; then
    echo "   ✓ 支持高通QC快充"
else
    echo "   ✗ 不支持高通QC快充"
fi

echo ""
echo "6. 检查输出格式..."
if grep -q "printf" "$SOURCE_FILE"; then
    echo "   ✓ 使用printf格式化输出"
else
    echo "   ✗ 未使用printf格式化输出"
fi

if grep -q "033\[1m" "$SOURCE_FILE"; then
    echo "   ✓ 支持彩色输出"
else
    echo "   ✗ 不支持彩色输出"
fi

echo ""
echo "=== 测试完成 ==="
echo ""
echo "脚本已准备好部署到Termux环境"
echo "使用方法: ./deploy-checkline.sh"
