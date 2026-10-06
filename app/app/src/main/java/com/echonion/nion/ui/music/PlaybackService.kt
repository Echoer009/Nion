package com.echonion.nion.ui.music

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 音乐播放前台服务 —— 基于 Media3 MediaSessionService 实现。
 *
 * 职责：
 * 1. 持有全局唯一的 ExoPlayer + MediaSession 实例（后台播放、通知栏控制的载体）
 * 2. 注册音频属性 —— 告诉系统这是音乐播放，接管音频焦点（打断策略由系统调度）
 * 3. 通知栏播放控制：MediaSessionService 配合默认的 DefaultMediaNotificationProvider
 *    自动生成 MediaStyle 通知（播放/暂停/上一首/下一首 + 封面），无需手写通知代码
 *
 * 生命周期：
 * - UI 通过 MediaController 连接本服务（见 MusicViewModel），controller 首次连接时
 *   系统自动 startForegroundService 拉起服务；播放暂停后自动停止前台
 * - onTaskRemoved（用户划掉任务）时暂停播放，避免后台空转
 *
 * 参照项目 reminder 包前台服务的注释风格；媒体通知部分交给 Media3 默认实现。
 */
class PlaybackService : MediaSessionService() {

    /** 全局媒体会话；onCreate 创建、onDestroy 释放，中间不为 null */
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // 音频属性：CONTENT_TYPE_MUSIC + USAGE_MEDIA 让系统按"音乐"处理音频焦点
        // （如来电时自动压低/暂停，与其他音乐 App 竞争焦点时行为正确）
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            // 耳机拔出自动暂停，避免外放尴尬
            .setHandleAudioBecomingNoisy(true)
            // 网络流播放期间持有 WakeLock（TYPE_PARTIAL），锁屏继续播放
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    /**
     * 返回当前 MediaSession —— MediaController 连接的入口。
     * 返回 null 表示服务不可用（onDestroy 后）。
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 用户从最近任务划掉 App 时调用：仅暂停不 release，
     * MediaSessionService 会在无活跃播放时自动停止服务。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            // 没在播放 → 直接停服；在播放则交由系统在播放结束/暂停后自动停
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 必须先 release player 再 release session，顺序不可反（session 持有 player 引用）
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
