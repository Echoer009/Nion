# vivo 设备 logcat 采集指南

## 设备限制

- 单个进程每秒最多输出 250 行，超出被静默丢弃。
- 敏感数据（手机号、IMEI、IP 等）会被脱敏为星号。debug 版本不受影响。
- `log.tag=M` 是 vivo 定制过滤等级，不得依赖 `setprop` 修改。

## 关键操作

### 1. 增大日志缓冲区

开发者选项 → 日志记录器缓冲区大小 → 设为最大（通常 8M 或 16M）。

不要关闭，关闭后无法抓日志。越大越不容易被其他进程挤掉。

### 2. 命令行抓日志

不要分步执行：

```bash
# Windows (Git Bash) — 使用 $ANDROID_HOME 或替换为你的 SDK 路径
adb logcat -c && \
adb shell am start -n com.echonion.nion/.MainActivity && \
adb logcat -d -v threadtime
```

必须用 `&&` 紧连：清空 → 启动 → 立即 dump。中间不能有任何停顿，否则系统进程刷屏把 app 日志挤出环形缓冲区。

过滤用 grep，不要用 `-s` 参数：

```bash
# 只看指定 tag
adb logcat -d -v threadtime | grep "NionApp"

# 看指定进程所有日志（先取 PID）
PID=$(adb shell pidof com.echonion.nion)
adb logcat -d -v threadtime | grep " $PID "
```

### 3. Kotlin 代码中打日志

```kotlin
import android.util.Log

Log.d("TAG", "message")
Log.e("TAG", "message", throwable)
```

`Log.wtf()` 不建议使用，可能导致系统弹框。

## 常见失败原因

| 现象 | 原因 |
|------|------|
| 日志为空 | 清空和 dump 之间间隔太久，缓冲区被覆盖 |
| 日志不全 | 缓冲区太小，增大到 8M+ |
| grep 无结果 | tag 名称拼写错误，或大小写不匹配 |
| 无 app 进程日志 | app 已崩溃或未启动，先 `pidof` 确认 |
