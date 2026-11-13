package tv.retwitch.mod;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * The launcher icon. {@code DarkIconPatch} moves the launcher entry onto one activity-alias per icon
 * (the first one enabled in the manifest); picking an icon goes through them in order, as AyuGram
 * does. The launcher redraws on its own after a few seconds.
 */
final class AppIcon {

    /** Same order and names as the aliases in {@code DarkIconPatch}. */
    private static final String[] ALIASES = {
            "tv.retwitch.mod.IconStars", "tv.retwitch.mod.IconStock", "tv.retwitch.mod.IconDark",
            "tv.retwitch.mod.IconBlack",
    };

    /** The aliases' icons, for the picker. */
    static final String[] DRAWABLES = {"rt_ic_launcher_stars", "rt_ic_launcher_stock", "rt_ic_launcher_dark",
            "rt_ic_launcher_black"};

    private AppIcon() {
    }

    static int current(Context context) {
        PackageManager pm = context.getPackageManager();
        for (int i = 0; i < ALIASES.length; i++) {
            int state = pm.getComponentEnabledSetting(new ComponentName(context, ALIASES[i]));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && i == 0) {
                return i;
            }
        }
        return 0;
    }

    static void set(Context context, int index) {
        PackageManager pm = context.getPackageManager();
        for (int i = 0; i < ALIASES.length; i++) {
            pm.setComponentEnabledSetting(new ComponentName(context, ALIASES[i]),
                    i == index ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
