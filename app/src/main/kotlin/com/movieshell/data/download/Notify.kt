package com.movieshell.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.movieshell.MainActivity
import com.movieshell.R

/**
 * 通知单入口（v0.26.0）。
 *
 * - 追剧更新：点通知直达详情页（带 subkey 恢复站点上下文）。
 * - 下载进度：前台服务式常驻通知（Worker 用 setForeground 接管）。
 */
object Notify {

    const val CH_FOLLOW = "follow_update"
    const val CH_DOWNLOAD = "download"
    // v1.4.34 C 组 P2-12：下载完成单独一个渠道（DEFAULT，与追剧/小说/源检测同级，
    // 有提示音、进通知栏）。不用 CH_DOWNLOAD 提级——已创建渠道的重要性改不动，
    // 老用户已有的 LOW 渠道提不上去；进度通知本身就该 LOW（常驻不打扰）。
    const val CH_DOWNLOAD_DONE = "download_done"
    // v0.85.0：小说自动追更
    const val CH_NOVEL = "novel_update"
    // v0.90.0：站源批量检测
    const val CH_PROBE = "source_probe"

    // v1.4.34 C 组 P2-01：通知 ID 不再用 String.hashCode()——不同标题/影片可能
    // 撞同一个值（vodId 空串时全撞 0），互相覆盖。
    // v1.4.34 修复轮（GPT 审码 P2-01）：改确定性 ID——同一 key 在任何进程永远
    // 拿同一 ID（同片更新覆盖旧通知，不同 key 不互盖；空串也有自己的 ID），
    // 进程重启不再换号，通知栏里的旧通知能被新通知覆盖。SHA-256 前 4 字节
    // 取 31 位再进 10 亿空间：key 只有几十个时碰撞概率可忽略；万一撞上也
    // 只是两条通知互相覆盖，不丢数据、不影响 ID 之外的任何逻辑。
    // 不用持久化映射：通知 ID 只在通知存活期间要求稳定，确定性哈希已满足，
    // 且不引入 SharedPreferences 的读写与迁移。
    fun stableNotificationId(key: String): Int {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        val raw = ((digest[0].toInt() and 0xFF) shl 24) or
            ((digest[1].toInt() and 0xFF) shl 16) or
            ((digest[2].toInt() and 0xFF) shl 8) or
            (digest[3].toInt() and 0xFF)
        return 10000 + (raw and Int.MAX_VALUE) % 1_000_000_000
    }

    const val EXTRA_ROUTE = "xiaying.route"
    const val EXTRA_SUBKEY = "xiaying.subkey"
    const val ACTION_ROUTE = "xiaying.ROUTE"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_FOLLOW) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_FOLLOW, "追剧更新", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (nm.getNotificationChannel(CH_DOWNLOAD) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_DOWNLOAD, "下载", NotificationManager.IMPORTANCE_LOW),
            )
        }
        // v1.4.34 C 组 P2-12：下载完成单独建渠道（DEFAULT：有提示音、进通知栏，
        // 与追剧/小说/源检测同级；不用横幅 HIGH——例行完成通知不值得打断当前界面）。
        if (nm.getNotificationChannel(CH_DOWNLOAD_DONE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_DOWNLOAD_DONE, "下载完成", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (nm.getNotificationChannel(CH_NOVEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_NOVEL, "小说追更", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (nm.getNotificationChannel(CH_PROBE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_PROBE, "源检测", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /** 打开 App 并跳到指定路由的 PendingIntent。 */
    fun routeIntent(context: Context, route: String, subKey: String = "", id: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "xiaying.ROUTE"
            putExtra(EXTRA_ROUTE, route)
            putExtra(EXTRA_SUBKEY, subKey)
            // v1.4.34 C 组 P2-01：data 只用稳定 id 区分意图，不再拼 route.hashCode()
            //（不同路由也可能撞同一个 hash；id 已唯一，hash 是多余的碰撞源）。
            data = Uri.parse("xiaying://route/$id")
        }
        return PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 追剧更新通知。 */
    fun followUpdate(context: Context, title: String, text: String, vodId: String, subKey: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // v1.4.34 C 组 P2-01：vodId.hashCode() 改稳定映射（空串不再全撞 0）。
        val id = stableNotificationId("follow:$vodId")
        val pi = routeIntent(context, "detail/${Uri.encode(vodId)}", subKey, id)
        val n = NotificationCompat.Builder(context, CH_FOLLOW)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    /** 下载进度通知（供 Worker 前台调用）。 */
    fun downloadProgress(context: Context, title: String, progress: Int): android.app.Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
    }

    /** 下载完成通知（点通知进下载管理页）。 */
    fun downloadDone(context: Context, title: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // v1.4.34 C 组 P2-01：title.hashCode() 改稳定映射；P2-12：走下载完成
        // 专属渠道（DEFAULT，有提示音），不再走 LOW 的下载进度渠道（无提示音、
        // 完成时用户收不到任何提醒）。
        val id = stableNotificationId("download:$title")
        val pi = routeIntent(context, "downloads", id = id)
        val n = NotificationCompat.Builder(context, CH_DOWNLOAD_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("下载完成")
            .setContentText(title)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    /** v0.85.0：小说自动追更通知（点通知进书架）。 */
    fun novelUpdate(context: Context, bookCount: Int, chapterCount: Int) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = routeIntent(context, "bookshelf", id = 9002)
        val n = NotificationCompat.Builder(context, CH_NOVEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("小说追更")
            .setContentText("$bookCount 本书共新增 $chapterCount 章")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(9002, n) }
    }

    /** v0.90.0：批量检测完成通知（点通知回站源页检测 tab；网页源 tab 插入后检测 tab 改为 3）。 */
    fun probeDone(context: Context, total: Int, ok: Int, slow: Int, fail: Int) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = routeIntent(context, "sources?tab=3", id = 9003)
        val n = NotificationCompat.Builder(context, CH_PROBE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("源检测完成")
            .setContentText("共 $total 个源：正常 $ok，缓慢 $slow，失败 $fail")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(9003, n) }
    }

    /** 进入追剧/下载页时调用一次：API 33+ 申请通知权限。 */
    @Composable
    fun RequestNotificationPermissionOnce() {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
