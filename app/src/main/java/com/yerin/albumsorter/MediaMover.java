package com.yerin.albumsorter;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 이미지를 다른 앨범(폴더)으로 옮기는 기능 */
final class MediaMover {
    private static final String TAG = "MediaMover";

    enum Result { OK, NEED_CONSENT, NOT_FOUND, FAILED }

    /** 이 앱이 옮기면서 새로 생긴 파일 경로 (다시 알림이 오지 않도록) */
    static final Set<String> ignorePaths = Collections.newSetFromMap(new ConcurrentHashMap<>());

    static final class Info {
        long id;
        boolean video;
        String name;
        String relPath;
    }

    private MediaMover() {}

    /** 사진·동영상을 한꺼번에 보는 MediaStore 목록 */
    static final Uri FILES = MediaStore.Files.getContentUri("external");
    static final String MEDIA_SEL = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
            + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ","
            + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")";

    /** ID → 동영상 여부 */
    private static final Map<Long, Boolean> videoCache = new ConcurrentHashMap<>();

    static void remember(long id, int mediaType) {
        videoCache.put(id, mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
    }

    static boolean isVideo(Context c, long id) {
        Boolean v = videoCache.get(id);
        if (v == null) {
            try (Cursor cur = c.getContentResolver().query(FILES,
                    new String[]{MediaStore.Files.FileColumns.MEDIA_TYPE},
                    MediaStore.MediaColumns._ID + "=?", new String[]{String.valueOf(id)}, null)) {
                if (cur != null && cur.moveToFirst()) {
                    remember(id, cur.getInt(0));
                    v = videoCache.get(id);
                }
            } catch (Exception ignored) {
            }
        }
        return v != null && v;
    }

    /** 사진이면 Images, 동영상이면 Video 주소 */
    static Uri uriOf(Context c, long id) {
        Uri base = isVideo(c, id) ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        return ContentUris.withAppendedId(base, id);
    }

    static Info query(Context c, long id) {
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH};
        try (Cursor cur = c.getContentResolver().query(uriOf(c, id), proj, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                Info i = new Info();
                i.id = id;
                i.video = isVideo(c, id);
                i.name = cur.getString(1);
                i.relPath = AlbumRepo.normalize(cur.getString(2));
                return i;
            }
        } catch (Exception e) {
            Log.w(TAG, "query failed", e);
        }
        return null;
    }

    static Result move(Context c, long id, String album) {
        album = AlbumRepo.normalize(album);
        Info info = query(c, id);
        if (info == null) return Result.NOT_FOUND;
        if (album.equals(info.relPath)) return Result.OK;

        String newName = uniqueName(c, album, info.name);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, album);
        if (!newName.equals(info.name)) v.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);

        try {
            int n = c.getContentResolver().update(uriOf(c, id), v, null, null);
            if (n > 0) return Result.OK;
        } catch (SecurityException e) {
            // 다른 앱이 만든 파일 → 사용자 허락 필요 (모든 파일 접근 권한이 있으면 직접 이동)
            if (Environment.isExternalStorageManager()) return fileMove(c, info, album, newName);
            return Result.NEED_CONSENT;
        } catch (Exception e) {
            Log.w(TAG, "update failed", e);
        }
        if (Environment.isExternalStorageManager()) return fileMove(c, info, album, newName);
        return Result.FAILED;
    }

    private static Result fileMove(Context c, Info info, String album, String newName) {
        if (info.relPath == null || info.name == null) return Result.FAILED;
        File root = Environment.getExternalStorageDirectory();
        File src = new File(root, info.relPath + info.name);
        File dir = new File(root, album);
        if (!dir.exists() && !dir.mkdirs()) return Result.FAILED;
        File dst = new File(dir, newName);
        ignorePaths.add(dst.getAbsolutePath());
        if (!src.renameTo(dst)) {
            ignorePaths.remove(dst.getAbsolutePath());
            return Result.FAILED;
        }
        MediaScannerConnection.scanFile(c,
                new String[]{src.getAbsolutePath(), dst.getAbsolutePath()}, null, null);
        return Result.OK;
    }

    /** 같은 이름이 있으면 "이름 (1).jpg" 처럼 바꿈 */
    static String uniqueName(Context c, String album, String name) {
        if (name == null || name.isEmpty()) name = "image_" + System.currentTimeMillis() + ".jpg";
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        String cand = name;
        int i = 1;
        while (exists(c, album, cand) && i < 1000) {
            cand = base + " (" + i + ")" + ext;
            i++;
        }
        return cand;
    }

    private static boolean exists(Context c, String album, String name) {
        try {
            if (new File(Environment.getExternalStorageDirectory(), album + name).exists()) return true;
        } catch (Exception ignored) {
        }
        ContentResolver cr = c.getContentResolver();
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        try (Cursor cur = cr.query(FILES,
                new String[]{MediaStore.MediaColumns._ID}, sel, new String[]{album, name}, null)) {
            return cur != null && cur.getCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
