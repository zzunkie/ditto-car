package dev.zzunkie.whenever.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 차가 켜지면(부팅) 화면을 열지 않아도 자동화 감시를 시작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) RuleService.start(context)
    }
}
