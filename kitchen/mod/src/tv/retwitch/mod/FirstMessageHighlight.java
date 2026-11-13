package tv.retwitch.mod;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * RTE's "first message" highlight: a viewer's first message in a channel
 * (IRC tag {@code first-msg=1}, kept in the chat message's tag map) gets a
 * purple background and a "FIRST MESSAGE" label. The tag is seen in the chat
 * event dispatcher ({@link ChatFilters}) with the message's {@code id} tag; the
 * row model carries the same id, so the row is matched exactly (its text is
 * rebuilt on every bind and can repeat).
 */
public final class FirstMessageHighlight {

    private static final String TAG = "RTFirstMsg";
    private static final int BACKGROUND = 0x52C832C8; // RTE #c832c852
    private static final int LABEL_COLOR = 0xFFC832C8;

    /** Ids of first messages, newest kept. */
    private static final Set<String> IDS = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 256;
                }
            }));
    /** String fields of the row model, one of them the id. */
    private static List<Field> rowFields;
    /** The row being bound is a first message, read once by {@link #isFirst}. UI thread. */
    private static boolean rowFirst;
    private static final Map<TextView, Integer> originalTopPadding = new WeakHashMap<>();
    private static boolean loggedTags;

    private FirstMessageHighlight() {
    }

    /** From {@link ChatFilters#filterEvent} for each live chat message. Never throws. */
    static void onChatMessage(Map<?, ?> tags) {
        try {
            if (tags == null) {
                return;
            }
            if (!loggedTags) {
                loggedTags = true;
                Log.d(TAG, "chat message tag keys: " + tags.keySet());
            }
            Object id = tags.get("id");
            if (id != null && "1".equals(String.valueOf(tags.get("first-msg")))) {
                IDS.add(id.toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "onChatMessage failed", t);
            CrashReporter.report(TAG, "onChatMessage failed", t);
        }
    }

    /** Injected at the entry of the message row bind with its model, before the text bind. Never throws. */
    public static void onRowBind(Object row) {
        rowFirst = false;
        UserHistory.onRowBind(row);
        RecentMessages.onMessageRowBound(row);
        tv.retwitch.mod.emotes.MentionHighlighter.onRowBind(row);
        try {
            if (row == null || IDS.isEmpty() || !Toggles.on(Toggles.FIRST_MESSAGE)) {
                return;
            }
            if (rowFields == null) {
                List<Field> fields = new ArrayList<>();
                for (Field f : row.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        fields.add(f);
                    }
                }
                rowFields = fields;
            }
            // Which field is the id doesn't matter: ids are UUIDs, the others names and text.
            for (Field f : rowFields) {
                Object value = f.get(row);
                if (value != null && IDS.contains(value)) {
                    rowFirst = true;
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onRowBind failed", t);
            CrashReporter.report(TAG, "onRowBind failed", t);
        }
    }

    /** The text bind of the row {@link #onRowBind} just saw; other rows bind text too, so it's read once. */
    public static boolean isFirst() {
        boolean first = rowFirst;
        rowFirst = false;
        return first;
    }

    /**
     * Sets the row background: the first-message highlight, else {@code color}
     * (mention highlight or transparent). Rows are recycled, so the label's
     * extra top padding is undone on rows that aren't first messages.
     */
    public static void apply(TextView row, boolean first, int color) {
        Integer top = originalTopPadding.get(row);
        if (first) {
            if (top == null) {
                top = row.getPaddingTop();
                originalTopPadding.put(row, top);
            }
            int labelSpace = Math.round(14 * row.getResources().getDisplayMetrics().density);
            row.setPadding(row.getPaddingLeft(), top + labelSpace, row.getPaddingRight(), row.getPaddingBottom());
            row.setBackground(new LabelDrawable(Strings.firstMessageLabel(),
                    row.getResources().getDisplayMetrics().density));
        } else {
            if (top != null) {
                row.setPadding(row.getPaddingLeft(), top, row.getPaddingRight(), row.getPaddingBottom());
                originalTopPadding.remove(row);
            }
            row.setBackground(new ColorDrawable(color));
        }
    }

    /** RTE's look: tinted background, small uppercase label in the top-right corner. */
    private static final class LabelDrawable extends Drawable {
        private final String label;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        LabelDrawable(String label, float density) {
            this.label = label;
            this.density = density;
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(10 * density);
            paint.setTextAlign(Paint.Align.RIGHT);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.drawColor(BACKGROUND);
            paint.setColor(LABEL_COLOR);
            canvas.drawText(label, b.right - 8 * density, b.top + 12 * density, paint);
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
