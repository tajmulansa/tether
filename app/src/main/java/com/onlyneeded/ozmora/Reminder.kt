package com.onlyneeded.ozmora

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Gentle "come back and play" reminders, scheduled on the phone (no server, no data leaves the device).
 * When the player leaves the game, six reminders are lined up: the first one 24 hours later, then one
 * every 12 hours (two a day), for three days. Opening the game cancels them. Nothing is sent at night.
 */
object Reminder {
    private const val CH = "reminders"
    private const val PREFS = "reminders"
    private const val COUNT = 6
    private const val FIRST_MS = 24L * 60 * 60 * 1000
    private const val GAP_MS = 12L * 60 * 60 * 1000

    private val LINES = arrayOf(
        "Your orb is still out there.",
        "One more orbit?",
        "The next anchor is waiting.",
        "A quiet minute. A clean release.",
        "The zones are dim without you.",
        "Whenever you are ready, the orbit is here.",
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply(); if (!on) cancel(c) }
    fun asked(c: Context) = prefs(c).getBoolean("asked", false)
    fun setAsked(c: Context) = prefs(c).edit().putBoolean("asked", true).apply()

    fun hasPermission(c: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun active(c: Context) = enabled(c) && hasPermission(c)

    private fun pending(c: Context, i: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, 7000 + i,
            Intent(c, ReminderReceiver::class.java).putExtra("i", i),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Not between 22:00 and 09:00: move to 09:00. */
    private fun daytime(t: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        if (h >= 22) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (h >= 22 || h < 9) { cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0) }
        return cal.timeInMillis
    }

    fun schedule(c: Context, from: Long = System.currentTimeMillis()) {
        cancel(c)
        if (!active(c)) return
        prefs(c).edit().putLong("left", from).apply()
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until COUNT) {
            val t = daytime(from + FIRST_MS + i * GAP_MS)
            if (t <= System.currentTimeMillis()) continue
            am.setAndAllowWhileIdle(AlarmManager.RTC, t, pending(c, i))
        }
    }

    fun cancel(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until COUNT) am.cancel(pending(c, i))
    }

    fun lastLeft(c: Context) = prefs(c).getLong("left", 0L)

    fun show(c: Context, i: Int) {
        if (!active(c)) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A gentle reminder to play"
            })
        }
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF8FA2FF.toInt())
            .setContentTitle("Ozmora")
            .setContentText(LINES[i % LINES.size])
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        try { NotificationManagerCompat.from(c).notify(7100, n) } catch (_: SecurityException) {}
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Reminder.show(c, i.getIntExtra("i", 0)) }
}

/** Alarms are forgotten when the phone restarts: line them up again from when the player last left. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            val left = Reminder.lastLeft(c)
            if (left > 0) Reminder.schedule(c, left)
        }
    }
}
