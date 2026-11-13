package tv.retwitch.mod;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.json.JSONObject;

/**
 * Evaluates {@code assets/retwitch-rn.js} ahead of Twitch's bundle. The script is only loaded while
 * something in it is on, so with the toggles off React Native runs untouched.
 */
public final class RnScript {

    private static final String TAG = "RnScript";
    private static final String ASSET = "retwitch-rn.js";
    /** Twitch's bundle has no segments, the id only names the script in native logs. */
    private static final int SEGMENT = 0x7ffffffe;

    private RnScript() {
    }

    /** Called at the start of {@code ReactInstance.loadJSBundle}. */
    public static void load(Object reactInstance) {
        try {
            boolean compactLive = Toggles.on(Toggles.COMPACT_LIVE_TAB);
            boolean hideFollowedShelf = Toggles.on(Toggles.HIDE_LIVE_FOLLOWED_SHELF);
            boolean hideClipsShelf = Toggles.on(Toggles.HIDE_LIVE_CLIPS_SHELF);
            boolean hideStoriesEmpty = Toggles.on(Toggles.HIDE_STORIES_EMPTY);
            String me = TwitchAuth.getUserId();
            boolean friends = Toggles.on(Toggles.FRIENDS_HOME) && !me.isEmpty();
            int previewSize = LandingSettings.getFollowingPreviewSize();
            boolean tabIndicator = Toggles.on(Toggles.FEED_TAB_INDICATOR);
            boolean hideStories = Toggles.on(Toggles.HIDE_HOME_STORIES);
            boolean hideTabs = Toggles.on(Toggles.HIDE_FEED_TABS);
            boolean favorites = Toggles.on(Toggles.FAVORITE_CHANNELS);
            boolean uptime = Toggles.on(Toggles.STREAM_UPTIME);
            boolean profileRecaps = Toggles.on(Toggles.HIDE_PROFILE_RECAPS);
            boolean profileClips = Toggles.on(Toggles.HIDE_PROFILE_FEATURED_CLIPS);
            boolean profileCategories = Toggles.on(Toggles.HIDE_PROFILE_CATEGORIES);
            boolean profileStreamers = Toggles.on(Toggles.HIDE_PROFILE_STREAMERS);
            if (!compactLive && !hideFollowedShelf && !hideClipsShelf && !hideStoriesEmpty && !friends && previewSize == 0
                    && !tabIndicator && !hideStories && !hideTabs && !favorites && !uptime
                    && !profileRecaps && !profileClips && !profileCategories && !profileStreamers) {
                return;
            }
            JSONObject config = new JSONObject()
                    .put("compactLive", compactLive)
                    .put("hideFollowedShelf", hideFollowedShelf)
                    .put("hideClipsShelf", hideClipsShelf)
                    .put("hideStoriesEmpty", hideStoriesEmpty)
                    .put("previewSize", previewSize)
                    .put("tabIndicator", tabIndicator)
                    .put("hideStories", hideStories)
                    .put("hideTabs", hideTabs)
                    .put("uptime", uptime)
                    .put("hideProfileRecaps", profileRecaps)
                    .put("hideProfileClips", profileClips)
                    .put("hideProfileCategories", profileCategories)
                    .put("hideProfileStreamers", profileStreamers)
                    .put("friends", friends ? me : "")
                    .put("favorites", favorites ? FavoriteChannels.list() : null)
                    .put("text", new JSONObject()
                            .put("favorites", Strings.pick("Избранное", "Favorites"))
                            .put("favorite", Strings.pick("Добавить в избранное", "Add to favorites"))
                            .put("unfavorite", Strings.pick("Убрать из избранного", "Remove from favorites"))
                            .put("friends", Strings.pick("Друзья RTE", "RTE friends"))
                            .put("watching", Strings.pick("смотрит", "watching"))
                            .put("more", Strings.pick("Показать всех", "Show all"))
                            .put("less", Strings.pick("Свернуть", "Show less")))
                    .put("locale", Locale.getDefault().toLanguageTag());
            Context context = AppContext.get();
            // registerSegment takes a path, and an asset has none.
            File file = new File(context.getCodeCacheDir(), ASSET);
            try (InputStream in = context.getAssets().open(ASSET); OutputStream out = new FileOutputStream(file)) {
                out.write(("globalThis.__retwitchRn=" + config + ";\n").getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
            }
            reactInstance.getClass().getMethod("registerSegment", int.class, String.class)
                    .invoke(reactInstance, SEGMENT, file.getPath());
        } catch (Throwable t) {
            Log.w(TAG, "load failed", t);
            CrashReporter.report(TAG, "load failed", t);
        }
    }
}
