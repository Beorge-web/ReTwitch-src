package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Favorite channels of the React Native Following tab ({@code retwitch-rn.js}, toggle {@code FAVORITE_CHANNELS}):
 * {@code [{id, login, name}]} in the order they were added. The script gets the list in its config and sends every
 * change back as a fetch of {@link #RN_URL} with the whole list, which the RN request hook
 * ({@link AdSettings#rnRequestUrl}) hands here and fails. The native channel page's ⋮ menu adds and removes too
 * ({@code ProfileMenuFavoritePatch}, feedback idea #64); the script only learns of that after a restart, so its saves get
 * this session's native changes merged in ({@link #NATIVE_ADDED}, {@link #NATIVE_REMOVED}).
 */
public final class FavoriteChannels {

    private static final String TAG = "RTFavorites";
    static final String RN_URL = "https://retwitch.invalid/favorites?";
    private static final String PREFS = "retwitch_favorites";
    private static final String KEY = "list";
    private static final int MAX = 200;
    private static final Pattern ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9_]{1,25}");

    /** Ids starred or unstarred in the native menu since the start, kept over the script's next saves. */
    private static final Set<String> NATIVE_ADDED = new HashSet<>();
    private static final Set<String> NATIVE_REMOVED = new HashSet<>();
    /** The native ⋮ menu's channel {id, login, name}, from its dialog's Args ({@link #onProfileMenu}). */
    private static volatile String[] menuChannel;

    private FavoriteChannels() {
    }

    /**
     * Entry of the native channel page's options dialog ({@code ChannelProfileOptionsDialogFragment.onCreateView}): its
     * presenter holds the Args (channel id, name, display name), read by return types. Never throws.
     */
    public static void onProfileMenu(Object fragment) {
        try {
            Object args = null;
            for (Method m : fragment.getClass().getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType().getName().endsWith(".profile.header.e")) {
                    m.setAccessible(true);
                    Object presenter = m.invoke(fragment);
                    for (Method a : presenter.getClass().getMethods()) {
                        if (a.getParameterTypes().length == 0 && a.getReturnType().getName().endsWith("OptionsDialogFragment$Args")) {
                            args = a.invoke(presenter);
                        }
                    }
                }
            }
            if (args == null) {
                Log.w(TAG, "profile menu: no Args");
                return;
            }
            menuChannel = new String[]{
                    (String) args.getClass().getMethod("getChannelId").invoke(args),
                    (String) args.getClass().getMethod("getChannelName").invoke(args),
                    (String) args.getClass().getMethod("getChannelDisplayName").invoke(args),
            };
        } catch (Throwable t) {
            Log.w(TAG, "profile menu failed", t);
        }
    }

    /** The menu row's label for the channel {@code login}, or null for no row. Compose calls it on every recomposition. */
    public static String nativeRowLabel(String login) {
        String[] channel = menuChannel;
        if (!Toggles.on(Toggles.FAVORITE_CHANNELS) || channel == null || login == null || !login.equalsIgnoreCase(channel[1])) {
            return null;
        }
        return has(channel[0]) ? Strings.pick("Убрать из избранного", "Remove from favorites")
                : Strings.pick("В избранное", "Add to favorites");
    }

    public static int nativeRowIcon(String login) {
        String[] channel = menuChannel;
        boolean on = channel != null && has(channel[0]);
        return UiHider.res(AppContext.get(), on ? "ic_subscribe_button_star_filled" : "ic_subscribe_button_star_empty", "drawable");
    }

    /** The row's click ({@code type} is Twitch's Function0): star or unstar, then close the menu as its own rows do. */
    public static Object nativeRowAction(String login, Object dismiss, Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "RTFavoriteRow";
                default:
                    toggleNative();
                    try {
                        dismiss.getClass().getMethod("invoke").invoke(dismiss);
                    } catch (Throwable t) {
                        Log.w(TAG, "dismiss failed", t);
                    }
                    return null;
            }
        });
    }

    private static void toggleNative() {
        String[] channel = menuChannel;
        Context context = AppContext.get();
        if (channel == null || context == null) {
            return;
        }
        try {
            JSONArray now = list();
            JSONArray next = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < now.length(); i++) {
                if (channel[0].equals(now.getJSONObject(i).optString("id"))) {
                    removed = true;
                } else {
                    next.put(now.getJSONObject(i));
                }
            }
            if (!removed) {
                next.put(new JSONObject().put("id", channel[0]).put("login", channel[1]).put("name", channel[2]));
            }
            synchronized (NATIVE_ADDED) {
                (removed ? NATIVE_REMOVED : NATIVE_ADDED).add(channel[0]);
                (removed ? NATIVE_ADDED : NATIVE_REMOVED).remove(channel[0]);
            }
            prefs(context).edit().putString(KEY, clean(next).toString()).apply();
            Toast.makeText(context, removed ? Strings.pickf("%s убран из избранного", "%s removed from favorites", channel[2])
                    : Strings.pickf("%s в избранном. В «Отслеживаемом» - после перезапуска", "%s is a favorite. In Following after a restart",
                    channel[2]), Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "native toggle failed", t);
        }
    }

    private static boolean has(String id) {
        JSONArray now = list();
        for (int i = 0; i < now.length(); i++) {
            if (id.equals(now.optJSONObject(i).optString("id"))) {
                return true;
            }
        }
        return false;
    }

    /** The saved list for the script's config; empty if there is none. Never throws. */
    static JSONArray list() {
        try {
            return clean(new JSONArray(prefs(AppContext.get()).getString(KEY, "[]")));
        } catch (Throwable t) {
            Log.w(TAG, "list failed", t);
            return new JSONArray();
        }
    }

    /** The script's save: keeps the list from the URL and returns a URL that fails the request. Never throws. */
    static String fromRn(String url) {
        try {
            JSONArray list = withNative(clean(new JSONArray(Uri.parse(url).getQueryParameter(KEY))));
            prefs(AppContext.get()).edit().putString(KEY, list.toString()).apply();
            Log.i(TAG, "saved " + list.length());
        } catch (Throwable t) {
            Log.w(TAG, "save failed", t);
        }
        return "http://127.0.0.1:0/";
    }

    /** The script's list with this session's native stars and unstars put over it (the script's list is from its start). */
    private static JSONArray withNative(JSONArray rn) throws org.json.JSONException {
        Set<String> added;
        Set<String> removed;
        synchronized (NATIVE_ADDED) {
            if (NATIVE_ADDED.isEmpty() && NATIVE_REMOVED.isEmpty()) {
                return rn;
            }
            added = new HashSet<>(NATIVE_ADDED);
            removed = new HashSet<>(NATIVE_REMOVED);
        }
        JSONArray saved = list();
        JSONArray out = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rn.length(); i++) {
            String id = rn.getJSONObject(i).optString("id");
            if (!removed.contains(id) && ids.add(id)) {
                out.put(rn.getJSONObject(i));
            }
        }
        for (int i = 0; i < saved.length(); i++) {
            String id = saved.getJSONObject(i).optString("id");
            if (added.contains(id) && ids.add(id)) {
                out.put(saved.getJSONObject(i));
            }
        }
        return out;
    }

    /** Well-formed entries only, each id once, at most {@link #MAX}. */
    static JSONArray clean(JSONArray in) throws org.json.JSONException {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < in.length() && out.length() < MAX; i++) {
            JSONObject entry = in.optJSONObject(i);
            String id = entry == null ? "" : entry.optString("id");
            String login = entry == null ? "" : entry.optString("login");
            if (!ID.matcher(id).matches() || !LOGIN.matcher(login).matches() || !seen.add(id)) {
                continue;
            }
            String name = entry.optString("name", login);
            out.put(new JSONObject().put("id", id).put("login", login)
                    .put("name", name.isEmpty() || name.length() > 64 ? login : name));
        }
        return out;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
