package com.yerin.albumsorter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 휴대폰을 다시 켜거나 앱을 업데이트하면 감시를 자동으로 다시 시작 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Prefs.isEnabled(context)) {
            try {
                context.startForegroundService(new Intent(context, WatcherService.class));
            } catch (Exception ignored) {
            }
        }
    }
}
