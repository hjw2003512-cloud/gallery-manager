package com.yerin.albumsorter;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

/** 갤러리 앨범(= Pictures / DCIM 아래 폴더) 목록 */
final class AlbumRepo {
    private AlbumRepo() {}

    /** 이미지를 넣을 수 있는 폴더인지 (안드로이드는 이미지를 Pictures/ 또는 DCIM/ 아래에만 둘 수 있음) */
    static boolean isValidAlbum(String p) {
        return p != null && (p.startsWith("Pictures/") || p.startsWith("DCIM/"));
    }

    static String normalize(String p) {
        if (p == null) return null;
        return p.endsWith("/") ? p : p + "/";
    }

    /** "Pictures/여행/" → "여행" */
    static String displayName(String path) {
        if (path == null) return "?";
        String s = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** 최근 사용 앨범을 먼저, 나머지는 이름순 */
    static List<String> listAlbums(Context c) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String r : Prefs.getRecent(c)) {
            if (isValidAlbum(r)) result.add(r);
        }

        TreeSet<String> others = new TreeSet<>((a, b) ->
                displayName(a).compareToIgnoreCase(displayName(b)) != 0
                        ? displayName(a).compareToIgnoreCase(displayName(b))
                        : a.compareTo(b));
        for (String a : Prefs.getCustomAlbums(c)) {
            if (isValidAlbum(a)) others.add(a);
        }

        ContentResolver cr = c.getContentResolver();
        String[] proj = {MediaStore.MediaColumns.RELATIVE_PATH};
        try (Cursor cur = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    String p = normalize(cur.getString(0));
                    if (isValidAlbum(p) && !p.equals("Pictures/") && !p.equals("DCIM/")) others.add(p);
                }
            }
        } catch (Exception ignored) {
        }

        result.addAll(others);
        return new ArrayList<>(result);
    }
}
