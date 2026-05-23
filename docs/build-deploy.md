# 构建与部署

## 环境要求

- Windows + Git Bash（或 WSL2）
- Android SDK 路径：`$HOME/AppData/Local/Android/Sdk`
- 手机通过 USB 直连电脑
- 手机需开启「USB 调试」和「USB 安装」权限

## 构建 APK

```bash
cd app && ./gradlew assembleDebug
```

APK 产物路径：`app/app/build/outputs/apk/debug/app-debug.apk`

## 安装到手机（Windows Git Bash）

```bash
ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# 安装 APK（-r 覆盖安装）
"$ADB" install -r /d/nion/app/app/build/outputs/apk/debug/app-debug.apk

# 强制停止应用
"$ADB" shell am force-stop com.echonion.nion

# 启动应用
"$ADB" shell am start -n com.echonion.nion/.MainActivity
```

## 一行命令：构建 + 安装 + 启动

```bash
cd /d/nion/app && ./gradlew assembleDebug && \
ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" && \
"$ADB" install -r /d/nion/app/app/build/outputs/apk/debug/app-debug.apk && \
"$ADB" shell am force-stop com.echonion.nion && \
"$ADB" shell am start -n com.echonion.nion/.MainActivity
```

## 常见问题

- **Install failed**：手机上确认 USB 安装权限弹窗
- **device not found**：检查 USB 线连接，运行 `"$ADB" devices` 确认设备
- **WSL2 环境**：需通过 `usbipd` 将 USB 设备从 Windows 透传到 WSL，使用 `./deploy.sh`
