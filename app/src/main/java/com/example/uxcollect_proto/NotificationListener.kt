package com.example.uxcollect_proto

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

class NotificationListener : NotificationListenerService() {
    // 지연 알림 저장
    private val delayedNotifications = mutableListOf<StatusBarNotification>()
    private lateinit var notificationManager: NotificationManager
    private val handler = Handler(Looper.getMainLooper())

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("UnspecifiedIntentRegisterReceiverFlag", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 커스텀 처리에서 제외할 패키지 이름 목록
        val excludedNotificationPackages = setOf(
            "com.sec.android.demonapp",
            "com.sec.android.app.shealth",
            "com.sec.android.systemui",
            "viva.republica.toss",
            "com.example.uxcollect_proto"
        )

        // 기능이 활성화되어 있고 패키지가 제외 목록에 없을 때만 로직 실행
        if (isFeatureEnabled() && sbn.packageName !in excludedNotificationPackages) {
            cancelNotification(sbn.key)

            synchronized(delayedNotifications) {
                delayedNotifications.add(sbn)
            }

            // 지연된 알림 재전송 스케줄링
            handler.postDelayed({
                synchronized(delayedNotifications) {
                    val notificationIterator = delayedNotifications.iterator()
                    while (notificationIterator.hasNext()) {
                        val delayedSbn = notificationIterator.next()
                        sendDelayedNotification(delayedSbn)
                        notificationIterator.remove()
                    }
                }
            }, 30000)
        }
    }

    private fun sendDelayedNotification(sbn: StatusBarNotification) {
        val channelID = "delayed_channel_id"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelID, "Delayed Notifications", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val originalNotification = sbn.notification
        val packageName = sbn.packageName
        val smallIcon = sbn.notification.smallIcon
        val smallIconCompat = IconCompat.createFromIcon(this, smallIcon)

        val title = originalNotification.extras.getString(Notification.EXTRA_TITLE) ?: "Notification"
        val text = originalNotification.extras.getString(Notification.EXTRA_TEXT) ?: "No details available"
        val timestamp = DateFormat.getTimeInstance().format(Date(sbn.postTime))
        // 알림을 클릭할 때 액션 정의
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: Intent() // Fallback to an empty intent if no launch intent is found

        val contentIntent = PendingIntent.getActivity(
            this, sbn.id, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 새로운 알림 생성 및 발송
        val newNotification = smallIconCompat?.let {
            NotificationCompat.Builder(this, channelID)
                .setContentTitle("$title - $packageName")
                .setContentText("$text (Delayed: $timestamp)")
                .setSmallIcon(it) // 아이콘 설정
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .addExtras(Bundle().apply { putBoolean("isDelayedNotification", true) })
                .build()
        }
        val uniqueNotificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(uniqueNotificationId, newNotification)
    }

    private fun isFeatureEnabled(): Boolean {
        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean("ServiceEnabled", false)
    }

}