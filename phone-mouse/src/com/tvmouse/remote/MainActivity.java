package com.tvmouse.remote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Touchpad remote for the TV Mouse Receiver. See CursorService for the protocol. */
public class MainActivity extends Activity {
    static final int TCP_PORT = 8765;
    static final int UDP_PORT = 8766;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private volatile Socket socket;
    private volatile OutputStream out;
    private TextView status;
    private EditText hostField, textField;
    private SharedPreferences prefs;
    private long lastAttempt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tvmouse", Context.MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (8 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F172A);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        // connection row
        LinearLayout conn = row();
        hostField = new EditText(this);
        hostField.setHint("TV address");
        hostField.setSingleLine(true);
        hostField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostField.setText(prefs.getString("host", ""));
        hostField.setContentDescription("tv_address");
        conn.addView(hostField, weight(1));
        conn.addView(button("Connect", "btn_connect", v -> connect(hostField.getText().toString().trim())));
        conn.addView(button("Find TV", "btn_find", v -> discover()));
        root.addView(conn);

        status = new TextView(this);
        status.setTextColor(0xFFCBD5E1);
        status.setText("Not connected");
        status.setContentDescription("status");
        status.setPadding(pad, pad / 2, pad, pad / 2);
        root.addView(status);

        // touchpad
        TouchpadView pad1 = new TouchpadView(this);
        pad1.setContentDescription("touchpad");
        root.addView(pad1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // navigation row
        LinearLayout nav = row();
        nav.addView(button("Back", "btn_back", v -> send("B")), weight(1));
        nav.addView(button("Home", "btn_home", v -> send("H")), weight(1));
        nav.addView(button("Click", "btn_click", v -> send("C")), weight(1));
        root.addView(nav);

        // text row
        LinearLayout text = row();
        textField = new EditText(this);
        textField.setHint("Text to type on the TV");
        textField.setSingleLine(true);
        textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        textField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        textField.setContentDescription("text_input");
        textField.setOnEditorActionListener((v, id, ev) -> { sendText(); return true; });
        text.addView(textField, weight(1));
        text.addView(button("Send", "btn_send", v -> sendText()));
        text.addView(button("Enter", "btn_enter", v -> send("E")));
        text.addView(button("Del", "btn_del", v -> send("D")));
        root.addView(text);

        setContentView(root);

    }

    @Override
    protected void onResume() {
        super.onResume();
        net.execute(() -> {
            if (out == null) { lastAttempt = 0; reconnect(); }
        });
    }

    @Override
    protected void onDestroy() {
        closeSocket();
        net.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    private Button button(String label, String desc, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setContentDescription(desc);
        b.setOnClickListener(l);
        return b;
    }

    private void setStatus(final String s) {
        main.post(() -> status.setText(s));
    }

    // ---- network (all socket work runs on the single "net" thread) ----

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        out = null;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    /** Blocking. Returns true when a TV Mouse Receiver answered at host. */
    private boolean open(String host) {
        closeSocket();
        try {
            final Socket s = new Socket();
            s.connect(new InetSocketAddress(host, TCP_PORT), 2000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(3000);
            final BufferedReader in = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.UTF_8));
            String hello = in.readLine();
            if (hello == null || !hello.startsWith("TVMOUSE ")) {
                s.close();
                return false;
            }
            s.setSoTimeout(0);
            socket = s;
            out = s.getOutputStream();
            prefs.edit().putString("host", host).apply();
            final String name = hello.substring(8);
            main.post(() -> {
                if (!hostField.hasFocus()) hostField.setText(host);
                status.setText("Connected: " + name);
            });
            // notices when the TV goes away (reboot, Wi-Fi drop) so the next touch reconnects
            new Thread(() -> {
                try { while (in.readLine() != null) { } } catch (Exception ignored) { }
                if (socket == s) {
                    closeSocket();
                    setStatus("TV disconnected. It reconnects when you touch the pad.");
                }
            }, "tvmouse-reader").start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Blocking. Asks the network for a TV; returns its address or null. */
    private String findTv() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(1500);
            byte[] q = "TVMOUSE_DISCOVER".getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), UDP_PORT));
            byte[] buf = new byte[256];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
            return msg.startsWith("TVMOUSE_HERE") ? p.getAddress().getHostAddress() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Blocking. Saved address first, then discovery (the TV's address can change). */
    private boolean reconnect() {
        long now = System.currentTimeMillis();
        if (now - lastAttempt < 3000) return false;
        lastAttempt = now;
        String saved = prefs.getString("host", "");
        if (!saved.isEmpty() && open(saved)) return true;
        String found = findTv();
        if (found != null && open(found)) return true;
        setStatus(saved.isEmpty()
                ? "No TV found. Enter the address shown on the TV."
                : "TV not reachable at " + saved + ". Is it on, and on the same Wi-Fi?");
        return false;
    }

    private void connect(final String host) {
        if (host.isEmpty()) { setStatus("Enter the TV address or tap Find TV"); return; }
        setStatus("Connecting to " + host + "...");
        net.execute(() -> {
            if (!open(host)) setStatus("No TV Mouse Receiver answered at " + host);
        });
    }

    private void discover() {
        setStatus("Looking for a TV on this network...");
        net.execute(() -> {
            String found = findTv();
            if (found == null || !open(found)) setStatus("No TV found. Enter the address shown on the TV.");
        });
    }

    private boolean write(String cmd) {
        OutputStream o = out;
        if (o == null) return false;
        try {
            o.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            return true;
        } catch (Exception e) {
            closeSocket();
            return false;
        }
    }

    private void send(final String cmd) {
        net.execute(() -> {
            if (write(cmd)) return;
            if (reconnect()) write(cmd);
        });
    }

    private void sendText() {
        String t = textField.getText().toString();
        if (t.isEmpty()) return;
        send("T " + Base64.encodeToString(t.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        textField.setText("");
    }

    // ---- touchpad ----

    /**
     * One finger moves the pointer, tap clicks, hold long-presses. Two fingers, or one
     * finger on the strip along the right edge, scroll.
     */
    private class TouchpadView extends View {
        private static final float SPEED = 1.6f;
        private static final float SCROLL_SPEED = 1.5f;
        private static final float STRIP = 0.14f;
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strip = new Paint();
        private final int slop;
        private float lastX, lastY, downX, downY, accX, accY;
        private long downTime;
        private boolean moved, multi, longSent, edgeScroll;
        private final Runnable longPress = () -> {
            if (!moved && !multi) { longSent = true; send("L"); }
        };

        TouchpadView(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setBackgroundColor(0xFF1E293B);
            border.setStyle(Paint.Style.STROKE);
            border.setColor(0xFF475569);
            border.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
            strip.setColor(0xFF273449);
            label.setColor(0xFF64748B);
            label.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
            label.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            float sx = getWidth() * (1 - STRIP);
            c.drawRect(sx, 0, getWidth(), getHeight(), strip);
            c.drawRect(1, 1, getWidth() - 1, getHeight() - 1, border);
            float cx = sx / 2f, cy = getHeight() / 2f, lh = label.getTextSize() * 1.6f;
            c.drawText("Touchpad", cx, cy - lh, label);
            c.drawText("drag: move    tap: click", cx, cy, label);
            c.drawText("hold: long press", cx, cy + lh, label);
            c.drawText("two fingers or right strip: scroll", cx, cy + 2 * lh, label);
            c.save();
            c.rotate(-90, sx + (getWidth() - sx) / 2f, cy);
            c.drawText("scroll", sx + (getWidth() - sx) / 2f, cy + label.getTextSize() / 3f, label);
            c.restore();
        }

        private float avgX(MotionEvent e) {
            float s = 0;
            for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
            return s / e.getPointerCount();
        }

        private float avgY(MotionEvent e) {
            float s = 0;
            for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
            return s / e.getPointerCount();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = downX = e.getX();
                    lastY = downY = e.getY();
                    downTime = e.getEventTime();
                    moved = false;
                    multi = false;
                    longSent = false;
                    edgeScroll = e.getX() > getWidth() * (1 - STRIP);
                    accX = accY = 0;
                    main.postDelayed(longPress, 600);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    multi = true;
                    main.removeCallbacks(longPress);
                    // re-base so the finger count change does not cause a jump
                    if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                        float sx = 0, sy = 0;
                        int n = 0;
                        for (int i = 0; i < e.getPointerCount(); i++) {
                            if (i == e.getActionIndex()) continue;
                            sx += e.getX(i);
                            sy += e.getY(i);
                            n++;
                        }
                        lastX = sx / n;
                        lastY = sy / n;
                    } else {
                        lastX = avgX(e);
                        lastY = avgY(e);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float cx = avgX(e), cy = avgY(e);
                    float dx = cx - lastX, dy = cy - lastY;
                    lastX = cx;
                    lastY = cy;
                    if (!moved && e.getPointerCount() == 1
                            && Math.hypot(cx - downX, cy - downY) < slop) return true;
                    if (!moved) main.removeCallbacks(longPress);
                    moved = true;
                    if (e.getPointerCount() >= 2 || (edgeScroll && !multi)) {
                        accX += dx * SCROLL_SPEED;
                        accY += dy * SCROLL_SPEED;
                        if (Math.abs(accX) >= 8 || Math.abs(accY) >= 8) {
                            send("S " + Math.round(accX) + " " + Math.round(accY));
                            accX = accY = 0;
                        }
                    } else if (!multi) {
                        send("M " + (dx * SPEED) + " " + (dy * SPEED));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    if (!moved && !multi && !longSent && e.getEventTime() - downTime < 300) send("C");
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
