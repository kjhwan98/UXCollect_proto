package com.example.uxcollect_proto

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity


class MainActivity : AppCompatActivity() {
    // 앱에서 사용하는 각종 권환 및 서비스에 대한 요청 코드와 알림 채널 ID 정의
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 100
        private const val ACTIVITY_RECOGNITION_PERMISSION_REQUEST_CODE = 101
        private const val notificationChannelId = "UsageStatsServiceChannel"

    }
    // 데이터 전송 상태를 받는 BroadcastReceiver를 정의
    private val transferDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isSuccess = intent.getBooleanExtra("TransferStatus", false)
            if (isSuccess) {
                Toast.makeText(context, "Data Transfer Successful", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Data Transfer Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 서비스 중지 상태를 받는 BroadcastReceiver를 정의
    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if ("com.example.app.SERVICE_STOPPED" == intent.action) {
                updateSyncStatus(false)
            }
        }
    }
    // 토글 버튼 정의(서비스 시작/중지)
    private lateinit var btnToggleFeature: Button


    @SuppressLint("UnspecifiedRegisterReceiverFlag", "InlinedApi")
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 위치, 활동 인식, 알림, 배터리 최적화 권한을 요청하는 메소드 호출
        requestLocationPermissions()
        requestActivityRecognitionPermission()
        requestNotificationPermission()
        requestBatteryOptimizationPermission()
        checkNotificationListenerPermission()
        startActivityRecognition() // 활동인식 시작

        // 데이터 전송 버튼을 설정하고 클릭 이벤트 처리
        val sendDataButton: Button = findViewById(R.id.btnSendDataNow)
        sendDataButton.setOnClickListener {
            sendDataNow(it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, UsageStatsService::class.java))
        } else {
            startService(Intent(this, UsageStatsService::class.java))
        }

        if (areLocationServicesEnabled() && hasUsageStatsPermission()) {
            startService(Intent(this, UsageStatsService::class.java))
        }

        if (!hasUsageStatsPermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } else {
            startService(Intent(this, UsageStatsService::class.java))
        }

        val transferDataFilter = IntentFilter("com.example.app.TRANSFER_DATA")
        val serviceStoppedFilter = IntentFilter("com.example.app.SERVICE_STOPPED")

        // Use RECEIVER_NOT_EXPORTED flag for Android 12 (API level 31) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerReceiver(transferDataReceiver, transferDataFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(serviceStoppedReceiver, serviceStoppedFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transferDataReceiver, transferDataFilter)
            registerReceiver(serviceStoppedReceiver, serviceStoppedFilter)
        }

        // 기능 토글 버튼의 클릭 이벤트 처리
        btnToggleFeature = findViewById(R.id.toggleFeatureButton)
        btnToggleFeature.setOnClickListener {
            toggleFeature()
        }

        startUsageStatsService()
    }

    // 활동 인식을 시작하는 메소드, 필요한 권한이 있는 경우에만 활동인식 시작
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startActivityRecognition() {
        // 권한 체크와 요청 로직
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            // 활동 전환을 감지하기 위한 PendingIntent 생성
            val intent = Intent(this, ActivityTransitionReceiver::class.java)
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, pendingIntentFlags)

            // 감지할 활동 유형과 전환 유형을 저장
            val transitions = listOf(
                DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE, DetectedActivity.ON_FOOT,
                DetectedActivity.RUNNING, DetectedActivity.STILL, DetectedActivity.TILTING,
                DetectedActivity.WALKING, DetectedActivity.UNKNOWN
            ).flatMap { activityType ->
                listOf(
                    ActivityTransition.Builder()
                        .setActivityType(activityType)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                        .build(),
                    ActivityTransition.Builder()
                        .setActivityType(activityType)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                        .build()
                )
            }
            // 활동 전환 업데이트 요청
            val request = ActivityTransitionRequest(transitions)
            ActivityRecognition.getClient(this).requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener {
                    Toast.makeText(this, "Activity recognition updates successfully requested", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to request activity recognition updates: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            // 필요한 권항이 없으면 사용자에게 권한 요청
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), ACTIVITY_RECOGNITION_PERMISSION_REQUEST_CODE)
        }
    }

    // 서비스 활성화 상태를 확인하고 설정하는 메소드
    private fun isServiceEnabled(): Boolean {
        // 서비스 활성화 여부를 SharedPreferences에서 가져옴
        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean("ServiceEnabled", false)
    }

    private fun setServiceEnabled(enabled: Boolean) {
        // 서비스 활성화 상태를 SharedPreferences에 저장
        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putBoolean("ServiceEnabled", enabled)
            apply()
        }
    }

    // 기능 토글 버튼의 상태를 업데이트
    private fun toggleFeature() {
        // 서비스 활성화/비활성화 상태를 토글하고 UI 업데이트
        val isServiceCurrentlyEnabled = isServiceEnabled()
        setServiceEnabled(!isServiceCurrentlyEnabled)
        updateToggleButton(btnToggleFeature, !isServiceCurrentlyEnabled)

        // 서비스 상태 변경 알림
        if (!isServiceCurrentlyEnabled) {
            Toast.makeText(this, "Feature Enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Feature Disabled", Toast.LENGTH_SHORT).show()
        }

    }

    // 버튼 상태 업데이트
    private fun updateToggleButton(button: Button, isServiceRunning: Boolean) {
        if (isServiceRunning) {
            button.text = "Turn On"
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.colorOn)) // Use your color for ON state
        } else {
            button.text = "Turn Off"
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.colorOff)) // Use your color for OFF state
        }
    }

    private fun checkNotificationListenerPermission() {
        // 알림 접근 권한이 부여되었는지 확인, 부여되지 않았다면 사용자에게 설정 변경을 요청
        if (!permissionGranted()) {
            AlertDialog.Builder(this)
                .setTitle("Notification Access Required")
                .setMessage("This app requires access to notifications to function properly.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun permissionGranted(): Boolean {
        // 알림 접근 권한이 부여되었는지 확인
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationPermission() {
        // 배터리 최적화 무시 권한 요청
        val packageName = packageName
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent()
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun updateSyncStatus(isSyncing: Boolean) {
        // 동기화 상태에 따라 UI 업데이트
        val progressBar = findViewById<ProgressBar>(R.id.collectionProgress)
        val textViewProgress = findViewById<TextView>(R.id.textViewProgress)

        if (isSyncing) {
            progressBar.indeterminateDrawable = ContextCompat.getDrawable(this, R.drawable.circle_progress_green)
            textViewProgress.text = "데이터 정상 수집중"
        } else {
            progressBar.indeterminateDrawable = ContextCompat.getDrawable(this, R.drawable.circle_progress_red)
            textViewProgress.text = "동기화 문제 발생"
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestNotificationPermission() {
        // 알림 허용 여부 확인
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("알림 허용 필요")
                .setMessage("앱의 기능을 완전히 사용하기 위해서는 알림 허용이 필요합니다.")
                .setPositiveButton("설정으로 이동") { dialog, which ->
                    // 사용자가 설정으로 이동하길 원할 경우, 앱의 알림 설정 화면으로 이동
                    val intent = Intent().apply {
                        action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            putExtra(Settings.EXTRA_CHANNEL_ID, applicationInfo.uid)
                        }
                    }
                    startActivity(intent)
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    // 앱이 다시 활성화될 때 호출
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onResume() {
        super.onResume()
        // 서비스 활성화 상태를 확인하고 UI 업데이트
        val isServiceEnabled = isServiceEnabled()
        updateToggleButton(btnToggleFeature, isServiceEnabled)
        // 사용 통계 권한이 있는지 확인, 해당 서비스 시작
        if (hasUsageStatsPermission()) {
            startService(Intent(this, UsageStatsService::class.java))
            // 알림 채널 활성화 상태를 확인하고 동기화 상태 업데이트
            updateSyncStatus(isNotificationChannelEnabled())
        } else {
            updateSyncStatus(false)
        }
        startUsageStatsService()
    }

    private fun startUsageStatsService() {
        val serviceIntent = Intent(this, UsageStatsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun isNotificationChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = notificationManager.getNotificationChannel(notificationChannelId)
            if (channel != null) {
                return channel.importance != NotificationManager.IMPORTANCE_NONE
            }
            // 채널이 존재하지 않으면 알림이 비활성화된 것으로 간주
            return false
        } else {
            // Oreo 미만 버전에서는 항상 true 반환
            return true
        }
    }

    // 위치 권한 요청 베소드
    private fun requestLocationPermissions() {
        // 세밀한 위치권한과 대략적인 위치 권한이 있느지 확인
        val hasFineLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val shouldRequestBackgroundLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        // 백그라운드 위치 권한을 별도로 요청해야 하는지 확인
        val hasBackgroundLocationPermission = if (shouldRequestBackgroundLocation) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android Q 미만 버전에서는 항상 true 처리
        }

        val permissionsToRequest = mutableListOf<String>()
        if (!hasFineLocationPermission) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!hasCoarseLocationPermission) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (shouldRequestBackgroundLocation && !hasBackgroundLocationPermission) {
            // 백그라운드 위치 권한 요청은 Android Q 이상에서만 수행
            permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), LOCATION_PERMISSION_REQUEST_CODE)
        }
    }

    // 위치 서비스 활성화 여부 확인 메소드
    private fun areLocationServicesEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // 활동 인식 권한 요청 메소드
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestActivityRecognitionPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), ACTIVITY_RECOGNITION_PERMISSION_REQUEST_CODE)
        }
    }

    // 권한 요청 결과 처리 메소드
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            ACTIVITY_RECOGNITION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 권한이 부여되면 활동인식 시작
                    startActivityRecognition()
                } else {
                    // 권한이 거부되면 사용자에게 필요성 설명
                    Toast.makeText(this, "Activity recognition permission is necessary for this feature to work", Toast.LENGTH_SHORT).show()
                }
            }
            // 위치 권한 요청 결과 처리
            LOCATION_PERMISSION_REQUEST_CODE -> {
                // 정밀위치, 대략적인 위치 및 백그라운드 위치 권한이 모두 부여되었는지 확인
                val fineLocationGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                val coarseLocationGranted = grantResults.size > 1 && grantResults[1] == PackageManager.PERMISSION_GRANTED
                val backgroundLocationPermissionIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 2 else -1
                val backgroundLocationGranted = if (backgroundLocationPermissionIndex != -1) {
                    grantResults.size > backgroundLocationPermissionIndex && grantResults[backgroundLocationPermissionIndex] == PackageManager.PERMISSION_GRANTED
                } else {
                    true // 안드로이드 Q미만에서는 항상 true
                }
                if (!(fineLocationGranted && coarseLocationGranted && backgroundLocationGranted)) {
                    // 필요한 위치 권한이 부여되지 않았다면 사용자에게 알림
                    Toast.makeText(this, "Location permission is necessary for this app's functionality", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 사용 통계 권한이 있는지 확인
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        appOps?.let {
            // API 레벨 29 이상에서는 unsafeCheckOpNoThrow를 사용
            val mode = it.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }
        return false
    }

    // 데이터를 즉시 전송하는 기능
    private fun sendDataNow(view: View) {
        // UsageStatsService 서비스에 'ACTION_SEND_DATA_NOW' 액션을 포함하는 인텐트 전송
        // 데이터 전송을 즉시 시작하도록 요청
        Intent(this, UsageStatsService::class.java).also { intent ->
            intent.action = "com.example.app.ACTION_SEND_DATA_NOW"
            startService(intent)
        }
    }

    // 액티비티가 파괴될 때 BroadcastReceiver를 등록 해제
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(transferDataReceiver)
        unregisterReceiver(serviceStoppedReceiver)
    }
}
