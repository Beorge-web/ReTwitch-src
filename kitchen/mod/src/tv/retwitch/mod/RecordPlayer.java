package tv.retwitch.mod;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import tv.retwitch.mod.emotes.EmoteCache;
import tv.retwitch.mod.emotes.ReplayChat;

/**
 * The mod's player for a recording ({@link Records}): IVS, the engine of Twitch's own theatre, plays the file
 * through {@link RecordServer} as an HLS VOD (exact length and seeking, .ts and fMP4 alike), and the recording's chat
 * ({@link ChatTape}) runs alongside at the video's time, drawn like the mod's live chat ({@link ReplayChat}).
 * Portrait: video on top, chat below; landscape: the chat as a column on the right (the theatre divider's width,
 * draggable when the divider is on), system bars hidden. Its own activity ({@link RecordPlayerActivity}): leaving it
 * while playing goes picture-in-picture, otherwise the video pauses. The mod's player gestures and seek steps apply
 * as in Twitch's player. The place watched to is kept per recording.
 */
final class RecordPlayer {

    private static final String TAG = "RTRecordPlayer";
    private static final String PLAYER = "com.amazonaws.ivs.player.Player";
    private static final String WATCHED = "watched";
    /** Chat rows kept on screen. */
    private static final int WINDOW = 200;
    private static final long[] DELAYS = {0, 2000, 5000, 10_000, -5000, -2000};
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f};
    private static final int BG = 0xFF0E0E10;
    private static final int FG = 0xFFEFEFF1;
    private static final int DIM = 0xFFADADB8;
    private static final int ACCENT = 0xFF9147FF;
    private static final String PIP_PLAY_PAUSE = "tv.retwitch.mod.RECORD_PLAY_PAUSE";
    /** Share of the video's width on each side that takes brightness and volume, as in {@link PlayerGestures}. */
    private static final float SIDE_ZONE = 0.35f;
    /** The open one: a second recording replaces it, one voice at a time. */
    private static RecordPlayer current;

    private final Activity activity;
    private final Context context;
    private final JSONObject meta;
    private final File dir;
    private final String channelId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Records.Index index;
    private boolean closed;
    private boolean started;
    private boolean pip;
    /** The play state the picture-in-picture params were last set for, null before the first. */
    private Boolean pipPlaying;
    private BroadcastReceiver pipReceiver;
    private View playerView;
    private Object player;
    private LinearLayout split;
    private FrameLayout videoBox;
    private View overlay;
    private ImageView playButton;
    private ImageView chatButton;
    private TextView timeView;
    private TextView delayChip;
    private TextView speedChip;
    private SeekBar seek;
    private Heat heat;
    private View handle;
    private int chatPercent = TheatreSettings.getSideChatWidthPercent();
    private LinearLayout chatBox;
    private TextView chatHeader;
    private ListView list;
    private final List<Row> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private volatile Chat chat;
    private RandomAccessFile chatFile;
    private int shown;
    /** The next update rebuilds the window: a seek or a new delay. */
    private boolean jump;
    private int delay;
    private int speed;
    private boolean chatVisible = true;
    private boolean dragging;
    private long ticks;
    private final Runnable tick = this::tick;
    private final Runnable hide = () -> showOverlay(false);

    static boolean canPlay(Context context, JSONObject meta) {
        String kind = meta.optString("kind");
        return !kind.isEmpty() && !Records.KIND_LEGACY.equals(kind)
                && new File(Records.dir(context, meta.optString("id")), Records.SEGMENTS).length() > 0;
    }

    static void show(Context context, JSONObject meta) {
        Intent intent = new Intent(context, RecordPlayerActivity.class)
                .putExtra(RecordPlayerActivity.EXTRA_ID, meta.optString("id"));
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    /** From {@link RecordPlayerActivity#onCreate}: the player built into it, or null (said in a toast) to finish it. */
    static RecordPlayer attach(Activity activity, String id) {
        try {
            JSONObject meta = id == null ? null : Records.read(Records.dir(activity, id));
            if (meta == null || !canPlay(activity, meta)) {
                throw new IOException("no recording " + id);
            }
            if (current != null) {
                current.activity.finish();
            }
            RecordPlayer player = new RecordPlayer(activity, meta);
            current = player;
            player.open();
            return player;
        } catch (Throwable t) {
            Log.w(TAG, "player failed", t);
            CrashReporter.report(TAG, "player failed", t);
            Toast.makeText(activity, Strings.pick("Плеер не открылся", "The player didn't open"), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private RecordPlayer(Activity activity, JSONObject meta) {
        this.activity = activity;
        this.context = activity;
        this.meta = meta;
        this.dir = Records.dir(context, meta.optString("id"));
        this.channelId = meta.optString("channelId", null);
        this.index = Records.index(dir);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    // ---- building ----

    private void open() throws Exception {
        FrameLayout root = new FrameLayout(context) {
            @Override
            protected void onConfigurationChanged(Configuration config) {
                super.onConfigurationChanged(config);
                arrange();
            }
        };
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        split = new LinearLayout(context);
        root.addView(split, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        videoBox = new FrameLayout(context) {
            @Override
            protected void onMeasure(int width, int height) {
                if (!landscape()) {
                    height = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(width) * 9 / 16, MeasureSpec.EXACTLY);
                }
                super.onMeasure(width, height);
            }
        };
        videoBox.setBackgroundColor(Color.BLACK);
        split.addView(videoBox);
        buildVideo();
        buildChat();
        handle = buildHandle();
        split.addView(handle);
        split.addView(chatBox);

        activity.setContentView(root);
        Window window = activity.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(BG);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        listenPip();
        arrange();
        start();
        loadChat();
    }

    private void buildVideo() throws Exception {
        playerView = (View) Class.forName("com.amazonaws.ivs.player.PlayerView").getConstructor(Context.class)
                .newInstance(context);
        playerView.getClass().getMethod("setControlsEnabled", boolean.class).invoke(playerView, false);
        player = playerView.getClass().getMethod("getPlayer").invoke(playerView);
        PlayerHook.setReplay(player);
        videoBox.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        Gestures gestures = new Gestures();
        View catcher = new View(context);
        catcher.setOnClickListener(v -> showOverlay(overlay.getVisibility() != View.VISIBLE));
        catcher.setOnTouchListener(gestures);
        videoBox.addView(catcher, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout layer = new FrameLayout(context);
        layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x22000000, 0x22000000, 0xB3000000}));
        layer.setOnClickListener(v -> showOverlay(false));
        layer.setOnTouchListener(gestures);
        overlay = layer;

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(4), dp(4), 0);
        top.addView(icon("ic_arrow_back_black_24", 24, Strings.pick("Назад", "Back"), v -> activity.finish()));
        TextView title = text(title(), 14, Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        delayChip = chip(v -> {
            delay = (delay + 1) % DELAYS.length;
            delayChip.setText(delayLabel());
            rebuildChat();
        });
        delayChip.setText(delayLabel());
        top.addView(delayChip);
        speedChip = chip(v -> {
            speed = (speed + 1) % SPEEDS.length;
            speedChip.setText(speedLabel());
            call("setPlaybackRate", new Class<?>[]{float.class}, SPEEDS[speed]);
        });
        speedChip.setText(speedLabel());
        top.addView(speedChip);
        chatButton = icon("ic_chat_hide", 22, Strings.pick("Чат", "Chat"), v -> {
            chatVisible = !chatVisible;
            arrange();
        });
        top.addView(chatButton);
        top.addView(icon("ic_rotate", 22, Strings.pick("Повернуть", "Rotate"), v -> rotate()));
        layer.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout middle = new LinearLayout(context);
        middle.setGravity(Gravity.CENTER);
        middle.addView(seekIcon(false));
        playButton = icon("ic_pause", 44, Strings.pick("Пауза", "Pause"), v -> togglePlay());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        playLp.leftMargin = dp(28);
        playLp.rightMargin = dp(28);
        middle.addView(playButton, playLp);
        middle.addView(seekIcon(true));
        layer.addView(middle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout bottom = new LinearLayout(context);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(12), 0, dp(12), dp(4));
        timeView = text("00:00", 12, Color.WHITE);
        bottom.addView(timeView);
        LinearLayout track = new LinearLayout(context);
        track.setOrientation(LinearLayout.VERTICAL);
        seek = new SeekBar(context);
        seek.setMax(Math.max(1, (int) index.total));
        seek.getProgressDrawable().setTint(ACCENT);
        seek.getThumb().setTint(Color.WHITE);
        heat = new Heat();
        heat.setPadding(seek.getPaddingLeft(), 0, seek.getPaddingRight(), 0);
        track.addView(heat, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        track.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    timeView.setText(LiveRecord.clock(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
                main.removeCallbacks(hide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                seekTo(bar.getProgress() * 1000L);
                scheduleHide();
            }
        });
        bottom.addView(track, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(text(LiveRecord.clock(index.total), 12, Color.WHITE));
        layer.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        videoBox.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildChat() {
        chatBox = new LinearLayout(context);
        chatBox.setOrientation(LinearLayout.VERTICAL);
        chatBox.setBackgroundColor(BG);
        chatHeader = text(Strings.pick("Чат записи", "Recording chat"), 13, DIM);
        chatHeader.setPadding(dp(12), dp(8), dp(12), dp(8));
        chatBox.addView(chatHeader);
        View rule = new View(context);
        rule.setBackgroundColor(0xFF2E2E35);
        chatBox.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        list = new ListView(context);
        list.setDivider(null);
        list.setStackFromBottom(true);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setAdapter(adapter);
        chatBox.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** The mod's seek steps (Player settings), shown on the buttons like in Twitch's VOD player ({@link VodSeek}). */
    private ImageView seekIcon(boolean forward) {
        int seconds = forward ? PlayerSettings.getSeekForwardSeconds() : PlayerSettings.getSeekBackSeconds();
        ImageView view = icon(forward ? "ic_forward_30" : "ic_rewind_10", 32, Strings.seekStep(forward, seconds),
                v -> seekBy((forward ? seconds : -seconds) * 1000L));
        VodSeek.showStep(view, forward);
        view.getDrawable().setTint(Color.WHITE);
        return view;
    }

    /** Between video and chat in landscape: dragging it sets the side chat's width, shared with Twitch's theatre. */
    private View buildHandle() {
        FrameLayout strip = new FrameLayout(context);
        strip.setBackgroundColor(BG);
        View grip = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(DIM);
        shape.setCornerRadius(dp(2));
        grip.setBackground(shape);
        strip.addView(grip, new FrameLayout.LayoutParams(dp(3), dp(36), Gravity.CENTER));
        strip.setOnTouchListener((v, ev) -> {
            int[] at = new int[2];
            split.getLocationOnScreen(at);
            int width = at[0] + split.getWidth() - Math.round(ev.getRawX());
            int percent = TheatreSettings.clamp(Math.round(width * 100f / TheatreDividerHook.chatWidthPx(100)));
            if (percent != chatPercent) {
                chatPercent = percent;
                arrange();
            }
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                TheatreSettings.setSideChatWidthPercent(context, chatPercent);
            }
            return true;
        });
        return strip;
    }

    private String title() {
        String display = meta.optString("display");
        String title = meta.optString("title");
        return title.isEmpty() ? display : display + " - " + title;
    }

    private boolean landscape() {
        return context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Portrait: video over chat; landscape: video beside the chat column (the theatre divider's width, of the long
     * edge like there), without system bars; picture-in-picture: the video alone.
     */
    private void arrange() {
        boolean wide = landscape() || pip;
        split.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        boolean chatShown = !pip && chatVisible && (!wide || chat == null || chat.count > 0);
        chatBox.setVisibility(chatShown ? View.VISIBLE : View.GONE);
        handle.setVisibility(wide && chatShown && TheatreSettings.isSideChatDividerEnabled() ? View.VISIBLE : View.GONE);
        chatHeader.setVisibility(wide ? View.GONE : View.VISIBLE);
        videoBox.setLayoutParams(wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        handle.setLayoutParams(new LinearLayout.LayoutParams(dp(12), ViewGroup.LayoutParams.MATCH_PARENT));
        chatBox.setLayoutParams(wide ? new LinearLayout.LayoutParams(TheatreDividerHook.chatWidthPx(chatPercent),
                ViewGroup.LayoutParams.MATCH_PARENT) : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setIcon(chatButton, chatVisible ? "ic_chat_hide" : "ic_chat_show");
        activity.getWindow().getDecorView().setSystemUiVisibility(wide ? View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0);
    }

    private void rotate() {
        activity.setRequestedOrientation(landscape() ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    // ---- playback ----

    private void start() throws IOException {
        Uri file = Uri.parse(meta.optString("uri"));
        boolean fmp4 = meta.optString("name").endsWith(".mp4");
        Uri hls = RecordServer.publish(meta.optString("id"), file, index, meta.optLong("initBytes"), fmp4);
        Object twitch = PlayerHook.currentPlayer();
        if (twitch != null && twitch != player) {
            invoke(twitch, "pause", new Class<?>[0]); // one voice at a time
        }
        call("load", new Class<?>[]{Uri.class}, hls);
        long watched = watched();
        if (watched > 10_000 && watched < (index.total - 10) * 1000) {
            call("seekTo", new Class<?>[]{long.class}, watched);
        }
        call("play", new Class<?>[0]);
        showOverlay(true);
        main.post(tick);
    }

    private void tick() {
        if (closed) {
            return;
        }
        long position = position();
        if (!dragging) {
            seek.setProgress((int) (position / 1000));
            timeView.setText(LiveRecord.clock(position / 1000.0));
        }
        boolean playing = playing();
        setIcon(playButton, playing ? "ic_pause" : "ic_play_arrow");
        if (!Boolean.valueOf(playing).equals(pipPlaying)) {
            pipPlaying = playing;
            PipGuard.setParams(activity, pipParams(playing));
        }
        showChat(position - DELAYS[delay]);
        if (++ticks % 4 == 0) {
            refreshRows();
        }
        main.postDelayed(tick, 250);
    }

    private long position() {
        Object value = call("getPosition", new Class<?>[0]);
        return value instanceof Long ? (Long) value : 0;
    }

    private boolean playing() {
        Object state = call("getState", new Class<?>[0]);
        return state != null && ("PLAYING".equals(state.toString()) || "BUFFERING".equals(state.toString()));
    }

    private void togglePlay() {
        call(playing() ? "pause" : "play", new Class<?>[0]);
        scheduleHide();
    }

    private void seekBy(long ms) {
        seekTo(Math.max(0, Math.min((long) (index.total * 1000) - 1000, position() + ms)));
        scheduleHide();
    }

    private void seekTo(long ms) {
        call("seekTo", new Class<?>[]{long.class}, ms);
        rebuildChat();
    }

    private void showOverlay(boolean show) {
        overlay.setVisibility(show ? View.VISIBLE : View.GONE);
        main.removeCallbacks(hide);
        if (show) {
            scheduleHide();
        }
    }

    private void scheduleHide() {
        main.removeCallbacks(hide);
        main.postDelayed(hide, 3500);
    }

    /** A method of IVS's public Player interface: its implementation class isn't public. */
    private Object call(String name, Class<?>[] types, Object... args) {
        return invoke(player, name, types, args);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = Class.forName(PLAYER).getMethod(name, types);
            return method.invoke(target, args);
        } catch (Throwable t) {
            Log.w(TAG, name + " failed: " + t);
            return null;
        }
    }

    // ---- leaving, picture-in-picture ----

    /** Only while playing, like other players; from Android 12 the auto-enter flag of {@link #pipParams} does it. */
    void onLeave() {
        if (Build.VERSION.SDK_INT < 31 && playing()) {
            try {
                activity.enterPictureInPictureMode(pipParams(true));
            } catch (Throwable t) {
                Log.w(TAG, "picture-in-picture refused: " + t);
            }
        }
    }

    void onPip(boolean pip) {
        this.pip = pip;
        if (pip) {
            showOverlay(false);
        } else if (!started) {
            // The window's close button: the activity stops first, then leaves picture-in-picture.
            activity.finishAndRemoveTask();
            return;
        }
        arrange();
    }

    void onStarted(boolean started) {
        this.started = started;
        if (!started && !activity.isInPictureInPictureMode()) {
            call("pause", new Class<?>[0]); // in the background without a picture: nothing to watch
        }
    }

    private PictureInPictureParams pipParams(boolean playing) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
        Rect video = new Rect();
        if (videoBox.getGlobalVisibleRect(video)) {
            builder.setSourceRectHint(video);
        }
        int icon = UiHider.res(context, playing ? "ic_pause" : "ic_play_arrow", "drawable");
        if (icon != 0) {
            String label = playing ? Strings.pick("Пауза", "Pause") : Strings.pick("Продолжить", "Resume");
            Icon image = Icon.createWithResource(context, icon).setTint(Color.WHITE);
            PendingIntent intent = PendingIntent.getBroadcast(context, 0,
                    new Intent(PIP_PLAY_PAUSE).setPackage(context.getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.setActions(Collections.singletonList(new RemoteAction(image, label, label, intent)));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(playing);
        }
        return builder.build();
    }

    /** The play/pause button of the picture-in-picture window. */
    private void listenPip() {
        pipReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                togglePlay();
            }
        };
        IntentFilter filter = new IntentFilter(PIP_PLAY_PAUSE);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(pipReceiver, filter);
        }
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (current == this) {
            current = null;
        }
        main.removeCallbacksAndMessages(null);
        saveWatched(position());
        try {
            activity.unregisterReceiver(pipReceiver);
        } catch (Throwable ignored) {
            // not registered: open() failed before
        }
        try {
            call("release", new Class<?>[0]);
        } finally {
            PlayerHook.setReplay(null);
            RecordServer.unpublish(meta.optString("id"));
            try {
                if (chatFile != null) {
                    chatFile.close();
                }
            } catch (IOException ignored) {
                // closed with the player
            }
        }
    }

    private long watched() {
        try (InputStream in = new FileInputStream(new File(dir, WATCHED))) {
            byte[] bytes = new byte[32];
            int n = in.read(bytes);
            return n > 0 ? Long.parseLong(new String(bytes, 0, n, StandardCharsets.US_ASCII).trim()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Its own file: meta.json belongs to the recorder. */
    private void saveWatched(long ms) {
        try (FileOutputStream out = new FileOutputStream(new File(dir, WATCHED))) {
            out.write(Long.toString(ms).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Log.w(TAG, "position not saved: " + e);
        }
    }

    // ---- chat ----

    /** Clocks of the chat's lines on the file's time, earliest first, with where each line is in chat.jsonl. */
    private static final class Chat {
        long[] ms;
        long[] offset;
        int[] length;
        int count;
    }

    private void loadChat() {
        final File file = new File(dir, Records.CHAT);
        new Thread(() -> {
            try {
                if (channelId != null) {
                    EmoteCache.ensureChannelLoaded(channelId);
                    ReplayChat.setBadges(VodDownload.gql("query($s:ID!){badges{setID version imageURL(size:DOUBLE)} "
                            + "user(id:$s){broadcastBadges{setID version imageURL(size:DOUBLE)}}}", channelId));
                }
            } catch (Throwable t) {
                Log.w(TAG, "badges failed: " + t);
            }
            Chat loaded = null;
            try {
                loaded = file.length() > 0 ? read(file, index) : null;
            } catch (Throwable t) {
                Log.w(TAG, "chat unreadable", t);
            }
            final Chat done = loaded;
            main.post(() -> {
                if (closed) {
                    return;
                }
                try {
                    if (done != null && done.count > 0) {
                        chatFile = new RandomAccessFile(file, "r");
                        chat = done;
                        chatHeader.setText(Strings.pickf("Чат записи · сообщений: %d", "Recording chat · %d messages",
                                done.count));
                        heat.set(done, index.total);
                    } else {
                        chatHeader.setText(Strings.pick("Чат с этой записью не сохранялся. Включите «Записывать чат» "
                                        + "в «Запись и скачивание»: со следующей записи он будет здесь.",
                                "This recording has no chat. Turn on \"Record the chat\" in Recording and downloads: "
                                        + "from the next recording it shows here."));
                        chatHeader.setVisibility(View.VISIBLE);
                    }
                    arrange();
                } catch (IOException e) {
                    Log.w(TAG, "chat not opened: " + e);
                }
            });
        }, "RTRecordChat").start();
    }

    private static Chat read(File file, Records.Index index) throws IOException {
        long[] ms = new long[1024];
        long[] offset = new long[1024];
        int[] length = new int[1024];
        int n = 0;
        byte[] head = new byte[32];
        int headLength = 0;
        long start = 0;
        long at = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 16)) {
            for (int c; (c = in.read()) >= 0 && n < Records.MAX_CHAT_LINES; at++) {
                if (c != '\n') {
                    if (headLength < head.length) {
                        head[headLength++] = (byte) c;
                    }
                    continue;
                }
                double seconds = Records.fileSeconds(index, Records.lineClock(head, headLength));
                if (Records.lineClock(head, headLength) != Long.MIN_VALUE && seconds >= 0) {
                    if (n == ms.length) {
                        ms = Arrays.copyOf(ms, n * 2);
                        offset = Arrays.copyOf(offset, n * 2);
                        length = Arrays.copyOf(length, n * 2);
                    }
                    ms[n] = Math.round(seconds * 1000);
                    offset[n] = start;
                    length[n] = (int) (at - start);
                    n++;
                }
                start = at + 1;
                headLength = 0;
            }
        }
        int[] order = Records.order(ms, n);
        Chat chat = new Chat();
        chat.ms = new long[n];
        chat.offset = new long[n];
        chat.length = new int[n];
        for (int i = 0; i < n; i++) {
            chat.ms[i] = ms[order[i]];
            chat.offset[i] = offset[order[i]];
            chat.length[i] = length[order[i]];
        }
        chat.count = n;
        return chat;
    }

    private static final class Row {
        final JSONObject message;
        final long born = System.currentTimeMillis();
        ReplayChat.Line line;

        Row(JSONObject message) {
            this.message = message;
        }
    }

    private int badgePx() {
        return dp(18 * ChatSettings.getFontScale() / 100f);
    }

    private Row row(int i) {
        try {
            byte[] bytes = new byte[chat.length[i]];
            chatFile.seek(chat.offset[i]);
            chatFile.readFully(bytes);
            Row row = new Row(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
            row.line = ReplayChat.build(row.message, badgePx());
            return row;
        } catch (Exception e) {
            Log.w(TAG, "chat line " + i + ": " + e);
            return null;
        }
    }

    /** The lines up to the video's time: new ones appended, a jump (seek, delay) rebuilds the window. */
    private void showChat(long t) {
        Chat c = chat;
        if (c == null) {
            return;
        }
        int end = Records.upTo(c.ms, c.count, t);
        if (end == shown && !jump) {
            return;
        }
        boolean atBottom = rows.isEmpty() || list.getLastVisiblePosition() >= rows.size() - 2;
        if (!jump && end > shown && end - shown <= WINDOW) {
            for (int i = shown; i < end; i++) {
                add(row(i));
            }
            while (rows.size() > WINDOW) {
                rows.remove(0);
            }
        } else {
            rows.clear();
            for (int i = Math.max(0, end - WINDOW / 2); i < end; i++) {
                add(row(i));
            }
            atBottom = true;
        }
        jump = false;
        shown = end;
        adapter.notifyDataSetChanged();
        if (atBottom && !rows.isEmpty()) {
            list.setSelection(rows.size() - 1);
        }
    }

    private void add(Row row) {
        if (row != null) {
            rows.add(row);
        }
    }

    private void rebuildChat() {
        jump = true;
    }

    /** Rows whose images were still downloading, and fresh ones (third-party emotes load late), drawn again. */
    private void refreshRows() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        int first = Math.max(0, list.getFirstVisiblePosition());
        int last = Math.min(rows.size() - 1, list.getLastVisiblePosition());
        for (int i = first; i <= last; i++) {
            Row row = rows.get(i);
            if (row.line.missing || now - row.born < 10_000) {
                row.line = ReplayChat.build(row.message, badgePx());
                changed = true;
            }
        }
        if (changed) {
            adapter.notifyDataSetChanged();
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            TextView view = convert instanceof TextView ? (TextView) convert : rowView();
            ReplayChat.bind(view, rows.get(position).line.text, channelId);
            return view;
        }
    }

    private TextView rowView() {
        TextView view = text("", 14 * ChatSettings.getFontScale() / 100f, FG);
        view.setPadding(dp(12), dp(3), dp(12), dp(3));
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private String delayLabel() {
        long ms = DELAYS[delay];
        return ms == 0 ? Strings.pick("чат ±0 с", "chat ±0 s")
                : Strings.pickf("чат %s с", "chat %s s", (ms > 0 ? "+" : "−") + Math.abs(ms) / 1000);
    }

    private String speedLabel() {
        float value = SPEEDS[speed];
        return (value == (int) value ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.2f", value)
                .replaceAll("0$", "")) + "x";
    }

    // ---- small views ----

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    private TextView chip(View.OnClickListener click) {
        TextView chip = text("", 12, Color.WHITE);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0x8C000000);
        shape.setStroke(1, 0x40FFFFFF);
        shape.setCornerRadius(dp(12));
        chip.setBackground(shape);
        chip.setOnClickListener(v -> {
            click.onClick(v);
            scheduleHide();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private ImageView icon(String name, int sizeDp, String description, View.OnClickListener click) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        setIcon(view, name);
        int pad = dp(10);
        view.setPadding(pad, pad, pad, pad);
        view.setContentDescription(description);
        view.setOnClickListener(click);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp) + 2 * pad, dp(sizeDp) + 2 * pad));
        return view;
    }

    private void setIcon(ImageView view, String name) {
        if (view == null || name.equals(view.getTag())) {
            return;
        }
        int id = UiHider.res(context, name, "drawable");
        if (id != 0) {
            Drawable drawable = context.getDrawable(id).mutate();
            drawable.setTint(Color.WHITE);
            view.setImageDrawable(drawable);
            view.setTag(name);
        }
    }

    /**
     * The mod's player gestures over the video, by the same toggles as in Twitch's player ({@link PlayerGestures}):
     * in landscape brightness and volume along the edges, and a swipe up on the portrait video / down in the middle
     * in landscape turns the screen. A taken drag is cancelled for the view under it, so its tap doesn't fire.
     */
    private final class Gestures implements View.OnTouchListener {
        private static final int NONE = 0;
        private static final int BRIGHTNESS = 1;
        private static final int VOLUME = 2;
        private static final int ROTATE = 3;
        private final int slop = ViewConfiguration.get(context).getScaledTouchSlop() * 2;
        private int mode;
        private boolean armed;
        private float startX;
        private float startY;
        private float startValue;
        private PlayerGestures.Badge badge;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    armed = false;
                    startX = ev.getRawX();
                    startY = ev.getRawY();
                    boolean wide = landscape();
                    float x = ev.getX() / Math.max(1, v.getWidth());
                    boolean side = x <= SIDE_ZONE || x >= 1 - SIDE_ZONE;
                    boolean rotate = Toggles.on(Toggles.PLAYER_SWIPE_FULLSCREEN);
                    if (wide && side) {
                        mode = !Toggles.on(Toggles.PLAYER_GESTURES) ? NONE : x <= SIDE_ZONE ? BRIGHTNESS : VOLUME;
                        startValue = mode == BRIGHTNESS ? PlayerGestures.brightness(activity.getWindow())
                                : PlayerGestures.volume(context);
                    } else {
                        mode = rotate ? ROTATE : NONE;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (mode == NONE) {
                        return armed;
                    }
                    float dy = startY - ev.getRawY();
                    if (!armed) {
                        if (Math.abs(dy) < slop || Math.abs(dy) < Math.abs(ev.getRawX() - startX) * 2) {
                            return false;
                        }
                        if (mode == ROTATE && dy > 0 == landscape()) {
                            mode = NONE; // up in landscape, down in portrait: nothing
                            return false;
                        }
                        armed = true;
                        MotionEvent cancel = MotionEvent.obtain(ev);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        v.onTouchEvent(cancel);
                        cancel.recycle();
                    }
                    if (mode == ROTATE) {
                        mode = NONE;
                        rotate();
                        return true;
                    }
                    float value = PlayerGestures.clamp(startValue + dy / Math.max(1, v.getHeight()));
                    if (mode == BRIGHTNESS) {
                        PlayerGestures.setBrightness(activity.getWindow(), value);
                    } else {
                        PlayerGestures.setVolume(context, value);
                    }
                    if (badge == null) {
                        badge = new PlayerGestures.Badge(videoBox);
                    }
                    badge.show(mode == BRIGHTNESS, value);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean consumed = armed;
                    armed = false;
                    mode = NONE;
                    if (consumed && badge != null) {
                        badge.hideLater();
                    }
                    return consumed;
                default:
                    return armed;
            }
        }
    }

    /** How busy the chat was along the recording, as bars over the seek bar. */
    private final class Heat extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] buckets = new int[0];
        private int max;

        Heat() {
            super(RecordPlayer.this.context);
            paint.setColor(0x8CBF94FF);
        }

        void set(Chat chat, double total) {
            buckets = new int[100];
            for (int i = 0; i < chat.count && total > 0; i++) {
                buckets[(int) Math.min(buckets.length - 1, chat.ms[i] / (total * 1000) * buckets.length)]++;
            }
            max = 1;
            for (int b : buckets) {
                max = Math.max(max, b);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth() - getPaddingLeft() - getPaddingRight();
            if (buckets.length == 0 || width <= 0) {
                return;
            }
            float step = width / buckets.length;
            for (int i = 0; i < buckets.length; i++) {
                float h = getHeight() * buckets[i] / (float) max;
                float x = getPaddingLeft() + i * step;
                canvas.drawRect(x, getHeight() - h, x + step - 1, getHeight(), paint);
            }
        }
    }
}
