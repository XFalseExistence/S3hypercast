package com.telephonichoudini.s3cast;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 6001;
    private MediaProjectionManager projectionManager;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(2, 8, 12));
        w.setNavigationBarColor(Color.rgb(2, 8, 12));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(42, 40, 42, 40);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(2, 8, 12));

        TextView title = new TextView(this);
        title.setText("S3 CAST // DEX BRIDGE");
        title.setTextColor(Color.rgb(80, 255, 205));
        title.setTextSize(27);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = new TextView(this);
        sub.setText("Waveshare S3 7B  •  1024×600  •  touch-back");
        sub.setTextColor(Color.rgb(120, 160, 175));
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, 8, 0, 32);
        root.addView(sub, sp);

        status = new TextView(this);
        status.setText("1. Flash receiver\n2. Join Wi-Fi S3-CAST / S3CAST77\n3. Start cast");
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.setMargins(0, 0, 0, 26);
        root.addView(status, stp);

        Button wifi = makeButton("OPEN WI-FI // JOIN S3-CAST");
        wifi.setOnClickListener(v -> openWifiPanel());
        root.addView(wifi, buttonParams());

        Button touch = makeButton("ENABLE 7B TOUCH CONTROL");
        touch.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(touch, buttonParams());

        Button start = makeButton("START CAST");
        start.setOnClickListener(v -> requestCapture());
        root.addView(start, buttonParams());

        Button stop = makeButton("STOP CAST");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, CastService.class);
            i.setAction(CastService.ACTION_STOP);
            startService(i);
            status.setText("Cast stopped.");
        });
        root.addView(stop, buttonParams());

        TextView note = new TextView(this);
        note.setText("Best results: rotate the phone landscape. v001 sends a 512×300 RGB565 desktop at 5 FPS, then the 7B expands it exactly 2×. DRM/secure apps may appear black by Android design.");
        note.setTextColor(Color.rgb(145, 165, 175));
        note.setTextSize(13);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.setMargins(0, 25, 0, 0);
        root.addView(note, np);

        setContentView(root);
        requestNotificationPermissionIfNeeded();
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 9, 0, 9);
        return p;
    }

    private void openWifiPanel() {
        try {
            startActivity(new Intent(Settings.Panel.ACTION_WIFI));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
        status.setText("Join S3-CAST, password S3CAST77. If Android says 'no internet', choose to stay connected.");
    }

    private void requestCapture() {
        status.setText("Waiting for Android screen-capture permission…");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            status.setText("Screen capture was not granted.");
            return;
        }
        Intent service = new Intent(this, CastService.class);
        service.putExtra(CastService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CastService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        status.setText("CASTING → 192.168.4.1:7070\nTouch-back listens on UDP 7071.");
        Toast.makeText(this, "S3 CAST started", Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7001);
        }
    }
}
