package dev.zzunkie.whenever.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.zzunkie.whenever.R
import dev.zzunkie.whenever.ui.MainActivity

/** 화면이 꺼져 있어도 규칙을 지켜보는 포그라운드 서비스 */
class RuleService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RUNNING, "자동화 감시", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "자동화 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL_RUNNING)
            .setContentTitle("Ditto")
            .setContentText("자동화를 지켜보고 있어요")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Engine.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        Engine.stop()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_RUNNING = "running"
        const val CHANNEL_ALERTS = "alerts"
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, RuleService::class.java))
    }
}
