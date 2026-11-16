package com.yerin.albumsorter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 첫 화면: 권한 · 감시 켜기 · 설정 · 내 앨범 */
public class MainActivity extends Activity {
    private static final int REQ_MEDIA = 1;
    private static final int REQ_NOTI = 2;

    private Button btnMedia, btnNoti, btnAll, btnBattery, btnToggle;
    private TextView status;
    private LinearLayout albumBox;

    static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\n]", "").trim();
    }

    private String mediaPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasMedia() {
        return checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNoti() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WatcherService.createChannels(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("앨범 정리");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("웹사이트나 다른 앱에서 이미지를 저장하면 알림이 와요. "
                + "알림을 눌러 앨범을 고르면 그 앨범으로 바로 옮겨집니다.", 14));

        // 1. 권한
        root.addView(header("1. 권한"));
        btnMedia = button(root, v -> requestPermissions(new String[]{mediaPermission()}, REQ_MEDIA));
        btnNoti = button(root, v -> {
            if (Build.VERSION.SDK_INT >= 33)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
        });
        btnAll = button(root, v -> openAllFilesSetting());
        root.addView(text("↑ 선택 사항이에요. 허용하면 옮길 때마다 뜨는 '수정 허용' 창 없이 바로 옮겨져요.", 12));
        btnBattery = button(root, v -> openBatterySetting());
        root.addView(text("↑ 권장해요. 삼성폰은 배터리 절약 때문에 앱이 꺼질 수 있어요.", 12));

        // 2. 감시
        root.addView(header("2. 감시"));
        status = text("", 15);
        root.addView(status);
        btnToggle = button(root, v -> toggle());

        // 3. 설정
        root.addView(header("3. 알림 받지 않을 사진"));
        CheckBox cam = new CheckBox(this);
        cam.setText("카메라로 찍은 사진 (DCIM/Camera)");
        cam.setChecked(Prefs.excludeCamera(this));
        cam.setOnCheckedChangeListener((b, c) -> Prefs.setExcludeCamera(this, c));
        root.addView(cam);
        CheckBox shot = new CheckBox(this);
        shot.setText("스크린샷");
        shot.setChecked(Prefs.excludeScreenshots(this));
        shot.setOnCheckedChangeListener((b, c) -> Prefs.setExcludeScreenshots(this, c));
        root.addView(shot);

        // 4. 내 앨범
        root.addView(header("4. 내 앨범"));
        root.addView(text("여기서 만든 앨범은 갤러리에 사진이 없어도 선택 목록에 나와요. "
                + "갤러리에 이미 있는 앨범(폴더)은 자동으로 목록에 나옵니다.", 12));
        albumBox = new LinearLayout(this);
        albumBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(albumBox);
        Button add = button(root, v -> askNewAlbum());
        add.setText("+ 앨범 만들기");

        root.addView(header("도움말"));
        root.addView(text("• 앨범은 휴대폰의 Pictures 폴더 안에 있는 폴더예요. 삼성 갤러리와 구글 포토의 '기기 폴더'에 바로 보여요.\n"
                + "• 사진 접근 권한은 꼭 '모두 허용'을 골라 주세요. '선택한 사진만'으로 하면 새 이미지를 감지하지 못해요.\n"
                + "• 알림의 '→ 앨범이름' 버튼을 누르면 마지막에 쓴 앨범으로 바로 들어가요.", 13));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private void refresh() {
        btnMedia.setText(hasMedia() ? "✓ 사진 접근 허용됨" : "사진 접근 허용하기 (필수)");
        btnMedia.setEnabled(!hasMedia());
        btnNoti.setText(hasNoti() ? "✓ 알림 허용됨" : "알림 허용하기 (필수)");
        btnNoti.setEnabled(!hasNoti());
        boolean all = Environment.isExternalStorageManager();
        btnAll.setText(all ? "✓ 모든 파일 접근 허용됨" : "모든 파일 접근 허용하기 (선택)");
        PowerManager pm = getSystemService(PowerManager.class);
        boolean bat = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        btnBattery.setText(bat ? "✓ 배터리 최적화 제외됨" : "배터리 최적화에서 제외하기 (권장)");
        btnBattery.setEnabled(!bat);

        boolean on = Prefs.isEnabled(this);
        status.setText(on ? "● 켜짐 — 새 이미지를 지켜보는 중" : "○ 꺼짐");
        btnToggle.setText(on ? "감시 끄기" : "감시 시작하기");

        albumBox.removeAllViews();
        List<String> custom = Prefs.getCustomAlbums(this);
        if (custom.isEmpty()) {
            albumBox.addView(text("(아직 없음)", 13));
        }
        for (String p : custom) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = text("📁  " + AlbumRepo.displayName(p) + "   (" + p + ")", 15);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button del = new Button(this);
            del.setText("목록에서 빼기");
            del.setOnClickListener(v -> {
                Prefs.removeCustomAlbum(this, p);
                refresh();
            });
            row.addView(del);
            albumBox.addView(row);
        }
    }

    private void toggle() {
        Intent svc = new Intent(this, WatcherService.class);
        if (Prefs.isEnabled(this)) {
            Prefs.setEnabled(this, false);
            stopService(svc);
        } else {
            if (!hasMedia()) {
                toast("먼저 사진 접근을 허용해 주세요");
                return;
            }
            if (!hasNoti()) toast("알림을 허용해야 앨범 선택 알림이 와요");
            Prefs.setEnabled(this, true);
            startForegroundService(svc);
        }
        refresh();
    }

    private void openAllFilesSetting() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                toast("설정 화면을 열 수 없어요");
            }
        }
    }

    private void openBatterySetting() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("설정 화면을 열 수 없어요");
        }
    }

    private void askNewAlbum() {
        EditText et = new EditText(this);
        et.setHint("예: 여행, 맛집, 인테리어");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("새 앨범 만들기")
                .setMessage("Pictures 폴더 안에 만들어져요")
                .setView(et)
                .setPositiveButton("만들기", (d, w) -> {
                    String n = sanitize(et.getText().toString());
                    if (n.isEmpty()) {
                        toast("앨범 이름을 입력해 주세요");
                        return;
                    }
                    Prefs.addCustomAlbum(this, "Pictures/" + n + "/");
                    refresh();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(6));
        return t;
    }

    private TextView text(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(LinearLayout parent, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
