package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The chat header (the "Chat" title, Subscribe / Gift a Sub, extension and leaderboard
 * icons above chat; in portrait the bar under the player): whether the whole header
 * shows, per orientation. The Gift a Sub button alone is {@link Toggles#HIDE_GIFT_BUTTON}.
 * Stored with the {@link Toggles}; the older settings (three switches, then a "without
 * Gift a Sub" mode here) are migrated on the first read. Read from hooks, never throws.
 */
public final class ChatHeaderSetting {

    public static final int SHOW = 0;
    /** 0.2.0 .. 0.3.2: header shown without Gift a Sub; now SHOW + {@link Toggles#HIDE_GIFT_BUTTON}. */
    private static final int NO_GIFT = 1;
    public static final int HIDE_PORTRAIT = 2;
    public static final int HIDE_LANDSCAPE = 3;
    public static final int HIDE_ALL = 4;

    private static final String KEY = "chat_header_mode";
    /** Settings choice order -> mode. */
    private static final int[] CHOICES = {SHOW, HIDE_PORTRAIT, HIDE_LANDSCAPE, HIDE_ALL};

    private ChatHeaderSetting() {
    }

    public static int mode() {
        try {
            Context context = AppContext.get();
            if (context == null) {
                return SHOW;
            }
            SharedPreferences prefs = Toggles.prefs(context);
            int mode = prefs.contains(KEY) ? prefs.getInt(KEY, SHOW) : migrate(prefs);
            if (mode == NO_GIFT) {
                mode = SHOW;
                prefs.edit().putBoolean(Toggles.HIDE_GIFT_BUTTON.key, true).apply();
            }
            if (!prefs.contains(KEY) || prefs.getInt(KEY, SHOW) != mode) {
                prefs.edit().putInt(KEY, mode).apply();
            }
            return mode;
        } catch (Throwable t) {
            return SHOW;
        }
    }

    /** Old switches (0.1.4 and earlier); with the gift switch plus one orientation only the orientation survived. */
    private static int migrate(SharedPreferences prefs) {
        boolean portrait = prefs.getBoolean("hide_portrait_chat_header", false);
        boolean landscape = prefs.getBoolean("hide_landscape_chat_header", false);
        if (portrait && landscape) {
            return HIDE_ALL;
        }
        if (portrait) {
            return HIDE_PORTRAIT;
        }
        if (landscape) {
            return HIDE_LANDSCAPE;
        }
        return prefs.getBoolean("hide_gift_button", false) ? NO_GIFT : SHOW;
    }

    /** Index of the current mode in the settings choice ({@code Strings.chatHeaderModes}). */
    static int choice() {
        int mode = mode();
        for (int i = 0; i < CHOICES.length; i++) {
            if (CHOICES[i] == mode) {
                return i;
            }
        }
        return 0;
    }

    static void setChoice(Context context, int index) {
        Toggles.prefs(context).edit().putInt(KEY, CHOICES[index]).apply();
    }

    /** Whether the whole header is hidden in this orientation. */
    public static boolean hidden(boolean landscape) {
        int mode = mode();
        return mode == HIDE_ALL || mode == (landscape ? HIDE_LANDSCAPE : HIDE_PORTRAIT);
    }

    /** Whether Twitch should leave the Gift a Sub button out of the header. */
    public static boolean giftHidden() {
        mode(); // migrates the old "without Gift a Sub" mode into the switch
        return Toggles.on(Toggles.HIDE_GIFT_BUTTON);
    }
}
