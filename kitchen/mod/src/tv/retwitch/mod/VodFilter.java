package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Category filter for a channel's videos ({@link Toggles#VOD_CATEGORY_FILTER},
 * {@code VodCategoryFilterPatch}). A chip above the native Videos tab opens a search over all
 * Twitch categories, as on the web (the channel's own categories until something is typed); the
 * pick goes into Twitch's own {@code ChannelVodsQuery} game filter ({@code options.gameIDs}) for
 * every shelf and "view all" list of that channel, and the shelves reload. shortcut: kept per
 * channel until the app restarts.
 */
public final class VodFilter {

    private static final String TAG = "RTVodFilter";
    private static final String CHIP_TAG = "retwitch_vod_filter";
    /** channel id -> {game id, game name} */
    private static final Map<String, String[]> PICKED = new ConcurrentHashMap<>();
    private static volatile Field channelField;

    private VodFilter() {
    }

    /**
     * Injected where Twitch's VOD fetcher reads its game id right before building the
     * {@code ChannelVodsQuery} options: the category picked for the fetcher's channel when it has
     * none of its own. Never throws.
     */
    public static String gameId(String original, Object fetcher) {
        if (original != null || fetcher == null || PICKED.isEmpty()) {
            return original;
        }
        try {
            if (!Toggles.on(Toggles.VOD_CATEGORY_FILTER)) {
                return null;
            }
            Field f = channelField;
            if (f == null) {
                for (Field c : fetcher.getClass().getDeclaredFields()) {
                    if (c.getType().getName().equals("tv.twitch.android.models.channel.ChannelInfo")) {
                        c.setAccessible(true);
                        channelField = f = c;
                    }
                }
            }
            String[] picked = PICKED.get(channelId(f.get(fetcher)));
            return picked != null ? picked[0] : null;
        } catch (Throwable t) {
            Log.w(TAG, "gameId failed", t);
            return null;
        }
    }

    /** Injected at the entry of {@code MainVideoListForChannelFragment.onViewCreated}: the chip. Never throws. */
    public static void attach(View root, final Object fragment) {
        try {
            if (!Toggles.on(Toggles.VOD_CATEGORY_FILTER) || !(root instanceof LinearLayout)
                    || root.findViewWithTag(CHIP_TAG) != null) {
                return;
            }
            Bundle args = (Bundle) fragment.getClass().getMethod("getArguments").invoke(fragment);
            final String channel = args == null ? null : channelId(args.getParcelable("channelInfo"));
            if (channel == null) {
                return;
            }
            Context context = root.getContext();
            float dp = context.getResources().getDisplayMetrics().density;
            int bg = SettingsScreen.themeColor(context, android.R.attr.colorBackground, 0xFF0E0E10);
            boolean dark = Color.luminance(bg) < 0.5f;
            final TextView chip = new TextView(context);
            chip.setTag(CHIP_TAG);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTextColor(dark ? 0xFFEFEFF1 : 0xFF0E0E10);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(Math.round(14 * dp), Math.round(7 * dp), Math.round(14 * dp), Math.round(7 * dp));
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(16 * dp);
            shape.setColor(dark ? 0x26FFFFFF : 0x14000000);
            chip.setBackground(shape);
            label(chip, PICKED.get(channel));
            chip.setOnClickListener(v -> pick(chip, channel, fragment));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Math.round(16 * dp), Math.round(8 * dp), Math.round(16 * dp), Math.round(4 * dp));
            LinearLayout list = (LinearLayout) root;
            // After the FTUE banner, above the shelves.
            list.addView(chip, Math.min(1, list.getChildCount()), lp);
        } catch (Throwable t) {
            Log.w(TAG, "attach failed", t);
            CrashReporter.report(TAG, "attach failed", t);
        }
    }

    private static void label(TextView chip, String[] picked) {
        chip.setText(Strings.vodCategory(picked != null ? picked[1] : null) + "  ▾");
    }

    /**
     * The picker, like the web's: a search over every Twitch category (anonymous GQL
     * {@code searchCategories}); until something is typed, the channel's own categories from its
     * last 100 videos, most videos first.
     */
    private static void pick(final TextView chip, final String channel, final Object fragment) {
        final Context context = chip.getContext();
        final Handler main = new Handler(Looper.getMainLooper());
        float dp = context.getResources().getDisplayMetrics().density;
        boolean dark = Color.luminance(SettingsScreen.themeColor(context, android.R.attr.colorBackground, 0xFF0E0E10)) < 0.5f;
        final int fg = dark ? 0xFFEFEFF1 : 0xFF0E0E10;
        // {id (null: no filter), name} per row
        final List<String[]> rows = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1,
                new ArrayList<String>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setTextColor(fg); // Twitch's textColorPrimary is its purple
                return v;
            }
        };
        final List<String[]> own = new ArrayList<>();
        final EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setHint(Strings.vodCategorySearch());
        search.setTextColor(fg);
        search.setHintTextColor(dark ? 0xFFADADB8 : 0xFF53535F);
        ListView list = new ListView(context);
        list.setAdapter(adapter);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(20 * dp), Math.round(8 * dp), Math.round(20 * dp), 0);
        box.addView(search);
        box.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(360 * dp)));
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(Strings.vodCategoryTitle())
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String[] row = rows.get(position);
            if (row[0] == null) {
                PICKED.remove(channel);
            } else {
                PICKED.put(channel, row);
            }
            dialog.dismiss();
            label(chip, PICKED.get(channel));
            reload(fragment);
        });
        final int[] generation = {0};
        final Runnable showOwn = () -> {
            List<String[]> items = new ArrayList<>();
            items.add(new String[]{null, Strings.vodCategoryAll(), null});
            items.addAll(own);
            show(adapter, rows, items);
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            private Runnable pending;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                final String query = s.toString().trim();
                final int mine = ++generation[0];
                main.removeCallbacks(pending);
                if (query.isEmpty()) {
                    showOwn.run();
                    return;
                }
                pending = () -> background(() -> {
                    final List<String[]> found = searchCategories(query);
                    main.post(() -> {
                        if (mine == generation[0]) {
                            show(adapter, rows, found);
                        }
                    });
                });
                main.postDelayed(pending, 300);
            }
        });
        showOwn.run();
        dialog.show();
        background(() -> {
            org.json.JSONArray edges = VodDownload.gql("query($s:ID!){user(id:$s){videos(first:100)"
                    + "{edges{node{game{id displayName}}}}}}", channel)
                    .getJSONObject("user").getJSONObject("videos").getJSONArray("edges");
            final List<String[]> games = categories(edges);
            main.post(() -> {
                own.addAll(games);
                if (search.getText().toString().trim().isEmpty()) {
                    showOwn.run();
                }
            });
        });
    }

    private interface Task {
        void run() throws Exception;
    }

    /** Off the main thread; a failure only logs (the picker keeps what it shows). */
    private static void background(final Task task) {
        new Thread(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.w(TAG, "category lookup failed", t);
            }
        }, TAG).start();
    }

    private static void show(ArrayAdapter<String> adapter, List<String[]> rows, List<String[]> items) {
        rows.clear();
        rows.addAll(items);
        adapter.clear();
        for (String[] row : items) {
            adapter.add(row.length > 2 && row[2] != null ? row[1] + " (" + row[2] + ")" : row[1]);
        }
    }

    /** {id, name} of Twitch's categories matching {@code query}, in Twitch's order (as the web's search). */
    private static List<String[]> searchCategories(String query) throws Exception {
        org.json.JSONArray edges = VodDownload.gql("query($s:String!){searchCategories(query:$s,first:20)"
                + "{edges{node{id displayName}}}}", query)
                .getJSONObject("searchCategories").getJSONArray("edges");
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < edges.length(); i++) {
            org.json.JSONObject node = edges.getJSONObject(i).getJSONObject("node");
            out.add(new String[]{node.getString("id"), node.optString("displayName")});
        }
        return out;
    }

    /** {id, name, count} per category of {@code edges} (GQL video edges), most videos first. */
    static List<String[]> categories(org.json.JSONArray edges) throws org.json.JSONException {
        Map<String, String[]> byId = new LinkedHashMap<>();
        for (int i = 0; i < edges.length(); i++) {
            org.json.JSONObject game = edges.getJSONObject(i).getJSONObject("node").optJSONObject("game");
            if (game == null || game.optString("id").isEmpty()) {
                continue;
            }
            String[] g = byId.get(game.getString("id"));
            if (g == null) {
                byId.put(game.getString("id"), new String[]{game.getString("id"), game.optString("displayName"), "1"});
            } else {
                g[2] = String.valueOf(Integer.parseInt(g[2]) + 1);
            }
        }
        List<String[]> out = new ArrayList<>(byId.values());
        java.util.Collections.sort(out, (a, b) -> Integer.parseInt(b[2]) - Integer.parseInt(a[2]));
        return out;
    }

    /** The fragment's presenter reloads its shelves ({@code retwitchReload}, a patch bridge). */
    private static void reload(Object fragment) {
        try {
            for (Class<?> k = fragment.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object presenter = f.get(fragment);
                    if (presenter == null) {
                        continue;
                    }
                    try {
                        presenter.getClass().getMethod("retwitchReload").invoke(presenter);
                        return;
                    } catch (NoSuchMethodException ignored) {
                        // not the presenter
                    }
                }
            }
            Log.w(TAG, "no presenter to reload");
        } catch (Throwable t) {
            Log.w(TAG, "reload failed", t);
            CrashReporter.report(TAG, "reload failed", t);
        }
    }

    private static String channelId(Object channelInfo) throws Exception {
        Object tuid = channelInfo == null ? null : channelInfo.getClass().getMethod("getTuid").invoke(channelInfo);
        return tuid == null ? null : tuid.toString();
    }
}
