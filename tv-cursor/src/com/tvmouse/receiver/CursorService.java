package com.tvmouse.receiver;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Draws a pointer over every app and executes commands from the phone app.
 *
 * Protocol (TCP 8765, one command per line):
 *   M dx dy   move pointer        C  click            L  long press
 *   S dx dy   scroll (drag)       B  Back             H  Home
 *   T base64  type UTF-8 text     E  Enter            D  delete one character
 * Discovery (UDP 8766): "TVMOUSE_DISCOVER" -> "TVMOUSE_HERE <model> 8765".
 */
public class CursorService extends AccessibilityService {
    static final String TAG = "TvMouse";
    static final int TCP_PORT = 8765;
    static final int UDP_PORT = 8766;
    static volatile CursorService instance;
    static volatile int clients;
    static volatile String mode = "off";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private CursorView cursorView;
    private int screenW, screenH;
    private float x, y;
    private boolean gestureBusy;
    /** True on TVs without touch support: Android cancels every injected gesture there. */
    private boolean useNodes;
    private long lastNodeScroll;

    // virtual USB mouse (TVs without touch support); see hidLoop()
    private static final String HID_REGISTER = "{\"id\":1,\"command\":\"register\",\"name\":\"TV Mouse\","
            + "\"vid\":6353,\"pid\":11328,\"bus\":\"usb\",\"descriptor\":[5,1,9,2,161,1,9,1,161,0,5,9,25,1,41,3,"
            + "21,0,37,1,149,3,117,1,129,2,149,1,117,5,129,3,5,1,9,48,9,49,9,56,21,129,37,127,117,8,149,3,"
            + "129,6,192,192]}\n";
    private final Object hidLock = new Object();
    private volatile boolean hidReady;
    private float hidDx, hidDy, hidWheel;
    private final java.util.ArrayDeque<Integer> hidClicks = new java.util.ArrayDeque<>();
    private float pendingScrollX, pendingScrollY;
    private ServerSocket server;
    private DatagramSocket discovery;
    private volatile boolean running;

    @Override
    protected void onServiceConnected() {
        instance = this;
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        screenW = size.x;
        screenH = size.y;
        x = screenW / 2f;
        y = screenH / 2f;

        PackageManager pm = getPackageManager();
        useNodes = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
                && !pm.hasSystemFeature(PackageManager.FEATURE_FAKETOUCH);

        cursorView = new CursorView();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(cursorView, lp);

        new Thread(this::serveTcp, "tvmouse-tcp").start();
        new Thread(this::serveUdp, "tvmouse-udp").start();
        mode = useNodes ? "element clicks (no touch support)" : "touch gestures";
        if (useNodes) new Thread(this::hidLoop, "tvmouse-hid").start();
        Log.i(TAG, "service connected, screen " + screenW + "x" + screenH + (useNodes ? ", node mode" : ", gesture mode"));
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        mode = "off";
        synchronized (hidLock) { hidLock.notifyAll(); }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        if (discovery != null) discovery.close();
        if (cursorView != null) {
            try { wm.removeView(cursorView); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    // ---- network ----

    private void serveTcp() {
        try {
            server = new ServerSocket(TCP_PORT);
            while (running) {
                final Socket s = server.accept();
                s.setTcpNoDelay(true);
                new Thread(() -> handleClient(s), "tvmouse-client").start();
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "tcp server stopped", e);
        }
    }

    private void handleClient(Socket s) {
        clients++;
        Log.i(TAG, "client connected " + s.getRemoteSocketAddress());
        try (Socket sock = s) {
            OutputStream out = sock.getOutputStream();
            out.write(("TVMOUSE " + Build.MODEL + " " + screenW + "x" + screenH + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                final String cmd = line.trim();
                if (!cmd.isEmpty()) main.post(() -> execute(cmd));
            }
        } catch (Exception e) {
            Log.w(TAG, "client error: " + e);
        } finally {
            clients--;
            Log.i(TAG, "client disconnected");
        }
    }

    private void serveUdp() {
        try {
            discovery = new DatagramSocket(UDP_PORT);
            byte[] buf = new byte[256];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                discovery.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (msg.startsWith("TVMOUSE_DISCOVER")) {
                    byte[] reply = ("TVMOUSE_HERE " + Build.MODEL.replace(' ', '_') + " " + TCP_PORT)
                            .getBytes(StandardCharsets.UTF_8);
                    discovery.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "udp discovery stopped", e);
        }
    }

    // ---- commands (main thread) ----

    private void execute(String cmd) {
        try {
            String[] p = cmd.split(" ", 3);
            switch (p[0]) {
                case "M":
                    if (hidReady) hidPost(Float.parseFloat(p[1]), Float.parseFloat(p[2]), 0, 0);
                    else move(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                    break;
                case "C":
                    if (hidReady) { hidPost(0, 0, 0, 60); Log.i(TAG, "mouse click"); }
                    else { tap(60); Log.i(TAG, "click " + (int) x + "," + (int) y); }
                    break;
                case "L":
                    if (hidReady) { hidPost(0, 0, 0, 800); Log.i(TAG, "mouse long press"); }
                    else { tap(800); Log.i(TAG, "long press " + (int) x + "," + (int) y); }
                    break;
                case "S":
                    if (hidReady) hidPost(0, 0, Float.parseFloat(p[2]), 0);
                    else scroll(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                    break;
                case "B": performGlobalAction(GLOBAL_ACTION_BACK); Log.i(TAG, "back"); break;
                case "H": performGlobalAction(GLOBAL_ACTION_HOME); Log.i(TAG, "home"); break;
                case "T":
                    type(new String(Base64.decode(p[1], Base64.DEFAULT), StandardCharsets.UTF_8));
                    break;
                case "E": enter(); break;
                case "D": deleteChar(); break;
                default: Log.w(TAG, "unknown command " + cmd);
            }
        } catch (Exception e) {
            Log.w(TAG, "bad command '" + cmd + "': " + e);
        }
    }

    private void move(float dx, float dy) {
        x = Math.max(0, Math.min(screenW - 1, x + dx));
        y = Math.max(0, Math.min(screenH - 1, y + dy));
        cursorView.invalidate();
    }

    private void tap(final long durationMs) {
        final int px = (int) x, py = (int) y;
        final boolean isLong = durationMs > 400;
        if (useNodes) { nodeClick(px, py, isLong); return; }
        Path path = new Path();
        path.moveTo(x, y);
        dispatch(path, durationMs, new GestureResultCallback() {
            @Override public void onCancelled(GestureDescription g) {
                Log.w(TAG, "gesture cancelled, switching to node mode");
                useNodes = true;
                nodeClick(px, py, isLong);
            }
        });
    }

    // ---- node mode: act on the accessibility node under the pointer ----

    private AccessibilityNodeInfo nodeAt(int px, int py) {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                if (!r.contains(px, py)) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                AccessibilityNodeInfo hit = deepest(root, px, py, 0);
                if (hit != null) return hit;
            }
        } catch (Exception e) {
            Log.w(TAG, "window lookup failed: " + e);
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root == null ? null : deepest(root, px, py, 0);
    }

    private AccessibilityNodeInfo deepest(AccessibilityNodeInfo n, int px, int py, int depth) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (!r.contains(px, py) || !n.isVisibleToUser()) return null;
        if (depth < 80) {
            for (int i = n.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c == null) continue;
                AccessibilityNodeInfo hit = deepest(c, px, py, depth + 1);
                if (hit != null) return hit;
            }
        }
        return n;
    }

    private void nodeClick(int px, int py, boolean isLong) {
        AccessibilityNodeInfo n = nodeAt(px, py);
        while (n != null && !(isLong ? n.isLongClickable() : n.isClickable())) n = n.getParent();
        if (n == null) {
            Log.i(TAG, "nothing " + (isLong ? "long-" : "") + "clickable at " + px + "," + py);
            return;
        }
        if (n.isFocusable()) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean ok = n.performAction(isLong
                ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
        Log.i(TAG, "node " + (isLong ? "long-click" : "click") + " ok=" + ok + " " + n.getClassName()
                + " '" + (n.getText() != null ? n.getText() : n.getContentDescription()) + "'");
    }

    /** One scroll step per ~120 px of finger travel, at most four a second. */
    private void nodeScroll() {
        long now = System.currentTimeMillis();
        boolean vertical = Math.abs(pendingScrollY) >= Math.abs(pendingScrollX);
        float amount = vertical ? pendingScrollY : pendingScrollX;
        if (Math.abs(amount) < 120 || now - lastNodeScroll < 250) return;
        pendingScrollX = 0;
        pendingScrollY = 0;
        lastNodeScroll = now;
        int action;
        if (vertical) {
            action = amount < 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        } else {
            action = amount < 0 ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()
                    : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();
        }
        AccessibilityNodeInfo n = nodeAt((int) x, (int) y);
        while (n != null) {
            if (n.isScrollable() && n.performAction(action)) {
                Log.i(TAG, "node scroll " + (vertical ? "v " : "h ") + (amount < 0 ? "forward" : "backward"));
                return;
            }
            n = n.getParent();
        }
        Log.i(TAG, "nothing scrollable under the pointer");
    }

    /** Scroll deltas are merged so only one drag gesture runs at a time. */
    private void scroll(float dx, float dy) {
        pendingScrollX += dx;
        pendingScrollY += dy;
        if (useNodes) { nodeScroll(); return; }
        if (!gestureBusy) flushScroll();
    }

    private void flushScroll() {
        float dx = Math.max(-screenW / 3f, Math.min(screenW / 3f, pendingScrollX));
        float dy = Math.max(-screenH / 3f, Math.min(screenH / 3f, pendingScrollY));
        pendingScrollX = 0;
        pendingScrollY = 0;
        if (Math.abs(dx) < 2 && Math.abs(dy) < 2) return;
        float sx = Math.max(20, Math.min(screenW - 20, x));
        float sy = Math.max(20, Math.min(screenH - 20, y));
        float ex = Math.max(1, Math.min(screenW - 1, sx + dx));
        float ey = Math.max(1, Math.min(screenH - 1, sy + dy));
        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(ex, ey);
        gestureBusy = true;
        dispatch(path, 150, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { done(); }
            @Override public void onCancelled(GestureDescription g) { useNodes = true; done(); }
            private void done() {
                gestureBusy = false;
                if (pendingScrollX != 0 || pendingScrollY != 0) flushScroll();
            }
        });
    }

    private void dispatch(Path path, long durationMs, GestureResultCallback cb) {
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();
        if (!dispatchGesture(g, cb, main)) {
            Log.w(TAG, "gesture rejected");
            gestureBusy = false;
        }
    }

    private AccessibilityNodeInfo focusedInput() {
        AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (n == null || !n.isEditable()) {
            Log.w(TAG, "no editable field has focus");
            return null;
        }
        return n;
    }

    private static String currentText(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) return "";
        CharSequence t = n.getText();
        return t == null ? "" : t.toString();
    }

    private void setText(AccessibilityNodeInfo n, String text, int caret) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        Bundle sel = new Bundle();
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        Log.i(TAG, "set text ok=" + ok + " len=" + text.length());
    }

    /** Inserts at the caret, replacing any selection (the address bar selects its URL on focus). */
    private void type(String s) {
        AccessibilityNodeInfo n = focusedInput();
        if (n == null) return;
        String cur = currentText(n);
        int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
        if (a < 0 || b < 0 || a > cur.length() || b > cur.length()) { a = cur.length(); b = cur.length(); }
        if (a > b) { int t = a; a = b; b = t; }
        setText(n, cur.substring(0, a) + s + cur.substring(b), a + s.length());
    }

    private void deleteChar() {
        AccessibilityNodeInfo n = focusedInput();
        if (n == null) return;
        String cur = currentText(n);
        int a = n.getTextSelectionStart(), b = n.getTextSelectionEnd();
        if (a < 0 || b < 0 || a > cur.length() || b > cur.length()) { a = cur.length(); b = cur.length(); }
        if (a > b) { int t = a; a = b; b = t; }
        if (a == b) {
            if (a == 0) return;
            a = cur.offsetByCodePoints(a, -1);
        }
        setText(n, cur.substring(0, a) + cur.substring(b), a);
    }

    private void enter() {
        AccessibilityNodeInfo n = focusedInput();
        if (n == null) return;
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 30) {
            ok = n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        Log.i(TAG, "enter ok=" + ok);
    }

    // ---- virtual mouse ----
    //
    // On a TV without touch support Android cancels every injected gesture, and element
    // clicks are ignored by many web pages. The system "hid" tool can create a virtual USB
    // mouse, which behaves like a real one, but only with shell rights. So the service
    // connects to the TV's own adb daemon and keeps "hid -" running there.

    private void hidPost(float dx, float dy, float wheel, int clickMs) {
        synchronized (hidLock) {
            hidDx += dx;
            hidDy += dy;
            hidWheel += wheel;
            if (clickMs > 0) hidClicks.add(clickMs);
            hidLock.notifyAll();
        }
    }

    private static byte[] report(int buttons, int dx, int dy, int wheel) {
        return ("{\"id\":1,\"command\":\"report\",\"report\":[" + buttons + "," + (dx & 0xff) + ","
                + (dy & 0xff) + "," + (wheel & 0xff) + "]}\n").getBytes(StandardCharsets.UTF_8);
    }

    private void hidLoop() {
        while (running) {
            AdbStream adb = null;
            try {
                adb = AdbStream.open(getFilesDir(), "exec:hid -", 120000);
                adb.write(HID_REGISTER.getBytes(StandardCharsets.UTF_8));
                Thread.sleep(1500);
                adb.write(report(0, 1, 1, 0));   // makes the system pointer appear
                synchronized (hidLock) { hidDx = hidDy = hidWheel = 0; hidClicks.clear(); }
                hidReady = true;
                mode = "virtual mouse";
                main.post(() -> cursorView.setVisibility(View.GONE));
                Log.i(TAG, "virtual mouse ready");
                while (running && !adb.isClosed()) {
                    int dx, dy, wheel, click;
                    synchronized (hidLock) {
                        while (running && !adb.isClosed() && Math.abs(hidDx) < 1 && Math.abs(hidDy) < 1
                                && Math.abs(hidWheel) < 30 && hidClicks.isEmpty()) {
                            hidLock.wait(1000);
                        }
                        dx = (int) hidDx;
                        dy = (int) hidDy;
                        hidDx -= dx;
                        hidDy -= dy;
                        wheel = (int) (hidWheel / 30);
                        hidWheel -= wheel * 30;
                        click = hidClicks.isEmpty() ? 0 : hidClicks.poll();
                    }
                    while (dx != 0 || dy != 0) {
                        int sx = Math.max(-127, Math.min(127, dx)), sy = Math.max(-127, Math.min(127, dy));
                        adb.write(report(0, sx, sy, 0));
                        dx -= sx;
                        dy -= sy;
                    }
                    if (wheel != 0) adb.write(report(0, 0, 0, Math.max(-127, Math.min(127, wheel))));
                    if (click > 0) {
                        adb.write(report(1, 0, 0, 0));
                        Thread.sleep(click);
                        adb.write(report(0, 0, 0, 0));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "virtual mouse unavailable: " + e);
            } finally {
                if (adb != null) adb.close();
            }
            if (hidReady) {
                hidReady = false;
                mode = "element clicks (no touch support)";
                main.post(() -> cursorView.setVisibility(View.VISIBLE));
            }
            try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
        }
    }

    // ---- pointer drawing ----

    private class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        CursorView() {
            super(CursorService.this);
            float d = getResources().getDisplayMetrics().density;
            fill.setColor(0xFFFFFFFF);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(0xFF000000);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f * d);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            float u = 2.2f * d;
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 16 * u);
            arrow.lineTo(4 * u, 12.5f * u);
            arrow.lineTo(7 * u, 19 * u);
            arrow.lineTo(9.5f * u, 18 * u);
            arrow.lineTo(6.5f * u, 11.5f * u);
            arrow.lineTo(11.5f * u, 11.5f * u);
            arrow.close();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.save();
            c.translate(x, y);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, stroke);
            c.restore();
        }
    }
}
