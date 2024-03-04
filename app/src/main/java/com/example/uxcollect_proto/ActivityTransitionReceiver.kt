package com.example.uxcollect_proto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

// 해당 클래스는 BroadcastReceiver를 상속 받아 활정전환 이벤트를 수신
class ActivityTransitionReceiver : BroadcastReceiver() {
    // 브로드 캐스트 메시지가 수신될 때마다 호출
    override fun onReceive(context: Context?, intent: Intent?) {
        // 인텐트에 활동 전환 결과가 포함되어 있는지
        if (ActivityTransitionResult.hasResult(intent)) {
            // 각 인텐트에서 활동 전환 결과 추출
            val result = intent?.let { ActivityTransitionResult.extractResult(it) }
            // 각 활동 전환 인텐트에 대해 반복 처리
            result?.transitionEvents?.forEach { event ->
                // 활동 타입 문자열 전환
                val activityString = toActivityString(event.activityType)
                // 변환된 활동 타입을 사용하여 활동정보 업데이트
                UsageStatsService.updateLastActivity(activityString)
            }
        }
    }
    // 활동 타입을 문자열로 변환
    private fun toActivityString(activityType: Int): String {
        return when (activityType) {
            DetectedActivity.IN_VEHICLE -> "In Vehicle"
            DetectedActivity.ON_BICYCLE -> "On Bicycle"
            DetectedActivity.ON_FOOT -> "On Foot"
            DetectedActivity.RUNNING -> "Running"
            DetectedActivity.STILL -> "Still"
            DetectedActivity.TILTING -> "Tilting"
            DetectedActivity.WALKING -> "Walking"
            DetectedActivity.UNKNOWN -> "Unknown"
            else -> "Other"
        }
    }
}