package com.yerin.albumsorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 알림을 누르면 뜨는 "앨범 고르기" 창 (갤러리 앨범 화면처럼 커버·이름·사진 수 표시) */
public class PickerActivity extends Activity {
    private static final int REQ_WRITE = 7;
    private static final String SAMSUNG_GALLERY = "com.sec.android.gallery3d";

    private long imageId;
    private boolean autoMode;
    private String pendingAlbum;
    private boolean consentTried;
    private String currentPath;
    private boolean secureMode;

    private final List<AlbumRepo.Album> albums = new ArrayList<>();
    private List<String> recent = new ArrayList<>();
    private AlbumAdapter adapter;
    private ImageView thumbView;
    private TextView infoView, emptyView;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<Long, Bitmap> cache = new LruCache<>(80);

    /** 정사각형 이미지뷰 (앨범 커버용) */
    static class SquareImageView extends ImageView {
        SquareImageView(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
            int size = getMeasuredWidth();
            setMeasuredDimension(size, size);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        imageId = getIntent().getLongExtra(WatcherService.EXTRA_ID, -1);
        if (imageId < 0) {
            finish();
            return;
        }
        String auto = getIntent().getStringExtra(WatcherService.EXTRA_AUTO_ALBUM);
        if (auto != null) {
            autoMode = true;
            TextView t = new TextView(this);
            t.setText("옮기는 중…");
            t.setPadding(dp(24), dp(24), dp(24), dp(24));
            setContentView(t);
            doMove(auto);
            return;
        }
        buildUi();
        load();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(10));

        // 저장된 이미지 미리보기
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        thumbView = new ImageView(this);
        thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbView.setBackground(rounded(0xFFE8EAF0, 10));
        thumbView.setClipToOutline(true);
        top.addView(thumbView, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("어느 앨범에 넣을까요?");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);
        infoView = new TextView(this);
        infoView.setTextSize(12);
        infoView.setTextColor(0xFF666A73);
        infoView.setMaxLines(2);
        infoView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(infoView);
        top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top);

        CheckBox others = new CheckBox(this);
        others.setText("다른 앱 폴더도 보기 (인스타그램, 카카오톡 등)");
        others.setTextSize(13);
        others.setChecked(Prefs.showOthers(this));
        others.setOnCheckedChangeListener((b, c) -> {
            Prefs.setShowOthers(this, c);
            load();
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = dp(8);
        root.addView(others, op);

        emptyView = new TextView(this);
        emptyView.setText("갤러리에서 만든 앨범이 없어요.\n아래 '+ 새 앨범'으로 만들거나, '다른 앱 폴더도 보기'를 켜 보세요.");
        emptyView.setTextSize(13);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(30), 0, dp(30));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView);

        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(14));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(0, dp(8), 0, dp(8));
        grid.setClipToPadding(false);
        adapter = new AlbumAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> doMove(albums.get(position).path));
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        if (hasSamsungGallery()) {
            Button secure = new Button(this);
            secure.setText("🔒 보안 폴더로");
            secure.setAllCaps(false);
            secure.setOnClickListener(v -> openForSecureFolder());
            buttons.addView(secure);
        }
        View spacer = new View(this);
        buttons.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        Button add = new Button(this);
        add.setText("+ 새 앨범");
        add.setOnClickListener(v -> askNewAlbum());
        Button cancel = new Button(this);
        cancel.setText("그대로 두기");
        cancel.setOnClickListener(v -> {
            cancelNotification();
            finish();
        });
        buttons.addView(add);
        buttons.addView(cancel);
        root.addView(buttons);

        setContentView(root);
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.88));
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void load() {
        boolean showOthers = Prefs.showOthers(this);
        pool.execute(() -> {
            MediaMover.Info info = MediaMover.query(this, imageId);
            Bitmap thumb = null;
            try {
                thumb = getContentResolver().loadThumbnail(MediaMover.uriOf(this, imageId), new Size(300, 300), null);
            } catch (Exception ignored) {
            }
            List<AlbumRepo.Album> list = AlbumRepo.load(this, showOthers);
            List<String> rec = Prefs.getRecent(this);
            Bitmap finalThumb = thumb;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (info == null) {
                    toast("이미지를 찾을 수 없어요 (이미 옮겨졌거나 삭제됨)");
                    cancelNotification();
                    finish();
                    return;
                }
                currentPath = info.relPath;
                if (finalThumb != null) thumbView.setImageBitmap(finalThumb);
                infoView.setText((info.video ? "🎬 동영상 · " : "") + "지금 위치: "
                        + AlbumRepo.displayName(info.relPath) + "\n" + info.name);
                recent = rec;
                albums.clear();
                for (AlbumRepo.Album a : list) if (!a.path.equals(currentPath)) albums.add(a);
                emptyView.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private class AlbumAdapter extends BaseAdapter {
        @Override public int getCount() { return albums.size(); }
        @Override public Object getItem(int i) { return albums.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell;
            SquareImageView cover;
            TextView name, count;
            if (convertView == null) {
                cell = new LinearLayout(PickerActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cover = new SquareImageView(PickerActivity.this);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setBackground(rounded(0xFFE8EAF0, 12));
                cover.setClipToOutline(true);
                cell.addView(cover, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                name = new TextView(PickerActivity.this);
                name.setTextSize(14);
                name.setTextColor(Color.BLACK);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setPadding(dp(2), dp(5), dp(2), 0);
                cell.addView(name);
                count = new TextView(PickerActivity.this);
                count.setTextSize(12);
                count.setTextColor(0xFF7A7F88);
                count.setPadding(dp(2), 0, dp(2), 0);
                cell.addView(count);
                cell.setTag(new View[]{cover, name, count});
            } else {
                cell = (LinearLayout) convertView;
                View[] v = (View[]) cell.getTag();
                cover = (SquareImageView) v[0];
                name = (TextView) v[1];
                count = (TextView) v[2];
            }

            AlbumRepo.Album a = albums.get(position);
            name.setText(a.name);
            String c = a.count > 0 ? a.count + "장" : "비어 있음";
            if (recent.contains(a.path)) c = "최근 · " + c;
            count.setText(c);

            cover.setTag(a.coverId);
            cover.setImageDrawable(null);
            if (a.coverId >= 0) {
                Bitmap b = cache.get(a.coverId);
                if (b != null) {
                    cover.setImageBitmap(b);
                } else {
                    long cid = a.coverId;
                    pool.execute(() -> {
                        Bitmap bmp = null;
                        try {
                            bmp = getContentResolver().loadThumbnail(MediaMover.uriOf(PickerActivity.this, cid), new Size(256, 256), null);
                        } catch (Exception ignored) {
                        }
                        if (bmp == null) return;
                        cache.put(cid, bmp);
                        Bitmap fb = bmp;
                        runOnUiThread(() -> {
                            Object tag = cover.getTag();
                            if (tag instanceof Long && (Long) tag == cid) cover.setImageBitmap(fb);
                        });
                    });
                }
            }
            return cell;
        }
    }

    private boolean hasSamsungGallery() {
        try {
            getPackageManager().getPackageInfo(SAMSUNG_GALLERY, 0);
            return true;
        } catch (Exception e) {
            return "samsung".equalsIgnoreCase(android.os.Build.MANUFACTURER);
        }
    }

    /** 보안 폴더로 보내기: "보안폴더 대기" 앨범으로 옮긴 뒤 갤러리를 일반 모드로 열기 */
    private void openForSecureFolder() {
        if (!Prefs.secureGuideSeen(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("보안 폴더로 보내기")
                    .setMessage("삼성 보안 폴더는 다른 앱이 직접 넣을 수 없어서, 이렇게 진행돼요.\n\n"
                            + "① 이 사진을 '" + AlbumRepo.SECURE_PENDING_NAME + "' 앨범으로 옮겨요.\n"
                            + "② 갤러리가 열리면 앨범 탭 → '" + AlbumRepo.SECURE_PENDING_NAME + "' 앨범을 여세요.\n"
                            + "③ 사진을 길게 눌러 선택(여러 장이면 '전체') → ⋮ 더보기 → '보안 폴더로 이동'.")
                    .setPositiveButton("진행", (d, w) -> {
                        Prefs.setSecureGuideSeen(this);
                        secureMode = true;
                        doMove(AlbumRepo.SECURE_PENDING);
                    })
                    .setNegativeButton("취소", null)
                    .show();
        } else {
            secureMode = true;
            doMove(AlbumRepo.SECURE_PENDING);
        }
    }

    /** 갤러리 앱을 처음 화면(일반 모드)으로 열기 — 이렇게 열어야 '보안 폴더로 이동' 메뉴가 다 나옴 */
    private void launchGalleryHome() {
        Intent i = getPackageManager().getLaunchIntentForPackage(SAMSUNG_GALLERY);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(getApplicationContext(),
                "앨범 탭 → '" + AlbumRepo.SECURE_PENDING_NAME + "' → 사진 길게 누르기 → ⋮ → 보안 폴더로 이동",
                Toast.LENGTH_LONG).show();
    }

    private void askNewAlbum() {
        EditText et = new EditText(this);
        et.setHint("예: 여행, 맛집, 인테리어");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("새 앨범 만들기")
                .setMessage("갤러리 앨범과 같은 위치(DCIM)에 만들어져요")
                .setView(et)
                .setPositiveButton("만들고 넣기", (d, w) -> {
                    String n = MainActivity.sanitize(et.getText().toString());
                    if (n.isEmpty()) {
                        toast("앨범 이름을 입력해 주세요");
                        return;
                    }
                    doMove(AlbumRepo.createAlbum(this, n));
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void doMove(String album) {
        MediaMover.Result r = MediaMover.move(this, imageId, album);
        switch (r) {
            case OK:
                cancelNotification();
                if (secureMode) {
                    launchGalleryHome();
                } else {
                    Prefs.pushRecent(this, album);
                    toast("「" + AlbumRepo.displayName(album) + "」 앨범에 넣었어요");
                }
                finish();
                break;
            case NEED_CONSENT:
                if (consentTried) {
                    toast("옮기지 못했어요");
                    if (autoMode) finish();
                    return;
                }
                consentTried = true;
                pendingAlbum = album;
                try {
                    PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(),
                            Collections.singletonList(MediaMover.uriOf(this, imageId)));
                    startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
                } catch (Exception e) {
                    toast("수정 허용 창을 열지 못했어요");
                    if (autoMode) finish();
                }
                break;
            case NOT_FOUND:
                toast("이미지를 찾을 수 없어요 (이미 옮겨졌거나 삭제됨)");
                cancelNotification();
                finish();
                break;
            default:
                secureMode = false;
                toast("옮기지 못했어요. 다른 앨범을 골라 보세요");
                if (autoMode) finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_WRITE) return;
        if (resultCode == RESULT_OK && pendingAlbum != null) {
            doMove(pendingAlbum);
        } else {
            consentTried = false;
            secureMode = false;
            toast("허용하지 않아서 옮기지 않았어요");
            if (autoMode) finish();
        }
    }

    private void cancelNotification() {
        getSystemService(NotificationManager.class).cancel(WatcherService.notifId(imageId));
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
