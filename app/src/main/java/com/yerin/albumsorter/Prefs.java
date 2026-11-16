package com.yerin.albumsorter;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 앱 설정 저장소 */
final class Prefs {
    private static final int MAX_RECENT = 8;
    private static final int MAX_SEEN = 300;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) { return sp(c).getBoolean("enabled", false); }
    static void setEnabled(Context c, boolean v) { sp(c).edit().putBoolean("enabled", v).apply(); }

    static long getLastId(Context c) { return sp(c).getLong("last_id", -1); }
    static void setLastId(Context c, long v) { sp(c).edit().putLong("last_id", v).apply(); }

    static boolean excludeCamera(Context c) { return sp(c).getBoolean("ex_camera", true); }
    static void setExcludeCamera(Context c, boolean v) { sp(c).edit().putBoolean("ex_camera", v).apply(); }

    static boolean excludeScreenshots(Context c) { return sp(c).getBoolean("ex_shot", true); }
    static void setExcludeScreenshots(Context c, boolean v) { sp(c).edit().putBoolean("ex_shot", v).apply(); }

    static boolean showOthers(Context c) { return sp(c).getBoolean("show_others", false); }
    static void setShowOthers(Context c, boolean v) { sp(c).edit().putBoolean("show_others", v).apply(); }

    static boolean secureGuideSeen(Context c) { return sp(c).getBoolean("secure_guide_v2", false); }
    static void setSecureGuideSeen(Context c) { sp(c).edit().putBoolean("secure_guide_v2", true).apply(); }

    // ----- 내가 만든 앨범 -----
    static List<String> getCustomAlbums(Context c) { return split(sp(c).getString("custom_albums", "")); }

    static void addCustomAlbum(Context c, String path) {
        List<String> l = getCustomAlbums(c);
        if (!l.contains(path)) l.add(path);
        sp(c).edit().putString("custom_albums", join(l)).apply();
    }

    static void removeCustomAlbum(Context c, String path) {
        List<String> l = getCustomAlbums(c);
        l.remove(path);
        sp(c).edit().putString("custom_albums", join(l)).apply();
        List<String> r = getRecent(c);
        r.remove(path);
        sp(c).edit().putString("recent", join(r)).apply();
    }

    // ----- 최근 사용 앨범 -----
    static List<String> getRecent(Context c) { return split(sp(c).getString("recent", "")); }

    static void pushRecent(Context c, String path) {
        List<String> l = getRecent(c);
        l.remove(path);
        l.add(0, path);
        while (l.size() > MAX_RECENT) l.remove(l.size() - 1);
        sp(c).edit().putString("recent", join(l)).apply();
    }

    // ----- 이미 알림을 보낸 이미지 ID -----
    static List<String> getSeen(Context c) { return split(sp(c).getString("seen", "")); }

    static void setSeen(Context c, List<String> ids) {
        List<String> l = new ArrayList<>(ids);
        while (l.size() > MAX_SEEN) l.remove(0);
        sp(c).edit().putString("seen", join(l)).apply();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("\n")) {
            if (!p.trim().isEmpty()) out.add(p);
        }
        return out;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }
}
