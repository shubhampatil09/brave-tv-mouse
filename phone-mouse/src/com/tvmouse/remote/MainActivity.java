package com.tvmouse.remote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
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

    // palette
    static final int BG = 0xFF0B1220;
    static final int CARD = 0xFF151F32;
    static final int CARD_BORDER = 0xFF263348;
    static final int FIELD_BG = 0xFF0E1726;
    static final int ACCENT = 0xFFFB542B;
    static final int ACCENT_PRESSED = 0xFFD8431E;
    static final int TEXT_PRIMARY = 0xFFF1F5F9;
    static final int TEXT_SECONDARY = 0xFF8798AE;
    static final int DOT_OK = 0xFF2ECC71;
    static final int DOT_WAIT = 0xFFF5A623;
    static final int DOT_ERR = 0xFFE25555;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private volatile Socket socket;
    private volatile OutputStream out;
    private TextView status;
    private View statusDot;
    private EditText hostField, textField;
    private SharedPreferences prefs;
    private long lastAttempt;
    private float density;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tvmouse", Context.MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        int pad = dp(14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        // header: title + connection pill
        LinearLayout header = row();
        TextView title = new TextView(this);
        title.setText("TV Mouse");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, weight(1));

        LinearLayout pill = row();
        pill.setBackground(roundedRect(CARD, CARD_BORDER, 1, 999));
        pill.setPadding(dp(10), dp(6), dp(12), dp(6));
        statusDot = new View(this);
        statusDot.setBackground(oval(DOT_WAIT));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotLp.gravity = Gravity.CENTER_VERTICAL;
        dotLp.rightMargin = dp(8);
        pill.addView(statusDot, dotLp);
        status = new TextView(this);
        status.setTextColor(TEXT_SECONDARY);
        status.setTextSize(13);
        status.setText("Not connected");
        status.setContentDescription("status");
        status.setMaxWidth(dp(170));
        pill.addView(status);
        header.addView(pill);
        root.addView(header);
        root.addView(spacer(12));

        // connection card
        LinearLayout connCard = row();
        connCard.setBackground(roundedRect(CARD, CARD_BORDER, 1, 14));
        connCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        hostField = new EditText(this);
        styleField(hostField, "TV address");
        hostField.setSingleLine(true);
        hostField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostField.setText(prefs.getString("host", ""));
        hostField.setContentDescription("tv_address");
        LinearLayout.LayoutParams hostLp = weight(1);
        hostLp.rightMargin = dp(8);
        connCard.addView(hostField, hostLp);
        connCard.addView(pillButton("Connect", "btn_connect", ACCENT, ACCENT_PRESSED, Color.WHITE, true,
                v -> connect(hostField.getText().toString().trim())));
        LinearLayout findBtn = outlineButton("Find TV", "btn_find", v -> discover());
        LinearLayout.LayoutParams findLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        findLp.leftMargin = dp(8);
        connCard.addView(findBtn, findLp);
        root.addView(connCard);
        root.addView(spacer(12));

        // touchpad
        TouchpadView pad1 = new TouchpadView(this);
        pad1.setContentDescription("touchpad");
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(pad1, padLp);
        root.addView(spacer(12));

        // navigation row
        LinearLayout nav = row();
        nav.addView(navButton("←", "Back", "btn_back", v -> send("B")), navWeight());
        LinearLayout.LayoutParams midLp = navWeight();
        midLp.leftMargin = dp(8);
        midLp.rightMargin = dp(8);
        nav.addView(navButton("⌂", "Home", "btn_home", v -> send("H")), midLp);
        nav.addView(navButton("●", "Click", "btn_click", v -> send("C")), navWeight());
        root.addView(nav);
        root.addView(spacer(12));

        // text row
        LinearLayout text = row();
        text.setBackground(roundedRect(CARD, CARD_BORDER, 1, 14));
        text.setPadding(dp(10), dp(10), dp(10), dp(10));
        textField = new EditText(this);
        styleField(textField, "Type text for the TV");
        textField.setSingleLine(true);
        textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        textField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        textField.setContentDescription("text_input");
        textField.setOnEditorActionListener((v, id, ev) -> { sendText(); return true; });
        LinearLayout.LayoutParams textLp = weight(1);
        textLp.rightMargin = dp(8);
        text.addView(textField, textLp);
        text.addView(iconButton("➤", "btn_send", ACCENT, ACCENT_PRESSED, v -> sendText()));
        text.addView(iconButton("⏎", "btn_enter", CARD_BORDER, 0xFF34425C, v -> send("E")), iconMargin());
        text.addView(iconButton("⌫", "btn_del", CARD_BORDER, 0xFF34425C, v -> send("D")), iconMargin());
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

    // ---- small UI helpers ----

    private int dp(float v) { return Math.round(v * density); }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private View spacer(float h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    private LinearLayout.LayoutParams navWeight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams iconMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        return lp;
    }

    /** Rounded rect, optionally bordered. radiusDp >= 500 renders as a pill/oval end-cap. */
    private GradientDrawable roundedRect(int fill, Integer stroke, float strokeDp, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != null) d.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return d;
    }

    private GradientDrawable oval(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    private StateListDrawable pillDrawable(int normal, int pressed, float radiusDp) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, roundedRect(pressed, null, 0, radiusDp));
        sl.addState(new int[]{}, roundedRect(normal, null, 0, radiusDp));
        return sl;
    }

    private void styleField(EditText e, String hint) {
        e.setHint(hint);
        e.setHintTextColor(TEXT_SECONDARY);
        e.setTextColor(TEXT_PRIMARY);
        e.setBackground(roundedRect(FIELD_BG, CARD_BORDER, 1, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    private Button pillButton(String label, String desc, int normal, int pressed, int textColor,
                               boolean bold, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(15);
        if (bold) b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setBackground(pillDrawable(normal, pressed, 10));
        b.setContentDescription(desc);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout outlineButton(String label, String desc, View.OnClickListener l) {
        LinearLayout b = row();
        b.setGravity(Gravity.CENTER);
        b.setBackground(outlinePillState());
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(desc);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(ACCENT);
        t.setTextSize(15);
        b.addView(t);
        b.setOnClickListener(l);
        return b;
    }

    private StateListDrawable outlinePillState() {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, roundedRect(0x1AFB542B, ACCENT, 1, 10));
        sl.addState(new int[]{}, roundedRect(Color.TRANSPARENT, ACCENT, 1, 10));
        return sl;
    }

    /** Two-line nav button: a symbol on top, a caption below, on a rounded card. */
    private LinearLayout navButton(String symbol, String caption, String desc, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(pillDrawable(CARD, 0xFF202E47, 14));
        b.setPadding(dp(8), dp(12), dp(8), dp(10));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(desc);
        TextView icon = new TextView(this);
        icon.setText(symbol);
        icon.setTextColor(TEXT_PRIMARY);
        icon.setTextSize(20);
        icon.setGravity(Gravity.CENTER);
        b.addView(icon);
        TextView label = new TextView(this);
        label.setText(caption);
        label.setTextColor(TEXT_SECONDARY);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        b.addView(label, lp);
        b.setOnClickListener(l);
        return b;
    }

    private View iconButton(String symbol, String desc, int normal, int pressed, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(symbol);
        b.setTextColor(TEXT_PRIMARY);
        b.setTextSize(17);
        b.setGravity(Gravity.CENTER);
        b.setBackground(pillDrawable(normal, pressed, 10));
        int s = dp(42);
        b.setLayoutParams(new ViewGroup.LayoutParams(s, s));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(desc);
        b.setOnClickListener(l);
        return b;
    }

    private void setStatus(final String s) { setStatus(s, null); }

    private void setStatus(final String s, final Integer dotColor) {
        main.post(() -> {
            status.setText(s);
            if (dotColor != null) statusDot.setBackground(oval(dotColor));
        });
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
                statusDot.setBackground(oval(DOT_OK));
            });
            // notices when the TV goes away (reboot, Wi-Fi drop) so the next touch reconnects
            new Thread(() -> {
                try { while (in.readLine() != null) { } } catch (Exception ignored) { }
                if (socket == s) {
                    closeSocket();
                    setStatus("TV disconnected. It reconnects when you touch the pad.", DOT_ERR);
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
                : "TV not reachable at " + saved + ". Is it on, and on the same Wi-Fi?", DOT_ERR);
        return false;
    }

    private void connect(final String host) {
        if (host.isEmpty()) { setStatus("Enter the TV address or tap Find TV", DOT_ERR); return; }
        setStatus("Connecting to " + host + "...", DOT_WAIT);
        net.execute(() -> {
            if (!open(host)) setStatus("No TV Mouse Receiver answered at " + host, DOT_ERR);
        });
    }

    private void discover() {
        setStatus("Looking for a TV on this network...", DOT_WAIT);
        net.execute(() -> {
            String found = findTv();
            if (found == null || !open(found)) setStatus("No TV found. Enter the address shown on the TV.", DOT_ERR);
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
        private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final float radius;
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
            radius = 16 * density;
            fill.setColor(0xFF121B2C);
            border.setStyle(Paint.Style.STROKE);
            border.setColor(CARD_BORDER);
            border.setStrokeWidth(1.5f * density);
            strip.setColor(0xFF1B2740);
            hint.setColor(TEXT_PRIMARY);
            hint.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
            hint.setTextAlign(Paint.Align.CENTER);
            hint.setAlpha(210);
            label.setColor(TEXT_SECONDARY);
            label.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
            label.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        }

        @Override
        protected void onDraw(Canvas c) {
            int save = c.save();
            c.clipPath(clip);
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
            float sx = getWidth() * (1 - STRIP);
            c.drawRect(sx, 0, getWidth(), getHeight(), strip);
            c.drawLine(sx, 0, sx, getHeight(), border);
            c.restoreToCount(save);
            c.drawRoundRect(new RectF(0.75f, 0.75f, getWidth() - 0.75f, getHeight() - 0.75f),
                    radius, radius, border);

            float cx = sx / 2f, cy = getHeight() / 2f, lh = hint.getTextSize() * 1.7f;
            c.drawText("⌂  Touchpad", cx, cy - lh, hint);
            c.drawText("drag to move · tap to click", cx, cy, label);
            c.drawText("hold to long-press", cx, cy + lh, label);
            c.drawText("two fingers or the strip to scroll", cx, cy + 2 * lh, label);

            c.save();
            float scx = sx + (getWidth() - sx) / 2f;
            c.rotate(-90, scx, cy);
            c.drawText("↕ scroll", scx, cy + label.getTextSize() / 3f, label);
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
