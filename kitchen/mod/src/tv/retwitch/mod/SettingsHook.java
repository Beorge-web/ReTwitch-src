package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.retwitch.mod.emotes.EmoteCache;
import tv.retwitch.mod.emotes.EmoteSettings;

/**
 * Entry point injected into the decompiled Twitch settings screen by the
 * ReTwitch "kitchen" toolchain.
 *
 * Kept intentionally independent from Twitch's (obfuscated) internal
 * classes: the smali patch only ever hands us a plain {@link Context} and
 * the already-built settings {@link View}, so this class only touches
 * public Android SDK APIs. That keeps the injection point stable across
 * app updates even though Twitch's internal class/method names are
 * renamed by R8 on every release.
 */
public final class SettingsHook {

    static final String LINK_SITE = "https://ext.rte.net.ru/";
    private static final String LINK_TELEGRAM = "https://t.me/reyohoho_twitch_ext";
    private static final String LINK_BOOSTY = "https://boosty.to/sentryward";
    static final String LINK_GITHUB = "https://github.com/reyohoho/ReTwitch-src";
    /** New issue: GitHub offers the bug and idea templates first. */
    private static final String LINK_ISSUES = LINK_GITHUB + "/issues/new/choose";

    private SettingsHook() {
    }

    /**
     * Injected right before {@code MainSettingsFragmentV2.onCreateView}
     * returns its view. Takes only the view (its own Context): in 31.x R8
     * reuses the {@code this} register by then. Never throws -- on failure the
     * original view is returned.
     */
    public static View wrap(View content) {
        try {
            return content == null ? null : wrap(content.getContext(), content);
        } catch (Throwable t) {
            android.util.Log.w("RTSettings", "wrap failed", t);
            CrashReporter.report("RTSettings", "wrap failed", t);
            return content;
        }
    }

    /**
     * Wraps the original settings screen content with an extra "ReTwitch"
     * row appended at the bottom, acting as a new settings menu entry.
     *
     * @param context original screen context (Fragment#requireContext())
     * @param content the original settings screen content view
     * @return a new root view to be returned from onCreateView instead of {@code content}
     */
    public static View wrap(Context context, View content) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(content, contentParams);

        root.addView(buildMenuItem(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private static View buildMenuItem(Context context) {
        LinearLayout item = new LinearLayout(context);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackground(SettingsScreen.brandBackground(dp(context, 16)));
        item.setPadding(dp(context, 16), dp(context, 14), dp(context, 14), dp(context, 14));
        item.setClipToOutline(true);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            item.setForeground(context.getDrawable(ripple.resourceId));
        }
        item.setContentDescription("ReTwitch");
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMainMenu(context);
            }
        });
        item.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Toast.makeText(context, "ReTwitch: dumping component graph to logcat (tag RTdiag)...", Toast.LENGTH_LONG).show();
                DiagProbe.dumpAsync(context);
                return true;
            }
        });

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(SettingsScreen.wordmark(context, "ReTwitch", 20));
        TextView subtitle = new TextView(context);
        subtitle.setText(t("Настройки мода", "Mod settings"));
        subtitle.setTextColor(0xDDFFFFFF);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(0, dp(context, 3), 0, 0);
        texts.addView(subtitle);
        item.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int chevron = UiHider.res(context, "ic_arrow_right", "drawable");
        if (chevron != 0) {
            android.widget.ImageView arrow = new android.widget.ImageView(context);
            android.graphics.drawable.Drawable icon = context.getDrawable(chevron).mutate();
            icon.setTint(Color.WHITE);
            arrow.setImageDrawable(icon);
            item.addView(arrow, new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)));
        }

        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        int margin = dp(context, 12);
        wrapper.setPadding(margin, margin / 2, margin, margin);
        wrapper.addView(item, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    static void showMainMenu(final Context context) {
        SettingsScreen.show(context, t("Настройки мода", "Mod settings"), SettingsHook::mainPage);
    }

    private static String t(String ru, String en) {
        return Strings.pick(ru, en);
    }

    /** The flags of the switches of a live preview's scene that are on. */
    private static long flags(SettingsPreview preview, Toggles.Toggle[] group) {
        long state = 0;
        for (Toggles.Toggle toggle : group) {
            if (toggle.scene == preview.scene && Toggles.on(toggle)) {
                state |= toggle.focus;
            }
        }
        return state;
    }

    private static long flag(boolean on, long flag) {
        return on ? flag : 0;
    }

    private static void mainPage(SettingsScreen s) {
        final Context c = s.context;
        s.hero("ReTwitch", BuildInfo.VERSION_NAME + " · " + t("о моде и что нового", "about and what's new"), AboutPage::build);
        s.goal();
        RecordSettings.cards(s);
        s.markNew("0.5.22-beta").tile("ic_lightning_bolt", 0xFF9147FF).action(t("Быстрая настройка", "Quick setup"),
                t("Главное за 8 вопросов", "The main things in 8 questions"), () -> QuickSetup.show(c));
        s.links(t("Новости / поддержать разработку", "News / support the development"), new SettingsScreen.Link[]{
                new SettingsScreen.Link("Telegram", SettingsLogos.mark(SettingsLogos.TELEGRAM, 4, 4.5f, 16), 0xFF229ED9, false,
                        () -> openLink(c, LINK_TELEGRAM)),
                new SettingsScreen.Link("Boosty", SettingsLogos.mark(SettingsLogos.BOOSTY, 0, 0, 24), 0xFFF15F2C, false,
                        () -> openLink(c, LINK_BOOSTY)),
                new SettingsScreen.Link(t("Сайт", "Website"),
                        new android.graphics.drawable.BitmapDrawable(c.getResources(), SettingsLogos.rte()), 0, false,
                        () -> openLink(c, LINK_SITE)),
                // GitHub's own dark: the grey 6B6B76 is what the screen gives a link that isn't there yet
                new SettingsScreen.Link("GitHub", tinted(c, "ic_github"), 0xFF24292F, false,
                        () -> openLink(c, LINK_GITHUB)),
        });
        s.section(t("Просмотр", "Watching"));
        s.tile("ic_no_prohibit_cancel", 0xFFEB5757).nav(t("Реклама", "Ads"), adsState(), SettingsHook::adsPage);
        s.tile("ic_play_arrow", 0xFF9147FF).nav(Strings.playerMenuItem(),
                t("Качество, кнопки, жесты, звук", "Quality, buttons, gestures, sound"), SettingsHook::playerPage);
        s.tile("ic_media_video", 0xFF2F80ED).nav(t("Видео и клипы", "VODs and clips"),
                t("Перемотка, фильтры, сабовые", "Seeking, filters, sub-only"), SettingsHook::vodPage);
        // Everything that saves files to the phone lives here: "записи" also means VODs, one place avoids the mix-up.
        s.markNew("0.5.15-beta").tile("ic_download", 0xFFD6336C).nav(t("Запись и скачивание", "Recording and downloads"),
                RecordSettings.summary(), RecordSettings::page);
        s.section(t("Общение", "Community"));
        s.tile("ic_chat_show", 0xFF12A594).nav(Strings.chatTitle(),
                t("Внешний вид, упоминания, фильтры", "Look, mentions, filters"), SettingsHook::chatPage);
        s.tile("ic_emotes", 0xFFE2A400).nav(t("Эмоуты", "Emotes"),
                t("7TV, BTTV, FFZ и панель эмоутов", "7TV, BTTV, FFZ and the emote panel"), SettingsHook::emotesPage);
        s.section(t("Приложение", "App"));
        s.tile("ic_grid", 0xFFE8590C).nav(Strings.uiMenuItem(),
                t("Что убрать с экрана, иконка", "What to remove from the screen, icon"), SettingsHook::uiPage);
        s.tile("home_filled", 0xFF2FA84F).nav(t("Главная", "Home"),
                t("Вкладки, Отслеживаемое, В эфире", "Tabs, Following, Live"), SettingsHook::homePage);
        s.tile("ic_broadcast", 0xFF3B82C4).nav(t("Сеть и доступ", "Network and access"),
                ProxySettings.getSelected() == ProxyBackend.DISABLED ? t("Прокси, вход без аккаунта", "Proxy, no-account access")
                        : t("Прокси: ", "Proxy: ") + ProxySettings.getSelected().label(), SettingsHook::networkPage);
        s.section(t("Сервис", "Service"));
        s.tile("ic_wrench", 0xFF6B6B76).nav(Strings.fixesTitle(), Strings.fixesSummary(), SettingsHook::fixesPage);
        s.tile("ic_users", 0xFFE64980).nav(Strings.rteTitle(), Strings.rteSummary(), SettingsHook::rtePage);
        s.markNew("0.5.27-beta").tile("ic_polls", 0xFFE2A400).external(t("Идеи и баги", "Ideas and bugs"),
                t("На GitHub, в Issues", "On GitHub, in Issues"), () -> openLink(c, LINK_ISSUES));
        s.markNew("0.5.14-beta").tile("language", 0xFF0E9F9A).nav(t("Язык и перевод", "Language and translation"),
                translationState(c), SettingsHook::translationPage);
        s.tile("ic_download", 0xFF6B6B76).nav(Strings.backupTitle(), Strings.backupSummary(), SettingsHook::backupPage);
        s.tile("bug", 0xFF6B6B76).nav(Strings.diagnosticsTitle(), Strings.diagnosticsSummary(), SettingsHook::diagnosticsPage);
    }

    private static android.graphics.drawable.Drawable tinted(Context context, String name) {
        int id = UiHider.res(context, name, "drawable");
        if (id == 0) {
            return null;
        }
        android.graphics.drawable.Drawable icon = context.getDrawable(id).mutate();
        icon.setTint(Color.WHITE);
        return icon;
    }

    /** The mod's purple, for its buttons outside the settings screen. */
    public static android.graphics.drawable.Drawable brandBackground(int radius) {
        return SettingsScreen.brandBackground(radius);
    }

    public static void openLink(Context context, String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(context, url, Toast.LENGTH_LONG).show();
        }
    }

    private static String adsState() {
        boolean client = AdSettings.isBlockClientAdsEnabled();
        boolean ssai = AdSettings.isBlockSsaiAdsEnabled();
        return client && ssai ? t("Блокируется", "Blocked") : client || ssai ? t("Блокируется частично", "Partly blocked")
                : t("Блокировка выключена", "Blocking is off");
    }

    /** The RTE account side: friends and the RTE subscription. */
    private static void rtePage(SettingsScreen s) {
        final Context c = s.context;
        s.markNew("0.4.0-alpha").action(Strings.rteSubscriptionTitle(), t("Раскраска ника, значок и другое", "Nick paint, badge and more"),
                () -> tv.retwitch.mod.emotes.RteFriends.showSubscription(c));
        s.section(Strings.friendsTitle());
        s.toggle(Toggles.RTE_FRIENDS);
        s.toggle(Toggles.FRIEND_BUTTON);
        s.action(Strings.friendsList(), null, () -> tv.retwitch.mod.emotes.RteFriends.showDialog(c));
        s.hint(Strings.friendsHint());
    }

    /** Ad blocking: the mechanisms are independent (see {@link AdSettings}). */
    private static void adsPage(SettingsScreen s) {
        final Context c = s.context;
        s.look(SettingsPreview.PLAYER, SettingsPreview.P_AD)
                .note(t("Во время рекламы мод подключает чистый запасной поток (иногда в 360p) или вырезает рекламные куски. "
                                + "Работает на телефоне (VAFT), внешний сервер не нужен; с прокси трансляций тоже.",
                        "During a break the mod switches to a clean backup stream (sometimes 360p) or cuts the ad segments. "
                                + "It runs on the phone (VAFT), no external server needed; with a stream proxy too."))
                .toggle(t("Реклама в трансляциях", "Ads in streams"), t("Вшитая в поток реклама", "Ads stitched into the stream"),
                        AdSettings.isBlockSsaiAdsEnabled(), true, on -> AdSettings.setBlockSsaiAdsEnabled(c, on));
        s.compare(t("Приложение само запускает видео- и аудиоролики", "The app starts video and audio ads by itself"),
                t("Эти ролики не запускаются", "These ads don't start"))
                .toggle(t("Реклама в приложении", "Ads in the app"), t("Ролики, которые запускает само приложение", "Ads the app plays itself"),
                        AdSettings.isBlockClientAdsEnabled(), true, on -> AdSettings.setBlockClientAdsEnabled(c, on));
        s.toggle(Toggles.BLOCK_DISPLAY_ADS);
        s.toggle(Toggles.HIDE_PROMOTIONS);
        if (UiHider.res(c, "social_gift_subs_banner_compose", "id") != 0) { // Twitch 32.0.0+
            s.toggle(Toggles.HIDE_SOCIAL_GIFT_SUBS);
        }
        s.toggle(Toggles.VAFT_BLOCKING_LABEL);
        s.section(t("Дополнительно", "Advanced"));
        s.toggle(Toggles.BLOCK_TRACKERS);
        s.toggle(Toggles.HIDE_COOKIE_CONSENT);
        s.note(t("Какой плеер мод называет Twitch, запрашивая стрим. Менять стоит, только если реклама прорывается.",
                "Which player the mod tells Twitch it is when requesting a stream. Change it only if ads get through."))
                .choice(t("Тип плеера", "Player type"), t("Для запроса трансляции", "For stream requests"),
                        Strings.vaftPlayerTypeLabels(),
                        java.util.Arrays.asList(AdSettings.PLAYER_TYPES).indexOf(AdSettings.getInitialPlayerType()), true,
                        i -> AdSettings.setInitialPlayerType(c, AdSettings.PLAYER_TYPES[i]));
    }

    private static void playerPage(SettingsScreen s) {
        final Context c = s.context;
        s.section(t("Качество при запуске", "Quality on start"));
        s.strip(Strings.defaultQualityWifi(), null, Strings.qualityLabels(), qualityIndex(false), false,
                i -> PlayerSettings.setDefaultQuality(c, false, PlayerSettings.QUALITY_HEIGHTS[i]));
        s.strip(Strings.defaultQualityCellular(), null, Strings.qualityLabels(), qualityIndex(true), false,
                i -> PlayerSettings.setDefaultQuality(c, true, PlayerSettings.QUALITY_HEIGHTS[i]));
        s.hint(t("Заменяет «Авто» Twitch. Если выбрать качество в меню плеера, сработает ваш выбор.",
                "Replaces Twitch's Auto. Picking a quality in the player's menu takes over."));
        s.section(t("Воспроизведение", "Playback"));
        s.group(Toggles.AUTO_RELOAD, () -> {
            String[] labels = new String[PlayerSettings.STALL_SECONDS.length];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = Strings.pickf("%d с", "%d s", PlayerSettings.STALL_SECONDS[i]);
            }
            s.markNew("0.5.22-beta");
            s.strip(t("Через сколько", "After"), t("Сколько ждать загрузку перед перезапуском", "How long to wait before restarting"),
                    labels, Math.max(0, indexOf(PlayerSettings.STALL_SECONDS, PlayerSettings.getStallSeconds())), false,
                    i -> PlayerSettings.setStallSeconds(c, PlayerSettings.STALL_SECONDS[i]));
        });
        s.toggle(Toggles.NORMAL_LATENCY);
        s.section(t("Разделы", "More"));
        s.nav(t("Кнопки на плеере", "Player buttons"), t("Перезагрузка, таймер, статистика", "Reload, timer, stats"),
                SettingsHook::playerButtonsPage);
        s.nav(t("Жесты", "Gestures"), t("Яркость, громкость, полный экран", "Brightness, volume, fullscreen"),
                SettingsHook::gesturesPage);
        s.nav(t("Полный экран", "Fullscreen"), t("Вырез камеры, ambilight, ширина чата", "Camera cutout, ambilight, chat width"),
                SettingsHook::fullscreenPage);
        s.nav(t("Звук", "Sound"), t("Компрессор и фильтры", "Compressor and filters"), SettingsHook::audioPage);
        s.nav(t("Данмаку", "Danmaku"), t("Чат летит поверх видео", "Chat flying over the video"), SettingsHook::danmakuPage);
    }

    private static void syncPlayerButtons(SettingsPreview preview) {
        preview.state = flags(preview, new Toggles.Toggle[]{Toggles.COMPRESSOR_BUTTON, Toggles.STATS_BUTTON,
                Toggles.MIRROR_BUTTON, Toggles.LATENCY_LABEL, Toggles.SHOW_CHATTERS_COUNT, Toggles.LIVE_RECORD})
                | flag(RefreshSettings.isButtonEnabled(), SettingsPreview.P_RELOAD)
                | flag(TimerSettings.isButtonEnabled(), SettingsPreview.P_TIMER);
    }

    private static void playerButtonsPage(SettingsScreen s) {
        final Context c = s.context;
        s.stage(SettingsPreview.PLAYER, SettingsHook::syncPlayerButtons);
        s.section(t("Кнопки", "Buttons"));
        s.look(SettingsPreview.PLAYER, SettingsPreview.P_RELOAD).toggle(t("Перезагрузка", "Reload"),
                t("Перезапускает стрим или запись, если завис", "Restarts a stream or VOD that got stuck"),
                RefreshSettings.isButtonEnabled(), false, on -> RefreshSettings.setButtonEnabled(c, on));
        s.look(SettingsPreview.PLAYER, SettingsPreview.P_TIMER).group(t("Таймер сна", "Sleep timer"),
                t("Рядом с шестерёнкой: закрывает приложение через заданное время", "Next to the gear: closes the app after a set time"),
                TimerSettings.isButtonEnabled(), false, on -> TimerSettings.setButtonEnabled(c, on),
                () -> s.toggle(t("Пауза вместо закрытия", "Pause instead of closing"), null, TimerSettings.isPauseMode(), false,
                        on -> TimerSettings.setPauseMode(c, on)));
        s.toggle(Toggles.STATS_BUTTON);
        s.toggle(Toggles.MIRROR_BUTTON);
        s.group(Toggles.STREAM_ARROWS, () -> s.strip(t("Листать", "Go through"), null,
                new String[]{t("Недавние", "Recent"), t("Отслеживаемые", "Followed")},
                PlayerSettings.getStreamArrowsMode(), false, i -> PlayerSettings.setStreamArrowsMode(c, i)));
        s.section(t("Показания", "Readouts"));
        s.toggle(Toggles.LATENCY_LABEL);
        s.toggle(Toggles.SHOW_CHATTERS_COUNT);
        s.toggle(Toggles.BATTERY_LABEL);
        s.toggle(Toggles.LATENCY_HINT);
        // The actions duplicate the player buttons: offered only while that button is off.
        boolean reloadAction = !RefreshSettings.isButtonEnabled();
        boolean timerAction = !TimerSettings.isButtonEnabled();
        if (reloadAction || timerAction) {
            s.section(t("Прямо сейчас", "Right now"));
        }
        if (reloadAction) {
            s.action(t("Перезагрузить плеер", "Reload the player"), null, ReloadHook::reloadPlayer);
        }
        if (timerAction) {
            s.action(TimerController.isRunning() ? t("Остановить таймер", "Stop the timer") : t("Запустить таймер", "Start the timer"),
                    null, () -> {
                        if (TimerController.isRunning()) {
                            TimerController.cancel();
                            TimerController.toast(c, Strings.timerCancelledToast());
                        } else {
                            TimerHook.showTimerDialog(c);
                        }
                    });
        }
    }

    private static void gesturesPage(SettingsScreen s) {
        s.toggle(Toggles.PLAYER_GESTURES);
        s.toggle(Toggles.PLAYER_GESTURES_VERTICAL);
        s.toggle(Toggles.PLAYER_SWIPE_FULLSCREEN);
    }

    private static void fullscreenPage(SettingsScreen s) {
        final Context c = s.context;
        s.look(SettingsPreview.PLAYER, SettingsPreview.P_CUTOUT).toggle(t("Видео под вырезом камеры", "Video under the camera cutout"),
                t("На весь экран, включая полосу у камеры", "Edge to edge, including the strip by the camera"),
                PlayerSettings.isDrawUnderCutoutEnabled(), true, on -> PlayerSettings.setDrawUnderCutoutEnabled(c, on));
        s.toggle(Toggles.AMBILIGHT);
        s.section(t("Боковой чат", "Side chat"));
        s.look(SettingsPreview.PLAYER, SettingsPreview.P_DIVIDER).toggle(t("Перетаскиваемая граница", "Draggable divider"),
                t("Тяните границу между плеером и чатом", "Drag the line between the player and chat"),
                TheatreSettings.isSideChatDividerEnabled(), false, on -> TheatreSettings.setSideChatDividerEnabled(c, on));
        s.action(t("Вернуть ширину чата", "Reset chat width"), t("Ширина Twitch по умолчанию", "Twitch's default width"), () -> {
            TheatreSettings.resetSideChatWidth(c);
            Toast.makeText(c, Strings.dividerWidthResetToast(), Toast.LENGTH_SHORT).show();
        });
        s.toggle(Toggles.WIDE_CHAT_PANELS);
        s.toggle(Toggles.CHAT_OVER_VIDEO);
        int[] steps = TheatreSettings.CHAT_BG_STEPS;
        String[] labels = new String[steps.length];
        for (int i = 0; i < steps.length; i++) {
            labels[i] = steps[i] + "%";
        }
        s.markNew("0.5.17-beta").note(t("0% - без фона, 100% - непрозрачный. Оверлей чата Twitch (одно сообщение поверх видео) тоже берёт эту непрозрачность, когда её выбрали.",
                "0% is no background, 100% is solid. Twitch's chat overlay (one message over the video) takes this opacity too once it's chosen."))
                .slider(t("Фон чата поверх видео", "Chat background over the video"), labels,
                        TheatreDividerHook.stepIndex(TheatreSettings.getChatBgOpacity()), false,
                        i -> TheatreSettings.setChatBgOpacity(c, steps[i]));
    }

    private static void audioPage(SettingsScreen s) {
        final Context c = s.context;
        if (android.os.Build.VERSION.SDK_INT < 28) {
            s.hint(Strings.compressorUnsupported());
            return;
        }
        s.toggle(Toggles.COMPRESSOR_BUTTON);
        s.section(t("Фильтры", "Filters"));
        int[] db = {-12, -9, -6, -3, 0, 3, 6, 9, 12};
        int[] percent = {0, 25, 50, 75, 100};
        s.markNew("0.4.6-alpha").group(Toggles.AUDIO_FILTERS.label(), Toggles.AUDIO_FILTERS.about(),
                Toggles.on(Toggles.AUDIO_FILTERS), false, on -> {
                    Toggles.set(c, Toggles.AUDIO_FILTERS, on);
                    AudioCompressor.refresh();
                }, () -> {
                    audioFilter(s, Strings.audioBoostLabel(), AudioCompressor.BOOST, new int[]{0, 2, 4, 6, 8, 10, 12}, false);
                    audioFilter(s, Strings.audioBassLabel(), AudioCompressor.BASS, db, false);
                    audioFilter(s, Strings.audioTrebleLabel(), AudioCompressor.TREBLE, db, false);
                    audioFilter(s, Strings.audioVoiceLabel(), AudioCompressor.VOICE, percent, true);
                    audioFilter(s, Strings.audioLimitLabel(), AudioCompressor.LIMIT, percent, true);
                });
        s.hint(t("Работают и вместе с компрессором, и без него. Лимитер срезает крики и алерты: чем больше, тем ниже потолок.",
                "They work with or without the compressor. The limiter tames screams and alerts: the higher, the lower the ceiling."));
    }

    private static void syncDanmaku(SettingsPreview preview) {
        preview.state = flag(Toggles.on(Toggles.DANMAKU), SettingsPreview.P_DANMAKU)
                | flag(Toggles.on(Toggles.DANMAKU_NICKS), SettingsPreview.P_DANMAKU_NICKS);
        preview.values[SettingsPreview.VALUE_DM_SPEED] = Danmaku.setting(Danmaku.KEY_SPEED, 1);
        preview.values[SettingsPreview.VALUE_DM_AREA] = Danmaku.setting(Danmaku.KEY_AREA, 1);
        preview.values[SettingsPreview.VALUE_DM_SIZE] = Danmaku.setting(Danmaku.KEY_SIZE, 1);
        preview.values[SettingsPreview.VALUE_DM_OPACITY] = Danmaku.setting(Danmaku.KEY_OPACITY, 2);
    }

    private static void danmakuPage(SettingsScreen s) {
        s.stage(SettingsPreview.PLAYER, SettingsPreview.P_DANMAKU, SettingsHook::syncDanmaku);
        s.group(Toggles.DANMAKU, () -> {
            s.toggle(Toggles.DANMAKU_NICKS);
            danmakuLevel(s, t("Скорость", "Speed"), Strings.danmakuSpeeds(), Danmaku.KEY_SPEED, 1);
            danmakuLevel(s, t("Сколько видео занимает", "Video area"), Strings.danmakuAreas(), Danmaku.KEY_AREA, 1);
            danmakuLevel(s, t("Размер текста", "Text size"), Strings.danmakuSizes(), Danmaku.KEY_SIZE, 1);
            danmakuLevel(s, t("Непрозрачность", "Opacity"), new String[]{"40%", "60%", "80%", "100%"}, Danmaku.KEY_OPACITY, 2);
        });
    }

    private static void danmakuLevel(SettingsScreen s, String title, String[] labels, String key, int fallback) {
        final Context c = s.context;
        s.slider(title, labels, Danmaku.setting(key, fallback), false, i -> Toggles.prefs(c).edit().putInt(key, i).apply());
    }

    /** VODs and clips: the lists, watching them, the extra buttons of their player. */
    private static void vodPage(SettingsScreen s) {
        final Context c = s.context;
        s.section(t("Списки видео", "Video lists"));
        s.toggle(Toggles.VOD_ABSOLUTE_DATES);
        s.toggle(Toggles.VOD_CATEGORY_FILTER);
        s.section(t("Просмотр", "Watching"));
        s.toggle(Toggles.SUB_VODS);
        s.toggle(Toggles.NO_AUTOPLAY);
        String[] steps = Strings.seekStepLabels(PlayerSettings.SEEK_STEPS);
        s.slider(t("Перемотка назад", "Seek back"), steps,
                indexOf(PlayerSettings.SEEK_STEPS, PlayerSettings.getSeekBackSeconds()), false,
                i -> PlayerSettings.setSeekSeconds(c, false, PlayerSettings.SEEK_STEPS[i]));
        s.slider(t("Перемотка вперёд", "Seek forward"), steps,
                indexOf(PlayerSettings.SEEK_STEPS, PlayerSettings.getSeekForwardSeconds()), false,
                i -> PlayerSettings.setSeekSeconds(c, true, PlayerSettings.SEEK_STEPS[i]));
        s.section(t("Кнопки на плеере", "Player buttons"));
        s.toggle(Toggles.CHAPTERS_BUTTON);
        s.toggle(Toggles.VOD_CHAT_SYNC);
    }

    private static void audioFilter(SettingsScreen s, String rowTitle, String key, int[] values, boolean percent) {
        final Context c = s.context;
        s.slider(rowTitle, Strings.audioLevelLabels(values, percent),
                Math.max(0, indexOf(values, AudioCompressor.filter(key))), false,
                i -> AudioCompressor.setFilter(c, key, values[i]));
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static int qualityIndex(boolean cellular) {
        return Math.max(0, indexOf(PlayerSettings.QUALITY_HEIGHTS, PlayerSettings.getDefaultQuality(cellular)));
    }

    private static void chatPage(SettingsScreen s) {
        s.toggle(Toggles.CHAT_OFF);
        s.nav(t("Внешний вид", "Look"), t("Размер текста, время, чередование", "Text size, time, alternating rows"),
                SettingsHook::chatLookPage);
        s.nav(t("Значки и ники", "Badges and nicks"), t("Значки других клиентов, раскраски", "Other clients' badges, paints"),
                SettingsHook::chatBadgesPage);
        s.nav(Strings.sectionMentions(), t("Подсветка, вибрация, свои слова", "Highlight, vibration, your own words"),
                SettingsHook::chatMentionsPage);
        s.nav(Strings.chatFiltersMenuItem(), t("Что не показывать в чате", "What not to show in chat"),
                SettingsHook::chatFiltersPage);
        s.nav(Strings.sectionMessages(), t("Скорость, прокрутка, удалённые, ссылки", "Speed, scrolling, deleted, links"),
                SettingsHook::chatMessagesPage);
        s.nav(t("Модерация", "Moderation"), t("Мод-логи, AutoMod, очистка чата", "Mod logs, AutoMod, chat clears"),
                SettingsHook::chatModerationPage);
    }

    private static void syncChatLook(SettingsPreview preview) {
        preview.state = flags(preview, new Toggles.Toggle[]{Toggles.CHAT_ALTERNATING, Toggles.COLORED_ME, Toggles.FIRST_MESSAGE})
                | flag(ChatSettings.isRteBadgesEnabled(), SettingsPreview.C_RTE)
                | flag(ChatSettings.isNickPaintsEnabled(), SettingsPreview.C_PAINT);
        preview.values[SettingsPreview.VALUE_TIME] = ChatSettings.getTimestampFormat();
        preview.values[SettingsPreview.VALUE_NAME] = ChatSettings.getNameFormat();
        preview.values[SettingsPreview.VALUE_FONT] = ChatSettings.getFontScale();
    }

    private static void chatLookPage(SettingsScreen s) {
        final Context c = s.context;
        s.stage(SettingsPreview.CHAT, SettingsHook::syncChatLook);
        int[] scales = ChatSettings.FONT_SCALES;
        String[] scaleLabels = new String[scales.length];
        for (int i = 0; i < scales.length; i++) {
            scaleLabels[i] = scales[i] == 100 ? Strings.timestampDefault() : scales[i] + "%";
        }
        s.slider(t("Размер текста", "Text size"), scaleLabels, Math.max(0, indexOf(scales, ChatSettings.getFontScale())), false,
                i -> ChatSettings.setFontScale(c, scales[i]));
        s.toggle(Toggles.CHAT_PINCH_TEXT);
        s.strip(t("Время сообщений", "Message time"), null, ChatTimestamps.samples(), ChatSettings.getTimestampFormat(), false,
                i -> ChatSettings.setTimestampFormat(c, i));
        s.strip(t("Ники не латиницей", "Non-Latin nicks"), t("Если ник написан кириллицей, иероглифами...", "When a nick is in Cyrillic, kanji..."),
                Strings.nameFormats(), ChatSettings.getNameFormat(), false, i -> ChatSettings.setNameFormat(c, i));
        s.section(t("Выделение", "Emphasis"));
        s.toggle(Toggles.CHAT_ALTERNATING);
        s.toggle(Toggles.COLORED_ME);
        s.toggle(Toggles.FIRST_MESSAGE);
    }

    private static void chatBadgesPage(SettingsScreen s) {
        final Context c = s.context;
        s.look(SettingsPreview.CHAT, SettingsPreview.C_RTE).toggle(t("Значок ReYohoho", "ReYohoho badge"),
                t("У тех, кто пользуется RTE или ReTwitch", "On people using RTE or ReTwitch"),
                ChatSettings.isRteBadgesEnabled(), false, on -> ChatSettings.setRteBadgesEnabled(c, on));
        s.look(SettingsPreview.CHAT, SettingsPreview.C_PAINT).toggle(t("Раскраска ников", "Nick paints"),
                t("Цветные ники пользователей RTE и 7TV", "Coloured nicks of RTE and 7TV users"),
                ChatSettings.isNickPaintsEnabled(), false, on -> ChatSettings.setNickPaintsEnabled(c, on));
        s.toggle(Toggles.COLORED_MENTIONS);
        s.section(t("Значки других клиентов", "Other clients' badges"));
        s.toggles(Toggles.THIRD_PARTY_BADGES);
    }

    private static void syncMentions(SettingsPreview preview) {
        preview.state = flag(ChatSettings.isMentionHighlightEnabled(), SettingsPreview.C_HIGHLIGHT);
        preview.values[SettingsPreview.VALUE_MENTION] = ChatSettings.getMentionColorIndex();
        preview.values[SettingsPreview.VALUE_WORDS] = ChatSettings.getHighlightWords().trim().isEmpty() ? 0
                : ChatSettings.getWordsColorIndex() + 2;
    }

    private static void chatMentionsPage(SettingsScreen s) {
        final Context c = s.context;
        s.stage(SettingsPreview.CHAT, SettingsPreview.C_HIGHLIGHT, SettingsHook::syncMentions);
        s.group(t("Подсветка", "Highlight"), t("Сообщения с вашим @ником", "Messages with your @nick"),
                ChatSettings.isMentionHighlightEnabled(), false, on -> ChatSettings.setMentionHighlightEnabled(c, on),
                () -> s.strip(t("Цвет", "Colour"), null, Strings.mentionColorLabels(), ChatSettings.getMentionColorIndex(), false,
                        i -> ChatSettings.setMentionColorIndex(c, i)));
        int[] lengths = {30, 60, 100, 200, 400};
        String[] lengthLabels = new String[lengths.length];
        for (int i = 0; i < lengths.length; i++) {
            lengthLabels[i] = Strings.vibrateMsLabel(lengths[i]);
        }
        s.group(t("Вибрация", "Vibration"), t("Когда вас упомянули", "When you're mentioned"),
                ChatSettings.isMentionVibrateEnabled(), false, on -> ChatSettings.setMentionVibrateEnabled(c, on),
                () -> s.slider(t("Длительность", "Length"), lengthLabels,
                        Math.max(0, indexOf(lengths, ChatSettings.getMentionVibrateMs())), false,
                        i -> ChatSettings.setMentionVibrateMs(c, lengths[i])));
        int[] volumes = {25, 50, 75, 100};
        String[] volumeLabels = {"25%", "50%", "75%", "100%"};
        s.markNew("0.5.2-beta").group(t("Звук", "Sound"), t("Когда вас упомянули: звук уведомления", "When you're mentioned: the notification sound"),
                ChatSettings.isMentionSoundEnabled(), false, on -> ChatSettings.setMentionSoundEnabled(c, on),
                () -> {
                    s.action(t("Мелодия", "Sound"), SoundPicker.title(c),
                            () -> SoundPicker.pick(c, () -> Toast.makeText(c, SoundPicker.title(c), Toast.LENGTH_SHORT).show()));
                    s.slider(t("Громкость", "Volume"), volumeLabels,
                            Math.max(0, indexOf(volumes, ChatSettings.getMentionSoundVolume())), false,
                            i -> ChatSettings.setMentionSoundVolume(c, volumes[i]));
                });
        s.toggle(Toggles.REPLY_ALERTS);
        String words = ChatSettings.getHighlightWords();
        s.action(t("Свои слова и ники", "Your own words and nicks"), words.isEmpty() ? Strings.noneLabel() : words,
                () -> showHighlightWordsDialog(s));
        String[] colors = Strings.mentionColorLabels();
        String[] wordColors = new String[colors.length + 1];
        wordColors[0] = t("Как упоминания", "As mentions");
        System.arraycopy(colors, 0, wordColors, 1, colors.length);
        s.markNew("0.5.10-beta").strip(t("Цвет своих слов", "Your words' colour"), null, wordColors,
                ChatSettings.getWordsColorIndex() + 1, false, i -> ChatSettings.setWordsColorIndex(c, i - 1));
    }

    private static void chatFiltersPage(SettingsScreen s) {
        final Context c = s.context;
        s.section(t("Скрыть в чате", "Hide in chat"));
        s.toggles(Toggles.CHAT_FILTERS);
        s.toggle(Toggles.HIDE_SERVICE_MESSAGES);
        s.section(t("Чёрные списки", "Blocklists"));
        s.look(SettingsPreview.CHAT, SettingsPreview.C_WORDS);
        s.action(Strings.blacklistWordsLabel(), Strings.blacklistCount(ChatBlacklist.count(ChatBlacklist.KEY_WORDS)),
                () -> ChatBlacklist.showEditor(c, ChatBlacklist.KEY_WORDS, Strings.blacklistWordsLabel(),
                        Strings.blacklistWordsHint()));
        s.action(Strings.blacklistUsersLabel(), Strings.blacklistCount(ChatBlacklist.count(ChatBlacklist.KEY_USERS)),
                () -> ChatBlacklist.showEditor(c, ChatBlacklist.KEY_USERS, Strings.blacklistUsersLabel(),
                        Strings.blacklistUsersHint()));
    }

    private static void chatMessagesPage(SettingsScreen s) {
        final Context c = s.context;
        s.section(t("Скорость", "Speed"));
        s.toggle(Toggles.CHAT_REALTIME);
        s.toggle(Toggles.CHAT_SYNC);
        s.look(SettingsPreview.CHAT, SettingsPreview.C_FADE).markNew("0.4.11-alpha")
                .note(t("На быстром чате возможны рывки и наезд строк, лучше не включать.",
                        "In a fast chat rows can jerk and overlap, better leave it off."))
                .toggle(Strings.smoothMessagesToggleLabel(),
                Strings.smoothMessagesToggleSummary(), ChatSettings.isSmoothMessagesEnabled(), true,
                on -> ChatSettings.setSmoothMessagesEnabled(c, on));
        s.section(t("Прокрутка", "Scrolling"));
        s.compare(t("Новые сообщения возвращают чат вниз, пока вы читаете выше", "New messages take chat back down while you read higher up"),
                t("Автопрокрутка работает только у конца", "Autoscroll only works near the bottom"))
                .toggle(t("Не сбрасывать прокрутку", "Keep scroll position"), t("Чат не уезжает вниз, пока вы читаете выше", "Chat stays put while you read higher up"),
                        ChatSettings.isStickyScrollEnabled(), true, on -> ChatSettings.setStickyScrollEnabled(c, on));
        s.toggle(Toggles.VOD_STICKY_SCROLL);
        s.toggle(Toggles.NEW_MESSAGES_COUNT);
        s.section(Strings.deletedModeLabel());
        s.lookEach(SettingsPreview.CHAT, SettingsPreview.C_DELETED, SettingsPreview.VALUE_DELETED)
                .choice(t("Как показывать", "How to show them"), Strings.deletedModes(), ChatSettings.getDeletedMode(), true,
                        i -> ChatSettings.setDeletedMode(c, i));
        String[] alphaLabels = new String[ChatSettings.DELETED_ALPHAS.length];
        for (int i = 0; i < alphaLabels.length; i++) {
            alphaLabels[i] = ChatSettings.DELETED_ALPHAS[i] + "%";
        }
        s.group(Toggles.DELETED_BACKGROUND, () -> {
            s.strip(t("Цвет", "Colour"), null, Strings.mentionColorLabels(), ChatSettings.getDeletedBgColorIndex(), false,
                    i -> ChatSettings.setDeletedBgColorIndex(c, i));
            s.slider(t("Насыщенность", "Strength"), alphaLabels, ChatSettings.getDeletedBgAlphaIndex(), false,
                    i -> ChatSettings.setDeletedBgAlphaIndex(c, i));
        });
        s.section(t("Удобства", "Conveniences"));
        s.compare(t("Сундук с баллами нужно нажимать вручную", "You have to tap the points chest yourself"),
                t("Баллы собираются сами", "Points are claimed by themselves"))
                .toggle(t("Собирать баллы канала", "Claim channel points"), t("Нажимает на сундук за вас", "Taps the chest for you"),
                        ChatSettings.isAutoClaimEnabled(), true, on -> ChatSettings.setAutoClaimEnabled(c, on));
        s.toggle(Toggles.REWARD_STATUS);
        s.toggle(Toggles.COPY_SEND);
        s.toggle(Toggles.USER_HISTORY);
        s.toggle(Toggles.ROBOTTY_HISTORY);
        s.toggle(Toggles.REPLY_THREADS);
        s.section(t("Ссылки", "Links"));
        s.toggle(Toggles.NO_LINK_DISCLAIMER);
        s.nav(Strings.linkPreviewMenuItem(), Strings.linkPreviewModes()[LinkPreviewSettings.getMode()],
                SettingsHook::linkPreviewPage);
    }

    private static void chatModerationPage(SettingsScreen s) {
        s.toggle(Toggles.MOD_LOGS);
        s.toggle(Toggles.PREVENT_MOD_CLEAR);
        s.toggle(Toggles.AUTOMOD_CHAT);
        s.toggle(Toggles.MOD_ACTIONS);
        s.toggle(Toggles.RAID_CARD);
    }

    /** Free-text highlight terms (see MentionHighlighter.Terms). */
    private static void showHighlightWordsDialog(final SettingsScreen s) {
        final Context c = s.context;
        final EditText input = new EditText(c);
        input.setText(ChatSettings.getHighlightWords());
        input.setHint("word, @nick");
        new AlertDialog.Builder(c)
                .setTitle(Strings.highlightWordsLabel())
                .setMessage(Strings.highlightWordsHint())
                .setView(input)
                .setPositiveButton(Strings.save(),
                        (d, which) -> ChatSettings.setHighlightWords(c, input.getText().toString()))
                .setNegativeButton(Strings.cancel(), null)
                .show();
    }

    private static void emotesPage(SettingsScreen s) {
        final Context c = s.context;
        s.section(t("Откуда брать эмоуты", "Emote sources"));
        s.look(SettingsPreview.CHAT, SettingsPreview.C_7TV).toggle("7TV", null, EmoteSettings.isSevenTvEnabled(), false,
                on -> EmoteSettings.setSevenTvEnabled(c, on));
        s.look(SettingsPreview.CHAT, SettingsPreview.C_BTTV).toggle("BetterTTV (BTTV)", null, EmoteSettings.isBttvEnabled(), false,
                on -> EmoteSettings.setBttvEnabled(c, on));
        s.look(SettingsPreview.CHAT, SettingsPreview.C_FFZ).toggle("FrankerFaceZ (FFZ)", null, EmoteSettings.isFfzEnabled(), false,
                on -> EmoteSettings.setFfzEnabled(c, on));
        s.toggle(Toggles.NO_GLOBAL_EMOTES);
        s.look(SettingsPreview.CHAT, SettingsPreview.C_HOMIES).toggle("Homies", t("В разделах 7TV", "In the 7TV sections"),
                EmoteSettings.isHomiesEnabled(), false, on -> EmoteSettings.setHomiesEnabled(c, on));
        s.section(t("В чате", "In chat"));
        int[] sizes = {16, 18, 20, 22, 24, 26, 28, 32, 36, 40, 48, 56, 64};
        String[] sizeLabels = new String[sizes.length];
        int sizeIndex = 0;
        for (int i = 0; i < sizes.length; i++) {
            sizeLabels[i] = String.valueOf(sizes[i]);
            if (sizes[i] <= EmoteSettings.getEmoteSizeDp()) {
                sizeIndex = i;
            }
        }
        s.slider(t("Размер", "Size"), sizeLabels, sizeIndex, false, i -> EmoteSettings.setEmoteSizeDp(c, sizes[i]));
        s.note(t("Смена качества сбрасывает кэш эмоутов.", "Changing the quality clears the emote cache."))
                .strip(t("Качество картинок", "Image quality"), null, Strings.emoteQualityModes(), EmoteSettings.getQuality() - 1, false,
                        i -> EmoteSettings.setQuality(c, i + 1));
        s.look(SettingsPreview.CHAT, SettingsPreview.C_MODIFIERS).toggle(t("Модификаторы", "Modifiers"),
                t("w! h! v! c! ffzX меняют соседний эмоут", "w! h! v! c! ffzX change the next emote"),
                EmoteSettings.isModifiersEnabled(), false, on -> EmoteSettings.setModifiersEnabled(c, on));
        s.toggle(Toggles.FFZ_GIANT_EMOTES);
        s.toggle(t("Карточка по нажатию", "Card on tap"), t("Нажатие на эмоут 7TV, BTTV или FFZ покажет, откуда он", "Tapping a 7TV, BTTV or FFZ emote shows where it's from"),
                EmoteSettings.isEmoteCardEnabled(), false, on -> EmoteSettings.setEmoteCardEnabled(c, on));
        s.section(t("Панель эмоутов", "Emote panel"));
        s.group(Toggles.EMOTE_FAVORITES, () -> {
            s.toggle(Toggles.EMOTE_FAVORITES_PER_CHANNEL);
            s.toggle(Toggles.FAVORITES_FIRST_AUTOCOMPLETE);
        });
        s.toggle(Toggles.FREQUENT_EMOTES);
        s.toggle(Toggles.PICKER_NO_STICKY_HEADERS);
        s.choice(t("Кнопка стирания", "Backspace"), t("Что удаляет кнопка в панели", "What the panel's button deletes"),
                Strings.pickerBackspaceModes(), EmoteSettings.getPickerBackspace(), false,
                i -> EmoteSettings.setPickerBackspace(c, i));
        s.section(t("Набор текста", "Typing"));
        s.look(SettingsPreview.INPUT, SettingsPreview.I_NO_COLON).toggle(t("Подсказки без двоеточия", "Suggestions without a colon"),
                t("Эмоуты предлагаются сразу, как начнёте печатать", "Emotes are suggested as soon as you type"),
                EmoteSettings.isAutocompleteNoColonEnabled(), false, on -> EmoteSettings.setAutocompleteNoColonEnabled(c, on));
        s.toggle(Toggles.EMOTE_INPUT_IMAGES);
        s.section(t("Загрузка", "Loading"));
        s.toggle(t("Через прокси ReYohoho", "Through the ReYohoho proxy"), t("Если эмоуты не грузятся напрямую", "If emotes won't load directly"),
                EmoteSettings.isProxyEnabled(), false, on -> EmoteSettings.setProxyEnabled(c, on));
        s.toggle(t("BTTV в WEBP", "BTTV as WEBP"), t("Меньше трафика", "Less traffic"), EmoteSettings.isBttvWebpEnabled(), false,
                on -> EmoteSettings.setBttvWebpEnabled(c, on));
        s.action(t("Очистить кэш", "Clear the cache"), Strings.cacheDiskUsage(formatBytes(EmoteCache.diskCacheBytes())), () -> {
            EmoteCache.resetAll(ChannelTracker.getCurrentChannelId());
            Toast.makeText(c, Strings.cacheResetToast(), Toast.LENGTH_SHORT).show();
        });
    }

    /** Link previews, laid out like the RTE extension's "Link Preview" panel. */
    private static void linkPreviewPage(SettingsScreen s) {
        final Context c = s.context;
        final int mode = LinkPreviewSettings.getMode();
        final int height = LinkPreviewSettings.getMaxHeightDp();
        final int width = LinkPreviewSettings.getMaxWidthDp();
        final boolean blur = LinkPreviewSettings.isBlurEnabled();
        final boolean zoom = LinkPreviewSettings.isZoomEnabled();
        s.lookEach(SettingsPreview.CHAT, SettingsPreview.C_LINK, SettingsPreview.VALUE_LINK)
                .choice(Strings.linkPreviewModeTitle(), Strings.linkPreviewModes(), mode, false,
                        i -> LinkPreviewSettings.save(c, i, height, width, blur, zoom));
        s.section(t("Картинка", "Image"));
        s.number(Strings.linkPreviewMaxHeight(), height,
                v -> LinkPreviewSettings.save(c, mode, v, width, blur, zoom));
        s.number(Strings.linkPreviewMaxWidth(), width,
                v -> LinkPreviewSettings.save(c, mode, height, v, blur, zoom));
        s.toggle(Strings.linkPreviewBlur(), null, blur, false,
                on -> LinkPreviewSettings.save(c, mode, height, width, on, zoom));
        s.toggle(Strings.linkPreviewZoom(), null, zoom, false,
                on -> LinkPreviewSettings.save(c, mode, height, width, blur, on));
    }

    /** Everything the mod hides, by where it is on screen; each thing in one place only. */
    private static void uiPage(SettingsScreen s) {
        final Context c = s.context;
        s.section(t("Что убрать с экрана", "What to remove from the screen"));
        s.nav(Strings.playerMenuItem(), t("Кнопки, подписки, предупреждение 18+", "Buttons, follow, 18+ warning"), SettingsHook::uiPlayerPage);
        s.nav(Strings.sectionChatHeader(), t("Заголовок, кнопки, лидеры", "Title, buttons, leaders"), SettingsHook::uiHeaderPage);
        s.nav(t("Закрепы и баннеры", "Pins and banners"), t("Всё, что висит над сообщениями", "Everything above the messages"), SettingsHook::uiBannersPage);
        s.nav(Strings.sectionChatInput(), t("Кнопки и поле в полном экране", "Buttons and the field in fullscreen"), SettingsHook::uiInputPage);
        s.section(t("Страница канала", "Channel page"));
        s.markNew("0.5.27-beta").choice(t("Вид страницы канала", "Channel page type"),
                new String[]{t("Как решит Twitch", "As Twitch decides"), t("Нативная", "Native"), "React Native"},
                LandingSettings.getChannelPageMode(), true, i -> LandingSettings.setChannelPageMode(c, i));
        s.toggle(Toggles.HIDE_PROFILE_FOLLOW);
        s.toggle(Toggles.HIDE_PROFILE_SUBSCRIBE);
        s.toggle(Toggles.HIDE_PROFILE_GIFT);
        s.toggle(Toggles.HIDE_PROFILE_RECAPS);
        s.toggle(Toggles.HIDE_PROFILE_FEATURED_CLIPS);
        s.toggle(Toggles.HIDE_PROFILE_CATEGORIES);
        s.toggle(Toggles.HIDE_PROFILE_STREAMERS);
        s.section(Strings.sectionAppIcon());
        s.markNew("0.4.17-alpha").icons(Strings.appIconLabel(), Strings.appIconModes(), AppIcon.DRAWABLES, AppIcon.current(c),
                i -> AppIcon.set(c, i));
    }

    private static void syncUiPlayer(SettingsPreview preview) {
        preview.state = flags(preview, Toggles.UI_PLAYER);
    }

    private static void uiPlayerPage(SettingsScreen s) {
        s.stage(SettingsPreview.PLAYER, SettingsHook::syncUiPlayer);
        s.toggles(Toggles.UI_PLAYER);
        s.section(t("Вертикальный вид", "Vertical view"));
        s.toggle(Toggles.HIDE_VERTICAL_BUTTONS);
        s.toggle(Toggles.HIDE_VERTICAL_PANEL);
    }

    private static void syncUiHeader(SettingsPreview preview) {
        preview.state = flags(preview, Toggles.UI_CHAT_HEADER);
    }

    private static void uiHeaderPage(SettingsScreen s) {
        final Context c = s.context;
        s.stage(SettingsPreview.HEADER, SettingsHook::syncUiHeader);
        s.choice(t("Шапка целиком", "Whole header"), t("Если убрать всё из строки, шапка скроется сама", "With nothing left in the row, the header goes by itself"),
                Strings.chatHeaderModes(), ChatHeaderSetting.choice(), false, i -> ChatHeaderSetting.setChoice(c, i));
        s.section(t("Скрыть в шапке", "Hide in the header"));
        s.toggles(Toggles.UI_CHAT_HEADER);
        s.section(t("Чат видео", "Video chat"));
        s.toggle(Toggles.HIDE_VOD_CHAT_HEADER);
    }

    private static void uiBannersPage(SettingsScreen s) {
        final Context c = s.context;
        s.section(t("Закрепы", "Pins"));
        s.toggle(Toggles.HIDE_PINNED_CHEERS);
        s.lookEach(SettingsPreview.BANNERS, SettingsPreview.B_PIN, SettingsPreview.VALUE_PINNED)
                .choice(t("Закрепы стримера и модераторов", "Streamer and mod pins"), Strings.pinnedMessagesModes(), ChatFilters.pinnedMode(), false,
                        i -> ChatFilters.setPinnedMode(c, i));
        s.section(t("Скрыть баннеры", "Hide banners"));
        s.toggles(Toggles.BANNERS);
    }

    private static void syncUiInput(SettingsPreview preview) {
        preview.state = flags(preview, Toggles.UI_CHAT_INPUT);
    }

    private static void uiInputPage(SettingsScreen s) {
        s.stage(SettingsPreview.INPUT, SettingsHook::syncUiInput);
        s.section(t("Скрыть", "Hide"));
        s.toggle(Toggles.HIDE_BITS_BUTTON);
        s.toggle(Toggles.HIDE_CHAT_SETTINGS_BUTTON);
        s.toggle(Toggles.EMOTE_BUTTON_TYPING_ONLY);
        s.toggle(Toggles.HIDE_POINTS_PORTRAIT);
        s.toggle(Toggles.HIDE_POINTS_LANDSCAPE);
        s.toggle(Toggles.HIDE_LANDSCAPE_CHAT_INPUT);
        s.toggle(Toggles.HIDE_ONE_CHAT_INPUT);
        s.section(t("Показать", "Show"));
        s.group(Toggles.POINTS_BALANCE_LANDSCAPE, () -> s.toggle(Toggles.POINTS_BALANCE_UNDER_ICON));
    }

    /** The home screen is React Native: these act on its data, so most take a restart. */
    private static void homePage(SettingsScreen s) {
        final Context c = s.context;
        s.look(SettingsPreview.HOME, SettingsPreview.H_FOLLOWING).toggle(t("Открывать «Отслеживаемое»", "Open Following"),
                t("При запуске вместо «В эфире»", "At launch instead of Live"),
                LandingSettings.isFollowingDefaultEnabled(), true, on -> LandingSettings.setFollowingDefaultEnabled(c, on));
        s.section(t("Нижнее меню: скрыть", "Bottom menu: hide"));
        s.toggle(Toggles.HIDE_TAB_BROWSE);
        s.toggle(Toggles.HIDE_TAB_ACTIVITY);
        s.toggle(Toggles.HIDE_TAB_CREATE);
        s.section(t("Отслеживаемое", "Following"));
        s.markNew("0.5.10-beta").lookEach(SettingsPreview.HOME, SettingsPreview.H_PREVIEW_SIZE, SettingsPreview.VALUE_PREVIEW)
                .choice(t("Превью каналов в эфире", "Live channel previews"),
                        new String[]{t("Как в Twitch", "As in Twitch"), t("Средние", "Medium"), t("Крупные", "Large")},
                        LandingSettings.getFollowingPreviewSize(), true, i -> LandingSettings.setFollowingPreviewSize(c, i));
        s.toggle(Toggles.FAVORITE_CHANNELS);
        s.toggle(Toggles.FRIENDS_HOME);
        s.toggle(Toggles.FOLLOWING_STREAK_TAG);
        s.toggle(Toggles.SORT_FOLLOWED_CATEGORIES);
        s.toggle(Toggles.SEPARATE_DRAWER_SORT);
        s.toggle(Toggles.HIDE_FOLLOWING_CATEGORIES);
        s.toggle(Toggles.HIDE_FOLLOWING_CONTINUE);
        s.toggle(Toggles.HIDE_FOLLOWING_OFFLINE);
        s.toggle(Toggles.HIDE_STORIES_EMPTY);
        s.section(t("Превью стримов", "Stream previews"));
        s.toggle(Toggles.STREAM_UPTIME);
        s.section(t("Шапка главной: скрыть", "Home header: hide"));
        s.toggle(Toggles.HIDE_HOME_STORIES);
        s.toggle(Toggles.HIDE_FEED_TABS);
        s.section(t("В эфире", "Live"));
        s.toggle(Toggles.COMPACT_LIVE_TAB);
        s.toggle(Toggles.HIDE_LIVE_FOLLOWED_SHELF);
        s.toggle(Toggles.HIDE_LIVE_CLIPS_SHELF);
    }

    private static void networkPage(SettingsScreen s) {
        final Context c = s.context;
        s.section(t("Прокси", "Proxy"));
        s.action(t("Прокси трансляций", "Stream proxy"), ProxySettings.getSelected().label(), () -> showProxyDialog(c));
        s.toggle(Toggles.CHAT_PROXY);
        s.hint(t("Прокси нужен, когда Twitch медленный или недоступен. Рекламу убирает не он, а блокировка из раздела «Реклама».",
                "A proxy helps when Twitch is slow or unreachable. It doesn't remove ads: the Ads section does."));
        s.section(t("Доступ", "Access"));
        s.toggle(Toggles.WATCH_LOGGED_OUT);
        s.toggle(Toggles.BAN_BYPASS);
    }

    private static void fixesPage(SettingsScreen s) {
        s.hint(Strings.fixesHint());
        s.toggles(Toggles.FIXES);
    }

    /** Settings export/import through the system file picker (see {@link SettingsBackup}). */
    private static void backupPage(SettingsScreen s) {
        final Context c = s.context;
        s.action(Strings.backupExport(), t("Файл со всеми настройками, включая избранное", "A file with all settings, favorites included"),
                () -> SettingsBackup.exportTo(c));
        s.action(Strings.backupImport(), t("Заменит текущие настройки мода", "Replaces the current mod settings"),
                () -> SettingsBackup.importFrom(c, s::restartNeeded));
        s.hint(Strings.backupHint());
        s.section(t("Сброс", "Reset"));
        s.markNew("0.4.0-alpha").danger(Strings.resetSettings(), t("Избранные эмоуты и смещения чата останутся", "Favorite emotes and chat offsets stay"),
                () -> new AlertDialog.Builder(c)
                        .setTitle(Strings.resetSettings())
                        .setMessage(Strings.resetSettingsConfirm())
                        .setPositiveButton(Strings.resetSettingsYes(), (d, w) -> {
                            SettingsBackup.resetAll(c);
                            s.restartNeeded();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
    }

    private static String translationState(Context c) {
        String state = TranslationFiles.name(TranslationFiles.chosenLanguage(c));
        int own = TranslationFiles.ownLanguages(c).size();
        return own == 0 ? state : state + " · " + Strings.pickf("своих файлов: %d", "own files: %d", own);
    }

    /** The mod's language and translation files (TranslationFiles). */
    private static void translationPage(SettingsScreen s) {
        final Context c = s.context;
        final String[] codes = TranslationFiles.languages(c);
        String[] labels = new String[codes.length];
        String chosen = TranslationFiles.chosenLanguage(c);
        int index = 0;
        for (int i = 0; i < codes.length; i++) {
            labels[i] = TranslationFiles.name(codes[i]);
            index = codes[i].equals(chosen) ? i : index;
        }
        s.strip(t("Язык мода", "Mod language"), t("Twitch остаётся на языке системы", "Twitch stays in the system language"),
                labels, index, false, i -> {
                    TranslationFiles.choose(c, codes[i]);
                    // Redrawn in the new language; strings elsewhere were already built.
                    new Handler(Looper.getMainLooper()).post(s::restartNeeded);
                });
        // The site checks a suggestion, the admin accepts it, kitchen/i18n.py pull brings it into the next build.
        String language = Strings.language();
        String site = LINK_SITE + "translate" + ("ru".equals(language) || "en".equals(language) ? "" : "/" + language);
        s.external(t("Перевести на сайте", "Translate on the website"),
                t("Удобный редактор: поиск, проверка строк, принятое попадёт в следующую версию мода",
                        "A handy editor: search, string checks, accepted strings go into the next mod version"),
                () -> openLink(c, site));
        int total = TranslationFiles.catalogSize(c);
        s.section(t("Переводы в моде", "Translations in the mod"));
        for (String code : TranslationFiles.shippedLanguages(c)) {
            s.action(TranslationFiles.name(code), Strings.pickf("%d из %d строк", "%d of %d strings",
                    TranslationFiles.shippedCount(c, code), total), null);
        }
        java.util.List<String> own = TranslationFiles.ownLanguages(c);
        if (!own.isEmpty()) {
            s.section(t("Ваши файлы", "Your files"));
            for (String code : own) {
                s.action(TranslationFiles.name(code), Strings.pickf("Строк: %d · только на этом телефоне",
                        "Strings: %d · on this phone only", TranslationFiles.own(c, code).size()), () ->
                        new AlertDialog.Builder(c)
                                .setTitle(TranslationFiles.name(code))
                                .setItems(new String[]{t("Поделиться файлом", "Share the file"), t("Удалить", "Delete")},
                                        (dialog, which) -> {
                                            if (which == 0) {
                                                TranslationFiles.share(c, code);
                                            } else {
                                                TranslationFiles.delete(c, code);
                                                s.restartNeeded();
                                            }
                                        })
                                .show());
            }
        }
        s.section(t("Файлы", "Files"));
        s.action(t("Скачать файл для перевода", "Download a file to translate"),
                t("Английский, русский и перевод рядом, для Google Таблиц или Excel",
                        "English, Russian and the translation side by side, for Google Sheets or Excel"),
                () -> TranslationFiles.exportFile(c));
        s.action(t("Загрузить свой перевод", "Load your translation"),
                t("Новый язык или правки к старому, работает сразу", "A new language or fixes to one, works right away"),
                () -> TranslationFiles.importFile(c, s::restartNeeded));
        s.hint(t("Загруженный файл работает только на этом телефоне и сильнее перевода в моде. Чтобы перевод попал в мод, "
                        + "загрузите файл на сайте («Перевести на сайте» выше) или поделитесь им с разработчиком: кнопка у вашего "
                        + "языка в «Ваших файлах».",
                "A loaded file works on this phone only and wins over the mod's translation. To get the translation into "
                        + "the mod, upload the file on the website (“Translate on the website” above) or share it with the "
                        + "developer: the button is at your language under “Your files”."));
    }

    private static void diagnosticsPage(SettingsScreen s) {
        final Context c = s.context;
        s.section(t("Логи для отчётов", "Logs for reports"));
        s.toggle(Toggles.VAFT_LOG.label(), Toggles.VAFT_LOG.about(), Toggles.on(Toggles.VAFT_LOG), false, on -> {
            Toggles.set(c, Toggles.VAFT_LOG, on);
            String file = tv.retwitch.mod.ads.VaftLog.setEnabled(c, on);
            if (file != null) {
                Toast.makeText(c, Strings.vaftLogToast(on, file), Toast.LENGTH_LONG).show();
            }
        });
        s.toggle(Toggles.UI_STALL_LOG.label(), Toggles.UI_STALL_LOG.about(), Toggles.on(Toggles.UI_STALL_LOG), false, on -> {
            Toggles.set(c, Toggles.UI_STALL_LOG, on);
            String file = UiStallLog.setEnabled(c, on);
            if (file != null) {
                Toast.makeText(c, Strings.vaftLogToast(on, file), Toast.LENGTH_LONG).show();
            }
        });
        s.markNew("0.4.7-alpha").toggle(Toggles.BAN_DIAG_LOG.label(), Toggles.BAN_DIAG_LOG.about(),
                Toggles.on(Toggles.BAN_DIAG_LOG), false, on -> {
                    Toggles.set(c, Toggles.BAN_DIAG_LOG, on);
                    String file = BanDiag.setEnabled(c, on);
                    if (file != null) {
                        Toast.makeText(c, Strings.vaftLogToast(on, file), Toast.LENGTH_LONG).show();
                    }
                });
        s.section(t("Проверки", "Checks"));
        s.action(Strings.pushMenuItem(), t("Статус регистрации в Firebase", "Firebase registration status"), () -> showPushStatusDialog(c));
        s.action(Strings.menuTestCrash(), t("Проверить отправку отчётов о падениях", "Check that crash reports get sent"), () -> showTestCrashDialog(c));
        s.section(t("Для смелых", "For the brave"));
        s.markNew("0.4.17-alpha").nav(Strings.experimentsTitle(), Strings.experimentsSummary(), ExperimentsPage::build);
    }

    /** What Firebase push registration reports on this device (see {@link PushRegistration}). */
    private static void showPushStatusDialog(final Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(Strings.pushMenuItem())
                .setMessage(PushRegistration.statusText(context))
                .setPositiveButton("OK", null);
        if (PushRegistration.needsNotificationPermission(context)) {
            try {
                PushRegistration.requestNotificationPermission(context);
            } catch (Throwable t) {
                android.util.Log.w("RTPush", "permission request failed", t);
                CrashReporter.report("RTPush", "permission request failed", t);
            }
            builder.setNeutralButton(Strings.pushRequestPermission(), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        PushRegistration.requestNotificationPermission(context);
                    } catch (Throwable t) {
                        android.util.Log.w("RTPush", "permission request failed", t);
                        CrashReporter.report("RTPush", "permission request failed", t);
                    }
                }
            });
        }
        builder.show();
    }

    /**
     * Deliberately crashes the app to verify the crash reporter (see
     * {@link CrashReporter}). Behind a confirmation dialog so it isn't hit by
     * accident. The throw happens straight from the dialog's click callback --
     * i.e. on the main thread, uncaught -- so it exercises exactly the
     * process-wide {@code Thread.setDefaultUncaughtExceptionHandler} that
     * AppMetrica installs, and the report is delivered on the next launch.
     */
    private static void showTestCrashDialog(final Context context) {
        new AlertDialog.Builder(context)
                .setTitle(Strings.testCrashTitle())
                .setMessage(Strings.testCrashMessage())
                .setPositiveButton(Strings.testCrashConfirm(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        throw new RuntimeException(
                                "ReTwitch TestCrash: deliberate crash from the settings menu"
                                        + " (mod " + BuildInfo.VERSION_NAME + ")");
                    }
                })
                .setNegativeButton(Strings.cancel(), null)
                .show();
    }

    /**
     * One row of the proxy picker: either a non-selectable group header or a
     * selectable {@link ProxyBackend}. Lets us present the ReYohoho hosts as
     * their own group, above the public anti-ad proxies, instead of one flat
     * list.
     */
    private static final class ProxyRow {
        final String header;         // non-null => group header
        final ProxyBackend backend;  // non-null => selectable backend
        volatile String statusSuffix = "";

        private ProxyRow(String header, ProxyBackend backend) {
            this.header = header;
            this.backend = backend;
        }

        static ProxyRow header(String title) {
            return new ProxyRow(title, null);
        }

        static ProxyRow item(ProxyBackend backend) {
            return new ProxyRow(null, backend);
        }

        boolean isHeader() {
            return header != null;
        }

        String label() {
            return backend.label() + statusSuffix + (backend.countsDrops() ? "" : "\n" + Strings.proxyNoDrops());
        }
    }

    private static List<ProxyRow> buildProxyRows() {
        List<ProxyRow> rows = new ArrayList<>();
        rows.add(ProxyRow.item(ProxyBackend.DISABLED));

        rows.add(ProxyRow.header(Strings.proxyGroupReYohoho()));
        for (ProxyBackend b : ProxyBackend.values()) {
            if (b.isReYohoho()) {
                rows.add(ProxyRow.item(b));
            }
        }

        rows.add(ProxyRow.header(Strings.proxyGroupAds()));
        for (ProxyBackend b : ProxyBackend.values()) {
            if (b.isPublicAdProxy()) {
                rows.add(ProxyRow.item(b));
            }
        }

        rows.add(ProxyRow.header(Strings.proxyGroupOther()));
        rows.add(ProxyRow.item(ProxyBackend.CUSTOM));
        return rows;
    }

    private static void showProxyDialog(final Context context) {
        final List<ProxyRow> rows = buildProxyRows();
        final ProxyBackend current = ProxySettings.getSelected();
        int checkedRow = 0;
        for (int i = 0; i < rows.size(); i++) {
            ProxyRow row = rows.get(i);
            if (!row.isHeader() && row.backend == current) {
                checkedRow = i;
            }
        }

        // A custom adapter (rather than a flat ArrayAdapter over
        // simple_list_item_single_choice) so we can interleave disabled
        // group headers with the selectable CheckedTextView rows the
        // single-choice list still renders with its built-in radio buttons.
        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return rows.size();
            }

            @Override
            public Object getItem(int position) {
                return rows.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public int getViewTypeCount() {
                return 2;
            }

            @Override
            public int getItemViewType(int position) {
                return rows.get(position).isHeader() ? 0 : 1;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return !rows.get(position).isHeader();
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ProxyRow row = rows.get(position);
                if (row.isHeader()) {
                    TextView tv = new TextView(context);
                    int padH = dp(context, 16);
                    tv.setPadding(padH, dp(context, 16), padH, dp(context, 6));
                    tv.setText(row.header);
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                    tv.setAllCaps(true);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    tv.setTextColor(0xFF9147FF); // Twitch purple accent
                    return tv;
                }
                CheckedTextView ctv = (convertView instanceof CheckedTextView)
                        ? (CheckedTextView) convertView
                        : (CheckedTextView) View.inflate(
                                context, android.R.layout.simple_list_item_single_choice, null);
                ctv.setText(row.label());
                return ctv;
            }
        };

        // Note: AlertDialog silently drops setMessage() when
        // setSingleChoiceItems() is also used, so any per-backend caveat has
        // to ride in the row label (see ProxyBackend) rather than as dialog
        // body text here.
        new AlertDialog.Builder(context)
                .setTitle(Strings.proxyTitle())
                .setSingleChoiceItems(adapter, checkedRow, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ProxyRow row = rows.get(which);
                        if (row.isHeader()) {
                            return; // headers are disabled, but guard anyway
                        }
                        ProxyBackend chosen = row.backend;
                        dialog.dismiss();
                        if (chosen.needsCustomUrl()) {
                            showCustomUrlDialog(context);
                        } else {
                            ProxySettings.setSelected(context, chosen);
                            showRestartNoticeDialog(context, chosen);
                        }
                    }
                })
                .setNegativeButton(Strings.close(), null)
                .show();

        measureHealth(rows, adapter);
    }

    /**
     * Fixed, always-live reference channel used to health-check every proxy.
     * lofigirl streams 24/7, so a probe against it is a stable "does a
     * playlist actually come back through this proxy" signal regardless of
     * what (if anything) the user is currently watching. See {@link ProxyHealth}
     * for why a real playlist GET is used instead of a bare TCP ping.
     */
    static final String HEALTH_CHECK_CHANNEL = "lofigirl";

    /** Synthetic live usher URL for {@link #HEALTH_CHECK_CHANNEL}, the same shape Twitch itself builds. */
    static Uri referenceUsherUri() {
        return Uri.parse("https://usher.ttvnw.net/api/channel/hls/" + HEALTH_CHECK_CHANNEL
                + ".m3u8?allow_source=true&allow_audio_only=true&fast_bread=true&type=any&player=twitchweb");
    }

    /**
     * Kicks off a background health check for every selectable backend and
     * appends the outcome to that row's label as results trickle in. Each
     * probe builds the backend's own request for the reference channel (via
     * {@link ProxyBackend#buildUri}) and confirms a real {@code #EXTM3U}
     * manifest comes back -- a bare ping was misleading for both PerfProd
     * (Cloudflare answers even when the origin is dead/forbidden) and
     * ReYohoho (bare host 404s but the passthrough works); see
     * {@link ProxyHealth}. Runs after the dialog is already shown so the
     * picker never blocks on network I/O -- rows start out as "checking..." and
     * fill in over the next few seconds.
     */
    private static void measureHealth(final List<ProxyRow> rows, final BaseAdapter adapter) {
        final String savedCustomUrl = ProxySettings.getCustomUrl();
        final Uri referenceUsher = referenceUsherUri();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        for (ProxyRow row : rows) {
            if (row.isHeader() || row.backend == ProxyBackend.DISABLED) {
                continue;
            }
            final ProxyRow itemRow = row;
            final ProxyBackend backend = row.backend;
            final String customUrl = backend.needsCustomUrl() ? savedCustomUrl : null;
            final Uri probeUri = backend.buildUri(referenceUsher, HEALTH_CHECK_CHANNEL, false, customUrl);
            if (probeUri == null) {
                continue; // nothing to probe (e.g. custom URL not entered yet)
            }
            itemRow.statusSuffix = Strings.proxyChecking();
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    final ProxyHealth.Result result = ProxyHealth.check(probeUri, backend.httpProxy());
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            itemRow.statusSuffix = formatStatus(result);
                            adapter.notifyDataSetChanged();
                        }
                    });
                }
            });
        }
        executor.shutdown();
        adapter.notifyDataSetChanged(); // show the "checking..." placeholders right away
    }

    private static String formatStatus(ProxyHealth.Result result) {
        if (result == null) {
            return "";
        }
        if (result.ok) {
            return Strings.proxyWorks();
        }
        return result.httpCode > 0
                ? Strings.proxyHttpError(result.httpCode)
                : Strings.proxyNoResponse();
    }

    private static void showCustomUrlDialog(Context context) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        input.setHint(Strings.customUrlHint());
        input.setText(ProxySettings.getCustomUrl());
        int pad = dp(context, 20);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(context)
                .setTitle(Strings.customUrlTitle())
                .setMessage(Strings.customUrlMessage())
                .setView(input)
                .setPositiveButton(Strings.save(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = input.getText().toString().trim();
                        ProxySettings.setCustomUrl(context, url);
                        ProxySettings.setSelected(context, ProxyBackend.CUSTOM);
                        showRestartNoticeDialog(context, ProxyBackend.CUSTOM);
                    }
                })
                .setNegativeButton(Strings.cancel(), null)
                .show();
    }

    /**
     * The usher-URL builders (live and VOD alike) only run once per
     * watched stream/video (the master playlist Twitch resolves at the
     * start of a viewing session, not on every manifest refresh), so
     * flipping the proxy setting while something is already open won't
     * retroactively affect it -- it takes effect next time. Leaving/
     * re-entering the same channel or video is usually enough, but
     * Twitch may keep a short-lived session cache for it, so a full
     * restart is offered as a guaranteed-to-work fallback.
     */
    private static void showRestartNoticeDialog(Context context, ProxyBackend chosen) {
        showRestartNoticeDialog(
                context,
                Strings.proxyRestartTitle(chosen.label()),
                Strings.proxyRestartMessage());
    }

    private static void showRestartNoticeDialog(Context context, String title, String message) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(Strings.restartNow(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        restartApp(context);
                    }
                })
                .setNegativeButton(Strings.later(), null)
                .show();
    }

    static void restartApp(Context context) {
        try {
            Context appContext = context.getApplicationContext();
            Intent launchIntent = appContext.getPackageManager()
                    .getLaunchIntentForPackage(appContext.getPackageName());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                appContext.startActivity(launchIntent);
            }
        } finally {
            Runtime.getRuntime().exit(0);
        }
    }

    static void showChangelog(Context context) {
        TextView message = new TextView(context);
        int pad = dp(context, 20);
        message.setPadding(pad, pad, pad, pad);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        message.setText(Html.fromHtml(Changelog.html(), Html.FROM_HTML_MODE_LEGACY));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(message);

        new AlertDialog.Builder(context)
                .setTitle(Strings.changelogTitle())
                .setView(scroll)
                .setPositiveButton("OK", null)
                .show();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return Strings.bytesUnavailable();
        }
        if (bytes < 1024) {
            return bytes + " " + Strings.unitBytes();
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.US, "%.1f " + Strings.unitKilobytes(), kb);
        }
        return String.format(java.util.Locale.US, "%.1f " + Strings.unitMegabytes(), kb / 1024.0);
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
