package com.yerin.albumsorter;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 알림의 "그대로 두기" 버튼 */
public class ActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (WatcherService.ACTION_DISMISS.equals(intent.getAction())) {
            int nid = intent.getIntExtra(WatcherService.EXTRA_NID, 0);
            context.getSystemService(NotificationManager.class).cancel(nid);
        }
    }
}
