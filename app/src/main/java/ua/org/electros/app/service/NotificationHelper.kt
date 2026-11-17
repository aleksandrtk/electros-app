package ua.org.electros.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ua.org.electros.app.MainActivity
import ua.org.electros.app.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID_CHARGING = "charging_status_channel"
        const val CHANNEL_ID_ALERTS = "charging_alerts_channel"
        const val CHANNEL_ID_BALANCING_INFO = "charging_balancing_info_channel"
        const val NOTIFICATION_ID_STATUS = 1001
        const val NOTIFICATION_ID_ALERT = 1002
        const val NOTIFICATION_ID_RESUME_ALERT = 1007
        const val NOTIFICATION_ID_FGS_BLOCKED = 1008
        const val NOTIFICATION_ID_BALANCING = 1009

        const val ACTION_STOP_CHARGE = "ua.org.electros.app.ACTION_STOP_CHARGE"
        const val ACTION_RESUME_CHARGE = "ua.org.electros.app.ACTION_RESUME_CHARGE"
    }

    private fun buildServiceActionPendingIntent(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, ChargeMonitorService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getForegroundService(context, requestCode, intent, flags)
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val statusChannel = NotificationChannel(
            CHANNEL_ID_CHARGING,
            context.getString(R.string.channel_status),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_status_desc)
            setShowBadge(false)
        }

        val alertsChannel = NotificationChannel(
            CHANNEL_ID_ALERTS,
            context.getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alerts_desc)
        }

        val balancingInfoChannel = NotificationChannel(
            CHANNEL_ID_BALANCING_INFO,
            context.getString(R.string.channel_dlb),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_dlb_desc)
        }

        notificationManager.createNotificationChannel(statusChannel)
        notificationManager.createNotificationChannel(alertsChannel)
        notificationManager.createNotificationChannel(balancingInfoChannel)
    }

    fun buildForegroundNotification(
        powerKw: Double,
        deliveredKwh: Double,
        targetKwh: Double,
        statusText: String,
        targetDurationMinutes: Int = 0,
        accumulatedChargingMs: Long = 0L
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopPendingIntent = buildServiceActionPendingIntent(1, ACTION_STOP_CHARGE)

        val timerPart = if (targetDurationMinutes > 0) {
            val targetMs = targetDurationMinutes * 60_000L
            val remSec = ((targetMs - accumulatedChargingMs).coerceAtLeast(0L) / 1000L)
            val h = remSec / 3600
            val m = (remSec % 3600) / 60
            context.getString(R.string.notif_timer_part, h.toInt(), m.toInt())
        } else ""

        val progress = when {
            targetKwh > 0.0 -> ((deliveredKwh / targetKwh) * 100).toInt().coerceIn(0, 100)
            targetDurationMinutes > 0 -> ((accumulatedChargingMs.toDouble() / (targetDurationMinutes * 60_000.0)) * 100).toInt().coerceIn(0, 100)
            else -> 0
        }

        val contentText = when {
            targetKwh > 0.0 -> context.getString(R.string.notif_progress_target, "%.1f".format(deliveredKwh), "%.0f".format(targetKwh), progress, timerPart)
            targetDurationMinutes > 0 -> context.getString(R.string.notif_progress_timer, "%.1f".format(deliveredKwh), timerPart)
            else -> context.getString(R.string.notif_progress_unlimited, "%.1f".format(deliveredKwh))
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_CHARGING)
            .setContentTitle(context.getString(R.string.notif_title, "%.1f".format(powerKw), statusText))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_nav_bolt)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_notif_stop, context.getString(R.string.notif_action_stop), stopPendingIntent)

        if (targetKwh > 0.0 || targetDurationMinutes > 0) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    fun showOutageAlert(retainedKwh: Double, autoResumeEnabled: Boolean = true) {
        val text = if (autoResumeEnabled) {
            context.getString(R.string.notif_outage_auto, "%.1f".format(retainedKwh))
        } else {
            context.getString(R.string.notif_outage_manual, "%.1f".format(retainedKwh))
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle(context.getString(R.string.notif_outage_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notif_warning)
            .setContentIntent(createOpenAppPendingIntent(7))
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT, builder.build())
    }

    fun showManualResumeAvailableAlert(amperes: Int, retainedKwh: Double) {
        val resumePendingIntent = buildServiceActionPendingIntent(5, ACTION_RESUME_CHARGE)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            6,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle(context.getString(R.string.notif_power_back_title))
            .setContentText(context.getString(R.string.notif_power_back_text, "%.1f".format(retainedKwh), amperes))
            .setSmallIcon(R.drawable.ic_nav_bolt)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_notif_play, context.getString(R.string.notif_action_resume), resumePendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_RESUME_ALERT, builder.build())
    }

    fun cancelManualResumeAlert() {
        notificationManager.cancel(NOTIFICATION_ID_RESUME_ALERT)
    }

    fun showTargetReachedAlert(deliveredKwh: Double) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(context.getString(R.string.notif_done_text, "%.1f".format(deliveredKwh)))
            .setSmallIcon(R.drawable.ic_nav_bolt)
            .setContentIntent(createOpenAppPendingIntent(8))
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT, builder.build())
    }

    fun showBalancingAlert(oldAmperes: Int, newAmperes: Int, voltage: Int) {
        val isLowered = newAmperes < oldAmperes
        val title = if (isLowered) {
            context.getString(R.string.notif_dlb_down_title)
        } else {
            context.getString(R.string.notif_dlb_up_title)
        }
        val text = if (isLowered) {
            context.getString(R.string.notif_dlb_down_text, voltage, oldAmperes, newAmperes)
        } else {
            context.getString(R.string.notif_dlb_up_text, voltage, oldAmperes, newAmperes)
        }
        val icon = if (isLowered) R.drawable.ic_notif_warning else R.drawable.ic_nav_bolt
        val channelId = if (isLowered) CHANNEL_ID_ALERTS else CHANNEL_ID_BALANCING_INFO

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            2,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_BALANCING, builder.build())
    }

    fun showConnectionLostAlert(currentAmperes: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle(context.getString(R.string.notif_conn_lost_title))
            .setContentText(context.getString(R.string.notif_conn_lost_text, currentAmperes))
            .setSmallIcon(R.drawable.ic_notif_warning)
            .setContentIntent(createOpenAppPendingIntent(9))
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT + 2, builder.build())
    }

    fun showDurationTargetReachedAlert(targetDurationMinutes: Int, deliveredKwh: Double) {
        val hours = targetDurationMinutes / 60
        val mins = targetDurationMinutes % 60
        val durLabel = if (mins == 0) context.getString(R.string.duration_h, hours) else context.getString(R.string.duration_h_min, hours, mins)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle(context.getString(R.string.notif_timer_done_title))
            .setContentText(context.getString(R.string.notif_timer_done_text, durLabel, "%.1f".format(deliveredKwh)))
            .setSmallIcon(R.drawable.ic_nav_bolt)
            .setContentIntent(createOpenAppPendingIntent(10))
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT, builder.build())
    }

    fun showOutageResumedAlert(amperes: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle(context.getString(R.string.notif_resumed_title))
            .setContentText(context.getString(R.string.notif_resumed_text, amperes))
            .setSmallIcon(R.drawable.ic_nav_bolt)
            .setContentIntent(createOpenAppPendingIntent(11))
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT + 3, builder.build())
    }

    private fun createOpenAppPendingIntent(requestCode: Int): PendingIntent {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun showStopFailedAlert() {
        val pendingIntent = createOpenAppPendingIntent(4)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle(context.getString(R.string.notif_stop_failed_title))
            .setContentText(context.getString(R.string.notif_stop_failed_text))
            .setSmallIcon(R.drawable.ic_notif_warning)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT + 4, builder.build())
    }

    fun showMonitorRestartRequiredAlert() {
        val pendingIntent = createOpenAppPendingIntent(5)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle(context.getString(R.string.notif_restart_title))
            .setContentText(context.getString(R.string.notif_restart_text))
            .setSmallIcon(R.drawable.ic_notif_warning)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_FGS_BLOCKED, builder.build())
    }
}

