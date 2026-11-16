package com.yerin.albumsorter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 새로 저장되는 이미지를 지켜보다가 "어느 앨범으로?" 알림을 띄우는 서비스 */
public class WatcherService extends Service {
    private static final String TAG = "WatcherService";

    static final String CH_WATCH = "watch";
    static final String CH_NEW = "new_image";
    static final String EXTRA_ID = "image_id";
    static final String EXTRA_AUTO_ALBUM = "auto_album";
    static final String EXTRA_NID = "notif_id";
    static final String ACTION_DISMISS = "com.yerin.albumsorter.DISMISS";

    private static final int FG_ID = 1;
    /** 최근 10분 안에 추가된 이미지도 다시 확인 (저장이 늦게 끝나는 경우 대비) */
    private static final long WINDOW_SEC = 600;

    private HandlerThread thread;
    private Handler handler;
    private ContentObserver observer;
    private final Runnable scanTask = this::scan;

    static int notifId(long imageId) {
        return 1000 + (int) (imageId % 100000);
    }

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel watch = new NotificationChannel(CH_WATCH, "감시 중 표시", NotificationManager.IMPORTANCE_MIN);
        watch.setDescription("앱이 새 이미지를 지켜보고 있다는 표시");
        NotificationChannel nw = new NotificationChannel(CH_NEW, "새 이미지 알림", NotificationManager.IMPORTANCE_HIGH);
        nw.setDescription("이미지가 저장되면 앨범을 고르라는 알림");
        nm.createNotificationChannel(watch);
        nm.createNotificationChannel(nw);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("앨범 정리 켜짐")
                .setContentText("새로 저장되는 이미지를 지켜보고 있어요")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        thread = new HandlerThread("image-watcher");
        thread.start();
        handler = new Handler(thread.getLooper());
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                handler.removeCallbacks(scanTask);
                handler.postDelayed(scanTask, 1500);
            }
        };
        getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        handler.post(scanTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scan() {
        try {
            doScan();
        } catch (Exception e) {
            Log.w(TAG, "scan failed", e);
        }
    }

    private void doScan() {
        ContentResolver cr = getContentResolver();
        long last = Prefs.getLastId(this);
        List<String> seenList = Prefs.getSeen(this);
        Set<String> seen = new HashSet<>(seenList);
        long since = System.currentTimeMillis() / 1000 - WINDOW_SEC;

        String[] proj = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
        };

        // 처음 켰을 때: 기존 사진에는 알림을 보내지 않도록 기준점만 저장
        if (last < 0) {
            long max = 0;
            try (Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                    MediaStore.MediaColumns.DATE_ADDED + ">?", new String[]{String.valueOf(since)}, null)) {
                if (c != null) while (c.moveToNext()) seenList.add(String.valueOf(c.getLong(0)));
            }
            try (Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID}, null, null,
                    MediaStore.MediaColumns._ID + " DESC")) {
                if (c != null && c.moveToFirst()) max = c.getLong(0);
            }
            Prefs.setLastId(this, max);
            Prefs.setSeen(this, seenList);
            return;
        }

        String sel = MediaStore.MediaColumns._ID + ">? OR " + MediaStore.MediaColumns.DATE_ADDED + ">?";
        String[] args = {String.valueOf(last), String.valueOf(since)};
        long max = last;
        boolean exCam = Prefs.excludeCamera(this);
        boolean exShot = Prefs.excludeScreenshots(this);
        List<String> custom = Prefs.getCustomAlbums(this);
        File root = Environment.getExternalStorageDirectory();

        try (Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                MediaStore.MediaColumns._ID + " ASC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                String rel = AlbumRepo.normalize(c.getString(2));
                if (id > max) max = id;
                String key = String.valueOf(id);
                if (seen.contains(key)) continue;
                seen.add(key);
                seenList.add(key);

                if (rel == null) continue;
                String full = new File(root, rel + (name == null ? "" : name)).getAbsolutePath();
                if (MediaMover.ignorePaths.remove(full)) continue;           // 이 앱이 옮긴 파일
                if (exCam && rel.startsWith("DCIM/Camera")) continue;          // 카메라 사진
                if (exShot && rel.toLowerCase(Locale.ROOT).contains("screenshot")) continue; // 스크린샷
                if (custom.contains(rel)) continue;                            // 이미 내 앨범에 저장됨

                showNotification(id, name, rel);
            }
        }
        Prefs.setLastId(this, max);
        Prefs.setSeen(this, seenList);
    }

    private void showNotification(long id, String name, String rel) {
        Uri uri = MediaMover.uriOf(id);
        Bitmap thumb = null;
        try {
            thumb = getContentResolver().loadThumbnail(uri, new Size(512, 512), null);
        } catch (Exception ignored) {
        }

        int nid = notifId(id);
        int rc = (int) (id % 100000) * 4;
        Icon icon = Icon.createWithResource(this, R.drawable.ic_notify);

        Intent pick = new Intent(this, PickerActivity.class)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pPick = PendingIntent.getActivity(this, rc, pick,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CH_NEW)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("새 이미지 · 어느 앨범에 넣을까요?")
                .setContentText(AlbumRepo.displayName(rel) + " 폴더에 저장됨 · " + name)
                .setContentIntent(pPick)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .addAction(new Notification.Action.Builder(icon, "앨범 선택", pPick).build());

        if (thumb != null) {
            b.setLargeIcon(thumb);
            b.setStyle(new Notification.BigPictureStyle()
                    .bigPicture(thumb)
                    .bigLargeIcon((Bitmap) null));
        }

        List<String> recent = new ArrayList<>(Prefs.getRecent(this));
        recent.remove(rel);
        if (!recent.isEmpty()) {
            String album = recent.get(0);
            Intent quick = new Intent(this, PickerActivity.class)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_AUTO_ALBUM, album)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pQuick = PendingIntent.getActivity(this, rc + 1, quick,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(icon,
                    "→ " + AlbumRepo.displayName(album), pQuick).build());
        }

        Intent dismiss = new Intent(this, ActionReceiver.class)
                .setAction(ACTION_DISMISS)
                .putExtra(EXTRA_NID, nid);
        PendingIntent pDismiss = PendingIntent.getBroadcast(this, rc + 2, dismiss,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(new Notification.Action.Builder(icon, "그대로 두기", pDismiss).build());

        try {
            getSystemService(NotificationManager.class).notify(nid, b.build());
        } catch (SecurityException e) {
            Log.w(TAG, "알림 권한 없음", e);
        }
    }
}
