package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStructure;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Developer tool: a tiny HTTP/1.0 server for reading and driving the app over adb (view tree with Compose semantics,
 * taps, swipes, text input, toggles and the mod's prefs, synthetic IRC lines, player state). Off unless the system property {@code debug.retwitch.server} is non-empty when
 * the process starts. Its value is the token every request sends as {@code X-Token}; it is re-read on each
 * request, so clearing the prop locks a running server. Listens only on the abstract unix socket
 * {@code retwitch-debug} (no TCP port) and answers only adbd (shell or root uid):
 * {@code adb forward tcp:7777 localabstract:retwitch-debug}.
 */
public final class DebugServer {

    private static final String TAG = "RTDebugServer";
    private static final String PROP = "debug.retwitch.server";
    private static final String SOCKET = "retwitch-debug";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The mod's own {@code setTag(int, ...)} keys all live here (grep 0x7e70 in the mod's sources). */
    private static final int TAG_FIRST = 0x7e700c00;
    private static final int TAG_LAST = 0x7e700eff;
    private static final int MAX_TEXT = 200;
    private static final String COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView";
    private static final String PREFS_PREFIX = "retwitch_";
    private static final Map<Integer, String> ID_NAMES = new HashMap<>();
    private static volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    private static boolean started;

    private DebugServer() {
    }

    /** From {@link UiHider#install}; does nothing without the prop. */
    static void install(Application application) {
        try {
            if (prop().isEmpty()) {
                return;
            }
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity a, Bundle b) {
                    // Background processes run Application.onCreate too: only the one with activities serves.
                    if (!started) {
                        started = true;
                        Thread thread = new Thread(DebugServer::serve, TAG);
                        thread.setDaemon(true);
                        thread.start();
                    }
                }
                public void onActivityStarted(Activity a) { }
                public void onActivityResumed(Activity a) { resumed = new WeakReference<>(a); }
                public void onActivityPaused(Activity a) {
                    if (resumed.get() == a) {
                        resumed = new WeakReference<>(null);
                    }
                }
                public void onActivityStopped(Activity a) { }
                public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                public void onActivityDestroyed(Activity a) { }
            });
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
        }
    }

    private static String prop() {
        try {
            Object value = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, PROP);
            return value == null ? "" : ((String) value).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void serve() {
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(SOCKET);
        } catch (Throwable t) {
            Log.w(TAG, "bind failed", t);
            return;
        }
        Log.i(TAG, "listening on localabstract:" + SOCKET);
        while (true) {
            LocalSocket socket = null;
            try {
                socket = server.accept();
                handle(socket);
            } catch (Throwable t) {
                Log.w(TAG, "request failed", t);
            } finally {
                try {
                    if (socket != null) {
                        socket.close();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void handle(LocalSocket socket) throws Exception {
        socket.setSoTimeout(5000);
        int uid = socket.getPeerCredentials().getUid();
        String[] lines = readHead(socket.getInputStream()).split("\r\n");
        String[] request = lines[0].split(" ");
        String token = null;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].regionMatches(true, 0, "X-Token:", 0, 8)) {
                token = lines[i].substring(8).trim();
            }
        }
        String want = prop();
        int code = 200;
        String body;
        // adbd runs as shell (2000), or root after `adb root`; any other uid is another app on the phone.
        if ((uid != 2000 && uid != 0) || want.isEmpty() || token == null
                || !MessageDigest.isEqual(want.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            code = 403;
            body = error("forbidden");
        } else if (request.length < 2 || !"GET".equals(request[0])) {
            code = 400;
            body = error("GET only");
        } else {
            String target = request[1];
            int q = target.indexOf('?');
            try {
                body = route(q < 0 ? target : target.substring(0, q), query(q < 0 ? "" : target.substring(q + 1)));
                if (body == null) {
                    code = 404;
                    body = error("unknown path");
                }
            } catch (TimeoutException e) {
                code = 503;
                body = error(e.getMessage());
            } catch (IllegalArgumentException e) {
                code = 400;
                body = error(e.getMessage());
            } catch (Throwable t) {
                code = 500;
                body = error(String.valueOf(t));
                Log.w(TAG, "request failed", t);
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.0 " + code + (code == 200 ? " OK" : " Error")
                + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static String readHead(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && sb.length() < 16384) {
            sb.append((char) c);
            if (sb.length() >= 4 && sb.lastIndexOf("\r\n\r\n") == sb.length() - 4) {
                break;
            }
        }
        return sb.toString();
    }

    private static Map<String, String> query(String raw) throws Exception {
        Map<String, String> q = new HashMap<>();
        for (String part : raw.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            q.put(URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), "UTF-8"),
                    eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
        }
        return q;
    }

    private static String route(String path, Map<String, String> q) throws Exception {
        switch (path) {
            case "/tree":
                return onMain(() -> tree(q.get("under"), q.get("text")));
            case "/tap":
                return tap(q);
            case "/swipe":
                return swipe(q);
            case "/toggles":
                return toggles();
            case "/toggle":
                return toggle(q);
            case "/irc":
                return irc(q);
            case "/state":
                return onMain(DebugServer::state);
            case "/prefs":
                return prefs(q);
            case "/text":
                return onMain(() -> text(q));
            default:
                return null;
        }
    }

    // ---- main thread ----

    @SuppressWarnings("unchecked")
    private static <T> T onMain(final Callable<T> task) throws Exception {
        final Object[] box = new Object[2];
        final CountDownLatch done = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                box[0] = task.call();
            } catch (Throwable t) {
                box[1] = t;
            }
            done.countDown();
        });
        if (!done.await(5, TimeUnit.SECONDS)) {
            throw new TimeoutException("main thread busy for 5 s");
        }
        if (box[1] instanceof Exception) {
            throw (Exception) box[1];
        }
        if (box[1] != null) {
            throw new RuntimeException((Throwable) box[1]);
        }
        return (T) box[0];
    }

    /** Every window root of the process, bottom to top: activities, dialogs, bottom sheets, popups. */
    @SuppressWarnings("unchecked")
    private static List<View> roots() throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            return android.view.inspector.WindowInspector.getGlobalWindowViews();
        }
        Class<?> global = Class.forName("android.view.WindowManagerGlobal");
        Field views = global.getDeclaredField("mViews");
        views.setAccessible(true);
        return new ArrayList<>((List<View>) views.get(global.getMethod("getInstance").invoke(null)));
    }

    // ---- /tree ----

    private static String tree(String under, String text) throws Exception {
        long t0 = SystemClock.uptimeMillis();
        List<View> roots = roots();
        StringBuilder windows = new StringBuilder();
        StringBuilder views = new StringBuilder();
        String needle = text == null ? null : text.toLowerCase(Locale.ROOT);
        for (int w = 0; w < roots.size(); w++) {
            View root = roots.get(w);
            if (windows.length() > 0) {
                windows.append(',');
            }
            windows.append("{\"w\":").append(w).append(",\"cls\":").append(json(simpleName(root)));
            if (root.getLayoutParams() instanceof WindowManager.LayoutParams) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
                windows.append(",\"title\":").append(json(String.valueOf(lp.getTitle()))).append(",\"type\":").append(lp.type);
            }
            windows.append(",\"shown\":").append(root.isShown()).append('}');
            collect(root, w, 0, under == null, under, needle, views);
        }
        return "{\"took_ms\":" + (SystemClock.uptimeMillis() - t0) + ",\"windows\":[" + windows + "],\"views\":["
                + views + "]}";
    }

    private static void collect(View v, int w, int depth, boolean inScope, String under, String needle, StringBuilder out) {
        boolean scope = inScope || under.equals(idName(v));
        if (scope && (needle == null || matches(v, needle))) {
            if (out.length() > 0) {
                out.append(',');
            }
            node(v, w, depth, out);
        }
        if (scope && v.isShown()) {
            for (Object[] c : composeNodes(v)) {
                AccessibilityNodeInfo info = (AccessibilityNodeInfo) c[0];
                if (needle == null || matches(info, needle)) {
                    if (out.length() > 0) {
                        out.append(',');
                    }
                    virtualNode(info, (Integer) c[2], w, depth + (Integer) c[1], out);
                }
            }
        }
        if (v instanceof ViewGroup && v.getVisibility() != View.GONE) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != null) {
                    collect(child, w, depth + 1, scope, under, needle, out);
                }
            }
        }
    }

    private static boolean matches(View v, String needle) {
        CharSequence text = v instanceof TextView ? ((TextView) v).getText() : null;
        CharSequence desc = v.getContentDescription();
        return (text != null && text.toString().toLowerCase(Locale.ROOT).contains(needle))
                || (desc != null && desc.toString().toLowerCase(Locale.ROOT).contains(needle));
    }

    private static void node(View v, int w, int depth, StringBuilder sb) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        sb.append("{\"w\":").append(w).append(",\"d\":").append(depth).append(",\"cls\":").append(json(simpleName(v)));
        String id = idName(v);
        if (id != null) {
            sb.append(",\"id\":").append(json(id));
        }
        int vis = v.getVisibility();
        if (vis != View.VISIBLE) {
            sb.append(",\"vis\":\"").append(vis == View.GONE ? 'G' : 'I').append('"');
        }
        if (v.getAlpha() != 1f) {
            sb.append(",\"a\":").append(v.getAlpha());
        }
        sb.append(",\"b\":[").append(loc[0]).append(',').append(loc[1]).append(',')
                .append(loc[0] + v.getWidth()).append(',').append(loc[1] + v.getHeight()).append(']');
        if (v instanceof TextView) {
            appendText(sb, "text", ((TextView) v).getText());
        }
        appendText(sb, "desc", v.getContentDescription());
        if (v.getTag() instanceof CharSequence) {
            appendText(sb, "tag", (CharSequence) v.getTag());
        }
        if (v.isClickable()) {
            sb.append(",\"click\":true");
        }
        if (v.isFocused()) {
            sb.append(",\"focus\":true");
        }
        boolean first = true;
        for (int key = TAG_FIRST; key <= TAG_LAST; key++) {
            Object value = v.getTag(key);
            if (value != null) {
                sb.append(first ? ",\"tags\":{" : ",").append('"').append(Integer.toHexString(key)).append("\":");
                appendTrimmed(sb, String.valueOf(value), 40);
                first = false;
            }
        }
        if (!first) {
            sb.append('}');
        }
        sb.append('}');
    }

    // ---- Compose ----

    /**
     * Compose semantics under an AndroidComposeView (its class name survives R8), pre-order: {node info, depth below
     * the host, semantics id}. Children of a virtual node have no public getter, so View.onProvideVirtualStructure
     * walks the accessibility provider for us into {@link Node}; AndroidComposeView only overrides the autofill one.
     */
    private static List<Object[]> composeNodes(View host) {
        List<Object[]> out = new ArrayList<>();
        if (!COMPOSE_VIEW.equals(host.getClass().getName())) {
            return out;
        }
        AccessibilityNodeProvider provider = host.getAccessibilityNodeProvider();
        if (provider == null) {
            return out;
        }
        Node root = new Node();
        host.onProvideVirtualStructure(root);
        for (Node self : root.children) {
            if (self != null) {
                for (Node child : self.children) {
                    addVirtual(provider, child, 1, out);
                }
            }
        }
        return out;
    }

    private static void addVirtual(AccessibilityNodeProvider provider, Node n, int depth, List<Object[]> out) {
        if (n == null) {
            return;
        }
        AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(n.id);
        if (info != null) {
            out.add(new Object[]{info, depth, n.id});
        }
        for (Node child : n.children) {
            addVirtual(provider, child, depth + 1, out);
        }
    }

    private static boolean matches(AccessibilityNodeInfo info, String needle) {
        CharSequence text = info.getText();
        CharSequence desc = info.getContentDescription();
        return (text != null && text.toString().toLowerCase(Locale.ROOT).contains(needle))
                || (desc != null && desc.toString().toLowerCase(Locale.ROOT).contains(needle));
    }

    private static void virtualNode(AccessibilityNodeInfo info, int id, int w, int depth, StringBuilder sb) {
        Rect r = new Rect();
        info.getBoundsInScreen(r);
        String cls = info.getClassName() == null ? "View" : info.getClassName().toString();
        sb.append("{\"w\":").append(w).append(",\"d\":").append(depth).append(",\"cls\":")
                .append(json(cls.substring(cls.lastIndexOf('.') + 1))).append(",\"compose\":").append(id);
        if (info.getViewIdResourceName() != null) {
            sb.append(",\"id\":").append(json(info.getViewIdResourceName()));
        }
        if (!info.isVisibleToUser()) {
            sb.append(",\"vis\":\"I\"");
        }
        sb.append(",\"b\":[").append(r.left).append(',').append(r.top).append(',').append(r.right).append(',')
                .append(r.bottom).append(']');
        appendText(sb, "text", info.getText());
        appendText(sb, "desc", info.getContentDescription());
        if (info.isClickable()) {
            sb.append(",\"click\":true");
        }
        if (info.isFocused()) {
            sb.append(",\"focus\":true");
        }
        sb.append('}');
    }

    /** Keeps only the shape View.populateVirtualStructure writes: each node's virtual id and its children. */
    private static final class Node extends ViewStructure {
        int id = AccessibilityNodeProvider.HOST_VIEW_ID;
        final List<Node> children = new ArrayList<>();

        @Override public void setId(int id, String packageName, String typeName, String entryName) { this.id = id; }
        @Override public void setChildCount(int num) {
            while (children.size() < num) {
                children.add(null);
            }
        }
        @Override public int addChildCount(int num) {
            int old = children.size();
            setChildCount(old + num);
            return old;
        }
        @Override public int getChildCount() { return children.size(); }
        @Override public ViewStructure newChild(int index) {
            setChildCount(index + 1);
            Node child = new Node();
            children.set(index, child);
            return child;
        }
        @Override public ViewStructure asyncNewChild(int index) { return newChild(index); }
        /** Hidden abstract method (not in the SDK stubs) that View.populateVirtualStructure calls. */
        public Rect getTempRect() { return tempRect; }
        private final Rect tempRect = new Rect();
        @Override public void asyncCommit() { }
        @Override public AutofillId getAutofillId() { return null; }
        @Override public Bundle getExtras() { return new Bundle(); }
        @Override public CharSequence getHint() { return null; }
        @Override public CharSequence getText() { return null; }
        @Override public int getTextSelectionEnd() { return -1; }
        @Override public int getTextSelectionStart() { return -1; }
        @Override public boolean hasExtras() { return false; }
        @Override public ViewStructure.HtmlInfo.Builder newHtmlInfoBuilder(String tagName) { return null; }
        @Override public void setAccessibilityFocused(boolean state) { }
        @Override public void setActivated(boolean state) { }
        @Override public void setAlpha(float alpha) { }
        @Override public void setAutofillHints(String[] hint) { }
        @Override public void setAutofillId(AutofillId id) { }
        @Override public void setAutofillId(AutofillId parentId, int virtualId) { }
        @Override public void setAutofillOptions(CharSequence[] options) { }
        @Override public void setAutofillType(int type) { }
        @Override public void setAutofillValue(AutofillValue value) { }
        @Override public void setCheckable(boolean state) { }
        @Override public void setChecked(boolean state) { }
        @Override public void setClassName(String className) { }
        @Override public void setClickable(boolean state) { }
        @Override public void setContentDescription(CharSequence contentDescription) { }
        @Override public void setContextClickable(boolean state) { }
        @Override public void setDataIsSensitive(boolean sensitive) { }
        @Override public void setDimens(int left, int top, int scrollX, int scrollY, int width, int height) { }
        @Override public void setElevation(float elevation) { }
        @Override public void setEnabled(boolean state) { }
        @Override public void setFocusable(boolean state) { }
        @Override public void setFocused(boolean state) { }
        @Override public void setHint(CharSequence hint) { }
        @Override public void setHtmlInfo(ViewStructure.HtmlInfo htmlInfo) { }
        @Override public void setInputType(int inputType) { }
        @Override public void setLocaleList(LocaleList localeList) { }
        @Override public void setLongClickable(boolean state) { }
        @Override public void setOpaque(boolean opaque) { }
        @Override public void setSelected(boolean state) { }
        @Override public void setText(CharSequence text) { }
        @Override public void setText(CharSequence text, int selectionStart, int selectionEnd) { }
        @Override public void setTextLines(int[] charOffsets, int[] baselines) { }
        @Override public void setTextStyle(float size, int fgColor, int bgColor, int style) { }
        @Override public void setTransformation(Matrix matrix) { }
        @Override public void setVisibility(int visibility) { }
        @Override public void setWebDomain(String domain) { }
    }

    private static void appendText(StringBuilder sb, String name, CharSequence text) {
        if (text != null && text.length() > 0) {
            sb.append(",\"").append(name).append("\":");
            appendTrimmed(sb, text.toString(), MAX_TEXT);
        }
    }

    private static void appendTrimmed(StringBuilder sb, String s, int max) {
        sb.append(json(s.length() > max ? s.substring(0, max) + "..." : s));
    }

    private static String simpleName(View v) {
        String name = v.getClass().getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** Resource entry name of the view's id, or null (no id, or a generated one). Main thread only. */
    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) {
            return null;
        }
        if (ID_NAMES.containsKey(id)) {
            return ID_NAMES.get(id);
        }
        String name = null;
        try {
            name = v.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            // generated id
        }
        ID_NAMES.put(id, name);
        return name;
    }

    // ---- /tap, /swipe ----

    private static String tap(Map<String, String> q) throws Exception {
        final String id = q.get("id");
        final String text = q.get("text");
        if ((id == null) == (text == null)) {
            throw new IllegalArgumentException("pass id or text");
        }
        final int index = intArg(q, "index", 0);
        final Object[] found = onMain(() -> {
            List<Object> hits = new ArrayList<>();
            String needle = text == null ? null : text.toLowerCase(Locale.ROOT);
            for (View root : roots()) {
                find(root, id, needle, hits);
            }
            if (index >= hits.size()) {
                throw new IllegalArgumentException(hits.size() + " shown views match, no index " + index);
            }
            Object target = hits.get(index);
            StringBuilder json = new StringBuilder();
            Rect r = new Rect();
            View root;
            if (target instanceof View) {
                View v = (View) target;
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                r.set(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
                root = v.getRootView();
                node(v, -1, 0, json);
            } else {
                Object[] c = (Object[]) target;
                AccessibilityNodeInfo info = (AccessibilityNodeInfo) c[0];
                info.getBoundsInScreen(r);
                root = ((View) c[3]).getRootView();
                virtualNode(info, (Integer) c[2], -1, 0, json);
            }
            int x = r.centerX();
            int y = r.centerY();
            StringBuilder sb = new StringBuilder("{\"matches\":").append(hits.size()).append(",\"x\":").append(x)
                    .append(",\"y\":").append(y).append(",\"target\":").append(json).append(",\"hit\":");
            node(deepestAt(root, x, y), -1, 0, sb);
            return new Object[]{root, x, y, sb.append('}').toString()};
        });
        View root = (View) found[0];
        int x = (Integer) found[1];
        int y = (Integer) found[2];
        long down = SystemClock.uptimeMillis();
        dispatch(root, down, MotionEvent.ACTION_DOWN, x, y);
        Thread.sleep(50);
        dispatch(root, down, MotionEvent.ACTION_UP, x, y);
        return (String) found[3];
    }

    /** Shown matches in /tree order: a View, or {info, depth, semantics id, host} for a Compose node. */
    private static void find(View v, String id, String needle, List<Object> hits) {
        if (!v.isShown()) {
            return;
        }
        if (v.getWidth() > 0 && v.getHeight() > 0
                && (id != null ? id.equals(idName(v)) : matches(v, needle))) {
            hits.add(v);
        }
        for (Object[] c : composeNodes(v)) {
            AccessibilityNodeInfo info = (AccessibilityNodeInfo) c[0];
            Rect r = new Rect();
            info.getBoundsInScreen(r);
            if (info.isVisibleToUser() && !r.isEmpty()
                    && (id != null ? id.equals(info.getViewIdResourceName()) : matches(info, needle))) {
                hits.add(new Object[]{info, c[1], c[2], v});
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                find(group.getChildAt(i), id, needle, hits);
            }
        }
    }

    /** The view a touch at screen (x, y) lands on, ignoring elevation and touch interception. */
    private static View deepestAt(View v, int x, int y) {
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child.isShown() && contains(child, x, y)) {
                    return deepestAt(child, x, y);
                }
            }
        }
        return v;
    }

    private static boolean contains(View v, int x, int y) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return x >= loc[0] && y >= loc[1] && x < loc[0] + v.getWidth() && y < loc[1] + v.getHeight();
    }

    private static String swipe(Map<String, String> q) throws Exception {
        final int x1 = intArg(q, "x1", -1);
        final int y1 = intArg(q, "y1", -1);
        int x2 = intArg(q, "x2", -1);
        int y2 = intArg(q, "y2", -1);
        int ms = intArg(q, "ms", 300);
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            throw new IllegalArgumentException("pass x1, y1, x2, y2 (screen px)");
        }
        // Topmost touchable window under the start point.
        View root = onMain(() -> {
            List<View> roots = roots();
            for (int i = roots.size() - 1; i >= 0; i--) {
                View r = roots.get(i);
                Object lp = r.getLayoutParams();
                boolean touchable = !(lp instanceof WindowManager.LayoutParams)
                        || (((WindowManager.LayoutParams) lp).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
                if (r.isShown() && touchable && contains(r, x1, y1)) {
                    return r;
                }
            }
            throw new IllegalArgumentException("no window at " + x1 + "," + y1);
        });
        int steps = Math.max(1, ms / 16);
        long down = SystemClock.uptimeMillis();
        dispatch(root, down, MotionEvent.ACTION_DOWN, x1, y1);
        for (int i = 1; i <= steps; i++) {
            Thread.sleep(ms / steps);
            dispatch(root, down, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps);
        }
        dispatch(root, down, MotionEvent.ACTION_UP, x2, y2);
        return "{\"window\":" + json(simpleName(root)) + ",\"steps\":" + steps + "}";
    }

    /**
     * A touch at screen (x, y) into the window's root. A DecorView hands it to its Window.Callback first, so
     * the mod's proxies (PlayerGestures, PanelBack) and the activity see it like a real one.
     */
    private static void dispatch(final View root, final long downTime, final int action, final int x, final int y)
            throws Exception {
        onMain(() -> {
            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            MotionEvent ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
            ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            // Raw stays on screen coordinates, x/y become window-local.
            ev.offsetLocation(-loc[0], -loc[1]);
            root.dispatchTouchEvent(ev);
            ev.recycle();
            return null;
        });
    }

    // ---- /toggles, /toggle, /irc, /state ----

    private static List<Toggles.Toggle> allToggles() throws Exception {
        List<Toggles.Toggle> all = new ArrayList<>();
        for (Field f : Toggles.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == Toggles.Toggle.class) {
                f.setAccessible(true);
                all.add((Toggles.Toggle) f.get(null));
            }
        }
        return all;
    }

    private static String toggles() throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (Toggles.Toggle t : allToggles()) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("{\"key\":").append(json(t.key)).append(",\"on\":").append(Toggles.on(t))
                    .append(",\"default\":").append(t.defaultOn).append(",\"restart\":").append(t.restart)
                    .append(",\"en\":").append(json(t.en)).append('}');
        }
        return sb.append(']').toString();
    }

    private static String toggle(Map<String, String> q) throws Exception {
        String key = q.get("key");
        String on = q.get("on");
        if (key == null || !("0".equals(on) || "1".equals(on))) {
            throw new IllegalArgumentException("pass key and on=0|1");
        }
        for (Toggles.Toggle t : allToggles()) {
            if (t.key.equals(key)) {
                boolean was = Toggles.on(t);
                Toggles.set(AppContext.get(), t, "1".equals(on));
                return "{\"key\":" + json(key) + ",\"was\":" + was + ",\"on\":" + Toggles.on(t) + ",\"restart\":" + t.restart + "}";
            }
        }
        throw new IllegalArgumentException("no toggle " + key);
    }

    private static String irc(Map<String, String> q) {
        String line = q.get("line");
        if (line == null || line.isEmpty()) {
            throw new IllegalArgumentException("pass line");
        }
        return "{\"queued\":" + JailChat.injectLine(line) + "}";
    }

    private static String state() {
        Object player = PlayerHook.currentPlayer();
        Activity activity = resumed.get();
        Boolean keyboard = null;
        if (activity != null && Build.VERSION.SDK_INT >= 30 && activity.getWindow() != null) {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            keyboard = insets != null && insets.isVisible(WindowInsets.Type.ime());
        }
        int orientation = AppContext.get().getResources().getConfiguration().orientation;
        return "{\"activity\":" + json(activity == null ? null : activity.getClass().getName())
                + ",\"orientation\":" + json(orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait")
                + ",\"keyboard\":" + keyboard
                + ",\"channel\":" + json(ChannelTracker.getCurrentChannelLogin())
                + ",\"channelId\":" + json(ChannelTracker.getCurrentChannelId())
                + ",\"vod\":" + json(ChannelTracker.getCurrentVodId())
                + ",\"theatre\":" + PlayerHook.theatreOpen()
                + ",\"player\":" + json(player == null ? null : PlayerStats.call(player, "getState") + ", "
                        + PlayerStats.snapshot(player)) + "}";
    }

    /**
     * /prefs: no file lists the mod's prefs files; file dumps one; key + value (type b|i|l|f|s, default s) or key +
     * remove=1 writes it first. Settings classes read prefs on use, so most writes act at once; restart:true toggles don't.
     */
    private static String prefs(Map<String, String> q) {
        Context context = AppContext.get();
        String file = q.get("file");
        if (file == null) {
            StringBuilder sb = new StringBuilder("[");
            String[] names = new java.io.File(context.getApplicationInfo().dataDir, "shared_prefs").list();
            for (String name : names == null ? new String[0] : names) {
                if (name.startsWith(PREFS_PREFIX) && name.endsWith(".xml")) {
                    sb.append(sb.length() > 1 ? "," : "").append(json(name.substring(0, name.length() - 4)));
                }
            }
            return sb.append(']').toString();
        }
        // The RTE login JWT lives there.
        if (!file.matches("retwitch_[a-z0-9_]+") || "retwitch_feedback".equals(file)) {
            throw new IllegalArgumentException("only retwitch_* prefs, not retwitch_feedback");
        }
        SharedPreferences prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE);
        String key = q.get("key");
        String value = q.get("value");
        if (key != null && "1".equals(q.get("remove"))) {
            prefs.edit().remove(key).commit();
        } else if (key != null && value != null) {
            SharedPreferences.Editor edit = prefs.edit();
            try {
                switch (q.containsKey("type") ? q.get("type") : "s") {
                    case "b": edit.putBoolean(key, Boolean.parseBoolean(value)); break;
                    case "i": edit.putInt(key, Integer.parseInt(value)); break;
                    case "l": edit.putLong(key, Long.parseLong(value)); break;
                    case "f": edit.putFloat(key, Float.parseFloat(value)); break;
                    case "s": edit.putString(key, value); break;
                    default: throw new IllegalArgumentException("type is b, i, l, f or s");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(value + " is not a number");
            }
            edit.commit();
        } else if (key != null) {
            throw new IllegalArgumentException("pass value (and type) or remove=1 with key");
        }
        StringBuilder values = new StringBuilder();
        StringBuilder types = new StringBuilder();
        for (Map.Entry<String, ?> e : new java.util.TreeMap<>(prefs.getAll()).entrySet()) {
            Object v = e.getValue();
            values.append(values.length() > 0 ? "," : "").append(json(e.getKey())).append(':')
                    .append(v instanceof String ? json((String) v) : v instanceof java.util.Set
                            ? new org.json.JSONArray((java.util.Set<?>) v).toString() : String.valueOf(v));
            types.append(types.length() > 0 ? "," : "").append(json(e.getKey())).append(':')
                    .append(json(v == null ? "null" : v.getClass().getSimpleName()));
        }
        return "{\"values\":{" + values + "},\"types\":{" + types + "}}";
    }

    /** /text: sets the text of the index-th shown EditText with that id, as if typed; nothing is sent. */
    private static String text(Map<String, String> q) throws Exception {
        String id = q.get("id");
        String value = q.get("value");
        if (id == null || value == null) {
            throw new IllegalArgumentException("pass id and value");
        }
        List<Object> hits = new ArrayList<>();
        for (View root : roots()) {
            find(root, id, null, hits);
        }
        int index = intArg(q, "index", 0);
        if (index >= hits.size() || !(hits.get(index) instanceof EditText)) {
            throw new IllegalArgumentException("no shown EditText #" + id + " at index " + index);
        }
        EditText field = (EditText) hits.get(index);
        field.setText(value);
        field.setSelection(field.length());
        StringBuilder sb = new StringBuilder();
        node(field, -1, 0, sb);
        return sb.toString();
    }

    // ---- helpers ----

    private static int intArg(Map<String, String> q, String name, int fallback) {
        String v = q.get(name);
        try {
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a number");
        }
    }

    private static String json(String s) {
        return s == null ? "null" : JSONObject.quote(s);
    }

    private static String error(String message) {
        return "{\"error\":" + json(message) + "}";
    }
}
