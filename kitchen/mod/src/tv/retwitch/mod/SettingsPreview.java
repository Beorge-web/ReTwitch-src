package tv.retwitch.mod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;

/**
 * A drawn mock-up of what a setting changes. A scene is a small piece of Twitch (the player, chat rows,
 * the chat header...) laid out in its own units and scaled to the view. {@link #state} holds the options
 * that are on; {@link #focus} is the option being explained: its element gets a ring while it is there
 * and a dashed outline once it is gone. "Before" and "after" are two views differing in {@link #state};
 * a page's live preview follows the real settings instead and has no focus.
 *
 * <p>The flags are per scene. They are compile-time constants, so {@link Toggles} can name them without
 * loading this class from the hooks.
 */
final class SettingsPreview extends View {

    static final int PLAYER = 1, CHAT = 2, HEADER = 3, BANNERS = 4, INPUT = 5, HOME = 6, PICKER = 7, VODS = 8,
            MENU = 9, VERTICAL = 10;

    static final long P_CAST = 1L, P_SHARE = 1L << 1, P_CLIP = 1L << 2, P_FULLSCREEN = 1L << 3, P_FOLLOW = 1L << 4,
            P_UNFOLLOW = 1L << 5, P_MATURE = 1L << 6, P_MODE_POPUP = 1L << 7, P_RELOAD = 1L << 8, P_TIMER = 1L << 9,
            P_COMPRESSOR = 1L << 10, P_STATS = 1L << 11, P_CHAPTERS = 1L << 12, P_DOWNLOAD = 1L << 13,
            P_CHAT_SYNC = 1L << 14, P_MIRROR = 1L << 15, P_LATENCY = 1L << 16, P_AMBILIGHT = 1L << 17, P_AD = 1L << 18,
            P_AD_LABEL = 1L << 19, P_UP_NEXT = 1L << 20, P_DANMAKU = 1L << 21, P_DANMAKU_NICKS = 1L << 22,
            P_GESTURES = 1L << 23, P_CUTOUT = 1L << 24, P_DIVIDER = 1L << 25, P_WIDE_PANEL = 1L << 26,
            P_CHATTERS = 1L << 27, P_LAYOUT = 1L << 28, P_RECORD = 1L << 29, P_REC_LABEL = 1L << 30, P_ROTATE = 1L << 31,
            P_MUTE = 1L << 32, P_BATTERY = 1L << 33;

    static final long C_ALT = 1L, C_ME = 1L << 1, C_FIRST = 1L << 2, C_PAINT = 1L << 3, C_RTE = 1L << 4,
            C_BADGE_FFZ = 1L << 5, C_BADGE_CHATTERINO = 1L << 6, C_BADGE_DANKCHAT = 1L << 7, C_BADGE_CHATSEN = 1L << 8,
            C_BADGE_HOMIES = 1L << 9, C_COLOR_MENTION = 1L << 10, C_HIGHLIGHT = 1L << 11, C_BOTS = 1L << 12,
            C_GIFS = 1L << 13, C_ANNOUNCE = 1L << 14, C_SUBS = 1L << 15, C_REDEEM = 1L << 16, C_STREAK = 1L << 17,
            C_SERVICE = 1L << 18, C_WORDS = 1L << 19, C_USERS = 1L << 20, C_MOD_LOG = 1L << 21, C_NO_CLEAR = 1L << 22,
            C_AUTOMOD = 1L << 23, C_RAID = 1L << 24, C_7TV = 1L << 25, C_BTTV = 1L << 26, C_FFZ = 1L << 27,
            C_HOMIES = 1L << 28, C_MODIFIERS = 1L << 29, C_GIANT = 1L << 30, C_REALTIME = 1L << 31,
            C_FADE = 1L << 32, C_LINK = 1L << 33, C_DELETED = 1L << 34, C_REPLY = 1L << 35,
            C_DELETED_BG = 1L << 36, C_NEW_COUNT = 1L << 37;

    static final long T_TITLE = 1L, T_BUTTONS = 1L << 1, T_GIFT = 1L << 2, T_EXTENSIONS = 1L << 3,
            T_LEADERBOARD = 1L << 4, T_REPLAY = 1L << 5;

    static final long B_HYPE = 1L, B_POLL = 1L << 1, B_RAID = 1L << 2, B_SHOUTOUT = 1L << 3, B_DROPS = 1L << 4,
            B_GIFTS = 1L << 5, B_SHARED = 1L << 6, B_PAID_PIN = 1L << 7, B_PIN = 1L << 8;

    static final long I_BITS = 1L, I_SETTINGS = 1L << 1, I_BALANCE = 1L << 2, I_LATENCY = 1L << 3, I_IMAGES = 1L << 4,
            I_FULLSCREEN = 1L << 5, I_OVERLAY = 1L << 6, I_NO_COLON = 1L << 7, I_CURSOR = 1L << 8, I_RAISED = 1L << 9,
            I_EMOTE_IDLE = 1L << 10, I_BALANCE_UNDER = 1L << 11, I_POINTS_PORTRAIT = 1L << 12, I_POINTS_LANDSCAPE = 1L << 13;

    static final long H_FOLLOWING = 1L, H_BROWSE = 1L << 1, H_ACTIVITY = 1L << 2, H_CREATE = 1L << 3,
            H_CATEGORIES = 1L << 4, H_CONTINUE = 1L << 5, H_OFFLINE = 1L << 6, H_STREAK = 1L << 7,
            H_COMPACT = 1L << 8, H_SHELF_FOLLOWED = 1L << 9, H_SHELF_CLIPS = 1L << 10, H_ADS = 1L << 11,
            H_PROMO = 1L << 12, H_FRIENDS = 1L << 13, H_PREVIEW_SIZE = 1L << 14, H_FAVORITES = 1L << 15;

    static final long K_FAVORITES = 1L, K_FREQUENT = 1L << 1, K_STICKY = 1L << 2, K_FIRST = 1L << 3, K_NO_GLOBAL = 1L << 4;

    static final long V_DATES = 1L, V_FILTER = 1L << 1, V_SUB = 1L << 2;

    static final long M_COPY_SEND = 1L, M_HISTORY = 1L << 1, M_LINK = 1L << 2, M_MOD = 1L << 3;

    static final long W_BUTTONS = 1L, W_PANEL = 1L << 1;

    /** Slots of {@link #values}: what a pick-one option or a slider feeds into the scene. */
    static final int VALUE_TIME = 0, VALUE_NAME = 1, VALUE_FONT = 2, VALUE_DELETED = 3, VALUE_EMOTE = 4,
            VALUE_MENTION = 5, VALUE_PINNED = 6, VALUE_LINK = 7, VALUE_DM_SPEED = 8, VALUE_DM_AREA = 9,
            VALUE_DM_SIZE = 10, VALUE_DM_OPACITY = 11, VALUE_PREVIEW = 12, VALUE_WORDS = 13;
    private static final int VALUES = 14;

    /** Theme of the page around the preview. */
    static final class Colors {
        final int bg;
        final int surface;
        final int fg;
        final int dim;
        final int line;
        final int accent;

        Colors(int bg, int surface, int fg, int dim, int line, int accent) {
            this.bg = bg;
            this.surface = surface;
            this.fg = fg;
            this.dim = dim;
            this.line = line;
            this.accent = accent;
        }
    }

    private static final int WHITE = 0xFFFFFFFF;
    private static final int LIVE = 0xFFEB0400;
    private static final int[] NICKS = {0xFFFF7F50, 0xFF1EC8A5, 0xFFB98CFF, 0xFFFFC34D};

    final int scene;
    long state;
    long focus;
    final int[] values = new int[VALUES];

    private final Colors colors;
    private final HashMap<String, Drawable> icons;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    /** Null during a dry run, which only moves {@link #y} to measure the rows. */
    private Canvas c;
    private boolean animated;
    /** Chat row cursor. */
    private float x;
    private float y;
    private int stripe;
    private float fontScale = 1f;

    SettingsPreview(Context context, int scene, long focus, Colors colors, HashMap<String, Drawable> icons) {
        super(context);
        this.scene = scene;
        this.focus = focus;
        this.colors = colors;
        this.icons = icons;
        values[VALUE_FONT] = 100;
        values[VALUE_EMOTE] = 22;
        values[VALUE_DM_SPEED] = 1;
        values[VALUE_DM_AREA] = 1;
        values[VALUE_DM_SIZE] = 1;
        values[VALUE_DM_OPACITY] = 2;
    }

    /** Scenes read best stacked at full width; the rest sit side by side. */
    static boolean stacked(int scene) {
        return scene == CHAT || scene == HEADER || scene == BANNERS || scene == INPUT;
    }

    private float designWidth() {
        return stacked(scene) ? 340 : 320;
    }

    private float designHeight() {
        switch (scene) {
            case PLAYER:
                return 180;
            case HOME:
            case VERTICAL:
                return 250;
            case PICKER:
                return 170;
            case VODS:
                return 176;
            case MENU:
                return 180;
            default:
                return Math.max(dryHeight(state), dryHeight(state ^ focus));
        }
    }

    private float dryHeight(long flags) {
        long saved = state;
        state = flags;
        c = null;
        draw();
        state = saved;
        return y + 6;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(designHeight() * width / designWidth()));
    }

    /** After a live preview's state changed. */
    void refresh() {
        requestLayout();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float scale = getWidth() / designWidth();
        canvas.save();
        canvas.scale(scale, scale);
        c = canvas;
        animated = false;
        draw();
        c = null;
        canvas.restore();
        if (animated) {
            postInvalidateOnAnimation();
        }
    }

    private void draw() {
        switch (scene) {
            case PLAYER:
                player();
                break;
            case CHAT:
                chat();
                break;
            case HEADER:
                header();
                break;
            case BANNERS:
                banners();
                break;
            case INPUT:
                input();
                break;
            case HOME:
                home();
                break;
            case PICKER:
                picker();
                break;
            case VODS:
                vods();
                break;
            case VERTICAL:
                vertical();
                break;
            default:
                menu();
                break;
        }
    }

    // ---- primitives ----

    private boolean on(long flag) {
        return (state & flag) != 0;
    }

    private static String t(String ru, String en) {
        return Strings.pick(ru, en);
    }

    private void box(float left, float top, float width, float height, float radius, int color) {
        if (c != null) {
            fill.setShader(null);
            fill.setColor(color);
            rect.set(left, top, left + width, top + height);
            c.drawRoundRect(rect, radius, radius, fill);
        }
    }

    private void gradient(float left, float top, float width, float height, float radius, boolean vertical, int from,
            int to) {
        if (c != null) {
            fill.setShader(new LinearGradient(left, top, vertical ? left : left + width, vertical ? top + height : top,
                    from, to, Shader.TileMode.CLAMP));
            rect.set(left, top, left + width, top + height);
            c.drawRoundRect(rect, radius, radius, fill);
            fill.setShader(null);
        }
    }

    private void circle(float cx, float cy, float radius, int color) {
        if (c != null) {
            fill.setShader(null);
            fill.setColor(color);
            c.drawCircle(cx, cy, radius, fill);
        }
    }

    /** @return the width of the text. */
    private float label(String value, float left, float baseline, float size, int color, boolean bold) {
        text.setTextSize(size);
        text.setTypeface(bold ? medium : Typeface.DEFAULT);
        if (c != null) {
            text.setColor(color);
            c.drawText(value, left, baseline, text);
        }
        return text.measureText(value);
    }

    private float width(String value, float size, boolean bold) {
        text.setTextSize(size);
        text.setTypeface(bold ? medium : Typeface.DEFAULT);
        return text.measureText(value);
    }

    private void icon(String name, float left, float top, float size, int color) {
        if (c == null) {
            return;
        }
        Drawable drawable = icons.get(name);
        if (drawable == null && !icons.containsKey(name)) {
            int id = UiHider.res(getContext(), name, "drawable");
            drawable = id == 0 ? null : getContext().getDrawable(id).mutate();
            icons.put(name, drawable);
        }
        if (drawable != null) {
            drawable.setTint(color);
            drawable.setBounds(Math.round(left), Math.round(top), Math.round(left + size), Math.round(top + size));
            drawable.draw(c);
        }
    }

    private void outline(float left, float top, float width, float height, float radius, boolean dashed) {
        if (c == null) {
            return;
        }
        pen.setStyle(Paint.Style.STROKE);
        pen.setColor(dashed ? colors.dim : colors.accent);
        pen.setStrokeWidth(dashed ? 1.2f : 1.8f);
        pen.setPathEffect(dashed ? new DashPathEffect(new float[]{4, 3}, 0) : null);
        rect.set(left, top, left + width, top + height);
        c.drawRoundRect(rect, radius, radius, pen);
    }

    /** An element the option removes. */
    private boolean hides(long flag, float left, float top, float width, float height, float radius) {
        boolean gone = on(flag);
        if (focus == flag) {
            outline(left - 2, top - 2, width + 4, height + 4, radius, gone);
        }
        return !gone;
    }

    /** An element the option adds. */
    private boolean adds(long flag, float left, float top, float width, float height, float radius) {
        boolean there = on(flag);
        if (focus == flag && there) {
            outline(left - 2, top - 2, width + 4, height + 4, radius, false);
        }
        return there;
    }

    /** A chat emote stand-in: a round face. */
    private void face(float left, float top, float size, int color) {
        float r = size / 2;
        float cx = left + r;
        float cy = top + r;
        circle(cx, cy, r, color);
        circle(cx - r * .36f, cy - r * .18f, r * .15f, 0xFF222222);
        circle(cx + r * .36f, cy - r * .18f, r * .15f, 0xFF222222);
        if (c != null) {
            pen.setStyle(Paint.Style.STROKE);
            pen.setPathEffect(null);
            pen.setColor(0xFF222222);
            pen.setStrokeWidth(r * .14f);
            pen.setStrokeCap(Paint.Cap.ROUND);
            rect.set(cx - r * .42f, cy - r * .1f, cx + r * .42f, cy + r * .58f);
            c.drawArc(rect, 25, 130, false, pen);
        }
    }

    // ---- player ----

    /** A stand-in stream: a sunset with a streamer's camera in the corner, asymmetric so that mirroring shows. */
    private void video(float left, float top, float width, float height, boolean mirrored) {
        if (c == null) {
            return;
        }
        c.save();
        c.clipRect(left, top, left + width, top + height);
        if (mirrored) {
            c.scale(-1, 1, left + width / 2, 0);
        }
        fill.setShader(new LinearGradient(0, top, 0, top + height, new int[]{0xFF2E1A66, 0xFFB5527A, 0xFFF4A261},
                new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        rect.set(left, top, left + width, top + height);
        c.drawRect(rect, fill);
        fill.setShader(null);
        circle(left + width * .3f, top + height * .5f, height * .13f, 0xFFFFE3A3);
        hill(left, top, width, height, .62f, .46f, .72f, 0xFF5B2C6F);
        hill(left, top, width, height, .78f, .6f, .9f, 0xFF2B1747);
        float camera = height * .36f;
        box(left + width - camera * 1.5f - 10, top + height - camera - 10, camera * 1.5f, camera, 5, 0xFF17171C);
        circle(left + width - camera * .75f - 10, top + height - camera * .62f - 10, camera * .2f, 0xFFD9B08C);
        float shoulders = camera * .34f;
        box(left + width - camera * .75f - 10 - shoulders, top + height - camera * .36f - 10, shoulders * 2,
                camera * .36f, 8, 0xFF9147FF);
        c.restore();
    }

    private void hill(float left, float top, float width, float height, float start, float mid, float end, int color) {
        path.reset();
        path.moveTo(left, top + height);
        path.lineTo(left, top + height * start);
        path.quadTo(left + width * .3f, top + height * mid, left + width * .55f, top + height * (mid + end) / 2);
        path.quadTo(left + width * .8f, top + height * end, left + width, top + height * mid);
        path.lineTo(left + width, top + height);
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    private void player() {
        box(0, 0, 320, 180, 0, 0xFF000000);
        if (focus == P_DIVIDER || focus == P_WIDE_PANEL) {
            sideChat();
            return;
        }
        boolean ad = focus == P_AD && !on(P_AD);
        boolean notch = focus == P_CUTOUT && !on(P_CUTOUT);
        boolean glow = focus == P_AMBILIGHT;
        float left = glow ? 44 : notch ? 28 : 0;
        float width = 320 - left - (glow ? 44 : 0);
        if (ad) {
            gradient(left, 0, width, 180, 0, false, 0xFF00B5AD, 0xFF2F80ED);
            label(t("Реклама", "An ad"), 112, 96, 24, WHITE, true);
        } else {
            video(left, 0, width, 180, focus == P_MIRROR && on(P_MIRROR)); // the button alone flips nothing
        }
        if (glow && on(P_AMBILIGHT)) {
            gradient(0, 0, 44, 180, 0, false, 0x00000000, 0xFFB5527A);
            gradient(276, 0, 44, 180, 0, false, 0xFFE08A5B, 0x00000000);
        }
        if (focus == P_CUTOUT) {
            circle(14, 90, 6, 0xFF000000);
            circle(14, 90, 3, 0xFF2A2A30);
        }
        if (focus == P_MATURE && !on(P_MATURE)) {
            box(0, 0, 320, 180, 0, 0xE0101014);
            label(t("Контент для взрослых", "Mature content"), 76, 78, 17, WHITE, true);
            box(92, 96, 136, 34, 6, colors.accent);
            label(t("Начать просмотр", "Start watching"), 110, 118, 13, WHITE, true);
            hides(P_MATURE, 20, 56, 280, 84, 8);
            return;
        }
        gradient(0, 0, 320, 56, 0, true, 0x99000000, 0x00000000);
        gradient(0, 124, 320, 56, 0, true, 0x00000000, 0xA6000000);
        icon("ic_arrow_down", 8, 8, 26, WHITE);
        if (focus == P_FOLLOW || focus == P_UNFOLLOW || focus == 0 && !on(P_STATS)) {
            metadata();
        }
        if (focus != P_DANMAKU || !on(P_DANMAKU)) { // the messages would run over them
            topButtons();
        }
        if (focus == P_GESTURES && on(P_GESTURES)) {
            gestures();
        } else if (focus == P_UP_NEXT) {
            upNext();
        } else if (ad) {
            box(10, 138, 96, 22, 4, 0xFFFFC34D);
            label(t("Реклама 0:15", "Ad 0:15"), 18, 153, 12, 0xFF18181B, true);
        } else if (!on(P_DANMAKU) && focus != P_STATS) {
            icon("ic_pause", 142, 72, 36, WHITE);
        }
        if (adds(P_STATS, 10, 40, 134, 66, 6)) {
            stats();
        }
        if (adds(P_AD_LABEL, 86, 44, 148, 22, 11)) {
            box(86, 44, 148, 22, 11, 0xCC000000);
            circle(98, 55, 3, 0xFFFFC34D);
            label(Strings.vaftBlockingLabel(null), 106, 59, 11, WHITE, true);
        }
        if (adds(P_REC_LABEL, 228, 40, 82, 20, 10)) {
            box(228, 40, 82, 20, 10, 0xB3000000);
            circle(240, 50, 3.5f, 0xFFEB5757);
            label("REC 42:13", 248, 54, 11, WHITE, true);
        }
        if (focus == P_LAYOUT) {
            layoutPicker();
        }
        bottomBar();
        if (focus == P_MODE_POPUP && hides(P_MODE_POPUP, 60, 124, 200, 26, 6)) {
            box(60, 124, 200, 26, 6, 0xF0202024);
            label(t("Режим чата: колонка", "Chat mode: column"), 92, 141, 12, WHITE, false);
        }
        if (on(P_DANMAKU)) {
            danmaku();
        }
    }

    /** A vertical-format stream's layout picker at the right: its labels reach over pause. */
    private void layoutPicker() {
        String[] modes = {t("Классический разделенный", "Classic split"), t("Вертикальный вид", "Vertical view")};
        for (int i = 0; i < modes.length; i++) {
            float top = 62 + i * 30;
            circle(294, top + 12, 12, i == 1 ? 0x55FFFFFF : 0x22FFFFFF);
            box(i == 1 ? 290 : 287, top + (i == 1 ? 5 : 7), i == 1 ? 8 : 14, i == 1 ? 14 : 10, 2, WHITE);
            float w = width(modes[i], 12, true);
            if (hides(P_LAYOUT, 276 - w, top + 3, w, 18, 4)) {
                label(modes[i], 276 - w, top + 17, 12, WHITE, true);
            }
        }
    }

    private void metadata() {
        float top = 36;
        if (focus != 0 || !on(P_FOLLOW)) {
            if (hides(P_FOLLOW, 10, top, 76, 20, 10)) {
                box(10, top, 76, 20, 10, 0xCC9147FF);
                label(t("Подписаться", "Subscribe"), 17, top + 14, 10, WHITE, true);
            }
        }
        if (focus != 0 || !on(P_UNFOLLOW)) {
            if (hides(P_UNFOLLOW, 92, top, 22, 20, 10)) {
                box(92, top, 22, 20, 10, 0x55FFFFFF);
                icon("ic_heart", 97, top + 4, 12, WHITE);
            }
        }
    }

    /** "Vertical view": the row of buttons over the video, the chat field under it. */
    private void vertical() {
        video(0, 0, 320, 206, false);
        if (hides(W_BUTTONS, 8, 8, 304, 30, 8)) {
            circle(24, 23, 12, colors.accent);
            box(166, 11, 88, 24, 12, WHITE);
            label(t("Отслеживать", "Follow"), 175, 27, 11.5f, 0xFF17171C, true);
            icon("star", 260, 12, 22, WHITE);
            icon("ic_leaderboard", 288, 12, 22, WHITE);
        }
        label(t("зритель: привет, чат", "viewer: hi chat"), 12, 176, 12, WHITE, false);
        if (hides(W_PANEL, 8, 186, 304, 58, 12)) {
            circle(22, 198, 11, colors.accent);
            box(38, 187, 274, 22, 11, 0xFF772CE8);
            icon("ic_pin", 44, 190, 16, WHITE);
            label(t("Закреплённое сообщение", "Pinned message"), 66, 203, 11.5f, WHITE, true);
            box(8, 214, 304, 30, 15, colors.surface);
            label(t("Чат", "Chat"), 22, 234, 13, colors.dim, false);
            icon("ic_emotes", 250, 219, 20, colors.fg);
            icon("ic_settings", 282, 219, 20, colors.fg);
        }
    }

    private void topButtons() {
        float slot = 320 - 34;
        icon("ic_settings", slot, 10, 22, WHITE);
        slot -= 30;
        if (hides(P_SHARE, slot, 10, 22, 22, 5)) {
            icon("ic_share", slot, 10, 22, WHITE);
        }
        slot -= 30;
        if (hides(P_CLIP, slot, 10, 22, 22, 5)) {
            icon("ic_clip", slot, 10, 22, WHITE);
        }
        slot -= 30;
        if (hides(P_CAST, slot, 10, 22, 22, 5)) {
            icon("chromecast", slot, 10, 22, WHITE);
        }
        slot -= 30;
        if (adds(P_TIMER, slot, 10, 22, 22, 5)) {
            icon("ic_clock", slot, 10, 22, WHITE);
        }
    }

    private void bottomBar() {
        float baseline = 169;
        circle(16, baseline - 4, 3.5f, LIVE);
        float at = 24 + label("1:23:45", 24, baseline, 11, WHITE, true);
        at += 8 + label(t("12,4 тыс.", "12.4K"), at + 8, baseline, 11, WHITE, true);
        if (adds(P_CHATTERS, at + 2, baseline - 12, 42, 15, 4)) {
            at += 4 + label(t("(9,8 тыс.)", "(9.8K)"), at + 4, baseline, 11, WHITE, true);
        }
        if (adds(P_LATENCY, at + 6, baseline - 12, 32, 15, 4)) {
            label(Strings.latencyShort(1400), at + 8, baseline, 11, 0xFFBFBFC8, false);
        }
        if (adds(P_BATTERY, 262, 124, 50, 18, 5)) {
            box(262, 124, 50, 18, 5, 0x99000000);
            label(BatteryLabel.text(85, false), 267, 137, 10.5f, WHITE, false);
        }
        float slot = 320 - 30;
        if (hides(P_ROTATE, slot, 150, 22, 22, 5)) {
            icon("ic_rotate", slot, 150, 22, WHITE);
        }
        slot -= 30;
        if (hides(P_FULLSCREEN, slot - 30, 150, 50, 22, 5)) {
            icon("ic_fullscreen_enter", slot, 150, 22, WHITE);
            icon("ic_chat_show", slot - 30, 150, 22, WHITE);
        }
        slot -= 58;
        String[] names = {"ic_refresh_white_24dp", null, null, "ic_info", null, null, "ic_download", "ic_clock"};
        long[] flags = {P_RELOAD, P_COMPRESSOR, P_RECORD, P_STATS, P_MIRROR, P_CHAPTERS, P_DOWNLOAD, P_CHAT_SYNC};
        for (int i = 0; i < flags.length; i++) {
            if (adds(flags[i], slot, 150, 22, 22, 5)) {
                glyph(flags[i], names[i], slot);
                slot -= 27;
            }
        }
        if (hides(P_MUTE, slot, 150, 22, 22, 5)) {
            icon("ic_volume_max", slot, 150, 22, WHITE);
        }
    }

    private void glyph(long flag, String name, float left) {
        float top = 150;
        if (flag == P_COMPRESSOR) {
            float[] bars = {8, 14, 18, 11};
            for (int i = 0; i < bars.length; i++) {
                box(left + 3 + i * 4.6f, top + 11 - bars[i] / 2, 3, bars[i], 1.5f, 0xFFBF94FF);
            }
        } else if (flag == P_MIRROR) {
            box(left + 10.2f, top + 2, 1.6f, 18, .8f, WHITE);
            box(left + 2, top + 5, 6, 12, 1.5f, 0x88FFFFFF);
            box(left + 14, top + 5, 6, 12, 1.5f, WHITE);
        } else if (flag == P_CHAPTERS) {
            for (int i = 0; i < 3; i++) {
                box(left + 3, top + 5 + i * 5, 16, 2.2f, 1, WHITE);
            }
        } else if (flag == P_RECORD) {
            circle(left + 11, top + 11, 8.5f, WHITE);
            circle(left + 11, top + 11, 6.6f, 0xFF26262C);
            circle(left + 11, top + 11, 4.2f, 0xFFEB5757);
        } else {
            icon(name, left, top, 22, WHITE);
        }
    }

    private void stats() {
        box(10, 40, 134, 66, 6, 0xCC000000);
        String[] rows = {t("Качество  1080p60", "Quality   1080p60"), t("Видео  6 200 кбит/с", "Video  6,200 kbps"),
                t("Задержка  1,4 с", "Latency  1.4 s"), t("Буфер  2,1 с", "Buffer  2.1 s")};
        for (int i = 0; i < rows.length; i++) {
            label(rows[i], 18, 55 + i * 14, 10.5f, WHITE, false);
        }
    }

    private void gestures() {
        box(14, 50, 6, 74, 3, 0x55FFFFFF);
        box(14, 84, 6, 40, 3, WHITE);
        box(300, 50, 6, 74, 3, 0x55FFFFFF);
        box(300, 70, 6, 54, 3, WHITE);
        icon("ic_moon_filled", 30, 76, 20, WHITE);
        icon("ic_volume_on", 270, 76, 20, WHITE);
        icon("ic_pause", 146, 76, 28, WHITE);
    }

    private void upNext() {
        if (on(P_UP_NEXT)) {
            icon("ic_icon_replay", 142, 72, 36, WHITE);
            return;
        }
        box(160, 56, 150, 78, 6, 0xE0101014);
        label(Strings.upNext(), 170, 76, 12, 0xFFBFBFC8, false);
        label("0:05", 276, 76, 12, WHITE, true);
        box(170, 86, 130, 38, 4, 0xFF34343B);
        label(t("Следующее видео", "Next video"), 178, 109, 11.5f, WHITE, true);
    }

    private void danmaku() {
        animated = true;
        String[] lines = {t("вот это момент", "what a play"), t("ахаха", "lmao"), t("всем привет", "hey everyone"),
                t("красиво", "so clean"), "GG WP", t("он это сделал", "he did it"), "POG"};
        float seconds = new float[]{10, 7, 5, 3.5f}[values[VALUE_DM_SPEED]];
        float size = new float[]{11, 14, 17, 20}[values[VALUE_DM_SIZE]];
        int alpha = 255 * new int[]{40, 60, 80, 100}[values[VALUE_DM_OPACITY]] / 100;
        float area = new float[]{.25f, .5f, .75f, 1f}[values[VALUE_DM_AREA]];
        int lanes = Math.max(1, (int) (180 * area / (size + 8)));
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < lines.length; i++) {
            float phase = (now / (seconds * 1000f) + i / (float) lines.length) % 1f;
            float baseline = 8 + (i % lanes + 1) * (size + 8) - 8;
            float left = 330 - phase * 480;
            if (on(P_DANMAKU_NICKS)) {
                left += 6 + label(new String[]{"mira", "zefir", "kaito"}[i % 3] + " ", left, baseline, size,
                        (NICKS[i % NICKS.length] & 0xFFFFFF) | alpha << 24, true);
            }
            text.setShadowLayer(3, 0, 1, 0xCC000000);
            label(lines[i], left, baseline, size, (WHITE & 0xFFFFFF) | alpha << 24, true);
            text.clearShadowLayer();
        }
    }

    /** Landscape player next to the chat column. */
    private void sideChat() {
        boolean poll = focus == P_WIDE_PANEL;
        float column = poll && on(P_WIDE_PANEL) ? 160 : 92;
        float video = 320 - column;
        video(0, 0, video, 180, false);
        box(video, 0, column, 180, 0, 0xFF18181B);
        if (poll) {
            box(video + 6, 8, column - 12, 92, 6, 0xFF26262C);
            label(t("Опрос", "Poll"), video + 14, 25, 11, WHITE, true);
            String first = t("Да, конечно", "Yes, of course");
            String second = t("Лучше не надо", "Better not");
            box(video + 12, 34, column - 24, 24, 4, 0xFF3A3A42);
            box(video + 12, 62, column - 24, 24, 4, 0xFF3A3A42);
            if (c != null) {
                c.save();
                c.clipRect(video + 14, 30, video + column - 14, 90);
            }
            label(first, video + 18, 50, 11, WHITE, false);
            label(second, video + 18, 78, 11, WHITE, false);
            if (c != null) {
                c.restore();
            }
        }
        for (int i = 0; i < (poll ? 3 : 7); i++) {
            float top = (poll ? 110 : 12) + i * 23;
            box(video + 8, top, 22 + (i * 13) % 26, 7, 3, NICKS[i % NICKS.length]);
            box(video + 8, top + 10, column - 22 - (i * 17) % 34, 6, 3, 0x44FFFFFF);
        }
        if (focus == P_DIVIDER && adds(P_DIVIDER, video - 7, 62, 14, 56, 7)) {
            box(video - 5, 62, 10, 56, 5, 0xFFEFEFF1);
            box(video - 1.5f, 76, 3, 28, 1.5f, 0xFF53535F);
        }
    }

    // ---- chat ----

    private void chat() {
        y = 6;
        stripe = 0;
        fontScale = values[VALUE_FONT] / 100f;
        String hi = t("всем привет", "hey everyone");
        String wow = t("вот это момент", "what a play");
        long f = focus;
        if (f == C_REALTIME || f == C_FADE) {
            realtime();
        } else if (f == C_RTE || f == C_BADGE_FFZ || f == C_BADGE_CHATTERINO || f == C_BADGE_DANKCHAT
                || f == C_BADGE_CHATSEN || f == C_BADGE_HOMIES || f == C_PAINT) {
            say(0, hi);
            say(1, wow);
        } else if (f == C_ME) {
            say(1, hi);
            me();
        } else if (f == C_FIRST) {
            say(1, hi);
            first();
        } else if (f == C_COLOR_MENTION) {
            mention(false);
            say(1, wow);
        } else if (f == C_HIGHLIGHT) {
            say(1, hi);
            mention(true);
            if (values[VALUE_WORDS] > 0) {
                int index = values[VALUE_WORDS] - 2;
                box(0, y, 340, rowHeight(), 0, ChatSettings.MENTION_COLORS[index < 0 ? values[VALUE_MENTION] : index]);
                say(0, t("сегодня розыгрыш", "giveaway today"));
            }
        } else if (f == C_REPLY) {
            say(1, hi);
            reply();
        } else if (f == C_BOTS) {
            say(0, hi);
            hideable(C_BOTS, () -> say(3, t("подпишись на канал!", "follow the channel!"), "Nightbot"));
            say(1, wow);
        } else if (f == C_GIFS) {
            gif();
        } else if (f == C_ANNOUNCE) {
            hideable(C_ANNOUNCE, () -> card(0xFF9147FF, t("Объявление", "Announcement"), t("Стрим в 20:00", "Stream at 8 pm")));
            say(1, wow);
        } else if (f == C_SUBS) {
            hideable(C_SUBS, () -> card(0xFFFFC34D, "mira", t("Подписка на 3 месяца", "Subscribed for 3 months")));
            say(1, wow);
        } else if (f == C_REDEEM) {
            hideable(C_REDEEM, () -> card(0xFF1EC8A5, "zefir", t("Награда: выбрать игру", "Redeemed: pick a game")));
            say(1, wow);
        } else if (f == C_STREAK) {
            hideable(C_STREAK, () -> card(0xFFFF7F50, "kaito", t("Серия просмотров: 12", "Watch streak: 12")));
            say(1, wow);
        } else if (f == C_SERVICE) {
            hideable(C_SERVICE, () -> notice(t("Добро пожаловать в чат!", "Welcome to the chat!")));
            say(1, wow);
        } else if (f == C_WORDS) {
            say(1, hi);
            hideable(C_WORDS, () -> say(2, t("заходи на мой сайт", "check out my site")));
            say(0, wow);
        } else if (f == C_USERS) {
            say(1, hi);
            hideable(C_USERS, () -> say(2, t("опять этот зритель", "that viewer again")));
            say(0, wow);
        } else if (f == C_MOD_LOG) {
            say(0, hi);
            say(2, t("сообщение нарушителя", "a rule-breaking message"));
            if (on(C_MOD_LOG)) {
                notice(t("zefir получил таймаут на 10 мин", "zefir was timed out for 10 min"));
            }
        } else if (f == C_NO_CLEAR) {
            if (on(C_NO_CLEAR)) {
                say(0, hi);
                say(1, wow);
                notice(Strings.chatClearedNotice());
            } else {
                notice(t("Чат очищен модератором", "Chat was cleared by a moderator"));
            }
        } else if (f == C_AUTOMOD) {
            say(1, hi);
            if (on(C_AUTOMOD)) {
                automod();
            }
        } else if (f == C_RAID) {
            say(1, hi);
            if (on(C_RAID)) {
                notice(Strings.raidArrivedNotice("mira", 120));
            }
        } else if (f == C_7TV || f == C_BTTV || f == C_FFZ || f == C_HOMIES) {
            emoteMessage(f);
        } else if (f == C_MODIFIERS) {
            modifiers();
        } else if (f == C_GIANT) {
            giant();
        } else if (f == C_LINK) {
            link();
        } else if (f == C_DELETED) {
            deleted(values[VALUE_DELETED]);
        } else if (f == C_NEW_COUNT) {
            say(0, hi);
            say(1, wow);
            say(3, t("чат сегодня живой", "chat's alive today"));
            String pill = on(C_NEW_COUNT) ? Strings.pickf("Новых сообщений: %d", "New messages: %d", 25)
                    : t("Другие сообщения - ниже.", "More messages below.");
            float w = width(pill, 12, true) + 24;
            box(170 - w / 2, y + 4, w, 26, 6, 0xE0000000);
            label(pill, 182 - w / 2, y + 21, 12, WHITE, true);
            if (focus == C_NEW_COUNT && on(C_NEW_COUNT)) {
                outline(168 - w / 2, y + 2, w + 4, 30, 8, false);
            }
            y += 34;
        } else if (f == C_DELETED_BG) {
            say(1, hi);
            deleted(ChatSettings.getDeletedMode());
            say(0, wow);
        } else {
            say(0, hi);
            say(1, wow);
            if (on(C_ME)) {
                me();
            }
            if (on(C_FIRST)) {
                first();
            } else {
                say(3, t("чат сегодня живой", "chat's alive today"));
            }
        }
    }

    /** A row the option filters out: dashed and empty once it is gone. */
    private void hideable(long flag, Runnable row) {
        if (!on(flag)) {
            row.run();
            return;
        }
        float height = rowHeight();
        outline(10, y + 1, 320, height - 2, 5, true);
        label(t("скрыто", "hidden"), 20, y + height * .68f, 11.5f, colors.dim, false);
        y += height;
    }

    private float rowHeight() {
        return 22 * fontScale + 3;
    }

    private void begin() {
        float height = rowHeight();
        if (on(C_ALT) && stripe++ % 2 == 1) {
            box(0, y, 340, height, 0, 0x14888888);
        }
        x = 10;
        String pattern = ChatSettings.TIMESTAMP_PATTERNS[values[VALUE_TIME]];
        if (!pattern.isEmpty()) {
            Calendar sample = Calendar.getInstance();
            sample.set(2026, 0, 1, 19, 5, 9);
            word(new SimpleDateFormat(pattern, Locale.getDefault()).format(sample.getTime()) + " ", colors.dim, false);
        }
    }

    private void end() {
        y += rowHeight();
    }

    private float baseline() {
        return y + rowHeight() * .71f;
    }

    private void word(String value, int color, boolean bold) {
        x += label(value, x, baseline(), 13.5f * fontScale, color, bold);
    }

    private void badge(int color, String letter) {
        float size = 15 * fontScale;
        float top = y + (rowHeight() - size) / 2;
        box(x, top, size, size, 3, color);
        label(letter, x + size * .24f, top + size * .76f, size * .68f, WHITE, true);
        x += size + 3;
    }

    private void nick(int who, String override) {
        String[] names = {"Кайто", "mira", "zefir", "Nightbot"}; // non-Latin in every locale: see the name format
        String[] logins = {"kaito_", "mira", "zefir", "nightbot"};
        String name = override != null ? override : names[who];
        if (who == 0 && values[VALUE_NAME] == 0) {
            name = name + " (" + logins[0] + ")";
        } else if (who == 0 && values[VALUE_NAME] == 2) {
            name = logins[0];
        }
        if (who == 0 && on(C_PAINT)) {
            paintedName(name);
        } else {
            word(name, NICKS[who], true);
        }
    }

    private void paintedName(String name) {
        float size = 13.5f * fontScale;
        text.setTextSize(size);
        text.setTypeface(medium);
        float width = text.measureText(name);
        if (c != null) {
            text.setShader(new LinearGradient(x, 0, x + width, 0, new int[]{0xFFFF5F6D, 0xFFFFC371, 0xFF47E5BC, 0xFF6A8BFF},
                    null, Shader.TileMode.CLAMP));
            c.drawText(name, x, baseline(), text);
            text.setShader(null);
        }
        if (focus == C_PAINT && on(C_PAINT)) {
            outline(x - 3, y + 1, width + 6, rowHeight() - 2, 5, false);
        }
        x += width;
    }

    private void say(int who, String message) {
        say(who, message, null);
    }

    private void say(int who, String message, String name) {
        begin();
        if (who == 0) {
            badges();
        }
        nick(who, name);
        word(": ", colors.fg, false);
        word(message, colors.fg, false);
        end();
    }

    private void badges() {
        long[] flags = {C_RTE, C_BADGE_FFZ, C_BADGE_CHATTERINO, C_BADGE_DANKCHAT, C_BADGE_CHATSEN, C_BADGE_HOMIES};
        int[] tints = {0xFF9147FF, 0xFF2AA198, 0xFF4F7CFF, 0xFF00A86B, 0xFFEC6A5E, 0xFFFF79C6};
        String[] letters = {"R", "F", "C", "D", "S", "H"};
        for (int i = 0; i < flags.length; i++) {
            if (on(flags[i])) {
                float size = 15 * fontScale;
                if (focus == flags[i]) {
                    outline(x - 2, y + (rowHeight() - size) / 2 - 2, size + 4, size + 4, 5, false);
                }
                badge(tints[i], letters[i]);
            }
        }
    }

    private void me() {
        begin();
        int color = on(C_ME) ? NICKS[2] : colors.fg;
        word("zefir ", NICKS[2], true);
        word(t("машет всем рукой", "waves to everyone"), color, false);
        if (focus == C_ME && on(C_ME)) {
            outline(6, y + 1, x - 2, rowHeight() - 2, 5, false);
        }
        end();
    }

    private void first() {
        float height = rowHeight() + 15;
        if (on(C_FIRST)) {
            box(0, y, 340, height, 0, 0x339147FF);
            box(0, y, 3, height, 0, colors.accent);
            label(Strings.firstMessageLabel(), 10, y + 12, 9.5f, colors.accent, true);
            y += 14;
        }
        say(2, t("я тут впервые", "first time here"));
        if (!on(C_FIRST)) {
            return;
        }
        y += 1;
    }

    private void mention(boolean highlight) {
        boolean tint = highlight && on(C_HIGHLIGHT);
        if (tint) {
            box(0, y, 340, rowHeight(), 0, ChatSettings.MENTION_COLORS[values[VALUE_MENTION]]);
        }
        begin();
        nick(2, null);
        word(": ", colors.fg, false);
        float start = x;
        boolean colored = !highlight && on(C_COLOR_MENTION);
        word("@mira", colored ? NICKS[1] : colors.fg, true);
        if (colored && focus == C_COLOR_MENTION) {
            outline(start - 3, y + 1, x - start + 6, rowHeight() - 2, 5, false);
        }
        word(t(" ты тут?", " you there?"), colors.fg, false);
        end();
    }

    private void reply() {
        if (on(C_REPLY)) {
            box(0, y, 340, rowHeight() + 15, 0, ChatSettings.MENTION_COLORS[values[VALUE_MENTION]]);
        }
        label(t("В ответ @mira: всем привет", "Replying to @mira: hey everyone"), 10, y + 12, 9.5f, colors.dim, false);
        y += 14;
        say(2, t("и тебе привет", "hey to you too"));
        y += 1;
    }

    private void card(int color, String title, String subtitle) {
        float height = 36;
        box(10, y + 1, 320, height, 4, 0x18888888);
        box(10, y + 1, 3, height, 1.5f, color);
        label(title, 20, y + 15, 12, colors.fg, true);
        label(subtitle, 20, y + 30, 12, colors.dim, false);
        y += height + 3;
    }

    private void notice(String value) {
        x = 10;
        label(value, 10, baseline(), 12.5f * fontScale, colors.dim, false);
        end();
    }

    private void gif() {
        begin();
        nick(1, null);
        word(": ", colors.fg, false);
        if (!on(C_GIFS)) {
            end();
            box(10, y, 92, 58, 5, 0xFF34343B);
            label("GIF", 42, y + 34, 15, WHITE, true);
            y += 62;
        } else {
            word(t("[GIF] танцующий кот", "[GIF] dancing cat"), colors.dim, false);
            end();
        }
    }

    private void automod() {
        float height = 62;
        box(10, y + 2, 320, height, 5, 0x33EB0400);
        label(t("🛡 AutoMod задержал сообщение", "🛡 AutoMod held a message"), 18, y + 19, 12, colors.fg, true);
        label(t("zefir: спорное сообщение", "zefir: a borderline message"), 18, y + 36, 12, colors.fg, false);
        float at = 18 + label(Strings.autoModAllow(), 18, y + 54, 12, colors.accent, true);
        label(Strings.autoModDeny(), at + 16, y + 54, 12, colors.accent, true);
        y += height + 4;
    }

    private void emoteMessage(long f) {
        begin();
        nick(1, null);
        word(": ", colors.fg, false);
        String code = f == C_7TV ? "catJAM" : f == C_BTTV ? "KEKW" : f == C_FFZ ? "OMEGALUL" : "homie";
        emote(f, code);
        word(" ", colors.fg, false);
        word(t("отлично", "nice"), colors.fg, false);
        end();
    }

    private void emote(long f, String code) {
        float size = values[VALUE_EMOTE];
        if (on(f)) {
            float top = y + (rowHeight() - Math.min(size, rowHeight())) / 2;
            face(x, top, Math.min(size, rowHeight()), f == C_7TV ? 0xFF5CE1E6 : f == C_BTTV ? 0xFFFFC34D : 0xFF8BE36B);
            float width = Math.min(size, rowHeight());
            if (focus == f) {
                outline(x - 2, top - 2, width + 4, width + 4, width / 2 + 2, false);
            }
            x += width + 3;
        } else {
            word(code, colors.fg, false);
        }
    }

    private void modifiers() {
        begin();
        nick(1, null);
        word(": ", colors.fg, false);
        if (on(C_MODIFIERS)) {
            float width = rowHeight() * 1.7f;
            if (c != null) {
                c.save();
                c.scale(1.7f, 1, x, 0);
            }
            face(x, y + 2, rowHeight() - 4, 0xFFFFC34D);
            if (c != null) {
                c.restore();
            }
            outline(x - 2, y, width, rowHeight(), 6, false);
        } else {
            word("w! ", colors.fg, false);
            face(x, y + 2, rowHeight() - 4, 0xFFFFC34D);
        }
        end();
    }

    private void giant() {
        begin();
        nick(1, null);
        word(": ", colors.fg, false);
        if (on(C_GIANT)) {
            end();
            face(10, y, 64, 0xFF5CE1E6);
            outline(8, y - 2, 68, 68, 34, false);
            y += 68;
        } else {
            face(x, y + 2, rowHeight() - 4, 0xFF5CE1E6);
            end();
        }
    }

    /** {@code values[VALUE_LINK]} is LinkPreviewSettings' mode: off, hide links, preview only, both. */
    private void link() {
        int mode = values[VALUE_LINK];
        begin();
        nick(1, null);
        word(": ", colors.fg, false);
        if (mode == 0 || mode == 3) {
            word("cdn.site/cat.png", 0xFFBF94FF, false);
        } else if (mode == 1) {
            word(t("(ссылка скрыта)", "(link hidden)"), colors.dim, false);
        }
        end();
        if (mode >= 2) {
            gradient(10, y, 112, 62, 5, false, 0xFF6A8BFF, 0xFFFF7F50);
            face(50, y + 15, 32, 0xFFFFE3A3);
            y += 66;
        }
    }

    /** {@code mode} is ChatSettings.DELETED_*. */
    private void deleted(int mode) {
        if (on(C_DELETED_BG)) {
            box(0, y, 340, rowHeight(), 0, ChatSettings.getDeletedBackground());
        }
        begin();
        nick(2, null);
        word(": ", colors.fg, false);
        String message = t("сообщение модератор удалил", "a message a mod deleted");
        if (mode == ChatSettings.DELETED_TWITCH) {
            word(t("<сообщение удалено>", "<message deleted>"), colors.dim, false);
        } else if (mode == ChatSettings.DELETED_TAP) {
            float start = x;
            word(t("<сообщение удалено>", "<message deleted>"), colors.dim, false);
            box(start, baseline() + 2, x - start, 1, 0, colors.dim);
        } else if (mode == ChatSettings.DELETED_TEXT) {
            word(message, colors.fg, false);
        } else if (mode == ChatSettings.DELETED_STRIKE) {
            float start = x;
            word(message, colors.dim, false);
            box(start, baseline() - 5, x - start, 1.2f, 0, colors.dim);
        } else {
            word(message, 0xFF7A7A85, false);
        }
        end();
    }

    /** Twitch hands messages over once a second; with the option they come one at a time, faded in. */
    private void realtime() {
        animated = true;
        long now = SystemClock.uptimeMillis();
        boolean oneByOne = focus == C_FADE || on(C_REALTIME);
        long tick = oneByOne ? now / 340 : now / 1000 * 3;
        float fade = focus == C_FADE && on(C_FADE) ? (now % 340) / 340f : 1f;
        String[] lines = {t("всем привет", "hey everyone"), t("вот это момент", "what a play"), t("ахаха", "lmao"),
                t("красиво", "so clean"), "GG WP", t("он это сделал", "he did it")};
        for (int i = 3; i >= 0; i--) {
            long index = tick - i;
            int alpha = i == 0 ? Math.round(fade * 255) : 255;
            begin();
            int who = (int) (index % 3);
            word(new String[]{"mira", "zefir", "kaito"}[who], (NICKS[who + 1] & 0xFFFFFF) | alpha << 24, true);
            word(": ", (colors.fg & 0xFFFFFF) | alpha << 24, false);
            word(lines[(int) (index % lines.length)], (colors.fg & 0xFFFFFF) | alpha << 24, false);
            end();
        }
    }

    // ---- chat header, banners, input ----

    private void header() {
        box(0, 0, 340, 400, 0, colors.bg);
        y = 0;
        float baseline = 28;
        if (focus == T_REPLAY) {
            // A video's chat: its own header bar instead of the live one.
            if (hides(T_REPLAY, 0, 0, 340, 44, 0)) {
                label(t("Повтор чата", "Chat Replay"), 14, baseline, 15, colors.fg, true);
                box(0, 44, 340, 1, 0, colors.line);
                y = 45;
            }
            say(1, t("всем привет", "hey everyone"));
            return;
        }
        boolean any = !on(T_TITLE) || !on(T_BUTTONS) || !on(T_EXTENSIONS) || !on(T_LEADERBOARD) || focus != 0;
        if (any) {
            if (hides(T_TITLE, 12, 10, 34, 24, 5)) {
                label(t("Чат", "Chat"), 14, baseline, 16, colors.fg, true);
            }
            if (hides(T_LEADERBOARD, 58, 12, 22, 20, 5)) {
                icon("ic_leaderboard", 59, 12, 20, colors.fg);
            }
            if (hides(T_EXTENSIONS, 88, 12, 22, 20, 5)) {
                icon("layout", 89, 12, 20, colors.fg);
            }
            if (hides(T_BUTTONS, 150, 8, 182, 28, 6)) {
                float left = 240;
                box(left, 8, 92, 28, 5, colors.accent);
                label(t("Подписаться", "Subscribe"), left + 8, 27, 12, WHITE, true);
                if (hides(T_GIFT, 150, 8, 82, 28, 6)) {
                    box(150, 8, 82, 28, 5, 0x28888888);
                    label(t("Подарок", "Gift"), 170, 27, 12, colors.fg, true);
                }
            }
            box(0, 44, 340, 1, 0, colors.line);
            y = 45;
        }
        boolean top = !on(T_LEADERBOARD);
        boolean extensions = !on(T_EXTENSIONS);
        if (top || extensions || focus != 0) {
            if (hides(T_LEADERBOARD, 8, y + 5, 324, 20, 5)) {
                icon("ic_trophy", 12, y + 7, 15, 0xFFFFC34D);
                label("1  mira   2  zefir   3  kaito", 32, y + 19, 12, colors.dim, false);
            }
            y += 28;
        }
        say(1, t("всем привет", "hey everyone"));
    }

    private void banners() {
        box(0, 0, 340, 400, 0, colors.bg);
        y = 8;
        long f = focus;
        if (f == B_HYPE) {
            banner(B_HYPE, 0xFFFF7F50, "ic_hypetrain", "Hype Train", t("Уровень 2 - осталось 45%", "Level 2, 45% to go"));
        } else if (f == B_POLL) {
            banner(B_POLL, 0xFF387AFF, "ic_prediction_event", t("Прогноз", "Prediction"),
                    t("Победит ли стример?", "Will the streamer win?"));
        } else if (f == B_RAID) {
            banner(B_RAID, 0xFFEB0400, "ic_raid", t("Рейд", "Raid"), t("mira и ещё 120 зрителей", "mira and 120 viewers"));
        } else if (f == B_SHOUTOUT) {
            banner(B_SHOUTOUT, 0xFF9147FF, "ic_sparkle_placeholder_badge", "Shoutout",
                    t("Загляните к zefir", "Check out zefir"));
        } else if (f == B_DROPS) {
            banner(B_DROPS, 0xFF00A86B, "drops", "Drops", t("Награда за просмотр", "A reward for watching"));
        } else if (f == B_GIFTS) {
            banner(B_GIFTS, 0xFFFF4F9A, "ic_gift", t("Подарите подписку", "Gift a sub"), t("Скидка 30%", "30% off"));
        } else if (f == B_SHARED) {
            banner(B_SHARED, 0xFF2F80ED, "ic_users", t("Общий чат", "Shared Chat"), t("С каналом mira", "With mira"));
        } else if (f == B_PAID_PIN) {
            if (hides(B_PAID_PIN, 8, y, 324, 26, 13)) {
                box(0, y - 2, 340, 30, 0, 0x18888888);
                box(10, y + 2, 138, 22, 11, 0xFFFFC34D);
                label(t("zefir: 100 Bits", "zefir: 100 Bits"), 20, y + 18, 11.5f, 0xFF18181B, true);
            }
            y += 32;
        } else {
            pinned();
        }
        say(1, t("всем привет", "hey everyone"));
    }

    /** {@code values[VALUE_PINNED]} is the pinned-message mode: show, hide, hide after 30 s. */
    private void pinned() {
        if (values[VALUE_PINNED] != 1) {
            box(10, y, 320, 48, 7, 0x22888888);
            icon("ic_pin", 18, y + 7, 16, colors.dim);
            label(t("Закреплено", "Pinned"), 40, y + 19, 12, colors.dim, false);
            label(t("Дискорд в описании канала", "Discord in the channel info"), 18, y + 39, 13, colors.fg, true);
            if (values[VALUE_PINNED] == 2) {
                box(286, y + 6, 36, 18, 9, colors.accent);
                label(t("30 с", "30 s"), 293, y + 19, 10.5f, WHITE, true);
            }
            y += 54;
        } else {
            outline(10, y, 320, 40, 7, true);
            label(t("скрыто", "hidden"), 22, y + 25, 11.5f, colors.dim, false);
            y += 46;
        }
    }

    private void banner(long flag, int color, String glyph, String title, String subtitle) {
        if (hides(flag, 10, y, 320, 44, 7)) {
            box(10, y, 320, 44, 7, (color & 0xFFFFFF) | 0x33000000);
            box(18, y + 8, 28, 28, 7, color);
            icon(glyph, 23, y + 13, 18, WHITE);
            label(title, 54, y + 18, 12.5f, colors.fg, true);
            label(subtitle, 54, y + 35, 12, colors.dim, false);
        }
        y += 50;
    }

    private void input() {
        box(0, 0, 340, 400, 0, colors.bg);
        y = 8;
        long f = focus;
        if (f == I_FULLSCREEN || f == I_OVERLAY || f == I_RAISED) {
            fullscreenInput(f);
            return;
        }
        if (f == I_NO_COLON) {
            if (on(I_NO_COLON)) {
                box(50, y, 240, 32, 7, colors.surface);
                face(58, y + 6, 20, 0xFF5CE1E6);
                label("catJAM", 86, y + 21, 13, colors.fg, true);
                face(150, y + 6, 20, 0xFFFFC34D);
                label("catKISS", 178, y + 21, 13, colors.fg, true);
                outline(48, y - 2, 244, 36, 8, false);
            } else {
                label(t("подсказок нет, нужно «:cat»", "no hints, it takes \":cat\""), 58, y + 21, 12.5f, colors.dim, false);
            }
            y += 38;
        }
        float top = y;
        float left = 10;
        if (focus == I_POINTS_PORTRAIT || focus == I_POINTS_LANDSCAPE) {
            if (hides(focus, left, top + 8, 20, 20, 5)) {
                icon("ic_channel_points", left, top + 8, 20, colors.accent);
                left += 30;
            }
        }
        // The live mock-up is one row for both orientations.
        boolean noPoints = on(I_POINTS_PORTRAIT) && on(I_POINTS_LANDSCAPE);
        if (focus == I_BALANCE || focus == I_BALANCE_UNDER || focus == 0 && !noPoints) {
            // The under-icon panels only move the balance, in both of them it's shown.
            boolean balance = focus == I_BALANCE_UNDER || on(I_BALANCE);
            if (balance && on(I_BALANCE_UNDER)) {
                // Under the icon, as UiHider.stackPoints lays it out.
                float width = label(t("4,9 тыс.", "4.9K"), left, top + 33, 10.5f, colors.fg, true);
                icon("ic_channel_points", left + (width - 18) / 2, top, 18, colors.accent);
                adds(I_BALANCE_UNDER, left, top, width, 36, 5);
                left += width;
            } else {
                float start = left;
                icon("ic_channel_points", left, top + 8, 20, colors.accent);
                left += 24;
                if (balance) {
                    left += 4 + label(t("4,9 тыс.", "4.9K"), left, top + 23, 13, colors.fg, true);
                    adds(I_BALANCE, start, top + 6, left - start, 24, 5);
                }
            }
            left += 6;
        }
        float right = 330;
        float send = right;
        if (hides(I_SETTINGS, right - 16, top + 8, 16, 20, 5)) {
            box(right - 10, top + 10, 3, 3, 1.5f, colors.fg);
            box(right - 10, top + 16, 3, 3, 1.5f, colors.fg);
            box(right - 10, top + 22, 3, 3, 1.5f, colors.fg);
            send = right - 22;
        } else if (focus == I_SETTINGS) {
            send = right - 22;
        }
        if (hides(I_BITS, send - 22, top + 7, 22, 22, 5)) {
            icon("ic_bits", send - 22, top + 7, 22, colors.fg);
            send -= 28;
        } else if (focus == I_BITS) {
            send -= 28;
        }
        box(left, top, send - left, 36, 7, colors.surface);
        if (on(I_IMAGES) && focus == I_IMAGES) {
            float at = left + 10 + label(t("вот так ", "like this "), left + 10, top + 23, 13.5f, colors.fg, false);
            face(at, top + 8, 20, 0xFF5CE1E6);
            outline(at - 2, top + 6, 24, 24, 12, false);
        } else if (focus == I_IMAGES) {
            label(t("вот так catJAM", "like this catJAM"), left + 10, top + 23, 13.5f, colors.fg, false);
        } else if (on(I_LATENCY)) {
            float width = label(Strings.latencyLabel(1400), left + 10, top + 23, 13.5f, colors.dim, false);
            if (focus == I_LATENCY) {
                outline(left + 7, top + 6, width + 6, 24, 5, false);
            }
        } else if (focus == I_CURSOR) {
            label(t("Отправить сообщение", "Send a message"), left + 12, top + 23, 13.5f, colors.dim, false);
            if (!on(I_CURSOR)) {
                box(left + 9, top + 9, 1.6f, 18, 0, colors.accent);
                outline(left + 5, top + 6, 9, 24, 4, false);
            }
        } else {
            label(t("Отправить сообщение", "Send a message"), left + 10, top + 23, 13.5f, colors.dim, false);
        }
        if (hides(I_EMOTE_IDLE, send - 28, top + 8, 20, 20, 5)) {
            icon("ic_emotes", send - 28, top + 8, 20, colors.fg);
        }
        y = top + 36 + 2;
    }

    private void fullscreenInput(long f) {
        video(0, 0, 340, 124, false);
        y = 8;
        if (f == I_OVERLAY) {
            box(10, 10, 150, 104, 6, 0x99000000);
            label("mira: " + t("привет", "hello"), 18, 30, 12, WHITE, false);
            label("zefir: GG", 18, 48, 12, WHITE, false);
            if (hides(I_OVERLAY, 16, 80, 138, 28, 6)) {
                box(16, 80, 138, 28, 6, 0xCC26262C);
                label(t("Сообщение...", "Message..."), 24, 99, 12, 0xFFBFBFC8, false);
            }
        } else if (f == I_RAISED) {
            boolean raised = on(I_RAISED);
            float width = raised ? 170 : 72;
            box(340 - width, 0, width, 124, 0, 0xFF18181B);
            box(0, 92, 340, 32, 0, 0xFF2A2A30);
            for (int i = 0; i < 12; i++) {
                box(8 + i * 27.5f, 100, 23, 16, 3, 0xFF43434B);
            }
            if (raised) {
                box(340 - width + 8, 58, width - 16, 28, 6, 0xFF34343B);
                label(t("Сообщение...", "Message..."), 340 - width + 16, 77, 12, 0xFFBFBFC8, false);
                outline(340 - width + 6, 56, width - 12, 32, 7, false);
            }
        } else {
            box(250, 0, 90, 124, 0, 0xFF18181B);
            box(258, 12, 44, 7, 3, NICKS[0]);
            box(258, 24, 70, 6, 3, 0x44FFFFFF);
            box(258, 40, 34, 7, 3, NICKS[1]);
            box(258, 52, 62, 6, 3, 0x44FFFFFF);
            if (hides(I_FULLSCREEN, 256, 92, 78, 26, 6)) {
                box(256, 92, 78, 26, 6, 0xFF34343B);
            }
        }
        y = 124;
    }

    // ---- home ----

    private void home() {
        box(0, 0, 320, 250, 0, colors.bg);
        long f = focus;
        boolean live = f == H_COMPACT || f == H_SHELF_FOLLOWED || f == H_SHELF_CLIPS;
        boolean following = !live;
        if (f == H_FOLLOWING) {
            following = on(H_FOLLOWING);
        }
        float at = 12;
        String[] tabs = {t("Отслеживаемое", "Following"), t("В эфире", "Live"), t("Клипы", "Clips")};
        for (int i = 0; i < tabs.length; i++) {
            boolean active = i == (following ? 0 : 1);
            float width = label(tabs[i], at, 24, 14, active ? colors.fg : colors.dim, true);
            if (active) {
                box(at, 30, width, 2, 1, colors.fg);
                if (f == H_FOLLOWING) {
                    outline(at - 5, 8, width + 10, 28, 6, false);
                }
            }
            at += width + 20;
        }
        y = 44;
        if (f == H_ADS) {
            if (hides(H_ADS, 12, y, 296, 52, 6)) {
                gradient(12, y, 296, 52, 6, false, 0xFFFF7F50, 0xFFFF4F9A);
                label(t("Реклама", "Sponsored"), 22, y + 30, 14, WHITE, true);
            }
            y += 60;
        }
        if (f == H_PROMO) {
            label(t("В эфире", "Live"), 12, y + 15, 14, colors.fg, true);
            if (hides(H_PROMO, 130, y, 178, 22, 11)) {
                box(130, y, 178, 22, 11, 0xFFFF75E6);
                label(t("Попробуйте без рекламы", "Try without ads"), 140, y + 15, 11.5f, 0xFF18181B, true);
            }
            y += 30;
        }
        if (live) {
            if (f == H_SHELF_FOLLOWED) {
                shelf(H_SHELF_FOLLOWED, "From channels you follow", false);
            }
            if (f == H_SHELF_CLIPS) {
                shelf(H_SHELF_CLIPS, "Clips for you", true);
            }
            if (f == H_COMPACT && !on(H_COMPACT)) {
                video(12, y, 296, 130, false);
                circle(24, y + 14, 4, LIVE);
                y += 136;
                label("mira", 12, y + 12, 13, colors.fg, true);
            } else {
                stream(0, false);
                stream(1, false);
                stream(2, false);
                if (f == H_COMPACT) {
                    outline(8, 42, 304, 150, 8, false);
                }
            }
        } else {
            if (f == H_CATEGORIES) {
                shelf(H_CATEGORIES, t("Категории", "Categories"), true);
            }
            if (f == H_CONTINUE) {
                shelf(H_CONTINUE, t("Продолжить просмотр", "Continue watching"), false);
            }
            if (f == H_PREVIEW_SIZE) {
                previews(values[VALUE_PREVIEW]);
                box(0, 206, 320, 44, 0, colors.bg);
                box(0, 206, 320, 1, 0, colors.line);
                nav();
                return;
            }
            boolean favorites = f == H_FAVORITES && on(H_FAVORITES);
            if (favorites && adds(H_FAVORITES, 10, y, 300, 104, 6)) {
                label("\u2605", 12, y + 13, 13, NICKS[3], true);
                label(t("Избранное · 3", "Favorites · 3"), 28, y + 13, 13, colors.fg, true);
                y += 20;
                stream(2, false);
                circle(24, y + 12, 11, 0x6653535F);
                circle(54, y + 12, 11, 0x6653535F);
                y += 28;
            }
            stream(0, f == H_STREAK && on(H_STREAK));
            stream(1, false);
            if (f == H_FRIENDS && adds(H_FRIENDS, 10, y, 300, 54, 6)) {
                label(t("Друзья RTE · 2", "RTE friends · 2"), 12, y + 13, 13, colors.fg, true);
                circle(24, y + 34, 10, 0xFF1EC8A5);
                label("kaito", 40, y + 31, 12, colors.fg, true);
                label(t("смотрит mira · Just Chatting", "watching mira · Just Chatting"), 40, y + 45, 10.5f, colors.dim, false);
                y += 58;
            }
            if (f == H_OFFLINE) {
                offline();
            }
            if (f != H_CATEGORIES && f != H_CONTINUE && f != H_OFFLINE && f != H_ADS && f != H_FRIENDS && !favorites) {
                stream(2, false);
            }
        }
        box(0, 206, 320, 44, 0, colors.bg);
        box(0, 206, 320, 1, 0, colors.line);
        nav();
    }

    /** Following's live rows at a preview size: 0 Twitch's, 1 medium, 2 full width. */
    private void previews(int size) {
        if (size == 0) {
            stream(0, false);
            stream(1, false);
            stream(2, false);
            return;
        }
        String[] names = {"mira", "zefir"};
        if (size == 1) {
            for (int i = 0; i < 2; i++) {
                float top = y;
                video(12, top, 136, 76, i == 1);
                circle(22, top + 66, 3, LIVE);
                label(names[i], 158, top + 16, 13, colors.fg, true);
                label(t("Название трансляции", "Stream title"), 158, top + 33, 11.5f, colors.dim, false);
                label("Just Chatting", 158, top + 49, 11, colors.dim, false);
                y += 84;
            }
            outline(8, 40, 304, 166, 8, false);
            return;
        }
        float top = y;
        video(12, top, 296, 118, false);
        circle(24, top + 106, 4, LIVE);
        circle(26, top + 136, 12, 0xFF2F80ED);
        label(names[0], 46, top + 134, 13, colors.fg, true);
        label(t("Название трансляции", "Stream title"), 46, top + 150, 11.5f, colors.dim, false);
        outline(8, top - 4, 304, 162, 8, false);
        y += 160;
    }

    private void stream(int index, boolean streak) {
        float thumb = 64;
        float top = y;
        if (c != null) {
            video(12, top, thumb * 1.6f, thumb * .72f, index == 1);
        }
        String[] names = {"mira", "zefir", "kaito"};
        float textX = 12 + thumb * 1.6f + 10;
        label(names[index], textX, top + 14, 13, colors.fg, true);
        label(t("Название трансляции", "Stream title"), textX, top + 30, 11.5f, colors.dim, false);
        if (streak) {
            box(textX, top + 34, 104, 16, 8, 0xFFFF7F50);
            label("Watch Streak 12", textX + 8, top + 46, 10, 0xFF18181B, true);
            outline(textX - 2, top + 32, 108, 20, 9, false);
        }
        y += thumb * .72f + 10;
    }

    private void shelf(long flag, String title, boolean tall) {
        if (hides(flag, 10, y, 300, tall ? 74 : 62, 6)) {
            label(title, 12, y + 13, 13, colors.fg, true);
            for (int i = 0; i < 4; i++) {
                if (tall) {
                    box(12 + i * 76, y + 20, 68, 54, 4, new int[]{0xFF5B2C6F, 0xFF2F80ED, 0xFF00A86B, 0xFFFF7F50}[i]);
                } else {
                    video(12 + i * 76, y + 20, 68, 40, i % 2 == 1);
                }
            }
        }
        y += tall ? 82 : 70;
    }

    private void offline() {
        if (hides(H_OFFLINE, 10, y, 300, 44, 6)) {
            label(t("Не в эфире", "Offline"), 12, y + 13, 13, colors.fg, true);
            for (int i = 0; i < 2; i++) {
                circle(24 + i * 120, y + 30, 11, 0xFF53535F);
                label(new String[]{"kaito", "zefir"}[i], 40 + i * 120, y + 34, 12, colors.dim, false);
            }
        }
        y += 50;
    }

    private void nav() {
        long[] flags = {0, H_BROWSE, H_CREATE, H_ACTIVITY, 0};
        String[] glyphs = {"home_filled", "ic_search", "ic_add", "ic_notification_inbox", "ic_user"};
        for (int i = 0; i < glyphs.length; i++) {
            float left = 16 + i * 62;
            if (flags[i] == 0) {
                icon(glyphs[i], left + 8, 216, 24, i == 0 ? 0xFFBF94FF : colors.fg);
            } else if (hides(flags[i], left + 6, 214, 28, 28, 7)) {
                icon(glyphs[i], left + 8, 216, 24, colors.fg);
            }
        }
    }

    // ---- emote picker ----

    private void picker() {
        box(0, 0, 320, 170, 0, colors.surface);
        y = 8;
        if (focus == K_STICKY) {
            pickerRows(0);
            if (!on(K_STICKY)) {
                box(0, 0, 320, 26, 0, colors.surface);
                label("7TV", 12, 18, 13, colors.fg, true);
                outline(4, 3, 312, 21, 5, false);
            }
            return;
        }
        if (focus == K_NO_GLOBAL) {
            pickerSection("7TV Channel", 0xFF5CE1E6, 6);
            if (hides(K_NO_GLOBAL, 6, y + 2, 308, 56, 6)) {
                pickerSection("7TV Global", 0xFF5CE1E6, 8);
            }
            return;
        }
        if (focus == K_FAVORITES && on(K_FAVORITES)) {
            pickerSection(t("Избранное", "Favorites"), 0xFFFF7F50, 6);
            outline(4, 4, 312, 58, 6, false);
        }
        if (focus == K_FIRST) {
            box(10, 8, 300, 30, 6, 0x22888888);
            face(on(K_FIRST) ? 18 : 62, 13, 20, 0xFFFF7F50);
            face(on(K_FIRST) ? 62 : 18, 13, 20, 0xFF5CE1E6);
            y = 46;
        }
        pickerSection(t("Часто используемые", "Frequently used"), 0xFFFFC34D, on(K_FREQUENT) ? 6 : 5);
        pickerSection("7TV", 0xFF5CE1E6, 12);
    }

    private void pickerRows(float start) {
        y = start + 4;
        pickerSection("7TV", 0xFF5CE1E6, 24);
    }

    private void pickerSection(String title, int color, int count) {
        label(title, 12, y + 13, 12.5f, colors.fg, true);
        y += 20;
        for (int i = 0; i < count; i++) {
            if (i > 0 && i % 8 == 0) {
                y += 34;
            }
            face(12 + (i % 8) * 38, y, 30, i % 3 == 1 ? 0xFFFFC34D : color);
        }
        y += 40;
    }

    // ---- VOD list ----

    private void vods() {
        box(0, 0, 320, 176, 0, colors.bg);
        y = 8;
        if (focus == V_FILTER) {
            if (adds(V_FILTER, 12, y, 134, 24, 12)) {
                box(12, y, 134, 24, 12, 0x28888888);
                label(t("Все категории  ▾", "All categories  ▾"), 22, y + 16, 11.5f, colors.fg, true);
            }
            y += 32;
        }
        String date = on(V_DATES) ? t("12 октября 2026, 19:05", "October 12, 2026, 7:05 PM")
                : t("1 нед. назад", "1 week ago");
        vodRow(t("Прохождение, часть 3", "Playthrough, part 3"), date, focus == V_SUB && !on(V_SUB));
        vodRow(t("Общение со зрителями", "Just chatting"), focus == V_DATES && on(V_DATES)
                ? t("5 октября 2026, 20:30", "October 5, 2026, 8:30 PM") : t("2 нед. назад", "2 weeks ago"), false);
    }

    private void vodRow(String title, String meta, boolean locked) {
        video(12, y, 112, 63, false);
        if (locked) {
            box(12, y, 112, 63, 0, 0xB0000000);
            icon("ic_lock", 56, y + 20, 24, WHITE);
        }
        label(title, 134, y + 16, 13, colors.fg, true);
        float width = label(meta, 134, y + 35, 12, colors.dim, false);
        if (focus == V_DATES && on(V_DATES)) {
            outline(131, y + 22, width + 6, 19, 5, false);
        }
        y += 71;
    }

    // ---- menus ----

    private void menu() {
        box(0, 0, 320, 180, 0, colors.bg);
        if (focus == M_LINK) {
            video(0, 0, 320, 180, false);
            if (!on(M_LINK)) {
                box(0, 0, 320, 180, 0, 0x99000000);
                box(30, 34, 260, 112, 8, 0xFF26262C);
                label(t("Эта ссылка ведёт на", "This link goes to"), 46, 62, 14, WHITE, true);
                label("example.com", 46, 84, 13, 0xFFBFBFC8, false);
                box(166, 106, 110, 28, 5, colors.accent);
                label(t("Продолжить", "Continue"), 184, 125, 12.5f, WHITE, true);
            } else {
                box(0, 0, 320, 34, 0, 0xFFEFEFF1);
                label("example.com", 14, 22, 13, 0xFF18181B, true);
            }
            return;
        }
        if (focus == M_HISTORY) {
            box(0, 0, 320, 180, 0, colors.surface);
            circle(34, 32, 18, 0xFF2F80ED);
            label("zefir", 62, 28, 15, colors.fg, true);
            label(t("Отслеживает 2 года", "Following for 2 years"), 62, 46, 12, colors.dim, false);
            if (adds(M_HISTORY, 12, 66, 296, 102, 6)) {
                box(12, 66, 296, 102, 6, 0x18888888);
                label(Strings.userHistoryRow(3), 22, 86, 12, colors.dim, true);
                String[] rows = {"19:01  " + t("всем привет", "hey everyone"), "19:03  " + t("вот это момент", "what a play"),
                        "19:05  GG WP"};
                for (int i = 0; i < rows.length; i++) {
                    label(rows[i], 22, 108 + i * 20, 12.5f, colors.fg, false);
                }
            }
            return;
        }
        if (focus == M_MOD) {
            box(0, 0, 320, 180, 0, colors.surface);
            String[] base = {t("Ответить", "Reply"), t("Копировать", "Copy")};
            String[] baseGlyphs = {"ic_chat_show", "ic_share"};
            for (int i = 0; i < base.length; i++) {
                icon(baseGlyphs[i], 18, 14 + i * 50, 22, colors.fg);
                label(base[i], 54, 31 + i * 50, 15, colors.fg, false);
            }
            if (adds(M_MOD, 8, 106, 150, 44, 7)) {
                icon("ic_sword", 18, 114, 22, colors.fg);
                label(t("Модерация", "Moderation"), 54, 131, 15, colors.fg, false);
                box(170, 34, 140, 136, 8, 0xFF2C2C33);
                label("zefir", 182, 56, 13, WHITE, true);
                String[] mod = {t("Удалить сообщение", "Delete message"), t("Таймаут", "Timeout"), t("Бан", "Ban"),
                        t("Снять бан", "Unban")};
                for (int i = 0; i < mod.length; i++) {
                    label(mod[i], 182, 82 + i * 24, 12, i == 2 ? LIVE : 0xFFDEDEE3, false);
                }
            }
            return;
        }
        box(0, 0, 320, 180, 0, colors.surface);
        String[] rows = {t("Ответить", "Reply"), t("Копировать", "Copy")};
        String[] glyphs = {"ic_chat_show", "ic_share"};
        for (int i = 0; i < rows.length; i++) {
            icon(glyphs[i], 18, 14 + i * 50, 22, colors.fg);
            label(rows[i], 54, 31 + i * 50, 15, colors.fg, false);
        }
        if (adds(M_COPY_SEND, 8, 106, 304, 44, 7)) {
            icon("ic_share", 18, 114, 22, colors.accent);
            label(t("Копировать и отправить", "Copy and send"), 54, 131, 15, colors.fg, false);
        }
    }
}
