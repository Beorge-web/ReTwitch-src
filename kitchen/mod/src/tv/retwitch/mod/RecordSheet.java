package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * The live player's record button (bit 256 of {@link ReloadHook}'s strip), the sheet it opens (where to start,
 * quality, when to stop, auto-record for the channel), the dialog to stop a running recording, and the
 * "● REC 42:13" label over the video while the player's controls are hidden.
 */
final class RecordSheet {

    private static final String TAG = "RTRecord";
    private static final int RED = 0xFFEB5757;
    private static final int ACCENT = 0xFF9147FF;
    private static final long[] STOP_AFTER_MS = {0, 3_600_000L, 3 * 3_600_000L};
    private static Handler main;
    private static WeakReference<TextView> label = new WeakReference<>(null);
    private static WeakReference<View> surface = new WeakReference<>(null);
    private static WeakReference<View> strip = new WeakReference<>(null);

    private RecordSheet() {
    }

    private static Handler main() {
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        return main;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    // ---- the button ----

    static View createButton(Context context) {
        final ImageView button = new ImageView(context);
        button.setImageDrawable(new Icon(dp(context, 24)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(Strings.pick("Запись трансляции", "Record the stream"));
        button.setClickable(true);
        button.setOnClickListener(v -> {
            try {
                tap(v.getContext());
            } catch (Throwable t) {
                Log.w(TAG, "record tap failed", t);
                CrashReporter.report(TAG, "record tap failed", t);
            }
        });
        // Red while this channel records: redrawn once a second while on screen.
        button.post(new Runnable() {
            @Override
            public void run() {
                if (button.isAttachedToWindow()) {
                    button.invalidate();
                    button.postDelayed(this, 1000);
                }
            }
        });
        int size = dp(context, 48);
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return button;
    }

    private static void tap(final Context context) {
        final String login = LiveRecord.currentLogin();
        if (login == null) {
            Toast.makeText(context, Strings.pick("Не нашёл канал", "Couldn't find the channel"), Toast.LENGTH_SHORT).show();
            return;
        }
        LiveRecord.Rec running = LiveRecord.find(login);
        if (running != null) {
            showStop(context, running);
            return;
        }
        Toast.makeText(context, Strings.pick("Смотрю, что есть у трансляции...", "Looking at the stream..."),
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            LiveRecord.Info info = null;
            try {
                info = LiveRecord.info(login);
            } catch (Throwable t) {
                Log.w(TAG, "record info failed: " + t);
            }
            final LiveRecord.Info found = info;
            main().post(() -> {
                try {
                    if (found == null) {
                        Toast.makeText(context, Strings.pick("Трансляция не найдена или не в эфире",
                                "The stream isn't live or wasn't found"), Toast.LENGTH_LONG).show();
                    } else {
                        showSheet(context, login, found);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "record sheet failed: " + t); // the activity went away meanwhile
                }
            });
        }, "RTRecordInfo").start();
    }

    private static void showStop(Context context, final LiveRecord.Rec rec) {
        AlertDialog.Builder b = new AlertDialog.Builder(context)
                .setTitle(Strings.pickf("Запись · %s", "Recording · %s", rec.display))
                .setMessage(LiveRecord.line(context, rec, false))
                .setPositiveButton(Strings.pick("Остановить", "Stop"), (d, w) -> LiveRecord.stop(rec))
                .setNegativeButton(Strings.pick("Закрыть", "Close"), null);
        if (rec.archive) {
            b.setNeutralButton(rec.paused ? Strings.downloadResume() : Strings.downloadPause(),
                    (d, w) -> LiveRecord.togglePause(rec));
        }
        b.show();
    }

    // ---- the sheet ----

    private static void showSheet(final Context context, final String login, final LiveRecord.Info info) {
        int bg = SettingsScreen.themeColor(context, android.R.attr.colorBackground, 0xFF0E0E10);
        final boolean dark = Color.luminance(bg) < .5f;
        final int fg = dark ? 0xFFEFEFF1 : 0xFF0E0E10;
        final int dim = dark ? 0xFFADADB8 : 0xFF53535F;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(context, 22), dp(context, 4), dp(context, 22), dp(context, 4));

        box.addView(caption(context, Strings.pick("Начать", "Start"), dim));
        RadioGroup start = new RadioGroup(context);
        final RadioButton fromStart = radio(context, Strings.pick("С начала трансляции", "From the start of the stream"),
                info.createdAt > 0 ? Strings.pickf("уже %s, без рекламы", "%s so far, no ads",
                        LiveRecord.clock((System.currentTimeMillis() - info.createdAt) / 1000.0))
                        : Strings.pick("без рекламы", "no ads"), fg, dim);
        RadioButton fromNow = radio(context, Strings.pick("С этого момента", "From now"),
                info.vodId != null ? null : Strings.pick("реклама вырезается, на её месте будет пропуск",
                        "ads are cut out, leaving a gap"), fg, dim);
        if (info.vodId != null) {
            start.addView(fromStart);
        }
        start.addView(fromNow);
        start.check(info.vodId != null ? fromStart.getId() : fromNow.getId());
        box.addView(start);

        box.addView(caption(context, Strings.pick("Качество", "Quality"), dim));
        String[] qualities = new String[info.options.size()];
        for (int i = 0; i < qualities.length; i++) {
            qualities[i] = info.options.get(i)[0].split(" · ")[0];
        }
        final int[] quality = {0};
        box.addView(chips(context, qualities, quality, fg, dark));

        box.addView(caption(context, Strings.pick("Остановить", "Stop"), dim));
        final int[] stop = {0};
        box.addView(chips(context, new String[]{Strings.pick("С концом эфира", "When the stream ends"),
                Strings.pick("Через 1 ч", "In 1 h"), Strings.pick("Через 3 ч", "In 3 h")}, stop, fg, dark));

        final Switch auto = new Switch(context);
        auto.setText(Strings.pick("Записывать этот канал автоматически", "Record this channel automatically"));
        auto.setTextColor(fg);
        auto.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        auto.setChecked(AutoRecord.has(login));
        auto.setPadding(0, dp(context, 14), 0, dp(context, 4));
        box.addView(auto);
        TextView autoNote = caption(context, Strings.pick("Каждая трансляция с начала, пока включена автозапись в настройках",
                "Every stream from its start, while auto-record is on in settings"), dim);
        autoNote.setPadding(0, 0, 0, dp(context, 8));
        box.addView(autoNote);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        final boolean hadAuto = AutoRecord.has(login);
        new AlertDialog.Builder(context)
                .setTitle(Strings.pickf("Запись · %s", "Record · %s", info.display))
                .setView(scroll)
                .setPositiveButton(Strings.pick("Записать", "Record"), (d, w) -> {
                    String label = info.options.get(quality[0])[0];
                    if (auto.isChecked()) {
                        AutoRecord.put(context, login, label);
                        if (!hadAuto && !AutoRecord.isOn()) {
                            AutoRecord.setOn(context, true);
                        }
                    } else if (hadAuto) {
                        AutoRecord.put(context, login, null);
                    }
                    if (PushRegistration.needsNotificationPermission(context)) {
                        PushRegistration.requestNotificationPermission(context);
                    }
                    long stopAt = STOP_AFTER_MS[stop[0]] == 0 ? 0 : System.currentTimeMillis() + STOP_AFTER_MS[stop[0]];
                    LiveRecord.start(context, new LiveRecord.Rec(login, label,
                            info.vodId != null && fromStart.isChecked(), stopAt, false));
                    Toast.makeText(context, Strings.pick("Запись пошла: Загрузки/ReTwitch",
                            "Recording to Downloads/ReTwitch"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static TextView caption(Context context, String text, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(0, dp(context, 14), 0, dp(context, 6));
        return view;
    }

    private static RadioButton radio(Context context, String title, String subtitle, int fg, int dim) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId());
        CharSequence text = title;
        if (subtitle != null) {
            android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(title).append('\n');
            int at = b.length();
            b.append(subtitle);
            b.setSpan(new android.text.style.ForegroundColorSpan(dim), at, b.length(), 0);
            b.setSpan(new android.text.style.RelativeSizeSpan(.85f), at, b.length(), 0);
            text = b;
        }
        button.setText(text);
        button.setTextColor(fg);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setPadding(dp(context, 6), dp(context, 6), 0, dp(context, 6));
        return button;
    }

    /** One-of chips in a row that scrolls sideways; {@code picked[0]} follows the tap. */
    private static View chips(final Context context, String[] labels, final int[] picked, final int fg, final boolean dark) {
        final LinearLayout row = new LinearLayout(context);
        final int idle = dark ? 0xFF26262C : 0xFFE6E6EA;
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = new TextView(context);
            chip.setText(labels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7));
            chip.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(context, 8);
            row.addView(chip, lp);
            chip.setOnClickListener(v -> {
                picked[0] = index;
                paint(row, picked[0], fg, idle);
            });
        }
        paint(row, picked[0], fg, idle);
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private static void paint(LinearLayout row, int picked, int fg, int idle) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView chip = (TextView) row.getChildAt(i);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(chip.getContext(), 16));
            bg.setColor(i == picked ? ACCENT : idle);
            chip.setBackground(bg);
            chip.setTextColor(i == picked ? Color.WHITE : fg);
        }
    }

    // ---- the REC label ----

    /**
     * From {@link ReloadHook}'s layout pass: keeps the label on the window while something records. The label
     * updates itself once a second; it shows only while the player's controls ({@code bar}, our strip) are hidden,
     * since the red button says the same then.
     */
    static void syncLabel(View root, View bar) {
        if (bar != null) {
            strip = new WeakReference<>(bar);
        }
        TextView view = label.get();
        boolean wanted = Toggles.on(Toggles.LIVE_RECORD) && Toggles.on(Toggles.LIVE_RECORD_LABEL)
                && !LiveRecord.ACTIVE.isEmpty();
        View decorView = root.getRootView();
        if (!wanted || !(decorView instanceof FrameLayout)) {
            if (view != null && !wanted) {
                drop(view);
            }
            return;
        }
        FrameLayout decor = (FrameLayout) decorView;
        if (view != null && view.getParent() == decor) {
            return;
        }
        if (view != null) {
            drop(view);
        }
        final TextView fresh = new TextView(decor.getContext());
        fresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        fresh.setTextColor(Color.WHITE);
        fresh.setSingleLine(true);
        fresh.setPadding(dp(fresh.getContext(), 8), dp(fresh.getContext(), 3), dp(fresh.getContext(), 8),
                dp(fresh.getContext(), 3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3000000);
        bg.setCornerRadius(dp(fresh.getContext(), 10));
        fresh.setBackground(bg);
        fresh.setVisibility(View.GONE);
        decor.addView(fresh, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        label = new WeakReference<>(fresh);
        fresh.post(new Runnable() {
            @Override
            public void run() {
                if (!fresh.isAttachedToWindow() || label.get() != fresh) {
                    return;
                }
                try {
                    place(fresh);
                } catch (Throwable t) {
                    Log.w(TAG, "REC label failed", t);
                }
                if (LiveRecord.ACTIVE.isEmpty()) {
                    drop(fresh);
                    return;
                }
                fresh.postDelayed(this, 1000);
            }
        });
    }

    private static void drop(TextView view) {
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        if (label.get() == view) {
            label = new WeakReference<>(null);
        }
    }

    /** Top right of the video, for the channel the player shows; hidden when it isn't recorded or is small. */
    private static void place(TextView view) {
        FrameLayout decor = (FrameLayout) view.getParent();
        LiveRecord.Rec rec = LiveRecord.find(LiveRecord.playerLogin());
        View video = surface.get();
        if (video == null || !video.isAttachedToWindow() || !video.isShown()) {
            video = Ambilight.largestSurface(decor);
            surface = new WeakReference<>(video);
        }
        View bar = strip.get();
        boolean controls = bar != null && bar.isShown();
        if (rec == null || video == null || controls || video.getWidth() < decor.getWidth() * 0.4f) {
            view.setVisibility(View.GONE);
            return;
        }
        String text = "● REC " + LiveRecord.clock(rec.seconds) + (rec.paused ? " II" : "");
        if (!text.contentEquals(view.getText())) {
            view.setText(text);
        }
        int[] at = new int[2];
        int[] base = new int[2];
        video.getLocationInWindow(at);
        decor.getLocationInWindow(base);
        int margin = dp(view.getContext(), 8);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        int right = Math.max(0, decor.getWidth() - (at[0] - base[0] + video.getWidth())) + margin;
        int top = at[1] - base[1] + margin;
        if (lp.rightMargin != right || lp.topMargin != top) {
            lp.rightMargin = right;
            lp.topMargin = top;
            view.setLayoutParams(lp);
        }
        view.setVisibility(View.VISIBLE);
        if (decor.getChildAt(decor.getChildCount() - 1) != view) {
            view.bringToFront();
        }
    }

    /** A ring with a dot: white, red while the shown channel records. */
    private static final class Icon extends Drawable {
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(int size) {
            this.size = size;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            boolean on = !LiveRecord.ACTIVE.isEmpty() && LiveRecord.find(LiveRecord.currentLogin()) != null;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            paint.setColor(on ? RED : Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size / 12f);
            canvas.drawCircle(cx, cy, size * 0.36f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, size * (on ? 0.2f : 0.17f), paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
