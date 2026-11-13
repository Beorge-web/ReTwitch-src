package tv.retwitch.mod;

import android.content.Context;
import android.util.Log;

/** VOD list dates as date and time ({@code VodDatesPatch}). */
public final class VodDates {

    private VodDates() {
    }

    /**
     * Injected at the entry of {@code VodModel.getRelativeCreatedAtString(Context)} (VOD list rows)
     * and of the static {@code (VodModelBase, Context) -> String} helper of the channel's video
     * shelves (thumbnail overlay): the date and time with {@link Toggles#VOD_ABSOLUTE_DATES};
     * null keeps Twitch's "1 week ago". Never throws.
     */
    public static String vodDate(Object vod, Context context) {
        try {
            if (vod == null || context == null || !Toggles.on(Toggles.VOD_ABSOLUTE_DATES)) {
                return null;
            }
            Object created = vod.getClass().getMethod("getCreatedAt").invoke(vod);
            long ms = parseUtc((String) (created != null ? created : vod.getClass().getMethod("getPublishedAt").invoke(vod)));
            return ms < 0 ? null : android.text.format.DateUtils.formatDateTime(context, ms,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_YEAR
                            | android.text.format.DateUtils.FORMAT_SHOW_TIME | android.text.format.DateUtils.FORMAT_ABBREV_MONTH);
        } catch (Throwable t) {
            Log.w("RTVodDates", "vodDate failed", t);
            return null;
        }
    }

    /** Millis of an ISO-8601 UTC timestamp ({@code 2026-10-28T17:59:41Z}, fraction ignored); -1 if unparsable. */
    static long parseUtc(String iso) {
        if (iso == null || iso.length() < 19) {
            return -1;
        }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (java.text.ParseException e) {
            return -1;
        }
    }
}
