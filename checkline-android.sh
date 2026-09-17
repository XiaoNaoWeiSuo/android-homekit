#!/data/data/com.termux/files/usr/bin/bash
# checkline - USB 设备、充电器、电池信息 (Android/Termux)
# 用法: checkline [title]

# 自动提权：如果sysfs不可读，用su重新运行自己
if [ ! -r /sys/class/power_supply/battery/capacity ] 2>/dev/null; then
    exec su -c "/data/data/com.termux/files/home/bin/checkline $*"
fi

B='\033[1m' N='\033[0m' Y='\033[33m' DIM='\033[2m'
TITLE_MODE=0
if [ "$1" = "title" ]; then
    TITLE_MODE=1
elif [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    echo "checkline - USB 设备、充电器、电池信息检查工具 (Android)"
    echo ""
    echo "用法: checkline [选项]"
    echo ""
    echo "选项:"
    echo "  title    显示设备标题信息"
    echo "  -h, --help  显示此帮助信息"
    exit 0
fi

if [ $TITLE_MODE -eq 1 ]; then
    MODEL=$(getprop ro.product.model 2>/dev/null)
    BRAND=$(getprop ro.product.brand 2>/dev/null)
    ANDROID_VER=$(getprop ro.build.version.release 2>/dev/null)
    SDK=$(getprop ro.build.version.sdk 2>/dev/null)
    SERIAL=$(getprop ro.serialno 2>/dev/null)
    echo -e "${B}=== $BRAND $MODEL - $SERIAL - Android $ANDROID_VER (SDK $SDK) ===${N}"
    echo ""
fi

echo -e "${B}USB${N}"
# USB设备检测
usb_found=0
if [ -d "/sys/bus/usb/devices" ]; then
    for dev in /sys/bus/usb/devices/[0-9]*; do
        [ -d "$dev" ] || continue
        # 跳过USB接口目录（包含冒号的）
        basename_dev=$(basename "$dev")
        case "$basename_dev" in
            *:*) continue ;;
        esac
        
        # 读取设备信息
        idvendor=$(cat "$dev/idVendor" 2>/dev/null)
        idproduct=$(cat "$dev/idProduct" 2>/dev/null)
        product=$(cat "$dev/product" 2>/dev/null)
        manufacturer=$(cat "$dev/manufacturer" 2>/dev/null)
        serial=$(cat "$dev/serial" 2>/dev/null)
        speed=$(cat "$dev/speed" 2>/dev/null)
        version=$(cat "$dev/version" 2>/dev/null)
        class=$(cat "$dev/bDeviceClass" 2>/dev/null)
        
        [ -z "$idvendor" ] && continue
        
        # 速度转换
        case "$speed" in
            "1.5") speed_str="1.5M" ;;
            "12") speed_str="12M" ;;
            "480") speed_str="480M" ;;
            "5000") speed_str="5G" ;;
            "10000") speed_str="10G" ;;
            "20000") speed_str="20G" ;;
            "40000") speed_str="40G" ;;
            "80000") speed_str="80G" ;;
            "120000") speed_str="120G" ;;
            *) speed_str="${speed}M" ;;
        esac
        
        # USB版本转换（从version字段推断）
        if [ -n "$version" ]; then
            major=$(echo "$version" | cut -d. -f1)
            minor=$(echo "$version" | cut -d. -f2)
            case "$major" in
                "1") usb_ver="USB 1.x" ;;
                "2") usb_ver="USB 2.0" ;;
                "3") 
                    if [ "$minor" = "00" ]; then
                        usb_ver="USB 3.0"
                    elif [ "$minor" = "10" ]; then
                        usb_ver="USB 3.1"
                    elif [ "$minor" = "20" ]; then
                        usb_ver="USB 3.2"
                    else
                        usb_ver="USB 3.$minor"
                    fi
                    ;;
                "4") usb_ver="USB4" ;;
                *) usb_ver="USB ?" ;;
            esac
        else
            usb_ver="USB ?"
        fi
        
        # 设备类别
        case "$class" in
            "0") class_str="由接口决定" ;;
            "1") class_str="音频" ;;
            "2") class_str="CDC通信" ;;
            "3") class_str="HID" ;;
            "7") class_str="打印机" ;;
            "8") class_str="大容量存储" ;;
            "9") class_str="HUB" ;;
            "239") class_str="视频" ;;
            "255") class_str="厂商自定义" ;;
            *) class_str="Class $class" ;;
        esac
        
        # 设备名称
        devname="$product"
        [ -z "$devname" ] && devname="$manufacturer"
        [ -z "$devname" ] && devname="设备 $idvendor:$idproduct"
        
        usb_found=1
        
        # 检测是否为充电设备
        charger_info=""
        if [ -f "/sys/class/power_supply/usb/online" ]; then
            usb_online=$(cat "/sys/class/power_supply/usb/online" 2>/dev/null)
            if [ "$usb_online" = "1" ]; then
                usb_type=$(cat "/sys/class/power_supply/usb/usb_type" 2>/dev/null)
                current_max=$(cat "/sys/class/power_supply/usb/current_max" 2>/dev/null)
                voltage_max=$(cat "/sys/class/power_supply/usb/voltage_max" 2>/dev/null)
                
                # 计算功率
                if [ -n "$current_max" ] && [ -n "$voltage_max" ]; then
                    # 转换为标准单位（微安->毫安，微伏->毫伏）
                    current_ma=$(echo "$current_max / 1000" | bc 2>/dev/null)
                    voltage_mv=$(echo "$voltage_max / 1000" | bc 2>/dev/null)
                    if [ -n "$current_ma" ] && [ -n "$voltage_mv" ]; then
                        power_w=$(echo "scale=1; $current_ma * $voltage_mv / 1000000" | bc 2>/dev/null)
                        [ -n "$power_w" ] && charger_info=" ${Y}⚡${power_w}W"
                    fi
                fi
            fi
        fi
        
        printf "  \033[1m%-32s\033[0m  %-8s  %s%s\n" "$devname" "$usb_ver" "$speed_str" "$charger_info"
        printf "  %32s  \033[2mVID:PID %s:%s  %s\033[0m\n" "" "$idvendor" "$idproduct" "$class_str"
        [ -n "$serial" ] && printf "  %32s  \033[2mSN: %s\033[0m\n" "" "$serial"
    done
fi

if [ $usb_found -eq 0 ]; then
    echo "  未检测到USB设备"
fi

# 充电器信息
echo ""
if [ $TITLE_MODE -eq 1 ]; then
    echo -e "${B}=== 充电器信息 ===${N}"
else
    echo -e "${B}Charger${N}"
fi

# 检查所有电源设备
charger_found=0
if [ -d "/sys/class/power_supply" ]; then
    for ps in /sys/class/power_supply/*/; do
        [ -d "$ps" ] || continue
        ps_name=$(basename "$ps")
        ps_type=$(cat "$ps/type" 2>/dev/null)
        ps_online=$(cat "$ps/online" 2>/dev/null)
        
        # 只显示在线的充电设备
        if [ "$ps_online" = "1" ] && [ "$ps_type" != "Battery" ]; then
            charger_found=1
            
            # 获取充电参数
            current_max=$(cat "$ps/current_max" 2>/dev/null)
            voltage_max=$(cat "$ps/voltage_max" 2>/dev/null)
            current_now=$(cat "$ps/current_now" 2>/dev/null)
            voltage_now=$(cat "$ps/voltage_now" 2>/dev/null)
            
            # 显示充电类型
            case "$ps_type" in
                "USB") 
                    usb_type=$(cat "$ps/usb_type" 2>/dev/null)
                    case "$usb_type" in
                        "SDP") charger_type="USB SDP" ;;
                        "DCP") charger_type="USB DCP" ;;
                        "CDP") charger_type="USB CDP" ;;
                        "PD") charger_type="USB PD" ;;
                        "PD_DRP") charger_type="USB PD DRP" ;;
                        "PD_PPS") charger_type="USB PD PPS" ;;
                        "USB_HVDCP") charger_type="QC" ;;
                        "USB_HVDCP_3") charger_type="QC3.0" ;;
                        "USB_HVDCP_3P5") charger_type="QC3.5" ;;
                        *) charger_type="USB $usb_type" ;;
                    esac
                    ;;
                "Mains") charger_type="AC" ;;
                "USB_DCP") charger_type="DCP" ;;
                "USB_CDP") charger_type="CDP" ;;
                "USB_PD") charger_type="PD" ;;
                *) charger_type="$ps_type" ;;
            esac
            
            # 计算功率
            power_info=""
            if [ -n "$current_max" ] && [ -n "$voltage_max" ]; then
                current_ma=$(echo "$current_max / 1000" | bc 2>/dev/null)
                voltage_mv=$(echo "$voltage_max / 1000" | bc 2>/dev/null)
                if [ -n "$current_ma" ] && [ -n "$voltage_mv" ]; then
                    power_w=$(echo "scale=1; $current_ma * $voltage_mv / 1000000" | bc 2>/dev/null)
                    [ -n "$power_w" ] && power_info="${power_w}W"
                fi
            fi
            
            # 显示实时充电状态
            realtime_info=""
            if [ -n "$current_now" ] && [ -n "$voltage_now" ]; then
                current_now_ma=$(echo "$current_now / 1000" | bc 2>/dev/null)
                voltage_now_mv=$(echo "$voltage_now / 1000" | bc 2>/dev/null)
                if [ -n "$current_now_ma" ] && [ -n "$voltage_now_mv" ]; then
                    power_now_w=$(echo "scale=1; $current_now_ma * $voltage_now_mv / 1000000" | bc 2>/dev/null)
                    [ -n "$power_now_w" ] && realtime_info="实时: ${voltage_now_mv}mV ${current_now_ma}mA ${power_now_w}W"
                fi
            fi
            
            printf "  \033[1m%s\033[0m  %s" "$charger_type" "$power_info"
            [ -n "$realtime_info" ] && printf "  \033[2m%s\033[0m" "$realtime_info"
            printf "\n"
        fi
    done
fi

if [ $charger_found -eq 0 ]; then
    echo "  未检测到充电器"
fi

# 电池信息
echo ""
echo -e "${B}Battery${N}"

battery_found=0
if [ -d "/sys/class/power_supply/battery" ]; then
    battery_dir="/sys/class/power_supply/battery"
    
    capacity=$(cat "$battery_dir/capacity" 2>/dev/null)
    status=$(cat "$battery_dir/status" 2>/dev/null)
    health=$(cat "$battery_dir/health" 2>/dev/null)
    technology=$(cat "$battery_dir/technology" 2>/dev/null)
    voltage_now=$(cat "$battery_dir/voltage_now" 2>/dev/null)
    current_now=$(cat "$battery_dir/current_now" 2>/dev/null)
    temp=$(cat "$battery_dir/temp" 2>/dev/null)
    cycle_count=$(cat "$battery_dir/cycle_count" 2>/dev/null)
    charge_full=$(cat "$battery_dir/charge_full" 2>/dev/null)
    charge_full_design=$(cat "$battery_dir/charge_full_design" 2>/dev/null)
    model_name=$(cat "$battery_dir/model_name" 2>/dev/null)
    manufacturer=$(cat "$battery_dir/manufacturer" 2>/dev/null)
    
    [ -n "$capacity" ] && battery_found=1
    
    if [ $battery_found -eq 1 ]; then
        # 状态显示
        case "$status" in
            "Charging") status_str="充电中" ;;
            "Discharging") status_str="放电中" ;;
            "Full") status_str="已充满" ;;
            "Not charging") status_str="未充电" ;;
            *) status_str="$status" ;;
        esac
        
        # 健康状态
        case "$health" in
            "Good") health_str="良好" ;;
            "Overheat") health_str="过热" ;;
            "Dead") health_str="损坏" ;;
            "Cold") health_str="过冷" ;;
            "Over voltage") health_str="过压" ;;
            "Unspecified failure") health_str="未知故障" ;;
            "Watchdog timer expire") health_str="看门狗超时" ;;
            "Safety timer expire") health_str="安全超时" ;;
            "Over current") health_str="过流" ;;
            "Warm") health_str="温暖" ;;
            "Cool") health_str="凉爽" ;;
            "Hot") health_str="过热" ;;
            *) health_str="$health" ;;
        esac
        
        # 温度（0.1°C转°C）
        temp_str=""
        if [ -n "$temp" ]; then
            temp_c=$(echo "scale=1; $temp / 10" | bc 2>/dev/null)
            temp_str="${temp_c}°C"
        fi
        
        # 电压和电流
        volt_str=""
        if [ -n "$voltage_now" ]; then
            volt_v=$(echo "scale=2; $voltage_now / 1000000" | bc 2>/dev/null)
            volt_str="${volt_v}V"
        fi
        
        curr_str=""
        power_str=""
        if [ -n "$current_now" ]; then
            curr_ma=$(echo "$current_now / 1000" | bc 2>/dev/null)
            [ -n "$curr_ma" ] && curr_str="${curr_ma}mA"
            
            if [ -n "$voltage_now" ] && [ -n "$curr_ma" ]; then
                voltage_v=$(echo "scale=2; $voltage_now / 1000000" | bc 2>/dev/null)
                [ -n "$voltage_v" ] && power_w=$(echo "scale=2; $voltage_v * $curr_ma / 1000" | bc 2>/dev/null)
                [ -n "$power_w" ] && power_str="${power_w}W"
            fi
        fi
        
        # 电池健康度计算
        health_pct=""
        if [ -n "$charge_full" ] && [ -n "$charge_full_design" ] && [ "$charge_full_design" != "0" ]; then
            health_pct=$(echo "scale=1; $charge_full * 100 / $charge_full_design" | bc 2>/dev/null)
        fi
        
        # 容量显示
        capacity_str=""
        if [ -n "$charge_full" ]; then
            # 转换为mAh（微安时->毫安时）
            capacity_mah=$(echo "$charge_full / 1000" | bc 2>/dev/null)
            [ -n "$capacity_mah" ] && capacity_str="${capacity_mah}mAh"
            if [ -n "$health_pct" ] && [ -n "$capacity_str" ]; then
                capacity_str="${capacity_str} (${health_pct}%健康)"
            fi
        fi
        
        # 输出电池信息
        printf "  \033[1m%s%% %s\033[0m" "$capacity" "$status_str"
        [ -n "$cycle_count" ] && [ "$cycle_count" != "0" ] && printf "  循环%d次" "$cycle_count"
        [ -n "$temp_str" ] && printf "  %s" "$temp_str"
        printf "\n"
        
        # 详细信息
        detail_info=""
        [ -n "$volt_str" ] && detail_info="${detail_info} ${volt_str}"
        [ -n "$curr_str" ] && detail_info="${detail_info} ${curr_str}"
        [ -n "$power_str" ] && detail_info="${detail_info} ${power_str}"
        [ -n "$capacity_str" ] && detail_info="${detail_info} ${capacity_str}"
        
        if [ -n "$detail_info" ]; then
            printf "  \033[2m%s\033[0m\n" "$detail_info"
        fi
        
        # 电池规格
        printf "  \033[2m健康: %s  技术: %s" "$health_str" "$technology"
        [ -n "$manufacturer" ] && printf "  厂商: %s" "$manufacturer"
        [ -n "$model_name" ] && printf "  型号: %s" "$model_name"
        printf "\033[0m\n"
    fi
fi

if [ $battery_found -eq 0 ]; then
    echo "  未检测到电池信息"
fi

echo ""
