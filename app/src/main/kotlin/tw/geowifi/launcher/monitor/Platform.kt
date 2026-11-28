package tw.geowifi.launcher.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import tw.geowifi.launcher.MainActivity
import tw.geowifi.launcher.R

object Permissions {
    fun fine(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun background(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return fine(context)
        return granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun nearbyWifi(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return granted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    fun notifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return granted(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    fun overlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun ignoringBattery(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun exactAlarm(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun fullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun bluetooth(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return granted(context, Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun locationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java)
        return if (Build.VERSION.SDK_INT >= 28) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun canMonitor(context: Context): Boolean = fine(context) && background(context)

    private fun granted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

object Notifications {
    const val monitorId = 41
    private const val monitorChannel = "monitor"
    private const val launchChannel = "launch"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val monitor = NotificationChannel(
            monitorChannel,
            context.getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.monitor_channel_desc) }
        val launch = NotificationChannel(
            launchChannel,
            context.getString(R.string.launch_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.launch_channel_desc) }
        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(launch)
    }

    fun monitoring(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, monitorChannel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.monitor_notification_title))
            .setContentText(context.getString(R.string.monitor_notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun launchPrompt(context: Context, ruleId: Long, appLabel: String, launch: Intent): Notification {
        val pending = PendingIntent.getActivity(
            context,
            ruleId.toInt(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, launchChannel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.launch_notification_title, appLabel))
            .setContentText(context.getString(R.string.launch_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
        if (Permissions.fullScreenIntent(context)) {
            builder.setFullScreenIntent(pending, true)
        }
        return builder.build()
    }
}

object AppLauncher {
    sealed interface Outcome {
        data object Opened : Outcome
        data object NeedsTap : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun launch(context: Context, ruleId: Long, packageName: String, label: String): Outcome {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Outcome.Failed(context.getString(R.string.launch_missing))
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (Permissions.overlay(context)) {
            try {
                context.startActivity(launch)
                return Outcome.Opened
            } catch (_: RuntimeException) {
                // Fall through to the notification the user can tap.
            }
        }
        Notifications.ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(10_000 + (ruleId % 10_000).toInt(), Notifications.launchPrompt(context, ruleId, label, launch))
        return Outcome.NeedsTap
    }
}

fun settingsIntent(action: String, packageName: String): Intent {
    return Intent(action).setData(Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
