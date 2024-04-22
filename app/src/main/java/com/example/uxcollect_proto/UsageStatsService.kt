package com.example.uxcollect_proto

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.Manifest
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat

class UsageStatsService : Service() {
    // 변수 선언
    private var updateRunnable: Runnable? = null
    private var collectionStartTime: Long = 0
    private var currentLux: Float = 0f  // Current illuminance
    private var currentProximity: Float = 0f
    private var isCharging: Boolean = false
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var proximitySensor: Sensor? = null
    private lateinit var realtimeDatabase: FirebaseDatabase
    private lateinit var deviceId: String
    private var lastKnownLocation: Location? = null
    private lateinit var locationManager: LocationManager
    private val locationListener: LocationListener = LocationListener { location ->
        lastKnownLocation = location
    }
    private val handler = Handler(Looper.getMainLooper())
    private var isCollectingStats: Boolean = true
    private val notificationChannelId = "UsageStatsServiceChannel"
    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private val screenEventList = mutableListOf<Map<String, Any>>()
    private lateinit var audioManager: AudioManager
    private val dataTransferReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            collectAndSendUsageStats()
        }
    }
    // 서비스 시작 명령 처리, 즉시 데이터 전송 인텐트 처리
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            "com.example.app.ACTION_SEND_DATA_NOW" -> collectAndSendUsageStats()
            // 다른 인텐트 액션 처리
        }
        return START_STICKY // 서비스가 강제 종료된 경우 시스템에 의해 다시 생성
    }

    // 배터리 충전 상태 변경 감지를 위한 BroadcastReceiver
    private val chargingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            isCharging = when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> isCharging
            }
            // 충전 상태 변경 이벤트 발송
            val chargingIntent = Intent("com.example.app.CHARGING_STATE_CHANGED")
            chargingIntent.putExtra("isCharging", isCharging)
            sendBroadcast(chargingIntent)
        }
    }

    // 시스템 설정에서 화면 밝기를 가져오는 함수
    private fun getCurrentScreenBrightness(): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            e.printStackTrace()
            -1 // 밝기 값을 가져 올 수 없는경우 -1 반환
        }
    }

    // 화면 켜짐/꺼짐 이벤트를 감지하기 위한 BroadcastReceiver
    private val screenOnOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 화면 상태 변경에 따른 데이터 처리
            val eventTime = System.currentTimeMillis()
            val formattedTime =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(eventTime))
            val eventType = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> "Screen Off"
                Intent.ACTION_SCREEN_ON -> "Screen On"
                else -> return
            }
            // 화면 이벤트를 파이어베이스에 전송
            val screenEvent = mapOf(
                "deviceId" to deviceId,
                "eventType" to eventType,
                "timestamp" to formattedTime
            )

            // Send each event directly to Firebase instead of storing it in the list
            realtimeDatabase.reference.child("screen_events").push().setValue(screenEvent)
                .addOnSuccessListener {
                    Log.d("UsageStatsService", "Screen event sent successfully.")
                }
                .addOnFailureListener { exception ->
                    Log.e("UsageStatsService", "Error sending screen event: ${exception.message}")
                }
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                //화면이 켜질 때 특정 동작 수행 가능
                val screenOnIntent = Intent("com.example.app.SCREEN_ON")
                context?.sendBroadcast(screenOnIntent)
            }
        }
    }


    override fun onBind(intent: Intent): IBinder? {
        return null // 바인드 서비스가 아니므로 널 반환
    }
    // 서비스 상태를 반환하는 함수
    private fun broadcastServiceStatus(isRunning: Boolean) {
        val intent = Intent("com.example.app.SERVICE_STATUS")
        intent.putExtra("isServiceRunning", isRunning)
        sendBroadcast(intent)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        // 서비스 생성시 초기화 작업 수행
        broadcastServiceStatus(true) // 서비스가 실행중임을 알림
        collectionStartTime = System.currentTimeMillis()
        deviceId = generateUniqueDeviceId() // 고유 디바이스 ID생성
        realtimeDatabase = FirebaseDatabase.getInstance() // 파이어베이스 인스턴스 초기화
        createNotificationChannel() // 알림 채널 생성
        startForegroundService() // 포그라우늗 서비스 시작
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        getLastKnownLocation()
        collectUsageStatsPeriodically()
        registerScreenOnOffReceiver()
        activityRecognitionClient = ActivityRecognition.getClient(this)

        // Check permissions for location updates
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestLocationUpdates() // 위치 정보 업데이트 요청
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        // 센서 리스너 등록
        sensorManager.registerListener(sensorEventListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(sensorEventListener, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        activityRecognitionClient = ActivityRecognition.getClient(this)
        // 사용 통계 주기적 업데이트 스케줄링
        scheduleUsageStatsUpdates()
        val dataTransferFilter = IntentFilter("com.example.app.TRANSFER_DATA")
        val chargingFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val notificationPolicyFilter = IntentFilter(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
        // RECEIVER_NOT_EXPORTED 플래그를 사용하기 위한 API 수준의 조건부 확인
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0 // API 33 미만인 경우 플래그 매개변수의 기본값 0
        }
        // BroadcastReceiver 등록
        registerReceiver(dataTransferReceiver, dataTransferFilter, receiverFlags)
        registerReceiver(chargingStateReceiver, chargingFilter, receiverFlags)
        registerReceiver(notificationPolicyReceiver, notificationPolicyFilter, receiverFlags)
    }

    // 수집 진행 상황을 발송하는 함수
    private fun sendCollectionProgress() {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - collectionStartTime // 경과 시간 계산
        val progressIntent = Intent("com.example.app.COLLECTION_PROGRESS")
        progressIntent.putExtra("elapsedTime", elapsedTime) // 경과 시간 데이터를 인텐트에 추가
        sendBroadcast(progressIntent)
    }

    // 마지막으로 알려진 위치 정보를 가져오는 함수
    private fun getLastKnownLocation() {
        // 위치 권한 확인
        val fineLocationPermission = Manifest.permission.ACCESS_FINE_LOCATION
        val coarseLocationPermission = Manifest.permission.ACCESS_COARSE_LOCATION

        if (ContextCompat.checkSelfPermission(
                this,
                fineLocationPermission
            ) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(
                this,
                coarseLocationPermission
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            val lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastLocation != null) {
                lastKnownLocation = lastLocation // 가져온 위치 정보 저장
                // 위치 정보를 이용한 추가 동작을 여기서 수행
            }
        }
    }

    // 위치 업데이트 요청
    private fun requestLocationUpdates() {
        // 위치 권한 확인
        val fineLocationPermission = Manifest.permission.ACCESS_FINE_LOCATION
        val coarseLocationPermission = Manifest.permission.ACCESS_COARSE_LOCATION

        if (ContextCompat.checkSelfPermission(
                this,
                fineLocationPermission
            ) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(
                this,
                coarseLocationPermission
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L, // 위치 정보 업데이트: 간격 5초
                10f,   // 위치 변경 허용거리: 10m
                locationListener // 위치 변경시 호출 될 리스너
            )
        }
    }

    // 알림 채널 생성
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                notificationChannelId,
                "Usage Stats Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel) // 알림 채널 등록
        }
    }

    // 알림 정책 변경을 감지
    private val notificationPolicyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED) {
                // 알림 접근 권한이 변경되었을 때 서비스 재시작
                restartForegroundService()
            }
        }
    }

    // 포그라운드 서비스 재시작
    private fun restartForegroundService() {
        stopSelf() // 현재 서비스 중지
        val serviceIntent = Intent(this, UsageStatsService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    // 포그라운드 서비스를 시작하는 함수
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)

        val pendingIntentFlags =
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification: Notification = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("Usage Stats Service")
            .setContentText("Collecting usage stats")
            .setSmallIcon(R.drawable.khu) // 알림 아이콘 생성
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    // 고유 디바이스 ID 생성
    private fun generateUniqueDeviceId(): String {
        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        var id = sharedPreferences.getString("deviceId", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            sharedPreferences.edit().putString("deviceId", id).apply()
        }
        return id
    }

    // 사용 통계를 주기적으로 수집
    private fun collectUsageStatsPeriodically() {
        isCollectingStats = true // 사용 통계 수집 상태 활성화
        scheduleUsageStatsUpdates() // 사용 통계 업데이트 스케줄링
    }

    // 알림이 활성화되어 있는지 확인
    private fun areNotificationsEnabled(): Boolean {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(notificationChannelId)
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled() // 알림 활성화 상태 반환
    }

    // 사용 통계 업데이트 스케줄링
    private fun scheduleUsageStatsUpdates() {
        updateRunnable = Runnable {
            if (isCollectingStats) {
                collectAndSendUsageStats() // 사용 통계 수집 및 전송
                sendCollectionProgress() // 수집 진행 상황을 알림

                if (!areNotificationsEnabled()) {
                    restartForegroundService() // 알림이 비활성화 되어 있으면 포그라운드 서비스 재시작
                }
            }
            handler.postDelayed(updateRunnable as Runnable, 1000 * 60 * 30) // 30분 마다 반복
        }
        handler.postDelayed(updateRunnable as Runnable, 1000 * 60 * 30) // 초기 실행 예약
    }

    // 마지막 데이터 전송 타임스탬프를 가져옴
    private fun getLastSentTimestamp(): Long {
        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return sharedPreferences.getLong("lastSentTimestamp", 0)
    }
    // 마지막 데이터 전송 타임스탬프를 업데이트
    private fun updateLastSentTimestamp(timestamp: Long) {
        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putLong("lastSentTimestamp", timestamp)
            apply()
        }
    }

    private fun getRingerMode(): String {
        return when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "Normal"
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Unknown"
        }
    }

    // 센서 이벤트 리스너
    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LIGHT -> {
                    currentLux = event.values[0]  // 조도 값 업데이트
                }

                Sensor.TYPE_PROXIMITY -> {
                    currentProximity = event.values[0]  // 근접 값 업데이트
                    updateProximityValueInPreferences(currentProximity)  // 공유 환경설정에 저장
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // 센서 정확도 변경시 필요한 경우 처리
        }
    }

    // 근접성 값 공유 환경 설정에 업데이트하는 함수
    private fun updateProximityValueInPreferences(proximityValue: Float) {
        val sharedPreferences = getSharedPreferences("SensorPrefs", Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putFloat("ProximityValue", proximityValue) // 근접성 값 저장
            apply()
        }
    }

    //// 화면 켜짐/꺼짐 상태를 감지하기 위한 BroadcastReceiver 등록
    private fun registerScreenOnOffReceiver() {
        val screenIntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF) // 화면 꺼짐 액션 추가
            addAction(Intent.ACTION_SCREEN_ON) // 화면 켜짐 액션 추가
        }
        registerReceiver(screenOnOffReceiver, screenIntentFilter)
    }

    // 사용 통계 및 환경 데이터 수집 및 전송
    private fun collectAndSendUsageStats() {
        // 사용 패턴과 환경데이터를 수집하여 파이어베이스에 전송
        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val anonymizeData = sharedPreferences.getBoolean("anonymizeData", false)
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val lastSentTimestamp = getLastSentTimestamp()
        val ringerMode = getRingerMode()
        val startTime =
            if (lastSentTimestamp == 0L) endTime - 1000 * 60 * 60 * 24 else lastSentTimestamp

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val eventTypeMap = mapOf(
            1 to "ACTIVITY_RESUMED",
            11 to "STANDBY_BUCKET_CHANGED",
            21 to "CONTINUING_FOREGROUND_SERVICE",
            2 to "ACTIVITY_PAUSED",
            12 to "NOTIFICATION_INTERRUPTION",
            22 to "ROLLOVER_FOREGROUND_SERVICE",
            3 to "END_OF_DAY",
            13 to "SLICE_PINNED_PRIV",
            23 to "ACTIVITY_STOPPED",
            4 to "CONTINUE_PREVIOUS_DAY",
            14 to "SLICE_PINNED",
            24 to "ACTIVITY_DESTROYED",
            5 to "CONFIGURATION_CHANGE",
            15 to "SCREEN_INTERACTIVE",
            25 to "FLUSH_TO_DISK",
            6 to "SYSTEM_INTERACTION",
            16 to "SCREEN_NON_INTERACTIVE",
            26 to "DEVICE_SHUTDOWN",
            7 to "USER_INTERACTION",
            17 to "KEYGUARD_SHOWN",
            27 to "DEVICE_STARTUP",
            8 to "SHORTCUT_INVOCATION",
            18 to "KEYGUARD_HIDDEN",
            28 to "USER_UNLOCKED",
            9 to "CHOOSER_ACTION",
            19 to "FOREGROUND_SERVICE_START",
            29 to "LOCUS_ID_SET",
            10 to "NOTIFICATION_SEEN",
            20 to "FOREGROUND_SERVICE_STOP",
            30 to "",
            31 to "APP_COMPONENT_USED",

            )
        var lastEventTime = 0L

        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)
            if (event.timeStamp > lastSentTimestamp) {
                val formattedTime = formatter.format(Date(event.timeStamp))
                lastEventTime = event.timeStamp
                val packageName = if (anonymizeData) "Anonymous" else event.packageName
                val className = if (anonymizeData) "Anonymous" else event.className
                val currentBrightness = getCurrentScreenBrightness()
                val eventTypeString = eventTypeMap[event.eventType] ?: "Unknown"
                val eventDetails = mutableMapOf<String, Any>(
                    "deviceId" to deviceId,
                    "packageName" to packageName,
                    "timestamp" to formattedTime,
                    "eventType" to eventTypeString,
                    "className" to className,
                    "ringerMode" to ringerMode,
                    "Illuminance" to currentLux,  // Adding illuminance sensor data
                    "Proximity" to currentProximity,
                    "isCharging" to isCharging,
                    "screenBrightness" to currentBrightness
                )
                // lastKnownLocation가 null이 아닌 경우에만 GPS 정보 추가
                lastKnownLocation?.let {
                    eventDetails["latitude"] = it.latitude.toString()
                    eventDetails["longitude"] = it.longitude.toString()
                }
                realtimeDatabase.reference.child("usage_events").push().setValue(eventDetails)
                    .addOnSuccessListener {
                        Log.d("UsageStatsService", "Usage event sent successfully.")
                    }
                    .addOnFailureListener { exception ->
                        Log.e(
                            "UsageStatsService",
                            "Error sending usage event: ${exception.message}"
                        )
                    }
            }
        }

        if (lastEventTime > 0) {
            updateLastSentTimestamp(lastEventTime)
        }

        synchronized(screenEventList) {
            screenEventList.forEach { screenEvent ->
                realtimeDatabase.reference.child("screen_events").push().setValue(screenEvent)
                    .addOnSuccessListener {
                        Log.d("UsageStatsService", "Screen event sent successfully.")
                    }
                    .addOnFailureListener { exception ->
                        Log.e(
                            "UsageStatsService",
                            "Error sending screen event: ${exception.message}"
                        )
                    }
            }
            screenEventList.clear() // Clear the list after sending the events
        }
    }

    override fun onDestroy() {
        // 서비스 종료시 지원 해제 및 정리 작업
        super.onDestroy()
        broadcastServiceStatus(false)
        unregisterReceiver(dataTransferReceiver)
        unregisterReceiver(chargingStateReceiver)
        unregisterReceiver(screenOnOffReceiver)
        unregisterReceiver(notificationPolicyReceiver)
        sensorManager.unregisterListener(sensorEventListener)
        locationManager.removeUpdates(locationListener)
        if (isCollectingStats) {
            handler.removeCallbacksAndMessages(null)
            isCollectingStats = false
        }
        if (updateRunnable != null) {
            handler.removeCallbacks(updateRunnable as Runnable)
        }
    }
}
