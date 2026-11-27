package com.movieshell.player

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.movieshell.core.log.LogBus
import java.io.File

/**
 * v0.294.29：磁力引擎独立进程（android:process=":magnet"）。
 * libtorrent 原生崩溃只会杀掉这个进程，主程序不受影响；主进程经回环 HTTP（/ctl/ 接口）控制。
 */
class MagnetService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // v0.294.38：前台服务 + 通知，切到后台 / 画中画时系统不回收磁力进程
        // v1.4.33：startForeground 失败不再只记日志——失败时服务若还活着，
        // 系统会在 5 秒内以 RemoteServiceException 杀掉 :magnet 进程，
        // 磁力“边下边播”直接断。失败时立刻把原因写进端口文件（主进程
        // MagnetRemote.ensure() 读到 fail: 即向用户报“磁力引擎启动失败”，
        // 可见、可重试），再 stopSelf()，不启动引擎线程（否则引擎起来后
        // 端口文件又被覆盖成端口号，主进程以为引擎可用，实际服务已死）。
        // v1.4.33 S7：失败原因写进端口文件（与引擎失败路径口径统一），
        // 只写死“前台服务启动失败”等于让用户照着重试必然同样失败。
        // S8：端口文件写入走 tmp+rename 原子写——主进程 250ms 一轮读，
        // 直接 writeText 可能读到空串白等一轮。
        val foregroundError = runCatching {
            com.movieshell.data.download.Notify.ensureChannels(this)
            val n = androidx.core.app.NotificationCompat.Builder(this, com.movieshell.data.download.Notify.CH_DOWNLOAD)
                .setSmallIcon(com.movieshell.R.drawable.ic_stat)
                .setContentTitle("侠影磁力引擎运行中")
                .setContentText("边下边播中，离开播放后自动停止")
                .setOngoing(true).setSilent(true)
                .setContentIntent(com.movieshell.data.download.Notify.routeIntent(this, "home", id = 9101))
                .build()
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(9101, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else startForeground(9101, n)
        }.onFailure { LogBus.w("XiaYingMagnet", "磁力前台服务启动失败：${it.message}") }.exceptionOrNull()
        if (foregroundError != null) {
            writePortFail(
                "前台服务启动失败：" + (foregroundError.message ?: foregroundError.javaClass.simpleName),
            )
            stopSelf()
            return
        }
        Thread({
            try {
                XiaYingMagnetEngine.ensureHttp(applicationContext)
                val port = XiaYingMagnetEngine.port()
                val f = MagnetRemote.portFile(applicationContext)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(port.toString()); tmp.renameTo(f)
            } catch (t: Throwable) {
                LogBus.e("XiaYingMagnet", "磁力进程启动失败：${t.message}")
                writePortFail(t.message ?: t::class.java.simpleName)
            }
        }, "magnet-boot").start()
    }

    /** S8：失败原因写端口文件也走 tmp+rename 原子写（见 onCreate）。 */
    private fun writePortFail(reason: String) {
        runCatching {
            val f = MagnetRemote.portFile(applicationContext)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText("fail:$reason")
            tmp.renameTo(f)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 主界面划掉：磁力进程一起退出，不在后台下载
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
