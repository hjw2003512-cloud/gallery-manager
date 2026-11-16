package com.yerin.albumsorter;

import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 갤러리 앨범 목록.
 * 삼성 갤러리에서 "앨범 만들기"로 만든 앨범은 실제로 DCIM/앨범이름 폴더예요.
 * 그래서 기본 목록은 DCIM 아래 폴더(카메라·스크린샷 제외) + 이 앱에서 만든 앨범입니다.
 */
final class AlbumRepo {
    private AlbumRepo() {}

    static final class Album {
        String path;        // 예: "DCIM/여행/"
        String name;        // 예: "여행"
        int count;
        long coverId = -1;  // 가장 최근 사진 ID
    }

    /** 보안 폴더로 보내기 전에 잠시 모아두는 앨범 */
    static final String SECURE_PENDING_NAME = "보안폴더 대기";
    static final String SECURE_PENDING = "DCIM/" + SECURE_PENDING_NAME + "/";

    /** 갤러리가 자동으로 만드는 폴더 (내 앨범이 아님) */
    private static final Set<String> SYSTEM_DIRS = new HashSet<>(Arrays.asList(
            "camera", "screenshots", "screen recordings", "screenrecorder", "screenrecords",
            "100andro", "restored", "videocaptures", "video captures"));

    static boolean isValidAlbum(String p) {
        return p != null && (p.startsWith("Pictures/") || p.startsWith("DCIM/"))
                && !p.equals("Pictures/") && !p.equals("DCIM/");
    }

    static String normalize(String p) {
        if (p == null) return null;
        return p.endsWith("/") ? p : p + "/";
    }

    /** "DCIM/여행/" → "여행" */
    static String displayName(String path) {
        if (path == null) return "?";
        String s = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = s.lastIndexOf('/');
        String n = i >= 0 ? s.substring(i + 1) : s;
        switch (n.toLowerCase(Locale.ROOT)) {
            case "camera": return "카메라";
            case "screenshots": return "스크린샷";
            case "download": return "다운로드";
            default: return n;
        }
    }

    /** 갤러리에서 사용자가 만든 앨범인지 */
    static boolean isUserAlbum(String path, List<String> custom) {
        if (path == null) return false;
        if (custom.contains(path)) return true;
        if (!path.startsWith("DCIM/") || path.equals("DCIM/")) return false;
        String[] parts = path.substring(5).split("/");
        if (parts.length == 0 || parts[0].isEmpty()) return false;
        for (String s : parts) if (s.startsWith(".")) return false;
        return !SYSTEM_DIRS.contains(parts[0].toLowerCase(Locale.ROOT));
    }

    /**
     * @param includeOthers true면 Pictures 아래 다른 앱 폴더(인스타그램, 카카오톡 등)도 포함
     */
    static List<Album> load(Context c, boolean includeOthers) {
        Map<String, Album> map = new LinkedHashMap<>();
        List<String> custom = Prefs.getCustomAlbums(c);

        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH};
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    String p = normalize(cur.getString(1));
                    if (!isValidAlbum(p)) continue;
                    Album a = map.get(p);
                    if (a == null) {
                        a = newAlbum(p);
                        map.put(p, a);
                    }
                    a.count++;
                    if (a.coverId < 0) a.coverId = cur.getLong(0);
                }
            }
        } catch (Exception ignored) {
        }

        // 사진이 아직 없는 빈 앨범(폴더)도 찾기: DCIM 아래 2단계까지
        try {
            File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
            File[] lv1 = dcim.listFiles();
            if (lv1 != null) {
                for (File d : lv1) {
                    if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                    String p = "DCIM/" + d.getName() + "/";
                    if (!map.containsKey(p)) map.put(p, newAlbum(p));
                    File[] lv2 = d.listFiles();
                    if (lv2 == null) continue;
                    for (File d2 : lv2) {
                        if (!d2.isDirectory() || d2.getName().startsWith(".")) continue;
                        String p2 = p + d2.getName() + "/";
                        if (!map.containsKey(p2)) map.put(p2, newAlbum(p2));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        for (String p : custom) {
            if (isValidAlbum(p) && !map.containsKey(p)) map.put(p, newAlbum(p));
        }

        List<Album> out = new ArrayList<>();
        for (Album a : map.values()) {
            if (a.path.equals(SECURE_PENDING)) continue;
            if (includeOthers || isUserAlbum(a.path, custom)) out.add(a);
        }

        List<String> recent = Prefs.getRecent(c);
        out.sort((x, y) -> {
            int rx = recent.indexOf(x.path), ry = recent.indexOf(y.path);
            if (rx < 0) rx = Integer.MAX_VALUE;
            if (ry < 0) ry = Integer.MAX_VALUE;
            if (rx != ry) return Integer.compare(rx, ry);
            boolean ux = isUserAlbum(x.path, custom), uy = isUserAlbum(y.path, custom);
            if (ux != uy) return ux ? -1 : 1;
            return x.name.compareToIgnoreCase(y.name);
        });
        return out;
    }

    /** 갤러리와 같은 방식으로 DCIM 아래에 새 앨범 만들기. 만든 경로를 돌려줌 */
    static String createAlbum(Context c, String name) {
        String path = "DCIM/" + name + "/";
        try {
            new File(Environment.getExternalStorageDirectory(), path).mkdirs();
        } catch (Exception ignored) {
        }
        Prefs.addCustomAlbum(c, path);
        return path;
    }

    private static Album newAlbum(String p) {
        Album a = new Album();
        a.path = p;
        a.name = displayName(p);
        return a;
    }
}
