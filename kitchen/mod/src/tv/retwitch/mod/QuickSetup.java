package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import tv.retwitch.mod.emotes.EmoteSettings;

/**
 * Quick setup: a few yes/no questions about the main features, each with a drawn preview. Shown once on the
 * first landing screen after install or update, and from the settings root. A step changes nothing until
 * "Turn on": then its ticked items go on and the unticked ones off. Items start ticked if recommended or
 * already on, so turning a step on never switches off what the user had. "Skip" leaves the step as it is.
 */
final class QuickSetup {

    private static final String TAG = "RTQuickSetup";
    /** State, not a setting: out of the settings backup (SettingsBackup.EXCLUDED). */
    static final String PREFS = "retwitch_quick_setup";
    private static final String KEY_SHOWN = "shown";
    private static final int ACCENT = 0xFF9147FF;
    /** After the landing screen's own launch cover and popups. */
    private static final long SHOW_DELAY_MS = 3000;

    private static boolean triedThisProcess;

    interface Getter {
        boolean get();
    }

    interface Setter {
        void set(Context context, boolean on);
    }

    private static final class Item {
        final String label;
        final Getter get;
        final Setter set;
        final int scene;
        final long flag;
        final boolean restart;
        final boolean recommended;

        Item(String label, Getter get, Setter set, int scene, long flag, boolean restart, boolean recommended) {
            this.label = label;
            this.get = get;
            this.set = set;
            this.scene = scene;
            this.flag = flag;
            this.restart = restart;
            this.recommended = recommended;
        }
    }

    private static final class Step {
        final String title;
        final String about;
        final int scene;
        final Item[] items;

        /** Null items (not in this Twitch build) are left out. */
        Step(String title, String about, int scene, Item... items) {
            this.title = title;
            this.about = about;
            this.scene = scene;
            this.items = java.util.Arrays.stream(items).filter(java.util.Objects::nonNull).toArray(Item[]::new);
        }
    }

    private static Item toggle(Toggles.Toggle toggle) {
        return new Item(toggle.label(), () -> Toggles.on(toggle), (c, on) -> Toggles.set(c, toggle, on),
                toggle.scene, toggle.focus, toggle.restart, true);
    }

    private static String t(String ru, String en) {
        return Strings.pick(ru, en);
    }

    private static List<Step> steps(Context context) {
        List<Step> steps = new ArrayList<>();
        // The gift subs banner exists from Twitch 32.0.0.
        Item giftSubs = UiHider.res(context, "social_gift_subs_banner_compose", "id") != 0 ? toggle(Toggles.HIDE_SOCIAL_GIFT_SUBS) : null;
        steps.add(new Step(t("Чистый интерфейс", "A clean interface"),
                t("Убирает кнопки, баннеры и подсказки, которыми почти никто не пользуется: Cast, «Поделиться», таблицы лидеров, "
                                + "Bits, предложения Turbo и прочее",
                        "Removes buttons, banners and hints hardly anyone uses: Cast, Share, leaderboards, Bits, Turbo offers and more"),
                SettingsPreview.PLAYER,
                toggle(Toggles.HIDE_PLAYER_CAST), toggle(Toggles.HIDE_PLAYER_SHARE), toggle(Toggles.HIDE_CHAT_MODE_POPUP),
                toggle(Toggles.HIDE_LEADERBOARDS), toggle(Toggles.HIDE_EXTENSIONS), toggle(Toggles.HIDE_BITS_BUTTON),
                toggle(Toggles.HIDE_PROMOTIONS), giftSubs, toggle(Toggles.HIDE_STORIES_EMPTY),
                toggle(Toggles.HIDE_COOKIE_CONSENT)));
        steps.add(new Step(t("Смайлы 7TV, BTTV и FFZ", "7TV, BTTV and FFZ emotes"),
                t("Смайлы как в браузере с расширениями: в чате, с анимацией, в панели смайлов и в поле ввода",
                        "Emotes as in a browser with extensions: in chat, animated, in the emote panel and in the input field"),
                SettingsPreview.CHAT,
                new Item("7TV", EmoteSettings::isSevenTvEnabled, EmoteSettings::setSevenTvEnabled,
                        SettingsPreview.CHAT, SettingsPreview.C_7TV, false, true),
                new Item("BetterTTV (BTTV)", EmoteSettings::isBttvEnabled, EmoteSettings::setBttvEnabled,
                        SettingsPreview.CHAT, SettingsPreview.C_BTTV, false, true),
                new Item("FrankerFaceZ (FFZ)", EmoteSettings::isFfzEnabled, EmoteSettings::setFfzEnabled,
                        SettingsPreview.CHAT, SettingsPreview.C_FFZ, false, true),
                toggle(Toggles.EMOTE_INPUT_IMAGES), toggle(Toggles.EMOTE_FAVORITES)));
        steps.add(new Step(t("Без рекламы", "No ads"),
                t("Вырезает рекламу из трансляций и не даёт приложению запускать ролики. Баннеры и спонсорские карточки тоже уходят",
                        "Cuts ads out of streams and stops the app from playing ad videos. Banners and sponsored cards go too"),
                SettingsPreview.PLAYER,
                new Item(t("Реклама в трансляциях", "Ads in streams"), AdSettings::isBlockSsaiAdsEnabled,
                        AdSettings::setBlockSsaiAdsEnabled, SettingsPreview.PLAYER, SettingsPreview.P_AD, true, true),
                new Item(t("Реклама в приложении", "Ads in the app"), AdSettings::isBlockClientAdsEnabled,
                        AdSettings::setBlockClientAdsEnabled, 0, 0, true, true),
                toggle(Toggles.BLOCK_DISPLAY_ADS), toggle(Toggles.BLOCK_TRACKERS)));
        steps.add(new Step(t("Удобный чат", "A handier chat"),
                t("Сообщения сразу, а не пачками раз в секунду, счётчик новых сообщений, подсветка ответов вам и первых сообщений "
                                + "зрителей, цветные /me и упоминания",
                        "Messages right away instead of one-second batches, a new message counter, highlighted replies to you and "
                                + "viewers' first messages, coloured /me and mentions"),
                SettingsPreview.CHAT,
                toggle(Toggles.CHAT_REALTIME), toggle(Toggles.NEW_MESSAGES_COUNT), toggle(Toggles.REPLY_ALERTS),
                toggle(Toggles.FIRST_MESSAGE), toggle(Toggles.COLORED_ME), toggle(Toggles.COLORED_MENTIONS),
                toggle(Toggles.COPY_SEND)));
        steps.add(new Step(t("Плеер", "Player"),
                t("Сразу максимальное качество по Wi-Fi, яркость и громкость свайпом по краям в полном экране, свайп в полный экран, "
                                + "перезапуск зависшего стрима",
                        "Highest quality on Wi-Fi right away, brightness and volume by swiping the edges in full screen, swipe to full "
                                + "screen, a frozen stream restarts itself"),
                SettingsPreview.PLAYER,
                new Item(t("Максимальное качество по Wi-Fi", "Highest quality on Wi-Fi"),
                        () -> PlayerSettings.getDefaultQuality(false) == -1,
                        (c, on) -> {
                            // Off only undoes our own choice: a picked height stays.
                            if (on || PlayerSettings.getDefaultQuality(false) == -1) {
                                PlayerSettings.setDefaultQuality(c, false, on ? -1 : 0);
                            }
                        }, 0, 0, false, true),
                toggle(Toggles.PLAYER_GESTURES), toggle(Toggles.PLAYER_SWIPE_FULLSCREEN), toggle(Toggles.AUTO_RELOAD),
                toggle(Toggles.NO_AUTOPLAY)));
        steps.add(new Step(t("Прокси трансляций", "Stream proxy"),
                t("Из России Twitch отдаёт только транскоды до 720p, исходное качество (1080p и выше) приходит через прокси. "
                                + "Мод сам проверит прокси ReYohoho и выберет работающий",
                        "From Russia Twitch serves only transcodes up to 720p, the source quality (1080p and up) comes through a "
                                + "proxy. The mod checks the ReYohoho proxies and picks one that works"),
                0,
                new Item(t("Прокси ReYohoho", "ReYohoho proxy"), () -> ProxySettings.getSelected() != ProxyBackend.DISABLED,
                        QuickSetup::setProxy, 0, 0, false, Strings.isRussian())));
        steps.add(new Step(t("Главная", "Home"),
                t("Открывается сразу на «Отслеживаемом», «В эфире» компактным списком, превью каналов крупнее",
                        "Opens right on Following, Live as a compact list, bigger channel previews"),
                SettingsPreview.HOME,
                new Item(t("Открывать «Отслеживаемое»", "Open Following"), LandingSettings::isFollowingDefaultEnabled,
                        LandingSettings::setFollowingDefaultEnabled, SettingsPreview.HOME, SettingsPreview.H_FOLLOWING, true, true),
                toggle(Toggles.COMPACT_LIVE_TAB),
                new Item(t("Крупные превью в «Отслеживаемом»", "Large previews in Following"),
                        () -> LandingSettings.getFollowingPreviewSize() == 2,
                        (c, on) -> {
                            if (on || LandingSettings.getFollowingPreviewSize() == 2) {
                                LandingSettings.setFollowingPreviewSize(c, on ? 2 : 0);
                            }
                        }, 0, 0, true, true)));
        steps.add(new Step(t("Скачивание и запись", "Downloads and recording"),
                t("Кнопки на плеере: скачать VOD или клип в выбранном качестве, записать трансляцию на телефон",
                        "Player buttons: download a VOD or a clip in the quality you pick, record a stream to the phone"),
                SettingsPreview.PLAYER,
                toggle(Toggles.DOWNLOAD_BUTTON), toggle(Toggles.LIVE_RECORD)));
        return steps;
    }

    /** ReYohoho proxies in order of preference; the wizard picks the first that returns a playlist. */
    private static final ProxyBackend[] PROXIES = {ProxyBackend.RTE_PROXY4, ProxyBackend.RTE_PROXY5,
            ProxyBackend.RTE_PROXY6, ProxyBackend.RTE_PROXY7};
    private static volatile ProxyBackend workingProxy;
    private static final AtomicInteger proxiesFailed = new AtomicInteger();
    /** "Turn on" came before any probe answered: the first working one is selected when it does. */
    private static volatile Context proxyWanted;

    /** Probes every proxy against the health-check channel, as the proxy picker does. */
    private static void probeProxies() {
        workingProxy = null;
        proxiesFailed.set(0);
        proxyWanted = null;
        Uri usher = SettingsHook.referenceUsherUri();
        for (ProxyBackend backend : PROXIES) {
            Uri probe = backend.buildUri(usher, SettingsHook.HEALTH_CHECK_CHANNEL, false, null);
            new Thread(() -> {
                boolean ok = probe != null && ProxyHealth.check(probe, backend.httpProxy()).ok;
                new Handler(Looper.getMainLooper()).post(() -> proxyAnswered(backend, ok));
            }, "RTQuickSetupProxy").start();
        }
    }

    private static void proxyAnswered(ProxyBackend backend, boolean ok) {
        if (ok && workingProxy == null) {
            workingProxy = backend;
            Context wanted = proxyWanted;
            if (wanted != null) {
                proxyWanted = null;
                selectProxy(wanted, backend);
            }
        } else if (!ok && proxiesFailed.incrementAndGet() == PROXIES.length && proxyWanted != null) {
            Toast.makeText(proxyWanted, t("Прокси сейчас не отвечают, выберите в настройках: Сеть и доступ",
                    "The proxies don't answer now, pick one in the settings: Network and access"), Toast.LENGTH_LONG).show();
            proxyWanted = null;
        }
    }

    private static void setProxy(Context context, boolean on) {
        if (!on) {
            ProxySettings.setSelected(context, ProxyBackend.DISABLED);
        } else if (workingProxy != null) {
            selectProxy(context, workingProxy);
        } else if (proxiesFailed.get() < PROXIES.length) {
            proxyWanted = context.getApplicationContext();
        } else {
            Toast.makeText(context, t("Прокси сейчас не отвечают, выберите в настройках: Сеть и доступ",
                    "The proxies don't answer now, pick one in the settings: Network and access"), Toast.LENGTH_LONG).show();
        }
    }

    private static void selectProxy(Context context, ProxyBackend backend) {
        ProxySettings.setSelected(context, backend);
        Toast.makeText(context, Strings.pickf("Прокси трансляций: %s", "Stream proxy: %s", backend.label()),
                Toast.LENGTH_SHORT).show();
    }

    static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityResumed(Activity a) { resumed(a); }
            public void onActivityPaused(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }

    /** Once, on the first landing screen of the process, if never shown. */
    private static void resumed(Activity activity) {
        try {
            if (triedThisProcess || !activity.getClass().getName().endsWith(".ViewerLandingActivity")) {
                return;
            }
            triedThisProcess = true;
            if (prefs(activity).getBoolean(KEY_SHOWN, false)) {
                return;
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus()) {
                    show(activity);
                } else {
                    triedThisProcess = false; // next landing resume tries again
                }
            }, SHOW_DELAY_MS);
        } catch (Throwable t) {
            Log.w(TAG, "resume failed", t);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void show(Context context) {
        try {
            prefs(context).edit().putBoolean(KEY_SHOWN, true).apply();
            new QuickSetup(context).dialog.show();
        } catch (Throwable t) {
            Log.w(TAG, "quick setup failed", t);
            CrashReporter.report(TAG, "quick setup failed", t);
        }
    }

    private final Context context;
    private final Dialog dialog;
    private final List<Step> steps;
    private final LinearLayout page;
    private final int bg;
    private final int surface;
    private final int fg;
    private final int fgDim;
    private final int line;
    private final int accentText;
    private final SettingsPreview.Colors colors;
    private final HashMap<String, Drawable> icons = new HashMap<>();
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private int index;
    private boolean restartNeeded;

    private QuickSetup(Context context) {
        this.context = context;
        steps = steps(context);
        bg = SettingsScreen.themeColor(context, android.R.attr.colorBackground, 0xFF0E0E10);
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
                if (index > 0) {
                    index--;
                    render();
                } else {
                    dismiss();
                }
            }
        };
        page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(bg);
        // Edge-to-edge (targetSdk 35+): keep the content clear of the system bars.
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        dialog.setContentView(page);
        probeProxies();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(bg));
            window.setStatusBarColor(bg);
            window.setNavigationBarColor(bg);
        }
        render();
    }

    private void render() {
        page.removeAllViews();
        if (index < steps.size()) {
            renderStep(steps.get(index));
        } else {
            renderDone();
        }
    }

    private void renderStep(Step step) {
        boolean[] ticked = new boolean[step.items.length];
        for (int i = 0; i < ticked.length; i++) {
            ticked[i] = step.items[i].recommended || step.items[i].get.get();
        }

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(12), dp(8), dp(4));
        TextView count = text((index + 1) + "/" + steps.size(), 14, fgDim);
        top.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView skipAll = button(t("Пропустить всё", "Skip all"), false);
        skipAll.setOnClickListener(v -> dialog.dismiss());
        top.addView(skipAll);
        page.addView(top);

        LinearLayout bar = new LinearLayout(context);
        bar.setPadding(dp(18), 0, dp(18), 0);
        for (int i = 0; i < steps.size(); i++) {
            View segment = new View(context);
            segment.setBackground(rounded(i <= index ? ACCENT : line, dp(2)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            bar.addView(segment, lp);
        }
        page.addView(bar);

        ScrollView scroll = new ScrollView(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = text(step.title, 26, fg);
        title.setTypeface(medium);
        body.addView(title);
        TextView about = text(step.about, 15, fgDim);
        about.setPadding(0, dp(8), 0, 0);
        body.addView(about);

        SettingsPreview preview = new SettingsPreview(context, step.scene, 0, colors, icons);
        preview.state = state(step, ticked);
        if (step.scene != 0) {
            FrameLayout card = new FrameLayout(context);
            card.setBackground(rounded(surface, dp(16)));
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.addView(preview);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.topMargin = dp(20);
            body.addView(card, cardLp);
        }

        // "What exactly": the items, each its own tick.
        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        for (int i = 0; i < step.items.length; i++) {
            final int which = i;
            CheckBox box = new CheckBox(context);
            box.setText(step.items[i].label);
            box.setTextColor(fg);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            box.setButtonTintList(ColorStateList.valueOf(ACCENT));
            box.setChecked(ticked[i]);
            box.setOnCheckedChangeListener((b, on) -> {
                ticked[which] = on;
                preview.state = state(step, ticked);
                preview.refresh();
            });
            details.addView(box);
        }
        TextView more = button(t("Дополнительно: что включится", "More: what gets turned on"), false);
        more.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        more.setPadding(0, dp(12), 0, dp(4));
        more.setOnClickListener(v -> {
            boolean open = details.getVisibility() != View.VISIBLE;
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            more.setText(open ? t("Скрыть список", "Hide the list") : t("Дополнительно: что включится", "More: what gets turned on"));
        });
        body.addView(more);
        body.addView(details);

        LinearLayout actions = new LinearLayout(context);
        actions.setPadding(dp(16), dp(8), dp(16), dp(16));
        TextView skip = button(t("Пропустить", "Skip"), false);
        skip.setOnClickListener(v -> next());
        actions.addView(skip, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView apply = button(t("Включить", "Turn on"), true);
        apply.setOnClickListener(v -> {
            apply(step, ticked);
            next();
        });
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        applyLp.leftMargin = dp(12);
        actions.addView(apply, applyLp);
        page.addView(actions);
    }

    private void renderDone() {
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView title = text(t("Готово", "All set"), 28, fg);
        title.setTypeface(medium);
        body.addView(title);
        String message = t("Остальное - в настройках мода: Профиль > Настройки > ReTwitch. Пройти быструю настройку ещё раз можно там же.",
                "Everything else is in the mod's settings: Profile > Settings > ReTwitch. Quick setup can be run again from there.");
        if (restartNeeded) {
            message += "\n\n" + t("Часть изменений заработает после перезапуска приложения.",
                    "Some changes take effect after the app restarts.");
        }
        TextView about = text(message, 16, fgDim);
        about.setPadding(0, dp(12), 0, 0);
        body.addView(about);
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(context);
        actions.setPadding(dp(16), dp(8), dp(16), dp(16));
        if (restartNeeded) {
            TextView later = button(t("Позже", "Later"), false);
            later.setOnClickListener(v -> dialog.dismiss());
            actions.addView(later, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        TextView done = button(restartNeeded ? t("Перезапустить", "Restart") : t("Готово", "Done"), true);
        done.setOnClickListener(v -> {
            dialog.dismiss();
            if (restartNeeded) {
                flushPrefs();
                SettingsHook.restartApp(context);
            }
        });
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        doneLp.leftMargin = restartNeeded ? dp(12) : 0;
        actions.addView(done, doneLp);
        page.addView(actions);
    }

    private void next() {
        index++;
        render();
    }

    private void apply(Step step, boolean[] ticked) {
        for (int i = 0; i < step.items.length; i++) {
            Item item = step.items[i];
            try {
                if (item.get.get() != ticked[i]) {
                    item.set.set(context, ticked[i]);
                    restartNeeded |= item.restart;
                }
            } catch (Throwable t) {
                Log.w(TAG, "apply " + item.label + " failed", t);
                CrashReporter.report(TAG, "apply failed", t);
            }
        }
    }

    private static long state(Step step, boolean[] ticked) {
        long state = 0;
        for (int i = 0; i < step.items.length; i++) {
            if (ticked[i] && step.items[i].scene == step.scene) {
                state |= step.items[i].flag;
            }
        }
        return state;
    }

    /** The settings went out with apply(); restartApp ends the process right away. */
    private void flushPrefs() {
        try {
            String[] files = new File(context.getApplicationInfo().dataDir, "shared_prefs").list();
            if (files == null) {
                return;
            }
            for (String file : files) {
                if (file.startsWith("retwitch_") && file.endsWith(".xml")) {
                    context.getSharedPreferences(file.substring(0, file.length() - 4), Context.MODE_PRIVATE).edit().commit();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "flush failed", t);
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private TextView button(String label, boolean primary) {
        TextView view = text(label, 16, primary ? 0xFFFFFFFF : accentText);
        view.setTypeface(medium);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setMinHeight(dp(44));
        view.setBackground(primary ? rounded(ACCENT, dp(12)) : null);
        return view;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
