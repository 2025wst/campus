package com.zzu.checkin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后重新安排定时（AlarmManager 的闹钟在重启后会丢）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            CheckinAlarm.ensureScheduled(context);
            CheckinStatus.appendLog("开机完成，已重新安排定时");
        } catch (Throwable ignored) {
        }
    }
}
