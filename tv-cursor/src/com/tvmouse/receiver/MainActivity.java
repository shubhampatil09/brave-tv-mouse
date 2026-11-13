package com.tvmouse.receiver;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Status screen: shows the TV's address and whether the cursor service is on. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            update();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0F172A);
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("TV Mouse Receiver");
        title.setTextSize(30);
        title.setTextColor(0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(18);
        status.setTextColor(0xFFCBD5E1);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        Button open = new Button(this);
        open.setText("Open Accessibility settings");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(open, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        open.requestFocus();

        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresh); }

    private void update() {
        StringBuilder sb = new StringBuilder();
        if (CursorService.instance != null) {
            sb.append("Cursor service: ON\n");
            sb.append("Mode: ").append(CursorService.mode).append('\n');
            sb.append("Phones connected: ").append(CursorService.clients).append('\n');
        } else {
            sb.append("Cursor service: OFF\n");
            sb.append("Turn on \"TV Mouse cursor\" in Accessibility settings.\n");
        }
        sb.append("\nTV address for the phone app:\n").append(addresses());
        sb.append("\nPort ").append(CursorService.TCP_PORT);
        status.setText(sb);
    }

    private static String addresses() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) sb.append(a.getHostAddress()).append('\n');
                }
            }
        } catch (Exception ignored) { }
        return sb.length() == 0 ? "(no network)\n" : sb.toString();
    }
}
