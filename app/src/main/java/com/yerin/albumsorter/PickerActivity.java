package com.yerin.albumsorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 알림을 누르면 뜨는 "앨범 고르기" 창 */
public class PickerActivity extends Activity {
    private static final int REQ_WRITE = 7;

    private long imageId;
    private boolean autoMode;
    private String pendingAlbum;
    private boolean consentTried;

    private final List<String> albums = new ArrayList<>();
    private List<String> recent = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ImageView thumbView;
    private TextView infoView;

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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(12));

        TextView title = new TextView(this);
        title.setText("어느 앨범에 넣을까요?");
        title.setTextSize(20);
        root.addView(title);

        thumbView = new ImageView(this);
        thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        tp.topMargin = dp(12);
        root.addView(thumbView, tp);

        infoView = new TextView(this);
        infoView.setTextSize(13);
        infoView.setPadding(0, dp(6), 0, dp(6));
        root.addView(infoView);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, albums) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                String p = getItem(position);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setText("📁  " + AlbumRepo.displayName(p));
                t2.setText(p + (recent.contains(p) ? "  · 최근 사용" : ""));
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> doMove(albums.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
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
                (int) (getResources().getDisplayMetrics().heightPixels * 0.85));
    }

    private void load() {
        new Thread(() -> {
            MediaMover.Info info = MediaMover.query(this, imageId);
            Bitmap thumb = null;
            try {
                thumb = getContentResolver().loadThumbnail(MediaMover.uriOf(imageId),
                        new android.util.Size(600, 600), null);
            } catch (Exception ignored) {
            }
            List<String> list = AlbumRepo.listAlbums(this);
            List<String> rec = Prefs.getRecent(this);
            Bitmap finalThumb = thumb;
            runOnUiThread(() -> {
                if (info == null) {
                    toast("이미지를 찾을 수 없어요 (이미 옮겨졌거나 삭제됨)");
                    cancelNotification();
                    finish();
                    return;
                }
                if (finalThumb != null) thumbView.setImageBitmap(finalThumb);
                infoView.setText("현재 위치: " + info.relPath + "\n파일: " + info.name);
                list.remove(info.relPath);
                recent = rec;
                albums.clear();
                albums.addAll(list);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void askNewAlbum() {
        EditText et = new EditText(this);
        et.setHint("예: 여행, 맛집, 인테리어");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("새 앨범 만들기")
                .setMessage("Pictures 폴더 안에 새 앨범이 만들어져요")
                .setView(et)
                .setPositiveButton("만들고 넣기", (d, w) -> {
                    String n = MainActivity.sanitize(et.getText().toString());
                    if (n.isEmpty()) {
                        toast("앨범 이름을 입력해 주세요");
                        return;
                    }
                    String path = "Pictures/" + n + "/";
                    Prefs.addCustomAlbum(this, path);
                    doMove(path);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void doMove(String album) {
        MediaMover.Result r = MediaMover.move(this, imageId, album);
        switch (r) {
            case OK:
                Prefs.pushRecent(this, album);
                cancelNotification();
                toast("「" + AlbumRepo.displayName(album) + "」 앨범에 넣었어요");
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
                            Collections.singletonList(MediaMover.uriOf(imageId)));
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
