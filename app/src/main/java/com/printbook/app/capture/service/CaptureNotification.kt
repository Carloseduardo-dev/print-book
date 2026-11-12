package com.printbook.app.capture.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.printbook.app.MainActivity

object CaptureNotification {
    const val CHANNEL_ID = "capture"
    const val NOTIFICATION_ID = 1001

    fun build(context: Context, captured: Int, message: String? = null): Notification {
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 1, CaptureService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("print-book capturando")
            .setContentText(message ?: "$captured páginas capturadas")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "Parar", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Notificação final (não fixa): tocar abre o livro capturado. */
    fun finished(context: Context, bookId: Long, title: String, pages: Int): Notification {
        val open = PendingIntent.getActivity(
            context, 2, MainActivity.openBookIntent(context, bookId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Captura concluída")
            .setContentText("$pages páginas em \"$title\". Toque para abrir.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }
}
