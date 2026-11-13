package tv.retwitch.mod;

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The ReTwitch settings screen: a full-screen stack of pages with rows grouped into cards. Generic UI
 * only; the pages live in {@link SettingsHook}. Settings apply as soon as a row changes; ones that need
 * an app restart raise a single bar at the bottom, which goes away if the change is undone.
 *
 * <p>A row with something to explain ({@link #look}, {@link #compare}, {@link #note}) has two zones, like
 * Android's primary switch rows: the text unfolds the explanation in place, the control changes the
 * setting. The "before" and "after" panes it unfolds change the setting too. A page whose options add up
 * pins a live mock-up above the list ({@link #stage}).
 *
 * <p>Rows added in the running version carry a NEW badge ({@code markNew(version)}, or
 * {@code Toggle.asNew(version)}); their section header and every sub-page row leading to them get one
 * too, so testers can find what to check.
 *
 * <p>The search field walks every page from the root and keeps rows whose title or explanation matches
 * in Russian or English ({@link Strings#otherLanguage} maps each shown string to the other one).
 */
final class SettingsScreen {

    interface Page {
        void build(SettingsScreen screen);
    }

    interface BoolSetter {
        void set(boolean on);
    }

    interface IntSetter {
        void set(int value);
    }

    /** Copies the real settings into a live preview. */
    interface Sync {
        void apply(SettingsPreview preview);
    }

    /** Fills a row's unfolded area, built on first use. */
    private interface Detail {
        void fill(LinearLayout area);
    }

    /** What the next row explains itself with. */
    private static final class Extra {
        boolean isNew;
        int scene;
        long focus;
        int slot = -1;
        String before;
        String after;
        String note;
        String glyph;
        int tint;
    }

    private static final String TAG = "RTSettings";
    private static final int ACCENT = 0xFF9147FF; // Twitch purple

    final Context context;
    private final Dialog dialog;
    private final TextView title;
    private final ScrollView scroll;
    private final FrameLayout stageSlot;
    /** Where rows go. */
    private LinearLayout list;
    private LinearLayout card;
    private final LinearLayout restartBar;
    private final ArrayList<String> titles = new ArrayList<>();
    private final ArrayList<Page> pages = new ArrayList<>();
    private final ArrayList<Integer> offsets = new ArrayList<>();
    private final int bg;
    private final int surface;
    private final int fg;
    private final int fgDim;
    private final int line;
    private final int accentText;
    private final SettingsPreview.Colors colors;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final HashMap<String, Drawable> icons = new HashMap<>();
    private Extra next = new Extra();
    /** Header of the current section, badged when a NEW row lands in it. */
    private LinearLayout lastSection;
    /** Sub-page (lambda class) -> whether it holds a NEW row; fixed for the screen's life. */
    private final HashMap<Class<?>, Boolean> probed = new HashMap<>();
    /** A page is being built only to count its rows: nothing is added to the screen. */
    private boolean probing;
    private int probeNew;
    private int probeOn;
    private final FrameLayout searchBox;
    private final EditText search;
    /** Normalized search text; null while the field is empty. */
    private String query;
    /** Breadcrumb of the page and section being searched, and the last one shown. */
    private String searchPath;
    private String searchSection;
    private String searchShown;
    private final HashSet<Class<?>> searched = new HashSet<>();
    /** The unfolded row: one at a time, kept across a page refresh by its key. */
    private String openKey;
    private LinearLayout openArea;
    private View openChevron;
    /** Refresh what shows a setting's state (panes, radio rows) after a change. */
    private final ArrayList<Runnable> syncs = new ArrayList<>();
    private SettingsPreview stage;
    private Sync stageSync;
    /** Value each restart-needing row had when the screen opened, to drop the bar when it is put back. */
    private final HashMap<String, Object> restartBase = new HashMap<>();
    private final HashSet<String> restartPending = new HashSet<>();
    private boolean restartForced;

    static void show(Context context, String title, Page page) {
        try {
            SettingsScreen screen = new SettingsScreen(context);
            screen.open(title, page);
            screen.dialog.show();
        } catch (Throwable t) {
            Log.w(TAG, "settings screen failed", t);
            CrashReporter.report(TAG, "settings screen failed", t);
        }
    }

    private SettingsScreen(Context context) {
        this.context = context;
        bg = themeColor(context, android.R.attr.colorBackground, 0xFF0E0E10);
        // Twitch's textColorPrimary is its purple; pick text colors from the background instead.
        boolean dark = Color.luminance(bg) < 0.5f;
        surface = dark ? 0xFF1C1C21 : 0xFFFFFFFF;
        fg = dark ? 0xFFEFEFF1 : 0xFF0E0E10;
        fgDim = dark ? 0xFFADADB8 : 0xFF53535F;
        line = dark ? 0x1AFFFFFF : 0x1A000000;
        accentText = dark ? 0xFFBF94FF : 0xFF772CE8;
        colors = new SettingsPreview.Colors(dark ? bg : 0xFFF2F2F4, surface, fg, fgDim, line, ACCENT);

        dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar) {
            @Override
            public void onBackPressed() {
                back();
            }

            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                // Back from a picker or a sub-dialog: refresh summaries/values.
                if (hasFocus && !pages.isEmpty()) {
                    render(true);
                }
            }
        };

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Twitch's own toolbar chevron; the arrow glyph only if the resource is gone.
        int chevron = UiHider.res(context, "ic_arrow_left", "drawable");
        TextView backButton = text(chevron != 0 ? "" : "←", 22, fg);
        if (chevron != 0) {
            Drawable icon = context.getDrawable(chevron).mutate();
            icon.setTint(fg);
            icon.setBounds(0, 0, dp(24), dp(24));
            backButton.setCompoundDrawables(icon, null, null, null);
            backButton.setPadding(dp(16), 0, 0, 0);
        }
        backButton.setGravity(Gravity.CENTER);
        backButton.setContentDescription(Strings.back());
        backButton.setBackground(ripple());
        backButton.setOnClickListener(v -> back());
        bar.addView(backButton, new LinearLayout.LayoutParams(dp(56), dp(56)));
        title = text("", 20, fg);
        title.setTypeface(medium);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        searchBox = new FrameLayout(context);
        searchBox.setBackground(rounded(surface, dp(14)));
        search = new EditText(context);
        ImageView clear = glyph("ic_close_small", fgDim, 20);
        buildSearch(clear);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        searchLp.setMargins(dp(12), dp(2), dp(12), dp(6));
        root.addView(searchBox, searchLp);

        stageSlot = new FrameLayout(context);
        stageSlot.setPadding(dp(12), 0, dp(12), dp(8));
        stageSlot.setVisibility(View.GONE);
        root.addView(stageSlot);

        scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // Keeps rows readable on tablets and in landscape.
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int side = Math.max(dp(12), (r - l - dp(640)) / 2);
            if (list.getPaddingLeft() != side) {
                list.setPadding(side, 0, side, dp(32));
            }
        });

        restartBar = buildRestartBar();
        root.addView(restartBar);

        // Edge-to-edge (targetSdk 35+): keep the bar and the restart bar clear of the system bars.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(bg));
            window.setStatusBarColor(bg);
            window.setNavigationBarColor(bg);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void buildSearch(ImageView clear) {
        ImageView magnifier = glyph("ic_search", fgDim, 20);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER_VERTICAL);
        iconLp.leftMargin = dp(14);
        searchBox.addView(magnifier, iconLp);

        search.setHint(Strings.settingsSearchHint());
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setTextColor(fg);
        search.setHintTextColor(fgDim);
        search.setBackground(null);
        search.setPadding(dp(44), 0, dp(44), 0);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, actionId, event) -> {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                clear.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
                render(false);
            }
        });
        searchBox.addView(search, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        clear.setVisibility(View.GONE);
        clear.setPadding(dp(13), dp(13), dp(13), dp(13));
        clear.setContentDescription(Strings.close());
        clear.setOnClickListener(v -> search.setText(""));
        searchBox.addView(clear, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.END | Gravity.CENTER_VERTICAL));
    }

    private LinearLayout buildRestartBar() {
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(rounded(ACCENT, dp(14)));
        bar.setPadding(dp(16), dp(12), dp(10), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(6), dp(12), dp(10));
        bar.setLayoutParams(lp);
        bar.addView(text(Strings.restartBar(), 14, Color.WHITE),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView button = text(Strings.restartNow(), 14, ACCENT);
        button.setTypeface(medium);
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setBackground(rounded(Color.WHITE, dp(10)));
        bar.addView(button);
        bar.setVisibility(View.GONE);
        bar.setOnClickListener(v -> SettingsHook.restartApp(context));
        return bar;
    }

    // ---- navigation ----

    void open(String pageTitle, Page page) {
        if (!pages.isEmpty()) {
            offsets.add(scroll.getScrollY());
        }
        titles.add(pageTitle);
        pages.add(page);
        openKey = null;
        if (query != null) {
            search.setText(""); // renders through the watcher
        } else {
            render(false);
        }
        slide(true);
    }

    private void back() {
        if (query != null) {
            search.setText("");
        } else if (pages.size() > 1) {
            titles.remove(titles.size() - 1);
            pages.remove(pages.size() - 1);
            openKey = null;
            render(false);
            int offset = offsets.remove(offsets.size() - 1);
            scroll.post(() -> scroll.scrollTo(0, offset));
            slide(false);
        } else {
            dialog.dismiss();
        }
    }

    private void slide(boolean forward) {
        scroll.setAlpha(0f);
        scroll.setTranslationX(dp(forward ? 24 : -24));
        scroll.animate().alpha(1f).translationX(0f).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void render(boolean keepScroll) {
        final int y = scroll.getScrollY();
        list.removeAllViews();
        next = new Extra();
        lastSection = null;
        card = null;
        openArea = null;
        openChevron = null;
        syncs.clear();
        stage = null;
        stageSync = null;
        stageSlot.removeAllViews();
        stageSlot.setVisibility(View.GONE);
        String q = normalize(search.getText().toString().trim());
        query = q.isEmpty() ? null : q;
        title.setText(titles.get(titles.size() - 1));
        // Search lives on the root page only (and stays while it has text).
        searchBox.setVisibility(pages.size() == 1 || query != null ? View.VISIBLE : View.GONE);
        try {
            if (query == null) {
                pages.get(pages.size() - 1).build(this);
            } else {
                searchAll();
            }
        } catch (Throwable t) {
            Log.w(TAG, "page build failed", t);
            CrashReporter.report(TAG, "page build failed", t);
        }
        scroll.post(() -> scroll.scrollTo(0, keepScroll ? y : 0));
    }

    /** Builds every page reachable from the root; {@link #skip} keeps the matching rows. */
    private void searchAll() {
        searchPath = "";
        searchSection = null;
        searchShown = null;
        searched.clear();
        Strings.otherLanguage = new ConcurrentHashMap<>();
        try {
            pages.get(0).build(this);
        } finally {
            Strings.otherLanguage = null;
        }
        if (list.getChildCount() == 0) {
            TextView none = text(Strings.settingsSearchEmpty(), 14, fgDim);
            none.setPadding(dp(16), dp(20), dp(16), dp(4));
            list.addView(none);
        }
    }

    private boolean matches(String value) {
        if (value == null) {
            return false;
        }
        if (normalize(value).contains(query)) {
            return true;
        }
        Map<String, String> other = Strings.otherLanguage;
        String translated = other != null ? other.get(value) : null;
        return translated != null && normalize(translated).contains(query);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static String join(String path, String part) {
        if (part == null || part.isEmpty()) {
            return path;
        }
        return path.isEmpty() ? part : path + " › " + part;
    }

    /**
     * Whether a row stays out of the screen: a probe only counts it, a search drops it unless it matches.
     * Also opens the section a kept search result belongs to.
     */
    private boolean skip(String rowTitle, String summary, Extra extra) {
        if (probing) {
            probeNew += extra.isNew ? 1 : 0;
            return true;
        }
        if (query == null) {
            return false;
        }
        if (!matches(rowTitle) && !matches(summary) && !matches(extra.note) && !matches(extra.before)
                && !matches(extra.after)) {
            return true;
        }
        String crumb = join(searchPath, searchSection);
        if (!crumb.equals(searchShown)) {
            searchShown = crumb;
            card = null;
            lastSection = crumb.isEmpty() ? null : header(crumb);
        }
        return false;
    }

    private CharSequence mark(String value) {
        if (query == null || value == null) {
            return value;
        }
        int at = normalize(value).indexOf(query);
        if (at < 0 || at + query.length() > value.length()) {
            return value;
        }
        SpannableString marked = new SpannableString(value);
        marked.setSpan(new BackgroundColorSpan(0x669147FF), at, at + query.length(), 0);
        return marked;
    }

    // ---- what the next row explains itself with ----

    /** Gives the next row a NEW badge if it was added in {@code version} and that is the running one. */
    SettingsScreen markNew(String version) {
        next.isNew |= BuildInfo.VERSION_NAME.equals(version);
        return this;
    }

    /** The next switch shows {@code focus} of a scene before and after. */
    SettingsScreen look(int scene, long focus) {
        next.scene = scene;
        next.focus = focus;
        return this;
    }

    /** The next pick-one row shows each choice in a scene, fed into its {@link SettingsPreview#values} slot. */
    SettingsScreen lookEach(int scene, long focus, int slot) {
        next.scene = scene;
        next.focus = focus;
        next.slot = slot;
        return this;
    }

    /** The next switch says what changes, for options with nothing to draw. */
    SettingsScreen compare(String before, String after) {
        next.before = before;
        next.after = after;
        return this;
    }

    /** A longer explanation, folded under the next row. */
    SettingsScreen note(String text) {
        next.note = text;
        return this;
    }

    /** A coloured icon tile at the start of the next row. */
    SettingsScreen tile(String glyph, int tint) {
        next.glyph = glyph;
        next.tint = tint;
        return this;
    }

    private Extra take() {
        Extra extra = next;
        next = new Extra();
        return extra;
    }

    private Extra take(Toggles.Toggle toggle) {
        if (toggle.isNew) {
            next.isNew = true;
        }
        if (toggle.scene != 0) {
            next.scene = toggle.scene;
            next.focus = toggle.focus;
        }
        if (toggle.before() != null) {
            next.before = toggle.before();
            next.after = toggle.after();
        }
        if (toggle.note() != null) {
            next.note = toggle.note();
        }
        return take();
    }

    // ---- page parts ----

    void section(String text) {
        card = null;
        if (probing) {
            return;
        }
        if (query != null) {
            searchSection = text;
            return;
        }
        lastSection = header(text);
    }

    private LinearLayout header(String text) {
        TextView header = text(text, 13, fgDim);
        header.setTypeface(medium);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(20), dp(16), dp(8));
        row.addView(header);
        list.addView(row);
        return row;
    }

    /** The page is being built for the search field, not shown. */
    boolean searching() {
        return query != null || probing;
    }

    void hint(String text) {
        if (query != null || probing) {
            return;
        }
        TextView hint = text(text, 13, fgDim);
        hint.setPadding(dp(16), dp(10), dp(16), dp(4));
        hint.setLineSpacing(dp(2), 1f);
        list.addView(hint);
        card = null;
    }

    /** Pins a mock-up of the page's scene above the rows; it follows every change on the page. */
    void stage(int scene, Sync sync) {
        stage(scene, 0, sync);
    }

    void stage(int scene, long focus, Sync sync) {
        if (query != null || probing) {
            return;
        }
        stage = new SettingsPreview(context, scene, focus, colors, icons);
        stageSync = sync;
        sync.apply(stage);
        FrameLayout frame = frame(stage);
        if (context.getResources().getConfiguration().screenHeightDp < 560) { // landscape: it would leave no room
            list.addView(frame, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        // The player is 16:9: full width would take a third of the screen.
        int width = SettingsPreview.stacked(scene) ? ViewGroup.LayoutParams.MATCH_PARENT : dp(250);
        stageSlot.addView(frame, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));
        stageSlot.setVisibility(View.VISIBLE);
    }

    /** The brand card on top of the root page; a plain row while searching. */
    void hero(String heading, String subtitle, Page page) {
        if (query != null || probing) {
            nav(Strings.menuAbout(), subtitle, page);
            return;
        }
        LinearLayout hero = new LinearLayout(context);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setBackground(brandBackground(dp(18)));
        hero.setPadding(dp(18), dp(16), dp(14), dp(16));
        hero.setClipToOutline(true);
        hero.setForeground(ripple());
        hero.setOnClickListener(v -> open(Strings.menuAbout(), page));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(wordmark(context, heading, 24));
        TextView under = text(subtitle, 13, 0xDDFFFFFF);
        under.setPadding(0, dp(4), 0, 0);
        texts.addView(under);
        hero.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(chevron(0xDDFFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        list.addView(hero, lp);
    }

    /** Boosty's orange: the goal reads as Boosty's, not as the mod's purple. */
    private static final int GOAL_COLOR = 0xFFF15F2C;
    /** Prefs and key of the Boosty goal whose card was closed to a strip (kept from Ideas and Bugs, where it lived). */
    private static final String GOAL_PREFS = "retwitch_feedback";
    private static final String GOAL_CLOSED = "goal_closed";

    /** The open Boosty goal ({@link BoostyGoal}) under the hero: a card, or a strip once closed; nothing while searching. */
    void goal() {
        if (query != null || probing) {
            return;
        }
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        list.addView(box, lp);
        renderGoal(box);
        BoostyGoal.load(g -> renderGoal(box));
    }

    private void renderGoal(LinearLayout box) {
        BoostyGoal g = BoostyGoal.cached();
        box.removeAllViews();
        box.setVisibility(g == null ? View.GONE : View.VISIBLE);
        if (g == null) {
            return;
        }
        boolean closed = context.getSharedPreferences(GOAL_PREFS, Context.MODE_PRIVATE).getLong(GOAL_CLOSED, 0) == g.id;
        box.setOnClickListener(null);
        if (closed) {
            box.setBackground(ripple());
            box.setPadding(dp(4), dp(4), dp(4), dp(4));
            LinearLayout line = new LinearLayout(context);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text("🎯 " + g.title, 12.5f, fg);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView percent = text(g.percent() + " %", 12.5f, GOAL_COLOR);
            percent.setTypeface(Typeface.DEFAULT_BOLD);
            percent.setPadding(dp(8), 0, 0, 0);
            line.addView(percent);
            box.addView(line);
            box.addView(goalBar(g, dp(4)), barLp(dp(4), dp(5)));
            box.setOnClickListener(v -> goalSheet(g));
            return;
        }
        box.setBackground(rounded(surface, dp(14)));
        box.setPadding(dp(14), dp(10), dp(8), dp(14));
        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView eyebrow = text(Strings.pick("СБОР НА BOOSTY", "BOOSTY FUNDRAISER"), 10.5f, GOAL_COLOR);
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        eyebrow.setLetterSpacing(0.08f);
        head.addView(eyebrow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 15, fgDim);
        close.setPadding(dp(10), dp(2), dp(6), dp(2));
        close.setContentDescription(Strings.pick("Свернуть", "Collapse"));
        close.setOnClickListener(v -> {
            context.getSharedPreferences(GOAL_PREFS, Context.MODE_PRIVATE).edit().putLong(GOAL_CLOSED, g.id).apply();
            renderGoal(box);
        });
        head.addView(close);
        box.addView(head);
        TextView name = text(g.title, 14, fg);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setPadding(0, dp(2), dp(4), 0);
        box.addView(name);
        box.addView(goalBar(g, dp(6)), barLp(dp(6), dp(8)));
        LinearLayout foot = new LinearLayout(context);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(8), dp(4), 0);
        foot.addView(text(g.sums(Strings.isRussian()) + " · " + g.percent() + " %", 12, fgDim),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        foot.addView(goalButton(Strings.pick("Поддержать", "Support")));
        box.addView(foot);
    }

    /** Progress as two weighted halves: no measuring, any width. */
    private View goalBar(BoostyGoal g, int height) {
        LinearLayout bar = new LinearLayout(context);
        bar.setBackground(rounded(Color.luminance(bg) < 0.5f ? 0xFF3A3A44 : 0xFFD9D9DF, height / 2));
        int done = Math.max(0, Math.min(100, g.percent()));
        View fill = new View(context);
        fill.setBackground(rounded(GOAL_COLOR, height / 2));
        bar.addView(fill, new LinearLayout.LayoutParams(0, height, done));
        bar.addView(new View(context), new LinearLayout.LayoutParams(0, height, 100 - done));
        return bar;
    }

    private static LinearLayout.LayoutParams barLp(int height, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.topMargin = top;
        return lp;
    }

    private TextView goalButton(String label) {
        TextView button = text(label, 13, Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setBackground(rounded(GOAL_COLOR, dp(8)));
        button.setOnClickListener(v -> SettingsHook.openLink(context, BoostyGoal.PAGE));
        return button;
    }

    /** A strip's tap: the whole goal with the rest of its description, in a sheet at the bottom. */
    private void goalSheet(BoostyGoal g) {
        Dialog sheet = new Dialog(context);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(20));
        TextView eyebrow = text(Strings.pick("СБОР НА BOOSTY", "BOOSTY FUNDRAISER"), 10.5f, GOAL_COLOR);
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        eyebrow.setLetterSpacing(0.08f);
        box.addView(eyebrow);
        TextView name = text(g.title, 16, fg);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setPadding(0, dp(6), 0, 0);
        box.addView(name);
        box.addView(goalBar(g, dp(6)), barLp(dp(6), dp(12)));
        TextView sums = text(g.sums(Strings.isRussian()) + " · " + g.percent() + " %", 13, fgDim);
        sums.setPadding(0, dp(8), 0, 0);
        box.addView(sums);
        if (!g.more.isEmpty()) {
            TextView more = text(g.more, 13, fgDim);
            more.setPadding(0, dp(10), 0, 0);
            box.addView(more);
        }
        LinearLayout row = new LinearLayout(context);
        row.setPadding(0, dp(16), 0, 0);
        row.addView(goalButton(Strings.pick("Открыть Boosty", "Open Boosty")));
        box.addView(row);
        sheet.setContentView(box);
        Window window = sheet.getWindow();
        if (window != null) {
            GradientDrawable top = new GradientDrawable();
            top.setColor(surface);
            float r = dp(20);
            top.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            window.setBackgroundDrawable(top);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        sheet.show();
    }

    /** One button of {@link #links}; a {@code soon} one is greyed out. {@code tint} 0: the icon fills the tile. */
    static final class Link {
        final String label;
        final Drawable icon;
        final int tint;
        final boolean soon;
        final Runnable action;

        Link(String label, Drawable icon, int tint, boolean soon, Runnable action) {
            this.label = label;
            this.icon = icon;
            this.tint = tint;
            this.soon = soon;
            this.action = action;
        }
    }

    /** A row of equal icon buttons under a caption; plain rows while searching. */
    void links(String caption, Link[] links) {
        if (query != null || probing) {
            for (Link link : links) {
                action(link.label, caption, link.action);
            }
            return;
        }
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(surface, dp(16)));
        box.setPadding(dp(8), dp(12), dp(8), dp(8));
        TextView head = text(caption, 13, fgDim);
        head.setTypeface(medium);
        head.setGravity(Gravity.CENTER);
        head.setPadding(dp(8), 0, dp(8), dp(8));
        box.addView(head);
        LinearLayout row = new LinearLayout(context);
        for (Link link : links) {
            row.addView(linkCell(link), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        list.addView(box, lp);
    }

    private View linkCell(Link link) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(6), 0, dp(6));
        cell.setBackground(ripple());
        cell.setContentDescription(link.label);
        cell.setOnClickListener(v -> apply(link.action));
        ImageView tile = new ImageView(context);
        tile.setImageDrawable(link.icon);
        tile.setBackground(rounded(link.soon ? 0xFF6B6B76 : link.tint == 0 ? Color.BLACK : link.tint, dp(13)));
        tile.setClipToOutline(true);
        if (link.tint != 0) {
            tile.setPadding(dp(11), dp(11), dp(11), dp(11));
        } else {
            tile.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        if (link.soon) {
            tile.setAlpha(.55f);
        }
        cell.addView(tile, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView label = text(link.label, 12.5f, link.soon ? fgDim : fg);
        label.setPadding(0, dp(6), 0, 0);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        cell.addView(label);
        return cell;
    }

    /**
     * Work going on in the background: read again every second while the page is open. No status, no card;
     * no {@code pauseLabel}, no buttons.
     */
    void progress(String rowTitle, final java.util.function.Supplier<String> status,
            final java.util.function.IntSupplier percent, final java.util.function.Supplier<String> pauseLabel,
            final Runnable pause, final Runnable cancel) {
        progress(rowTitle, status, percent, pauseLabel, pause, cancel, Strings.cancel());
    }

    /**
     * A card for running work, refreshed every second; hidden while {@code status} is null. {@code pauseLabel}:
     * null drops both buttons, "" only the pause one.
     */
    void progress(String rowTitle, final java.util.function.Supplier<String> status,
            final java.util.function.IntSupplier percent, final java.util.function.Supplier<String> pauseLabel,
            final Runnable pause, final Runnable cancel, String cancelLabel) {
        if (query != null || probing) {
            return;
        }
        card = null;
        final LinearLayout box = card();
        card = null;
        box.setPadding(dp(16), dp(12), dp(16), dp(14));
        box.addView(text(rowTitle, 15, fg));
        final TextView line = text("", 13, fgDim);
        line.setPadding(0, dp(2), 0, dp(8));
        box.addView(line);
        final ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        box.addView(bar);
        final LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        final TextView pauseButton = cardButton();
        TextView cancelButton = cardButton();
        cancelButton.setText(cancelLabel);
        actions.addView(pauseButton);
        actions.addView(cancelButton);
        box.addView(actions);
        final Runnable show = () -> {
            String now = status.get();
            box.setVisibility(now == null ? View.GONE : View.VISIBLE);
            line.setText(now);
            bar.setProgress(percent.getAsInt());
            String label = pauseLabel.get();
            actions.setVisibility(label == null ? View.GONE : View.VISIBLE);
            pauseButton.setVisibility(label == null || label.isEmpty() ? View.GONE : View.VISIBLE);
            pauseButton.setText(label);
        };
        pauseButton.setOnClickListener(v -> {
            pause.run();
            show.run();
        });
        cancelButton.setOnClickListener(v -> {
            cancel.run();
            show.run();
        });
        new Runnable() {
            @Override
            public void run() {
                show.run();
                // A detached view keeps this in its queue, so the loop ends with the page.
                box.postDelayed(this, 1000);
            }
        }.run();
    }

    private TextView cardButton() {
        TextView button = text("", 14, ACCENT);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(10), dp(12), dp(4));
        button.setBackground(ripple());
        return button;
    }

    /** Long plain text filling a page: a licence. */
    void document(String value) {
        if (query != null || probing) {
            return;
        }
        TextView view = text(value, 12.5f, fgDim);
        view.setPadding(dp(4), dp(4), dp(4), dp(4));
        view.setTextIsSelectable(true);
        list.addView(view);
    }

    // ---- switches ----

    void toggles(Toggles.Toggle[] group) {
        for (Toggles.Toggle toggle : group) {
            toggle(toggle);
        }
    }

    void toggle(Toggles.Toggle toggle) {
        switchRow(toggle.label(), toggle.about(), Toggles.on(toggle), toggle.restart,
                on -> Toggles.set(context, toggle, on), take(toggle), null);
    }

    void toggle(String rowTitle, String summary, boolean value, final boolean restart, final BoolSetter setter) {
        switchRow(rowTitle, summary, value, restart, setter, take(), null);
    }

    /** A switch with rows that only make sense while it is on: {@code children} fold away with it. */
    void group(Toggles.Toggle toggle, Runnable children) {
        Extra extra = take(toggle);
        group(toggle.label(), toggle.about(), Toggles.on(toggle), toggle.restart,
                on -> Toggles.set(context, toggle, on), extra, children);
    }

    void group(String rowTitle, String summary, boolean value, boolean restart, BoolSetter setter, Runnable children) {
        group(rowTitle, summary, value, restart, setter, take(), children);
    }

    private void group(String rowTitle, String summary, boolean value, boolean restart, BoolSetter setter, Extra extra,
            Runnable children) {
        if (probing || query != null) {
            switchRow(rowTitle, summary, value, restart, setter, extra, null);
            children.run();
            return;
        }
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), 0, 0, 0);
        box.setVisibility(value ? View.VISIBLE : View.GONE);
        switchRow(rowTitle, summary, value, restart, setter, extra, box);
        LinearLayout outer = card();
        outer.addView(box);
        card = box;
        children.run();
        card = outer;
    }

    private void switchRow(String rowTitle, String summary, boolean value, boolean restart, BoolSetter setter,
            Extra extra, View revealed) {
        if (probing) {
            probeOn += value ? 1 : 0;
        }
        if (skip(rowTitle, summary, extra)) {
            return;
        }
        final Switch control = newSwitch(value);
        final boolean[] state = {value};
        final BoolSetter change = on -> {
            if (state[0] == on) {
                return;
            }
            state[0] = on;
            control.setChecked(on);
            if (revealed != null) {
                TransitionManager.beginDelayedTransition(list, transition());
                revealed.setVisibility(on ? View.VISIBLE : View.GONE);
            }
            apply(() -> setter.set(on));
            track(rowTitle, restart, !on, on);
        };
        control.setContentDescription(rowTitle);
        Detail detail = extra.scene == 0 && extra.before == null && extra.note == null && !restart ? null
                : area -> fillSwitch(area, extra, restart, () -> state[0], change);
        addRow(rowTitle, summary, extra, restart, control, v -> change.set(!state[0]), detail, null);
    }

    private Switch newSwitch(boolean value) {
        Switch view = new Switch(context);
        view.setChecked(value);
        view.setClickable(false);
        view.setFocusable(false);
        int[][] states = {{android.R.attr.state_checked}, {}};
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(14));
        track.setColor(new ColorStateList(states, new int[]{ACCENT, Color.luminance(bg) < .5f ? 0xFF3F3F46 : 0xFFC9C9D1}));
        track.setSize(dp(46), dp(28));
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        knob.setColor(Color.WHITE);
        knob.setSize(dp(22), dp(22));
        view.setThumbDrawable(new InsetDrawable(knob, dp(3)));
        view.setTrackDrawable(track);
        view.setThumbTextPadding(0);
        view.setSwitchMinWidth(dp(46));
        return view;
    }

    /** Two panes, before and after; tapping one sets the switch. */
    private void fillSwitch(LinearLayout area, Extra extra, boolean restart, BooleanSupplier state, BoolSetter change) {
        if (extra.note != null) {
            addNote(area, extra.note);
        }
        if (extra.scene != 0 || extra.before != null) {
            boolean stacked = extra.scene == 0 || SettingsPreview.stacked(extra.scene);
            View off;
            View on;
            if (extra.scene != 0) {
                SettingsPreview after = new SettingsPreview(context, extra.scene, extra.focus, colors, icons);
                after.state = extra.focus;
                off = pane(Strings.previewBefore(), new SettingsPreview(context, extra.scene, extra.focus, colors, icons),
                        () -> !state.getAsBoolean(), v -> change.set(false));
                on = pane(Strings.previewAfter(), after, state, v -> change.set(true));
            } else {
                off = pane(Strings.previewBefore(), paneText(extra.before), () -> !state.getAsBoolean(),
                        v -> change.set(false));
                on = pane(Strings.previewAfter(), paneText(extra.after), state, v -> change.set(true));
            }
            area.addView(pair(off, on, stacked));
        }
        if (restart) {
            addNote(area, Strings.restartNote());
        }
    }

    private View pair(View first, View second, boolean stacked) {
        LinearLayout panes = new LinearLayout(context);
        panes.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams firstLp = stacked
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams secondLp = stacked
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (stacked) {
            secondLp.topMargin = dp(10);
        } else {
            secondLp.leftMargin = dp(10);
        }
        panes.addView(first, firstLp);
        panes.addView(second, secondLp);
        return panes;
    }

    private TextView paneText(String value) {
        TextView view = text(value, 13.5f, fg);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setLineSpacing(dp(2), 1f);
        view.setBackgroundColor(colors.bg);
        return view;
    }

    /** A longer explanation; a short line ending with ':' heads the lines under it. */
    private void addNote(LinearLayout area, String value) {
        SpannableString styled = new SpannableString(value);
        int start = 0;
        for (String line : value.split("\n", -1)) {
            if (line.length() <= 40 && line.endsWith(":")) {
                styled.setSpan(new StyleSpan(Typeface.BOLD), start, start + line.length(), 0);
                styled.setSpan(new ForegroundColorSpan(fg), start, start + line.length(), 0);
            }
            start += line.length() + 1;
        }
        TextView note = text("", 13, fgDim);
        note.setText(styled);
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(0, 0, 0, dp(10));
        area.addView(note);
    }

    /** A framed piece of content under a caption with a radio mark; the frame lights up when selected. */
    private View pane(String label, View content, BooleanSupplier selected, View.OnClickListener click) {
        LinearLayout pane = new LinearLayout(context);
        pane.setOrientation(LinearLayout.VERTICAL);
        LinearLayout caption = new LinearLayout(context);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setPadding(dp(2), 0, 0, dp(6));
        View mark = new View(context);
        caption.addView(mark, new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView name = text(label, 12.5f, fgDim);
        name.setPadding(dp(6), 0, 0, 0);
        caption.addView(name);
        pane.addView(caption);
        FrameLayout frame = frame(content);
        pane.addView(frame);
        pane.setOnClickListener(click);
        Runnable paint = () -> {
            boolean on = selected.getAsBoolean();
            name.setTextColor(on ? fg : fgDim);
            name.setTypeface(on ? medium : Typeface.DEFAULT);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(on ? ACCENT : 0);
            dot.setStroke(dp(on ? 3 : 1) + 1, on ? 0x559147FF : fgDim);
            mark.setBackground(dot);
            GradientDrawable stroke = new GradientDrawable();
            stroke.setCornerRadius(dp(10));
            stroke.setStroke(on ? dp(2) : 1, on ? ACCENT : line);
            frame.setForeground(stroke);
        };
        paint.run();
        syncs.add(paint);
        return pane;
    }

    private FrameLayout frame(View content) {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(rounded(colors.bg, dp(10)));
        frame.setClipToOutline(true);
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    // ---- pick one ----

    /** Options as chips right in the row, however many: for choices a page's live preview shows. */
    void strip(String rowTitle, String summary, String[] labels, int index, boolean restart, IntSetter setter) {
        Extra extra = take();
        if (skip(rowTitle, summary, extra)) {
            return;
        }
        final int[] current = {Math.max(0, Math.min(index, labels.length - 1))};
        chips(rowTitle, summary, labels, current, which -> {
            int was = current[0];
            current[0] = which;
            apply(() -> setter.set(which));
            track(rowTitle, restart, was, which);
        }, extra, restart);
    }

    /** One image per option, side by side. */
    void icons(String rowTitle, String[] labels, String[] mipmaps, int index, IntSetter setter) {
        Extra extra = take();
        if (skip(rowTitle, null, extra)) {
            return;
        }
        final int[] current = {index};
        LinearLayout row = new LinearLayout(context);
        row.setPadding(dp(12), dp(14), dp(12), dp(14));
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            ImageView image = new ImageView(context);
            int id = UiHider.res(context, mipmaps[i], "mipmap");
            if (id != 0) {
                image.setImageDrawable(context.getDrawable(id));
            }
            image.setPadding(dp(10), dp(10), dp(10), dp(10));
            image.setAdjustViewBounds(true);
            image.setBackgroundColor(0x22888888); // the dark icon would vanish on a dark frame
            View pane = pane(labels[i], image, () -> current[0] == which, v -> {
                if (current[0] != which) {
                    current[0] = which;
                    apply(() -> setter.set(which));
                    sync();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(4);
            lp.rightMargin = dp(4);
            row.addView(pane, lp);
        }
        addToCard(row);
    }

    /** Short options as chips right in the row, longer ones or ones with a preview unfold as a list. */
    void choice(final String rowTitle, final String[] labels, int index, final boolean restart,
            final IntSetter setter) {
        choice(rowTitle, null, labels, index, restart, setter);
    }

    void choice(final String rowTitle, String summary, final String[] labels, int index, final boolean restart,
            final IntSetter setter) {
        Extra extra = take();
        if (skip(rowTitle, summary, extra)) {
            return;
        }
        final int[] current = {Math.max(0, Math.min(index, labels.length - 1))};
        final IntSetter change = which -> {
            if (which == current[0]) {
                return;
            }
            int was = current[0];
            current[0] = which;
            apply(() -> setter.set(which));
            track(rowTitle, restart, was, which);
        };
        int chars = 0;
        for (String label : labels) {
            chars += label.length();
        }
        if (extra.scene == 0 && chars <= 32 + 4 * labels.length) {
            chips(rowTitle, summary, labels, current, change, extra, restart);
            return;
        }
        TextView value = text(labels[current[0]], 13, accentText);
        value.setSingleLine(true);
        value.setMaxWidth(dp(150));
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        syncs.add(() -> value.setText(labels[current[0]]));
        addRow(rowTitle, summary, extra, restart, value, null, area -> {
            if (extra.note != null) {
                addNote(area, extra.note);
            }
            for (int i = 0; i < labels.length; i++) {
                final int which = i;
                area.addView(option(labels[i], extra, which, () -> current[0] == which, v -> change.set(which)));
            }
            if (restart) {
                addNote(area, Strings.restartNote());
            }
        }, null);
    }

    private View option(String label, Extra extra, int which, BooleanSupplier selected, View.OnClickListener click) {
        View content;
        if (extra.scene != 0) {
            SettingsPreview preview = new SettingsPreview(context, extra.scene, extra.focus, colors, icons);
            preview.values[extra.slot] = which;
            content = preview;
        } else {
            content = paneText(label);
        }
        View pane = pane(label, content, selected, click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        pane.setLayoutParams(lp);
        return pane;
    }

    private void chips(String rowTitle, String summary, String[] labels, int[] current, IntSetter change, Extra extra,
            boolean restart) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), 0, dp(12));
        row.addView(rowTexts(rowTitle, summary, extra, restart), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        HorizontalScrollView strip = new HorizontalScrollView(context);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setPadding(0, 0, dp(16), 0);
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            TextView chip = text(labels[i], 13.5f, fg);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setOnClickListener(v -> {
                if (current[0] != which) {
                    change.set(which);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chips.addView(chip, lp);
            Runnable paint = () -> {
                boolean on = current[0] == which;
                GradientDrawable shape = new GradientDrawable();
                shape.setCornerRadius(dp(10));
                shape.setColor(on ? ACCENT : 0);
                if (!on) {
                    shape.setStroke(1, fgDim);
                }
                chip.setBackground(shape);
                chip.setTextColor(on ? Color.WHITE : fg);
            };
            paint.run();
            syncs.add(paint);
        }
        strip.addView(chips);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stripLp.topMargin = dp(10);
        row.addView(strip, stripLp);
        addToCard(row);
    }

    /** Ordered values: a slider with the value at the end of the title line. */
    void slider(final String rowTitle, final String[] labels, int index, final boolean restart, final IntSetter setter) {
        Extra extra = take();
        if (skip(rowTitle, null, extra)) {
            return;
        }
        final int[] current = {Math.max(0, Math.min(index, labels.length - 1))};
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(8));
        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(rowTexts(rowTitle, null, extra, restart),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text(labels[current[0]], 14, accentText);
        value.setTypeface(medium);
        head.addView(value);
        row.addView(head);
        SeekBar bar = new SeekBar(context);
        bar.setMax(labels.length - 1);
        bar.setProgress(current[0]);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(fgDim));
        bar.setPadding(0, dp(10), 0, dp(10));
        bar.setContentDescription(rowTitle);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && progress != current[0]) {
                    int was = current[0];
                    current[0] = progress;
                    value.setText(labels[progress]);
                    apply(() -> setter.set(progress));
                    track(rowTitle, restart, was, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar);
        addToCard(row);
    }

    void number(final String rowTitle, final int value, final IntSetter setter) {
        Extra extra = take();
        if (skip(rowTitle, null, extra)) {
            return;
        }
        TextView shown = text(String.valueOf(value), 14, accentText);
        addRow(rowTitle, null, extra, false, shown, v -> {
            final EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(value));
            input.setSelectAllOnFocus(true);
            new AlertDialog.Builder(context)
                    .setTitle(rowTitle)
                    .setView(input)
                    .setPositiveButton(Strings.save(), (d, which) -> {
                        try {
                            int parsed = Integer.parseInt(input.getText().toString().trim());
                            apply(() -> setter.set(parsed));
                        } catch (NumberFormatException ignored) {
                            // keep the old value
                        }
                    })
                    .setNegativeButton(Strings.cancel(), null)
                    .show();
        }, null, null);
    }

    // ---- navigation and actions ----

    void nav(final String rowTitle, String summary, final Page page) {
        Extra extra = take();
        if (probing) {
            page.build(this);
            return;
        }
        if (hasNew(page)) {
            extra.isNew = true;
        }
        boolean keep = !skip(rowTitle, summary, extra);
        if (keep) {
            View trailing = chevron(fgDim);
            int on = pages.size() > 1 && query == null ? countOn(page) : 0;
            if (on > 0) {
                LinearLayout end = new LinearLayout(context);
                end.setGravity(Gravity.CENTER_VERTICAL);
                TextView count = text(String.valueOf(on), 13, fgDim);
                count.setPadding(0, 0, dp(6), 0);
                end.addView(count);
                end.addView(trailing);
                trailing = end;
            }
            addRow(rowTitle, summary, extra, false, trailing, v -> open(rowTitle, page), null, null);
        }
        if (query != null && searched.add(page.getClass())) {
            String path = searchPath;
            String section = searchSection;
            searchPath = join(join(path, section), rowTitle);
            searchSection = null;
            try {
                page.build(this);
            } finally {
                searchPath = path;
                searchSection = section;
                card = null;
            }
        }
    }

    void action(String rowTitle, String summary, final Runnable action) {
        Extra extra = take();
        if (skip(rowTitle, summary, extra)) {
            return;
        }
        addRow(rowTitle, summary, extra, false, null, action == null ? null : v -> apply(action), null, null);
    }

    /** A row leaving the app, with the mark for that at its end. */
    void external(String rowTitle, String summary, final Runnable action) {
        Extra extra = take();
        if (skip(rowTitle, summary, extra)) {
            return;
        }
        ImageView mark = glyph("external_site_link", fgDim, 16);
        mark.setLayoutParams(new LinearLayout.LayoutParams(dp(16), dp(16)));
        addRow(rowTitle, summary, extra, false, mark, v -> apply(action), null, null);
    }

    /** An action that destroys something: red, and it is the caller's job to ask first. */
    void danger(String rowTitle, String summary, final Runnable action) {
        Extra extra = take();
        if (skip(rowTitle, summary, extra)) {
            return;
        }
        addRow(rowTitle, summary, extra, false, null, v -> apply(action), null, 0xFFEB5757);
    }

    /**
     * A row for something kept (a recording): a coloured chip under the summary for its state, a button
     * ({@code primary} filled with the accent) and a menu, which is also the row's tap.
     */
    void item(String rowTitle, String summary, String chip, int chipColor, String button, boolean primary,
            final Runnable onButton, final Runnable onMenu) {
        Extra extra = take();
        if (skip(rowTitle, summary, extra)) {
            return;
        }
        LinearLayout texts = rowTexts(rowTitle, summary, extra, false);
        TextView name = (TextView) ((LinearLayout) texts.getChildAt(0)).getChildAt(0);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (chip != null) {
            TextView label = text(chip, 11.5f, chipColor);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setPadding(dp(6), dp(1), dp(6), dp(1));
            label.setBackground(rounded((chipColor & 0x00FFFFFF) | 0x2E000000, dp(4)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(5);
            texts.addView(label, lp);
        }
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(10), dp(6), dp(10));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (button != null) {
            TextView action = text(button, 13.5f, primary ? Color.WHITE : fg);
            action.setTypeface(medium);
            action.setGravity(Gravity.CENTER);
            action.setSingleLine(true);
            action.setPadding(dp(12), dp(7), dp(12), dp(7));
            action.setBackground(rounded(primary ? ACCENT : (fg & 0x00FFFFFF) | 0x1F000000, dp(8)));
            action.setOnClickListener(v -> apply(onButton));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(10);
            row.addView(action, lp);
        }
        ImageView more = glyph("ic_more_vertical", fgDim, 20);
        more.setPadding(dp(8), dp(8), dp(8), dp(8));
        more.setBackground(ripple());
        more.setContentDescription(Strings.pick("Ещё", "More"));
        more.setOnClickListener(v -> apply(onMenu));
        row.addView(more, new LinearLayout.LayoutParams(dp(36), dp(36)));
        row.setBackground(ripple());
        row.setOnClickListener(v -> apply(onMenu));
        addToCard(row);
    }

    /** Builds {@code page} without views to see whether it has NEW rows. */
    private boolean hasNew(Page page) {
        Boolean known = probed.get(page.getClass());
        if (known != null) {
            return known;
        }
        boolean found = probe(page)[0] > 0;
        probed.put(page.getClass(), found);
        return found;
    }

    private int countOn(Page page) {
        return probe(page)[1];
    }

    /** @return NEW rows and switches that are on, sub-pages included. */
    private int[] probe(Page page) {
        Extra savedNext = next;
        LinearLayout savedCard = card;
        boolean savedProbing = probing;
        int savedNew = probeNew;
        int savedOn = probeOn;
        String savedQuery = query;
        next = new Extra();
        probing = true;
        probeNew = 0;
        probeOn = 0;
        query = null;
        try {
            page.build(this);
            return new int[]{probeNew, probeOn};
        } catch (Throwable t) {
            Log.w(TAG, "page probe failed", t);
            return new int[]{0, 0};
        } finally {
            next = savedNext;
            card = savedCard;
            probing = savedProbing;
            probeNew = savedNew;
            probeOn = savedOn;
            query = savedQuery;
        }
    }

    // ---- rows ----

    private LinearLayout card() {
        if (card == null) {
            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(rounded(surface, dp(16)));
            card.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            list.addView(card, lp);
        }
        return card;
    }

    private void addToCard(View row) {
        LinearLayout target = card();
        if (target.getChildCount() > 0 || target != list && target.getPaddingLeft() > 0) {
            View divider = new View(context);
            divider.setBackgroundColor(line);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.leftMargin = dp(16);
            target.addView(divider, lp);
        }
        target.addView(row);
    }

    private LinearLayout rowTexts(String rowTitle, String summary, Extra extra, boolean restart) {
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleLine = new LinearLayout(context);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("", 15.5f, fg);
        name.setText(mark(rowTitle));
        titleLine.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
        if (restart) {
            ImageView mark = glyph("ic_refresh_white_24dp", fgDim, 14);
            mark.setContentDescription(Strings.restartNote());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(14));
            lp.leftMargin = dp(6);
            titleLine.addView(mark, lp);
        }
        if (extra.isNew) {
            TextView badge = newBadge();
            titleLine.addView(badge);
            // The title takes the width first, and a long one left the badge too little for "NEW".
            int beside = dp(18) + Math.round(badge.getPaint().measureText("NEW")) + (restart ? dp(20) : 0);
            titleLine.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l > beside && name.getMaxWidth() != r - l - beside) {
                    name.setMaxWidth(r - l - beside);
                    v.post(v::requestLayout);
                }
            });
            if (lastSection != null && lastSection.getChildCount() == 1) {
                lastSection.addView(newBadge());
            }
        }
        texts.addView(titleLine);
        if (summary != null && !summary.isEmpty()) {
            TextView under = text("", 13, fgDim);
            under.setText(mark(summary));
            under.setLineSpacing(dp(1), 1f);
            under.setPadding(0, dp(2), 0, 0);
            texts.addView(under);
        }
        return texts;
    }

    /**
     * With {@code detail} the text unfolds it and {@code control} works on its own, set off by a thin line;
     * without it the whole row is the control.
     */
    private void addRow(String rowTitle, String summary, Extra extra, boolean restart, View control,
            View.OnClickListener click, Detail detail, Integer titleColor) {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        LinearLayout texts = rowTexts(rowTitle, summary, extra, restart);
        if (titleColor != null) {
            ((TextView) ((LinearLayout) texts.getChildAt(0)).getChildAt(0)).setTextColor(titleColor);
        }
        LinearLayout main = new LinearLayout(context);
        main.setGravity(Gravity.CENTER_VERTICAL);
        main.setPadding(dp(16), dp(11), detail != null ? dp(8) : dp(16), dp(11));
        if (extra.glyph != null) {
            main.addView(tileView(extra.glyph, extra.tint));
        }
        main.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wrapper.addView(row);
        if (detail == null) {
            if (control != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(12);
                main.addView(control, lp);
            }
            if (click != null) {
                row.setBackground(ripple());
                row.setOnClickListener(click);
            }
        } else {
            final LinearLayout area = new LinearLayout(context);
            area.setOrientation(LinearLayout.VERTICAL);
            area.setPadding(dp(16), dp(2), dp(16), dp(10));
            area.setVisibility(View.GONE);
            wrapper.addView(area);
            final ImageView caret = glyph("ic_arrow_down", fgDim, 18);
            LinearLayout.LayoutParams caretLp = new LinearLayout.LayoutParams(dp(18), dp(18));
            caretLp.leftMargin = dp(8);
            main.addView(caret, caretLp);
            final String key = rowTitle;
            main.setBackground(ripple());
            main.setOnClickListener(v -> unfold(key, area, detail, caret));
            if (click != null) {
                View separator = new View(context);
                separator.setBackgroundColor(line);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(1, dp(28));
                lp.gravity = Gravity.CENTER_VERTICAL;
                row.addView(separator, lp);
                FrameLayout zone = new FrameLayout(context);
                zone.setPadding(dp(12), 0, dp(14), 0);
                zone.setBackground(ripple());
                zone.setOnClickListener(click);
                zone.addView(control, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
                row.addView(zone, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } else if (control != null) {
                control.setPadding(dp(4), 0, dp(14), 0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                main.addView(control, main.getChildCount() - 1, lp);
            }
            if (key.equals(openKey)) {
                detail.fill(area);
                area.setVisibility(View.VISIBLE);
                caret.setRotation(180f);
                openArea = area;
                openChevron = caret;
            }
        }
        addToCard(wrapper);
    }

    private void unfold(String key, LinearLayout area, Detail detail, View caret) {
        TransitionManager.beginDelayedTransition(list, transition());
        boolean wasOpen = openArea == area;
        if (openArea != null) {
            openArea.setVisibility(View.GONE);
            ObjectAnimator.ofFloat(openChevron, View.ROTATION, 0f).setDuration(180).start();
        }
        openArea = null;
        openChevron = null;
        openKey = null;
        if (wasOpen) {
            return;
        }
        if (area.getChildCount() == 0) {
            detail.fill(area);
        }
        area.setVisibility(View.VISIBLE);
        ObjectAnimator.ofFloat(caret, View.ROTATION, 180f).setDuration(180).start();
        openArea = area;
        openChevron = caret;
        openKey = key;
        // The panes can land below the fold: bring them in once they are laid out.
        area.postDelayed(() -> area.requestRectangleOnScreen(new Rect(0, 0, area.getWidth(), area.getHeight())), 200);
    }

    private AutoTransition transition() {
        AutoTransition transition = new AutoTransition();
        transition.setDuration(180);
        return transition;
    }

    /** Raises the restart bar and refreshes the page after a change made outside a row (settings import). */
    void restartNeeded() {
        restartForced = true;
        restartBar.setVisibility(View.VISIBLE);
        render(true);
    }

    private void track(String key, boolean restart, Object was, Object now) {
        if (restart) {
            if (!restartBase.containsKey(key)) {
                restartBase.put(key, was);
            }
            if (restartBase.get(key).equals(now)) {
                restartPending.remove(key);
            } else {
                restartPending.add(key);
            }
            restartBar.setVisibility(restartForced || !restartPending.isEmpty() ? View.VISIBLE : View.GONE);
        }
        sync();
    }

    private void sync() {
        for (Runnable refresh : syncs) {
            refresh.run();
        }
        if (stage != null) {
            stageSync.apply(stage);
            stage.refresh();
        }
    }

    private void apply(Runnable change) {
        try {
            change.run();
        } catch (Throwable t) {
            Log.w(TAG, "setting change failed", t);
            CrashReporter.report(TAG, "setting change failed", t);
        }
    }

    // ---- helpers ----

    private View tileView(String name, int tint) {
        ImageView tile = glyph(name, Color.WHITE, 20);
        tile.setBackground(rounded(tint, dp(9)));
        tile.setPadding(dp(7), dp(7), dp(7), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.rightMargin = dp(14);
        tile.setLayoutParams(lp);
        return tile;
    }

    private ImageView glyph(String name, int tint, int sizeDp) {
        ImageView view = new ImageView(context);
        int id = UiHider.res(context, name, "drawable");
        if (id != 0) {
            Drawable icon = context.getDrawable(id).mutate();
            icon.setTint(tint);
            view.setImageDrawable(icon);
        }
        return view;
    }

    private View chevron(int tint) {
        ImageView view = glyph("ic_arrow_right", tint, 18);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
        return view;
    }

    private TextView newBadge() {
        TextView badge = text("NEW", 10, Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setSingleLine(true);
        badge.setPadding(dp(5), dp(1), dp(5), dp(1));
        badge.setBackground(rounded(ACCENT, dp(4)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        badge.setLayoutParams(lp);
        return badge;
    }

    /** The purple of the mod's own surfaces: this screen's hero and its entry in Twitch's settings. */
    static GradientDrawable brandBackground(int radius) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF772CE8, 0xFF9147FF, 0xFFBF4FE0});
        shape.setCornerRadius(radius);
        return shape;
    }

    /** The "ReTwitch" logo drawn for the launch screen; the plain name if the build has none. */
    static View wordmark(Context context, String name, int heightDp) {
        int id = UiHider.res(context, "splash_twitch_wordmark", "drawable");
        float density = context.getResources().getDisplayMetrics().density;
        if (id != 0) {
            Drawable logo = context.getDrawable(id).mutate();
            logo.setTint(Color.WHITE);
            ImageView image = new ImageView(context);
            image.setImageDrawable(logo);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_START);
            image.setContentDescription(name);
            image.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(heightDp * density)));
            return image;
        }
        TextView text = new TextView(context);
        text.setText(name);
        text.setTextColor(Color.WHITE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, heightDp * .9f);
        return text;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private Drawable ripple() {
        TypedValue value = new TypedValue();
        return context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                ? context.getDrawable(value.resourceId) : null;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int themeColor(Context context, int attr, int fallback) {
        try {
            TypedValue value = new TypedValue();
            if (!context.getTheme().resolveAttribute(attr, value, true)) {
                return fallback;
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
            return context.getColorStateList(value.resourceId).getDefaultColor();
        } catch (Throwable t) {
            return fallback;
        }
    }
}
