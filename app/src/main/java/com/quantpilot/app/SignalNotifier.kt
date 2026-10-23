package com.quantpilot.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object SignalNotifier {
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_SIGNAL = "signal"
    const val NOTIF_MONITOR_ID = 1

    fun initChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "监控状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "QuantPilot 后台监控运行状态"
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_SIGNAL, "买卖信号", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "策略信号变化时推送"
            }
        )
    }

    fun monitorNotification(context: Context, text: String) =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setContentTitle("QuantPilot 监控中")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun sendSignal(context: Context, stockName: String, signalText: String, strategyName: String, notifId: Int) {
        val notif = NotificationCompat.Builder(context, CHANNEL_SIGNAL)
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setContentTitle("$signalText · $stockName")
            .setContentText("策略：$strategyName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notifId, notif)
    }
}
