package tv.retwitch.mod;

import java.util.Locale;

/**
 * Centralised, device-language-aware UI strings for the ReTwitch settings
 * menu and its related dialogs/toasts.
 *
 * <p>The mod started out Russian-only; now that non-Russian users have
 * picked it up, every user-facing string is resolved through here so it can
 * be shown in either Russian or English. The choice is made automatically
 * from the device locale ({@link Locale#getDefault()}): Russian for {@code
 * ru} devices, English for everyone else, unless the user picked the mod's
 * language on the "Language and translation" page ({@link TranslationFiles}).
 * Other languages are translations of the English text, see {@code kitchen/i18n.py};
 * strings the user loaded from a file go over the build's (any language but English).
 *
 * <p>Locale is read on every access (rather than cached in a {@code static
 * final}) so that a system language change is picked up without needing an
 * app restart, and so the value is correct regardless of how early this
 * class happens to be loaded.
 *
 * <p>Proper nouns and provider names (ReTwitch, ReYohoho, 7TV, Luminous,
 * PerfProd, "LIVE"/"VOD", ...) are intentionally left untranslated.
 */
public final class Strings {

    private Strings() {
    }

    /** The language the user picked for the mod, "" for the device's; null until read. */
    private static volatile String chosen;

    /** The mod's language: the one picked in its settings, or the device's. */
    static String language() {
        String own = chosen;
        if (own == null) {
            android.content.Context context = AppContext.get();
            if (context != null) { // too early otherwise: read on a later string
                own = chosen = TranslationFiles.chosenLanguage(context);
            }
        }
        if (own != null && !own.isEmpty()) {
            return own;
        }
        Locale locale = Locale.getDefault();
        return locale != null ? locale.getLanguage() : "en";
    }

    /** After the user picked a language or changed their strings: everything is read again on the next string. */
    static void languageChanged(String picked) {
        chosen = picked;
        loadedLanguage = null;
    }

    /** @return {@code true} when the mod is in Russian, {@code false} otherwise (-> English or a translation). */
    public static boolean isRussian() {
        return "ru".equals(language());
    }

    /** Set by the settings search while it builds the pages: shown text -> the same text in the other language. */
    static volatile java.util.Map<String, String> otherLanguage;

    /** Picks between the Russian and English variant based on the device language. */
    public static String pick(String ru, String en) {
        String shown = translate(ru, en);
        java.util.Map<String, String> other = otherLanguage;
        if (other != null && ru != null && en != null) {
            other.put(shown, shown == ru || shown != en ? en : ru);
        }
        return shown;
    }

    /** {@link #pick} of a {@code String.format} template; a translation with broken placeholders falls back to English. */
    public static String pickf(String ru, String en, Object... args) {
        try {
            return String.format(Locale.ROOT, pick(ru, en), args);
        } catch (java.util.IllegalFormatException e) {
            return String.format(Locale.ROOT, en, args);
        }
    }

    /** Translations other than Russian: {@code assets/retwitch-i18n.json}, {"lang": {"English text": "translation"}}. */
    private static final String I18N_ASSET = "retwitch-i18n.json";
    private static volatile String loadedLanguage;
    private static volatile java.util.Map<String, String> translations = java.util.Collections.emptyMap();

    /** The mod language's text: the user's own string, the build's translation (kitchen/i18n.py), or ru/en. */
    private static String translate(String ru, String en) {
        String language = language();
        String shown = "ru".equals(language) ? ru : en;
        if (en == null || "en".equals(language)) {
            return shown;
        }
        if (!language.equals(loadedLanguage)) {
            loadTranslations(language);
        }
        String translated = translations.get(en);
        return translated != null ? translated : shown;
    }

    private static synchronized void loadTranslations(String language) {
        if (language.equals(loadedLanguage)) {
            return;
        }
        android.content.Context context = AppContext.get();
        if (context == null) {
            return; // too early: retried on the next string
        }
        // Russian is in the code: only the user's own strings, no need to read the asset on every Russian start.
        java.util.Map<String, String> map = "ru".equals(language) ? new java.util.HashMap<>() : shipped(context, language);
        map.putAll(TranslationFiles.own(context, language));
        translations = map;
        loadedLanguage = language;
    }

    private static java.util.Map<String, String> shipped(android.content.Context context, String language) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        try (java.io.InputStream in = context.getAssets().open(I18N_ASSET)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                bytes.write(buf, 0, n);
            }
            org.json.JSONObject strings = new org.json.JSONObject(bytes.toString("UTF-8")).optJSONObject(language);
            if (strings != null) {
                for (java.util.Iterator<String> keys = strings.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    map.put(key, strings.getString(key));
                }
            }
        } catch (Exception e) {
            android.util.Log.w("ReTwitch", "translations not loaded: " + e);
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Main menu
    // ------------------------------------------------------------------

    public static String menuAbout() {
        return pick("О моде", "About the mod");
    }

    public static String menuTestCrash() {
        return pick("Тест падения", "Test crash");
    }

    // ---- settings backup ----

    public static String backupTitle() {
        return pick("Резервная копия", "Backup");
    }

    public static String backupSummary() {
        return pick("Сохранить, загрузить, сбросить", "Save, load, reset");
    }

    public static String backupExport() {
        return pick("Сохранить в файл", "Save to a file");
    }

    public static String backupImport() {
        return pick("Загрузить из файла", "Load from a file");
    }

    public static String chatProxyDown() {
        return pick("Чат-прокси ReYohoho сейчас недоступен - чат подключён напрямую. Повторная попытка через 10 минут.",
                "The ReYohoho chat proxy is unavailable right now: chat is connected directly. Retrying in 10 minutes.");
    }

    /** Every step ends in 0 or 5, so "секунд" fits them all. */
    public static String seekStep(boolean forward, int seconds) {
        return forward ? pickf("Вперед на %d секунд", "Forward %d seconds", seconds)
                : pickf("Назад на %d секунд", "Rewind %d seconds", seconds);
    }

    public static String downloadPause() {
        return pick("Пауза", "Pause");
    }

    public static String downloadResume() {
        return pick("Продолжить", "Resume");
    }

    public static String downloadTitle() {
        return pick("Скачивание", "Download");
    }

    public static String downloadNothing() {
        return pick("Нечего скачивать: плеер ещё не загрузил видео", "Nothing to download: the player hasn't loaded a video yet");
    }

    public static String downloadStarted() {
        return pick("Скачивание началось (Загрузки/ReTwitch)", "Download started (Downloads/ReTwitch)");
    }

    public static String downloadQuality() {
        return pick("Качество", "Quality");
    }

    public static String downloadVodQuality() {
        return pick("Качество (не закрывайте приложение до конца)", "Quality (keep the app open until done)");
    }

    public static String downloadSource() {
        return pick("Источник", "Source");
    }

    public static String downloadAudioOnly() {
        return pick("Только звук", "Audio only");
    }

    /** Three lines: why, "name: text", the Allow / Deny buttons ({@code AutoModChat.decorate}). */
    public static String autoModHeld(String name, String text, String why) {
        return pick("🛡 AutoMod задержал сообщение", "🛡 AutoMod held a message") + (why.isEmpty() ? "" : ": " + why)
                + "\n" + name + ": " + text + "\n" + autoModAllow() + "   •   " + autoModDeny();
    }

    public static String autoModBlockedTerm() {
        return pick("запрещённое слово канала", "the channel's blocked term");
    }

    public static String autoModResolved(String name, String status, String resolver) {
        String what;
        if ("ALLOWED".equals(status)) {
            what = pick("разрешено", "allowed");
        } else if ("DENIED".equals(status)) {
            what = pick("отклонено", "denied");
        } else if ("EXPIRED".equals(status)) {
            what = pick("истекло", "expired");
        } else {
            what = status;
        }
        return pick("🛡 AutoMod: сообщение от ", "🛡 AutoMod: the message from ") + name + " " + what
                + (resolver.isEmpty() ? "" : " (" + resolver + ")");
    }

    public static String autoModAllow() {
        return pick("Разрешить", "Allow");
    }

    public static String autoModDeny() {
        return pick("Отклонить", "Deny");
    }

    public static String autoModAllowed() {
        return pick("разрешено", "allowed");
    }

    public static String autoModDenied() {
        return pick("отклонено", "denied");
    }

    public static String vodCategory(String name) {
        return name != null ? pick("Категория: ", "Category: ") + name : pick("Все категории", "All categories");
    }

    public static String vodCategoryAll() {
        return pick("Все категории", "All categories");
    }

    public static String vodCategoryTitle() {
        return pick("Категория", "Category");
    }

    public static String vodCategorySearch() {
        return pick("Поиск по всем категориям Twitch", "Search all Twitch categories");
    }

    public static String downloadFailed() {
        return pick("Скачивание не удалось", "Download failed");
    }

    public static String rteTitle() {
        return pick("RTE", "RTE");
    }

    public static String rteSummary() {
        return pick("Подписка и друзья", "Subscription and friends");
    }

    public static String rteSubscriptionTitle() {
        return pick("Подписка RTE", "RTE subscription");
    }

    public static String rteSubscriptionActive(int tier, String until) {
        String details = (tier > 0 ? pickf(", уровень %d", ", tier %d", tier) : "")
                + (until.isEmpty() ? "" : pickf(", до %s", ", until %s", until));
        return pickf("Активна%s. Раскраска ника и значок RTE видны всем пользователям RTE и ReTwitch.",
                "Active%s. Your RTE nick paint and badge show to everyone using RTE or ReTwitch.", details);
    }

    public static String rteSubscriptionNone() {
        return pick("Подписки RTE нет. Оформить её (раскраска ника, значок и другое) можно на ext.rte.net.ru.",
                "No RTE subscription. It (nick paint, badge and more) is available on ext.rte.net.ru.");
    }

    public static String rteSite() {
        return pick("Открыть сайт", "Open the site");
    }

    public static String friendsTitle() {
        return pick("Друзья RTE", "RTE friends");
    }

    public static String friendsList() {
        return pick("Список друзей", "Friends list");
    }

    public static String friendsRequests(int count) {
        return pickf("заявок: %d", "%d requests", count);
    }

    public static String friendsManage() {
        return pick("Управлять", "Manage");
    }

    public static String friendsEmpty() {
        return pick("Друзей пока нет. Добавить их можно на ext.rte.net.ru/friends.",
                "No friends yet. Add them on ext.rte.net.ru/friends.");
    }

    public static String friendsLoadFailed() {
        return pick("Сервер RTE не ответил, попробуйте позже.", "The RTE server didn't answer, try again later.");
    }

    public static String friendsNeedLogin() {
        return pick("Нужно войти в аккаунт Twitch", "Sign in to Twitch first");
    }

    public static String friendsHint() {
        return pick("Друзей добавляют на ext.rte.net.ru/friends. Стрим, который вы смотрите, "
                        + "друзья видят через 5 минут после входа на канал. Нажмите на друга в эфире, чтобы открыть его канал.",
                "Friends are added on ext.rte.net.ru/friends. Friends see the stream you watch "
                        + "5 minutes after you open the channel. Tap an online friend to open their channel.");
    }

    public static String resetSettings() {
        return pick("Сбросить все настройки", "Reset all settings");
    }

    public static String resetSettingsConfirm() {
        return pick("Все настройки мода вернутся к значениям по умолчанию. Избранные эмоуты и смещения чата "
                        + "VOD останутся. Нужен перезапуск.",
                "Every mod setting goes back to its default. Favorite emotes and VOD chat offsets stay. "
                        + "A restart is needed.");
    }

    public static String resetSettingsYes() {
        return pick("Сбросить", "Reset");
    }

    public static String backupHint() {
        return pick("Настройки самого Twitch и вход в аккаунт не затрагиваются.",
                "Twitch's own settings and your login are not touched.");
    }

    public static String backupSaved() {
        return pick("Настройки сохранены в файл", "Settings saved to the file");
    }

    public static String backupRestored(int count) {
        return pickf("Настройки загружены (%d). Перезапустите приложение",
                "Settings loaded (%d). Restart the app", count);
    }

    public static String backupNotOurs() {
        return pick("Это не файл настроек ReTwitch", "This is not a ReTwitch settings file");
    }

    public static String backupReadFailed() {
        return pick("Не удалось прочитать файл", "Could not read the file");
    }

    public static String backupFailed() {
        return pick("Не удалось сохранить файл", "Could not save the file");
    }

    public static String backupNoPicker() {
        return pick("Нет приложения для выбора файла", "No file picker on this device");
    }

    // ------------------------------------------------------------------
    // Ad blocking
    // ------------------------------------------------------------------

    /** The label on the video during a VAFT break; {@code quality} like "360p", or null when ads are cut. */
    public static String vaftBlockingLabel(String quality) {
        String text = pick("Реклама блокируется", "Blocking ads");
        return quality == null ? text : text + " (" + quality + ")";
    }

    public static String vaftLogToast(boolean on, String file) {
        return on ? pick("Лог пишется в Загрузки/", "Logging to Downloads/") + file
                : pick("Лог сохранён: Загрузки/", "Log saved: Downloads/") + file;
    }

    public static String[] vaftPlayerTypeLabels() {
        return new String[]{
                pick("Как в Twitch (лучше всего)", "As in Twitch (best)"),
                "popout - Source",
                "embed - Source",
                "site - Source",
                pick("autoplay - 360p, почти без рекламы", "autoplay - 360p, nearly ad-free"),
        };
    }

    public static String testCrashTitle() {
        return pick("Тест краш-репортинга", "Test crash reporting");
    }

    public static String testCrashMessage() {
        return pick(
                "Приложение сейчас аварийно завершится, чтобы проверить отправку "
                        + "краш-отчёта. Отчёт уйдёт при следующем запуске. Продолжить?",
                "The app will crash now to verify crash reporting. The report is sent "
                        + "on the next launch. Continue?");
    }

    public static String testCrashConfirm() {
        return pick("Крашнуть", "Crash");
    }

    public static String on() {
        return pick("вкл", "on");
    }

    public static String off() {
        return pick("выкл", "off");
    }

    // ------------------------------------------------------------------
    // Common buttons
    // ------------------------------------------------------------------

    public static String save() {
        return pick("Сохранить", "Save");
    }

    public static String cancel() {
        return pick("Отмена", "Cancel");
    }

    public static String close() {
        return pick("Закрыть", "Close");
    }

    public static String reset() {
        return pick("Сбросить", "Reset");
    }

    public static String ok() {
        return "OK";
    }

    // ------------------------------------------------------------------
    // Cutout dialog
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Default landing tab dialog
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Chat dialog
    // ------------------------------------------------------------------

    public static String chatTitle() {
        return pick("Чат", "Chat");
    }

    public static String deletedModeLabel() {
        return pick("Удалённые сообщения", "Deleted messages");
    }

    /** Order of ChatSettings.DELETED_*. */
    public static String[] deletedModes() {
        return new String[]{
                pick("Как в Twitch: текст скрыт", "As in Twitch: text hidden"),
                pick("По нажатию, как у модераторов", "On tap, as for moderators"),
                pick("Текстом, как обычно", "As regular text"),
                pick("Зачёркнутыми", "Struck through"),
                pick("Серыми", "In grey"),
        };
    }

    public static String timestampDefault() {
        return pick("Как в Twitch", "Twitch default");
    }

    public static String smoothMessagesToggleLabel() {
        return pick("Анимация новых сообщений", "Animate new messages");
    }

    public static String smoothMessagesToggleSummary() {
        return pick("Плавное появление. На быстром чате даёт рывки", "A soft fade-in. Causes jerks in a fast chat");
    }

    /** Order of ChatSettings.MENTION_COLORS. */
    public static String[] mentionColorLabels() {
        return new String[]{
                pick("Красный", "Red"), pick("Фиолетовый", "Purple"),
                pick("Оранжевый", "Orange"), pick("Жёлтый", "Yellow"), pick("Зелёный", "Green"),
                pick("Синий", "Blue"),
        };
    }

    public static String vibrateMsLabel(int ms) {
        return pickf("%d мс", "%d ms", ms);
    }

    public static String highlightWordsLabel() {
        return pick("Свои слова и ники", "Your own words and nicks");
    }

    public static String highlightWordsHint() {
        return pick("Через запятую. Слово - подсветка сообщений с ним, @ник - всех сообщений этого зрителя.",
                "Comma separated. A word highlights messages containing it, @nick every message from that viewer.");
    }

    public static String noneLabel() {
        return pick("Нет", "None");
    }

    // ------------------------------------------------------------------
    // Player/chat divider (landscape side-by-side)
    // ------------------------------------------------------------------

    public static String dividerWidthResetToast() {
        return pick("Ширина чата сброшена", "Chat width reset");
    }

    public static String dividerHandleDesc() {
        return pick("Перетащите, чтобы изменить размер чата и плеера",
                "Drag to resize the chat and the player");
    }

    public static String dividerFailedToast() {
        return pick("ReTwitch: не удалось добавить полоску размера чата",
                "ReTwitch: failed to add the chat resize handle");
    }

    // ------------------------------------------------------------------
    // Sleep/exit timer
    // ------------------------------------------------------------------

    public static String timerDialogTitle() {
        return TimerSettings.isPauseMode() ? pick("Пауза через", "Pause in")
                : pick("Закрыть приложение через", "Close the app in");
    }

    public static String timerHoursSuffix() {
        return pick("ч.", "h.");
    }

    public static String timerMinutesSuffix() {
        return pick("мин.", "min.");
    }

    public static String timerStart() {
        return pick("Запустить", "Start");
    }

    public static String timerStop() {
        return pick("Остановить", "Stop");
    }

    public static String timerCantSetZero() {
        return pick("Нельзя задать 0", "Can't set 0");
    }

    public static String timerStartedToast(String remaining) {
        return pick("Таймер выключения: ", "Exit timer set: ") + remaining;
    }

    public static String timerCancelledToast() {
        return pick("Таймер выключения отменён", "Exit timer cancelled");
    }

    public static String timerRunningHint(String remaining) {
        return pick("Уже запущен, осталось: ", "Already running, remaining: ")
                + (remaining != null ? remaining : "");
    }

    public static String timerButtonTalkback() {
        return pick("Таймер выключения", "Sleep/exit timer");
    }

    public static String timerFailedToast() {
        return pick("ReTwitch: не удалось добавить кнопку таймера",
                "ReTwitch: failed to add the timer button");
    }

    public static String timerNotifyTitle() {
        return pick("Таймер выключения", "Exit timer");
    }

    public static String timerNotifyChannel() {
        return pick("Таймер выключения", "Exit timer");
    }

    public static String timerCancel() {
        return pick("Отменить", "Cancel");
    }

    // ------------------------------------------------------------------
    // Reload player (refresh stream/VOD)
    // ------------------------------------------------------------------

    public static String reloadButtonTalkback() {
        return pick("Перезагрузить плеер", "Reload player");
    }

    public static String reloadToast() {
        return pick("Перезагружаю плеер...", "Reloading player...");
    }

    public static String reloadUnavailableToast() {
        return pick("ReTwitch: перезагрузка плеера пока недоступна, открой трансляцию/VOD",
                "ReTwitch: player reload isn't ready yet, open a stream/VOD first");
    }

    public static String reloadFailedToast() {
        return pick("ReTwitch: не удалось добавить кнопку перезагрузки",
                "ReTwitch: failed to add the reload button");
    }

    // ------------------------------------------------------------------
    // Third-party emotes dialog
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Emote cache dialog
    // ------------------------------------------------------------------

    public static String cacheDiskUsage(String size) {
        return pick("Кэш изображений на диске: ", "Image cache on disk: ") + size;
    }

    public static String cacheResetToast() {
        return pick("Кэш эмоутов сброшен, обновляю...", "Emote cache cleared, refreshing...");
    }

    public static String cacheResetButtonDesc() {
        return pick("Сбросить кэш эмоутов этого канала", "Reset this channel's emote cache");
    }

    /** The viewer card's RTE friend button by friendship status. */
    public static String friendButton(String status) {
        switch (status) {
            case "accepted":
                return pick("Друзья RTE", "RTE friends");
            case "pending_sent":
                return pick("Заявка отправлена", "Request sent");
            case "pending_received":
                return pick("Принять в друзья", "Accept request");
            default:
                return pick("Добавить в друзья", "Add friend");
        }
    }

    public static String emotesRefresh() {
        return pick("Только обновить", "Just refresh");
    }

    public static String emotesRefreshToast() {
        return pick("Обновляю эмоуты 7TV, BTTV и FFZ...", "Refreshing 7TV, BTTV and FFZ emotes...");
    }

    public static String cacheResetTitle() {
        return pick("Сбросить кэш эмоутов", "Reset emote cache");
    }

    public static String cacheResetMessage() {
        return pick(
                "Эмоут добавили или удалили, а здесь он старый? «Только обновить» заново запросит наборы 7TV, BTTV и FFZ. "
                        + "«Сбросить» ещё и перекачает все картинки.",
                "An emote was added or removed but here it's the old one? \"Just refresh\" fetches the 7TV, BTTV and FFZ "
                        + "sets again. \"Reset\" also downloads every image again.");
    }

    // ------------------------------------------------------------------
    // Proxy picker
    // ------------------------------------------------------------------

    public static String proxyTitle() {
        return pick("Прокси-сервер (LIVE/VOD)", "Proxy server (LIVE/VOD)");
    }

    public static String proxyFailedToast() {
        return pick("Прокси не ответил - поток напрямую с Twitch", "Proxy failed -- playing straight from Twitch");
    }

    public static String proxyGroupReYohoho() {
        return pick("ReYohoho - 1080/1440",
                "ReYohoho - 1080/1440");
    }

    public static String proxyGroupAds() {
        return pick("Others", "Others");
    }

    public static String proxyGroupOther() {
        return pick("Другое", "Other");
    }

    public static String proxyChecking() {
        return pick(" - проверка...", " - checking...");
    }

    public static String proxyWorks() {
        return pick(" - ✓ работает", " - ✓ works");
    }

    public static String proxyHttpError(int code) {
        return " - ✗ HTTP " + code;
    }

    public static String proxyNoDrops() {
        return pick("Drops не засчитываются", "Drops don't count");
    }

    public static String proxyNoResponse() {
        return pick(" - ✗ не отвечает", " - ✗ no response");
    }

    // ------------------------------------------------------------------
    // Custom proxy URL dialog
    // ------------------------------------------------------------------

    public static String customUrlTitle() {
        return pick("Свой URL прокси", "Custom proxy URL");
    }

    public static String customUrlHint() {
        return pick("https://example.com/https://usher...  или  .../playlist/{channel}",
                "https://example.com/https://usher...  or  .../playlist/{channel}");
    }

    public static String customUrlMessage() {
        return pick(
                "Укажите шаблон адреса. \"{url}\" будет заменён на весь исходный "
                        + "usher-запрос, \"{channel}\" - на имя канала. Если нет ни одного "
                        + "плейсхолдера, исходный usher-запрос будет добавлен в конец пути "
                        + "(как для проксей ReYohoho).",
                "Enter a URL template. \"{url}\" is replaced with the whole original "
                        + "usher request, \"{channel}\" - with the channel name. If neither "
                        + "placeholder is present, the original usher request is appended to "
                        + "the end of the path (as with the ReYohoho proxies).");
    }

    // ------------------------------------------------------------------
    // Restart notice
    // ------------------------------------------------------------------

    public static String proxyRestartTitle(String backendLabel) {
        return pick("Прокси: ", "Proxy: ") + backendLabel;
    }

    public static String proxyRestartMessage() {
        return pick(
                "Настройка применится при следующем открытии трансляции или VOD. "
                        + "Если после выхода и повторного захода ничего не изменилось - "
                        + "перезапустите приложение.",
                "The setting applies the next time you open a stream or VOD. If nothing "
                        + "changes after leaving and re-entering - restart the app.");
    }

    public static String restartNow() {
        return pick("Перезапустить", "Restart");
    }

    public static String later() {
        return pick("Позже", "Later");
    }

    // ------------------------------------------------------------------
    // About dialog
    // ------------------------------------------------------------------

    public static String playerMenuItem() {
        return pick("Плеер", "Player");
    }

    public static String defaultQualityWifi() {
        return pick("Wi-Fi", "Wi-Fi");
    }

    public static String defaultQualityCellular() {
        return pick("Мобильная сеть", "Cellular");
    }

    public static String[] qualityLabels() {
        return new String[]{
                pick("Авто", "Auto"),
                pick("Максимум", "Highest"),
                "1080p", "720p", "480p", "360p", "160p",
        };
    }

    public static String chaptersTitle() {
        return pick("Главы", "Chapters");
    }

    public static String chaptersEmpty() {
        return pick("У этого VOD нет глав", "This VOD has no chapters");
    }

    public static String chaptersUnavailable() {
        return pick("Главы недоступны: откройте VOD заново", "Chapters unavailable: reopen the VOD");
    }

    public static String compressorOn() {
        return pick("Аудиокомпрессор включён", "Audio compressor on");
    }

    public static String compressorOff() {
        return pick("Аудиокомпрессор выключен", "Audio compressor off");
    }

    public static String compressorUnsupported() {
        return pick("Аудиокомпрессор требует Android 9+", "The audio compressor needs Android 9+");
    }

    public static String compressorTalkback() {
        return pick("Аудиокомпрессор", "Audio compressor");
    }

    public static String latencyLabel(long ms) {
        return pick("Задержка ", "Latency ") + latencyShort(ms);
    }

    /** The chat input hint: the side chat's field is narrow, so just the seconds. */
    public static String latencyShort(long ms) {
        String seconds = String.format(java.util.Locale.US, "%.1f", ms / 1000f);
        return seconds + pick(" с", " s");
    }

    public static String statsTitle() {
        return pick("Статистика плеера", "Player stats");
    }

    public static String statsNoPlayer() {
        return pick("Плеер ещё не запущен", "No player yet");
    }

    public static String statsQuality() {
        return pick("Качество  ", "Quality   ");
    }

    public static String statsVideo() {
        return pick("Видео     ", "Video     ");
    }

    public static String statsLatency() {
        return pick("Задержка  ", "Latency   ");
    }

    public static String statsBuffer() {
        return pick("Буфер     ", "Buffer    ");
    }

    public static String statsNetwork() {
        return pick("Сеть      ", "Network   ");
    }

    public static String statsFrames(long dropped, long decoded) {
        return pickf("Кадры     потеряно %d из %d",
                "Frames    %d dropped of %d", dropped, decoded);
    }

    public static String upNext() {
        return pick("Далее", "Up Next");
    }

    public static String[] seekStepLabels(int[] seconds) {
        String[] labels = new String[seconds.length];
        for (int i = 0; i < seconds.length; i++) {
            int s = seconds[i];
            labels[i] = s < 60 || s % 60 != 0 ? pickf("%d с", "%d s", s) : pickf("%d мин", "%d min", s / 60);
        }
        return labels;
    }

    public static String vodChatSyncTitle() {
        return pick("Смещение чата", "Chat offset");
    }

    public static String vodChatSyncUnavailable() {
        return pick("Смещение чата недоступно: откройте VOD заново", "Chat offset unavailable: reopen the VOD");
    }

    public static String vodChatSyncValue(int seconds) {
        String value = seconds > 0 ? "+" + seconds : String.valueOf(seconds);
        return pickf("%s с", "%s s", value);
    }

    public static String vodChatSyncHint() {
        return pick("Плюс - чат раньше, минус - позже. Своё значение для каждого VOD.",
                "Plus shows chat earlier, minus later. Saved per VOD.");
    }

    public static String audioBoostLabel() {
        return pick("Усиление громкости", "Volume boost");
    }

    public static String audioBassLabel() {
        return pick("Басы", "Bass");
    }

    public static String audioTrebleLabel() {
        return pick("Верхние частоты", "Treble");
    }

    public static String audioVoiceLabel() {
        return pick("Разборчивость голоса", "Voice clarity");
    }

    public static String audioLimitLabel() {
        return pick("Лимитер громких звуков", "Limiter for loud sounds");
    }

    /** Choice labels for an audio filter: "0" = off, else "+3 dB" / "50%". */
    public static String[] audioLevelLabels(int[] values, boolean percent) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            int v = values[i];
            labels[i] = v == 0 ? pick("Выкл.", "Off")
                    : percent ? v + "%" : (v > 0 ? "+" : "") + v + pick(" дБ", " dB");
        }
        return labels;
    }

    /** Order of ChatSettings.NAME_*. */
    public static String[] nameFormats() {
        return new String[]{
                pick("Ник и логин", "Nick and login"),
                pick("Только ник", "Nick only"),
                pick("Только логин", "Login only"),
        };
    }

    public static String uiMenuItem() {
        return pick("Интерфейс", "Interface");
    }

    // ---- settings screen ----

    public static String back() {
        return pick("Назад", "Back");
    }

    public static String settingsSearchHint() {
        return pick("Поиск настроек", "Search settings");
    }

    public static String settingsSearchEmpty() {
        return pick("Ничего не найдено", "Nothing found");
    }

    public static String restartBar() {
        return pick("Нужен перезапуск", "Restart needed");
    }

    public static String restartNote() {
        return pick("Применится после перезапуска приложения", "Applies after an app restart");
    }

    public static String updateTitle(String version) {
        return pick("Обновление ", "Update ") + version;
    }

    public static String updateNow() {
        return pick("Обновить", "Update");
    }

    public static String updateDownloading(String version) {
        return pick("Скачивание ", "Downloading ") + version;
    }

    public static String updateLatest() {
        return pick("У вас последняя версия", "You have the latest version");
    }

    public static String updateCheckFailed() {
        return pick("Не удалось проверить обновления", "Couldn't check for updates");
    }

    public static String updateFailed() {
        return pick("Обновление не удалось, попробуйте ещё раз", "The update failed, try again");
    }

    public static String updateAllowInstall() {
        return pick("Разрешите установку, и обновление продолжится", "Allow installing and the update will go on");
    }

    public static String previewBefore() {
        return pick("До", "Before");
    }

    public static String previewAfter() {
        return pick("После", "After");
    }

    public static String sectionMessages() {
        return pick("Сообщения", "Messages");
    }

    public static String sectionMentions() {
        return pick("Упоминания", "Mentions");
    }

    public static String sectionChatHeader() {
        return pick("Шапка чата", "Chat header");
    }

    public static String sectionChatInput() {
        return pick("Поле ввода чата", "Chat input");
    }

    /** Order matches {@code ChatHeaderSetting.CHOICES}. */
    public static String[] chatHeaderModes() {
        return new String[]{
                pick("Показывать", "Show"),
                pick("Скрывать в портрете", "Hide in portrait"),
                pick("Скрывать в полном экране", "Hide in fullscreen"),
                pick("Скрывать всегда", "Always hide"),
        };
    }

    public static String fixesTitle() {
        return pick("Исправления", "Fixes");
    }

    public static String fixesSummary() {
        return pick("Уже включены, трогать незачем", "Already on, no need to touch them");
    }

    public static String fixesHint() {
        return pick("Исправляют ошибки Twitch и мода. Выключайте, только если из-за исправления что-то сломалось.",
                "They fix Twitch and mod bugs. Turn one off only if it broke something.");
    }

    public static String diagnosticsTitle() {
        return pick("Диагностика", "Diagnostics");
    }

    public static String diagnosticsSummary() {
        return pick("Логи и проверки для отчётов об ошибках", "Logs and checks for bug reports");
    }

    public static String rteStaffBadgeDescription() {
        return pick("Значок команды ReYohoho.", "A member of the ReYohoho team.");
    }

    public static String rteBadgeDescription() {
        return pick("Значок ReYohoho: зритель пользуется расширением RTE (ReYohoho Twitch Extension) или ReTwitch. "
                        + "Значок и раскраску ника видят все, у кого стоит RTE или ReTwitch.",
                "ReYohoho badge: the viewer uses the RTE extension (ReYohoho Twitch Extension) or ReTwitch. "
                        + "The badge and nick paint show to everyone using RTE or ReTwitch.");
    }

    public static String sevenTvAdded(String emote, String who) {
        return pickf("7TV: %s добавил эмоут %s", "7TV: %s added the emote %s", who, emote);
    }

    public static String sevenTvRemoved(String emote, String who) {
        return pickf("7TV: %s удалил эмоут %s", "7TV: %s removed the emote %s", who, emote);
    }

    public static String sevenTvRenamed(String from, String to, String who) {
        return pickf("7TV: %s переименовал эмоут %s в %s",
                "7TV: %s renamed the emote %s to %s", who, from, to);
    }

    public static String loggedOutButton() {
        return pick("ReTwitch: продолжить без аккаунта", "ReTwitch: continue without an account");
    }

    public static String[] danmakuSpeeds() {
        return new String[]{pick("Медленно", "Slow"), pick("Обычно", "Normal"), pick("Быстро", "Fast"),
                pick("Очень быстро", "Very fast")};
    }

    public static String[] danmakuAreas() {
        return new String[]{pick("Верхняя четверть", "Top quarter"), pick("Верхняя половина", "Top half"),
                pick("Три четверти", "Three quarters"), pick("Всё видео", "Whole video")};
    }

    public static String[] danmakuSizes() {
        return new String[]{pick("Мелкий", "Small"), pick("Обычный", "Normal"), pick("Крупный", "Large"),
                pick("Очень крупный", "Huge")};
    }

    public static String userHistoryRow(int count) {
        return pickf("История сообщений в этом чате (%d)", "Message history in this chat (%d)", count);
    }

    public static String userHistoryEmpty() {
        return pick("С тех пор как открыт чат, зритель ничего не писал.",
                "The viewer hasn't written anything since the chat was opened.");
    }

    public static String sectionAppIcon() {
        return pick("Иконка приложения", "App icon");
    }

    public static String appIconLabel() {
        return pick("Иконка", "Icon");
    }

    public static String[] appIconModes() {
        return new String[]{
                pick("Звёзды", "Stars"),
                pick("Как в Twitch", "As in Twitch"),
                pick("Тёмная", "Dark"),
                pick("Чёрная", "Black"),
        };
    }

    public static String experimentsTitle() {
        return pick("Эксперименты Twitch", "Twitch experiments");
    }

    public static String experimentsSummary() {
        return pick("Флаги функций Twitch: включить то, что ещё не раскатили", "Twitch feature flags: turn on what isn't rolled out yet");
    }

    public static String experimentsHint() {
        return pick("Флаги Eppo, которыми Twitch включает функции и тесты. Изменения применяются после перезапуска. "
                        + "Многие флаги без поддержки сервера ничего не дадут, а некоторые (вход, оплата) могут сломать приложение - "
                        + "тогда сбросьте всё. «Сейчас» - значение, прочитанное нативным кодом в этой сессии; флаги React Native его не показывают.",
                "Eppo flags Twitch turns features and tests on with. Changes apply after a restart. "
                        + "Many flags do nothing without server support, and some (login, payments) can break the app; "
                        + "reset everything then. \"Now\" is the value native code read this session; React Native flags don't show it.");
    }

    public static String experimentsFilter() {
        return pick("Фильтр", "Filter");
    }

    public static String experimentsFilterNone() {
        return pick("Все флаги", "All flags");
    }

    public static String experimentsReset(int count) {
        return pickf("Сбросить все изменения (%d)", "Reset all changes (%d)", count);
    }

    public static String experimentsResetHint() {
        return pick("Все флаги вернутся к значениям Twitch, остальные настройки мода останутся",
                "All flags go back to Twitch's values, the other mod settings stay");
    }

    public static String experimentsChanged() {
        return pick("Изменено", "Changed");
    }

    public static String experimentsAll(int count) {
        return pickf("Все флаги (%d)", "All flags (%d)", count);
    }

    public static String experimentsNow() {
        return pick("сейчас", "now");
    }

    public static String experimentsOverride() {
        return pick("мод", "mod");
    }

    public static String experimentsAsTwitch() {
        return pick("Как в Twitch", "As in Twitch");
    }

    public static String experimentsCustom() {
        return pick("Своё значение...", "Custom value...");
    }

    public static String firstMessageLabel() {
        return "FIRST MESSAGE"; // RTE shows it in English in every locale
    }

    public static String linkPreviewModeTitle() {
        return pick("Что показывать", "What to show");
    }

    public static String chatFiltersMenuItem() {
        return pick("Фильтры", "Filters");
    }

    public static String blacklistWordsLabel() {
        return pick("Слова", "Words");
    }

    public static String blacklistWordsHint() {
        return pick("Сообщения с этими словами или фразами не показываются. По одной на строку, регистр не важен.",
                "Messages with these words or phrases are hidden. One per line, any case.");
    }

    public static String blacklistUsersLabel() {
        return pick("Зрители", "Viewers");
    }

    public static String blacklistUsersHint() {
        return pick("Сообщения этих пользователей не показываются. Один логин на строку.",
                "Messages from these users are hidden. One login per line.");
    }

    public static String blacklistCount(int count) {
        return count == 0 ? pick("Пусто", "Empty") : pick("Записей: ", "Entries: ") + count;
    }

    public static String[] pinnedMessagesModes() {
        return new String[]{pick("Показывать", "Show"), pick("Скрывать", "Hide"),
                pick("Скрывать через 30 секунд", "Hide after 30 seconds")};
    }

    public static String chatClearedNotice() {
        return pick("Модератор очистил чат - ReTwitch оставил сообщения",
                "A moderator cleared chat - ReTwitch kept the messages");
    }

    public static String raidArrivedNotice(String raider, int viewers) {
        return pickf("%s рейдит канал: зрителей - %d",
                "%s is raiding with a party of %d", raider, viewers);
    }

    public static String linkPreviewMenuItem() {
        return pick("Превью ссылок", "Link preview");
    }

    public static String[] linkPreviewModes() {
        return new String[]{
                pick("Только ссылка", "Link only"),
                pick("Скрывать ссылки на картинки и видео", "Hide links to images and videos"),
                pick("Только превью", "Preview only"),
                pick("Превью и ссылка", "Preview and link"),
        };
    }

    public static String[] emoteQualityModes() {
        return new String[]{"1x", "2x", "3x", "4x"};
    }

    public static String[] pickerBackspaceModes() {
        return new String[]{pick("Как в Twitch", "As in Twitch"), pick("Слово или эмоут целиком", "A whole word or emote"),
                pick("По одной букве", "One character")};
    }

    public static String linkPreviewMaxHeight() {
        return pick("Наибольшая высота, dp", "Max height, dp");
    }

    public static String linkPreviewMaxWidth() {
        return pick("Наибольшая ширина, dp", "Max width, dp");
    }

    public static String linkPreviewBlur() {
        return pick("Размывать до нажатия", "Blur until tapped");
    }

    public static String linkPreviewZoom() {
        return pick("Увеличивать по нажатию", "Enlarge on tap");
    }

    public static String pushRequestPermission() {
        return pick("Разрешить уведомления", "Allow notifications");
    }

    public static String pushMenuItem() {
        return pick("Push-уведомления (FCM)", "Push notifications (FCM)");
    }

    public static String changelogTitle() {
        return pick("История изменений", "Changelog");
    }

    // ------------------------------------------------------------------
    // Byte formatting
    // ------------------------------------------------------------------

    public static String bytesUnavailable() {
        return pick("недоступно", "unavailable");
    }

    public static String unitBytes() {
        return pick("Б", "B");
    }

    public static String unitKilobytes() {
        return pick("КБ", "KB");
    }

    public static String unitMegabytes() {
        return pick("МБ", "MB");
    }

    // ------------------------------------------------------------------
    // Proxy backend labels
    // ------------------------------------------------------------------

    public static String proxyDisabled() {
        return pick("Отключено", "Disabled");
    }

    public static String proxyCustom() {
        return pick("Свой URL...", "Custom URL...");
    }

    // ------------------------------------------------------------------
    // Emote proxy fallback
    // ------------------------------------------------------------------

    public static String proxyUnavailableToast() {
        return pick("ReYohoho прокси недоступен, пробуем напрямую",
                "ReYohoho proxy unavailable, trying directly");
    }
}
