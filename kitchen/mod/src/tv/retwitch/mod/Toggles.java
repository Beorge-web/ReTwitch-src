package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Simple on/off feature switches shown as checkbox groups in the ReTwitch
 * menu (the settings screen). Switches default to off, except bug fixes
 * ({@code defaultOn}). Read from injected hooks via {@link #on}, which never throws.
 */
public final class Toggles {

    private static final String PREFS_NAME = "retwitch_toggles";

    /** One switch: preference key, Russian/English label, restart needed. */
    public static final class Toggle {
        final String key;
        final String ru;
        final String en;
        final boolean restart;
        /** Bug fixes default on; features off. */
        final boolean defaultOn;
        /** Added in the current {@link BuildInfo#VERSION_NAME}: shown with a NEW badge. */
        boolean isNew;
        /** {@link SettingsPreview} scene and flag showing what the switch changes; 0 if there's nothing to draw. */
        int scene;
        long focus;
        private String aboutRu;
        private String aboutEn;
        private String noteRu;
        private String noteEn;
        private String beforeRu;
        private String beforeEn;
        private String afterRu;
        private String afterEn;

        Toggle(String key, String ru, String en, boolean restart) {
            this(key, ru, en, restart, false);
        }

        Toggle(String key, String ru, String en, boolean restart, boolean defaultOn) {
            this.key = key;
            this.ru = ru;
            this.en = en;
            this.restart = restart;
            this.defaultOn = defaultOn;
        }

        /** Version the switch was added in; NEW only while that is the running version. */
        Toggle asNew(String version) {
            isNew = BuildInfo.VERSION_NAME.equals(version);
            return this;
        }

        /** One line under the title: what the switch does, in plain words. */
        Toggle about(String ru, String en) {
            aboutRu = ru;
            aboutEn = en;
            return this;
        }

        Toggle look(int scene, long focus) {
            this.scene = scene;
            this.focus = focus;
            return this;
        }

        /** What changes for the viewer, for switches with nothing to draw. */
        Toggle compare(String beforeRu, String afterRu, String beforeEn, String afterEn) {
            this.beforeRu = beforeRu;
            this.afterRu = afterRu;
            this.beforeEn = beforeEn;
            this.afterEn = afterEn;
            return this;
        }

        /** A longer explanation, unfolded on demand. */
        Toggle note(String ru, String en) {
            noteRu = ru;
            noteEn = en;
            return this;
        }

        String label() {
            return Strings.pick(ru, en);
        }

        String about() {
            return aboutRu == null ? null : Strings.pick(aboutRu, aboutEn);
        }

        String note() {
            return noteRu == null ? null : Strings.pick(noteRu, noteEn);
        }

        String before() {
            return beforeRu == null ? null : Strings.pick(beforeRu, beforeEn);
        }

        String after() {
            return afterRu == null ? null : Strings.pick(afterRu, afterEn);
        }
    }

    public static final Toggle HIDE_NATIVE_GIFS = new Toggle("hide_native_gifs", "GIF-сообщения", "GIF messages", false)
            .about("Вместо анимации только подпись",
                    "Just the caption instead of the animation")
            .look(SettingsPreview.CHAT, SettingsPreview.C_GIFS);

    public static final Toggle HIDE_ANNOUNCEMENTS = new Toggle("hide_announcements", "Объявления", "Announcements", false)
            .about("Цветные сообщения стримера и модераторов",
                    "Coloured messages from the streamer and mods")
            .look(SettingsPreview.CHAT, SettingsPreview.C_ANNOUNCE);
    public static final Toggle HIDE_SUB_NOTICES = new Toggle("hide_sub_notices", "Подписки и подарки", "Subs and gifts", false)
            .about("Сообщения о новых и подаренных подписках",
                    "Notices about new and gifted subs")
            .look(SettingsPreview.CHAT, SettingsPreview.C_SUBS);
    public static final Toggle HIDE_REDEMPTIONS = new Toggle("hide_redemptions", "Награды за баллы", "Point redemptions", false)
            .about("Сообщения о наградах за баллы канала",
                    "Notices about channel point rewards")
            .look(SettingsPreview.CHAT, SettingsPreview.C_REDEEM);
    public static final Toggle HIDE_MILESTONES = new Toggle("hide_milestones", "Серии просмотров", "Watch streaks", false)
            .about("Сообщения «смотрю N стримов подряд»",
                    "\"Watched N streams in a row\" notices")
            .look(SettingsPreview.CHAT, SettingsPreview.C_STREAK);
    public static final Toggle HIDE_SERVICE_MESSAGES = new Toggle("hide_service_messages", "Служебные сообщения", "Service messages", false)
            .about("Подключение, приветствие, смена режима чата",
                    "Connecting, welcome, chat mode changes")
            .look(SettingsPreview.CHAT, SettingsPreview.C_SERVICE);

    public static final Toggle NO_MANIFEST_CACHE = new Toggle("no_manifest_cache", "Всегда свежий поток", "Always a fresh stream", false, true)
            .about("Перезапущенный стрим играет сразу",
                    "A restarted stream plays right away")
            .compare("После перезапуска стрима плеер до 5 минут цепляется за старую трансляцию и ничего не показывает",
                    "Плеер сразу запрашивает новую трансляцию",
                    "After a stream restarts, the player clings to the old broadcast for up to 5 minutes and shows nothing",
                    "The player asks for the new broadcast right away");

    /** Bug fix: RN preview images were cached by their never-changing URL forever (RnLivePreviewsPatch). */
    /** Bug fix: with a large font the home tab row scrolls, and Twitch's underline kept the unscrolled place (retwitch-rn.js). */
    public static final Toggle FEED_TAB_INDICATOR = new Toggle("feed_tab_indicator", "Полоска под вкладками главной", "Home tab underline", true, true)
            .about("«Отслеживаемое», «В эфире», «Клипы» с крупным шрифтом",
                    "Following, Live and Clips with a large font")
            .compare("Полоска уезжает вправо от выбранной вкладки",
                    "Полоска под выбранной вкладкой",
                    "The underline sits to the right of the selected tab",
                    "The underline sits under the selected tab")
            .note("Если прокрутить строку вкладок пальцем, полоска встанет на место после смены вкладки.",
                    "If the tab row is scrolled by hand, the underline catches up on the next tab change.")
            .asNew("0.5.14-beta");

    public static final Toggle FRESH_RN_PREVIEWS = new Toggle("fresh_rn_previews", "Свежие превью трансляций", "Fresh stream previews", false, true)
            .about("Превью в «Отслеживаемом» обновляются раз в 2 минуты",
                    "Previews in Following refresh every 2 minutes")
            .compare("Карточка стрима показывает старый кадр, пока не перезапустишь приложение",
                    "Кадр обновляется сам и при оттяжке списка вниз",
                    "A stream card shows an old frame until the app restarts",
                    "The frame refreshes on its own and on pull to refresh");
    /** Bug fix: the RN Following tab asks for followed categories without a limit (RnFollowingHook.withAllCategories). */
    public static final Toggle ALL_FOLLOWED_CATEGORIES = new Toggle("all_followed_categories", "Все отслеживаемые категории", "All followed categories", true, true)
            .about("В «Отслеживаемом» полный список, а не первые 10",
                    "Following shows the full list, not just the first 10")
            .compare("10 категорий, на которые вы подписались раньше всего",
                    "Все категории, до 100",
                    "The 10 categories you followed first",
                    "All categories, up to 100")
            .asNew("0.5.3-beta");

    public static final Toggle AUTO_RELOAD = new Toggle("auto_reload", "Перезапуск при зависании", "Restart when stuck", false)
            .about("Если стрим слишком долго грузится; сколько ждать - ниже",
                    "If a stream buffers for too long; how long to wait is below")
            .compare("Стрим висит на загрузке, пока не перезайдёшь",
                    "Плеер перезагружается сам",
                    "A stream hangs on loading until you reopen it",
                    "The player reloads by itself");
    public static final Toggle NORMAL_LATENCY = new Toggle("normal_latency", "Обычная задержка", "Normal latency", false)
            .about("Стабильнее на слабой сети, но стрим на несколько секунд позже",
                    "Steadier on a weak connection, a few seconds later")
            .compare("Стрим почти вровень с чатом, на слабой сети бывает буферизация",
                    "Реже буферизует, но всё происходит на несколько секунд позже",
                    "Stream nearly level with chat, may buffer on a weak connection",
                    "Buffers less, but everything happens a few seconds later")
            .note("Эта настройка сильнее исправления «Низкая задержка без рекламы»: с ней стримы всегда идут с обычной задержкой.",
                    "This overrides the \"Low latency with ad blocking\" fix: with it, streams always play at normal latency.");
    public static final Toggle DANMAKU = new Toggle("danmaku", "Данмаку", "Danmaku", false)
            .about("Сообщения чата летят поверх видео в полном экране",
                    "Chat messages fly over the video in fullscreen").asNew("0.4.18-alpha");
    public static final Toggle DANMAKU_NICKS = new Toggle("danmaku_nicks", "Показывать ники", "Show nicks", false, true)
            .about("Ник цветом зрителя перед сообщением",
                    "The nick in the viewer's colour before the message").asNew("0.4.18-alpha");
    public static final Toggle WATCH_LOGGED_OUT = new Toggle("watch_logged_out", "Без аккаунта", "No account", true)
            .about("Смотреть, не входя в Twitch. До 1080p, функции аккаунта недоступны",
                    "Watch without signing in. Up to 1080p, no account features")
            .compare("При запуске только экран входа",
                    "На экране входа кнопка «продолжить без аккаунта»",
                    "Only the login screen at launch",
                    "The login screen has a \"continue without an account\" button").asNew("0.4.18-alpha");
    public static final Toggle BAN_BYPASS = new Toggle("ban_bypass", "Если вас забанили на канале", "If you're banned on a channel", false)
            .about("Показывает чат и запускает стрим без привязки к аккаунту",
                    "Shows the chat and starts the stream not tied to your account")
            .compare("Плашка закрывает чат, а если канал запретил просмотр, стрим не играет",
                    "Чат виден под плашкой, стрим идёт анонимно",
                    "A notice covers the chat, and the stream won't play if the channel forbids it",
                    "The chat shows under the notice, the stream plays anonymously").asNew("0.4.18-alpha");
    public static final Toggle NO_AUTOPLAY = new Toggle("no_autoplay", "Без автозапуска следующего", "No autoplay", false)
            .about("Не запускать следующую запись или клип после обратного отсчёта",
                    "Don't start the next VOD or clip after the countdown")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_UP_NEXT);
    public static final Toggle VOD_CATEGORY_FILTER = new Toggle("vod_category_filter", "Фильтр по категории", "Category filter", false)
            .about("Кнопка над видео на странице канала",
                    "A button above a channel's videos")
            .look(SettingsPreview.VODS, SettingsPreview.V_FILTER).asNew("0.4.2-alpha");
    public static final Toggle VOD_ABSOLUTE_DATES = new Toggle("vod_absolute_dates", "Точные даты", "Exact dates", false)
            .about("Дата и время вместо «1 нед. назад»",
                    "Date and time instead of \"1 week ago\"")
            .look(SettingsPreview.VODS, SettingsPreview.V_DATES).asNew("0.4.2-alpha");
    public static final Toggle PLAYER_GESTURES = new Toggle("player_gestures", "Яркость и громкость", "Brightness and volume", false)
            .about("Свайп у левого и правого края в полном экране",
                    "Swipe along the left and right edges in fullscreen")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_GESTURES);
    public static final Toggle PLAYER_SWIPE_FULLSCREEN = new Toggle("player_swipe_fullscreen", "Свайп в полный экран", "Swipe to fullscreen", false)
            .about("Вверх по плееру - развернуть, вниз по центру - выйти",
                    "Up on the player to enter, down in the middle to leave")
            .compare("В полный экран только кнопкой или поворотом телефона",
                    "Свайп вверх по плееру разворачивает его, вниз по центру возвращает обратно",
                    "Fullscreen takes the button or turning the phone",
                    "A swipe up on the player goes fullscreen, a swipe down in the middle comes back")
            .asNew("0.4.18-alpha");
    public static final Toggle PLAYER_GESTURES_VERTICAL = new Toggle("player_gestures_vertical", "Жесты в «Вертикальном виде»", "Gestures in Vertical view", false)
            .about("Те же жесты на вертикальных стримах",
                    "The same on portrait streams")
            .compare("На вертикальном стриме свайпы ничего не делают",
                    "Яркость и громкость по краям, свайп вниз по центру сворачивает плеер",
                    "Swipes do nothing on a portrait stream",
                    "Brightness and volume at the edges, a swipe down in the middle minimizes the player")
            .asNew("0.3.9-alpha");

    public static final Toggle COMPRESSOR_BUTTON = new Toggle("compressor_button", "Кнопка компрессора", "Compressor button", false)
            .about("Выравнивает тихое и громкое, включается прямо в плеере. Android 9+",
                    "Evens out quiet and loud, toggled right in the player. Android 9+")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_COMPRESSOR)
            .note("Долгое нажатие на кнопку открывает панель звука: компрессор и фильтры меняются прямо во время стрима",
                    "A long press on the button opens the audio panel: the compressor and the filters change while the stream plays");
    public static final Toggle AUDIO_FILTERS = new Toggle("audio_filters", "Фильтры звука", "Audio filters", false)
            .about("Усиление, басы, верхние частоты, голос, лимитер. Android 9+",
                    "Boost, bass, treble, voice, limiter. Android 9+").asNew("0.4.6-alpha");

    /** Player group in the menu. */
    public static final Toggle AMBILIGHT = new Toggle("ambilight", "Ambilight", "Ambilight", false)
            .about("Поля вокруг видео светятся его цветами, в плеере появляется кнопка",
                    "The bars around the video glow with its colours, with a button in the player")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_AMBILIGHT);

    public static final Toggle CHAPTERS_BUTTON = new Toggle("chapters_button", "Главы", "Chapters", false)
            .about("Список категорий записи с переходом к нужной",
                    "The VOD's categories, jump to any of them")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_CHAPTERS);
    public static final Toggle DOWNLOAD_BUTTON = new Toggle("download_button", "Скачивание", "Download", false)
            .about("Видео и клипы в папку Загрузки/ReTwitch",
                    "VODs and clips to the Downloads/ReTwitch folder")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_DOWNLOAD).asNew("0.4.0-alpha");
    /** Live recording (LiveRecord, RecordService, RecordSheet). */
    public static final Toggle LIVE_RECORD = new Toggle("live_record", "Запись трансляций", "Stream recording", false)
            .about("Кнопка записи в плеере live: файл в Загрузки/ReTwitch, можно с начала эфира",
                    "A record button in the live player: a file in Downloads/ReTwitch, even from the stream's start")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_RECORD)
            .note("Запись идёт в фоне, пока висит уведомление, и при выключенном экране. Если у канала включены записи "
                            + "трансляций, мод пишет их архив: он отстаёт от эфира на секунды, рекламы в нём нет, качество "
                            + "исходное, а при обрыве сети запись потом догоняет. Без архива пишется сам эфир: реклама "
                            + "вырезается, на её месте и на время без сети будет пропуск. Если запись прервётся, файл всё "
                            + "равно откроется, а в «Записи» её можно продолжить. Файл .ts, его играют VLC, MX Player и "
                            + "плеер мода. Час 1080p - около 2,7 ГБ.",
                    "Recording goes on in the background while its notification is up, and with the screen off. "
                            + "If the channel keeps VODs of its streams, the mod records that archive: seconds behind the "
                            + "stream, with no ads, in the source quality, and after a network drop it catches up. "
                            + "Without one it records the stream itself: ads are cut out, leaving a gap, as does time "
                            + "without network. A recording cut short still opens, and it can go on from Recordings. The "
                            + "file is .ts, VLC, MX Player and the mod's player play it. An hour of 1080p is about 2.7 GB.")
            .asNew("0.5.14-beta");
    public static final Toggle LIVE_RECORD_LABEL = new Toggle("live_record_label", "Метка REC на видео",
            "REC label on the video", false, true)
            .about("Время записи в углу видео, пока кнопки плеера скрыты",
                    "The recording's time in the video's corner while the player's buttons are hidden")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_REC_LABEL).asNew("0.5.14-beta");
    public static final Toggle LIVE_RECORD_ARCHIVE = new Toggle("live_record_archive", "Писать из архива канала",
            "Record from the channel's archive", false, true)
            .about("Без рекламы, в исходном качестве и с начала эфира, если у канала есть записи",
                    "No ads, source quality and from the stream's start, when the channel keeps VODs")
            .compare("Пишется сам эфир: реклама вырезана с пропуском, из РФ часто до 720p",
                    "Архив трансляции: без рекламы, исходное качество, отстаёт от эфира на секунды",
                    "The stream itself: ads cut out with a gap, from Russia often 720p at most",
                    "The stream's archive: no ads, source quality, seconds behind the stream")
            .note("Звук в архиве тот же, что в записи канала на Twitch. Если стример отдаёт в архив отдельную дорожку без "
                            + "музыки (Twitch VOD Track в OBS) или Twitch заглушил кусок за авторские права, в файле будет так же. "
                            + "Нужен звук как в эфире - выключите: запишется сам эфир",
                    "The archive has the same sound as the channel's recording on Twitch. If the streamer sends the archive a "
                            + "separate track without music (Twitch VOD Track in OBS) or Twitch muted a part for copyright, the file "
                            + "has it too. For the sound of the stream itself, turn this off: the stream itself is recorded")
            .asNew("0.5.14-beta");
    /** The chat along with a recording or a VOD download (ChatTape), for RecordPlayer. */
    public static final Toggle LIVE_RECORD_CHAT = new Toggle("live_record_chat", "Записывать чат", "Record the chat",
            false)
            .about("Чат сохраняется вместе с записью и скачанным видео, плеер мода показывает его как в Twitch",
                    "The chat is saved with a recording and a downloaded video, the mod's player shows it as Twitch does")
            .note("У архива канала и VOD это чат повтора Twitch, тот же, что под VOD: он качается вместе с видео. У эфира "
                            + "без архива мод сам подключается к чату канала на время записи. Большой чат - около 4 МБ "
                            + "на час. Смотреть: Записи > запись > «Смотреть с чатом».",
                    "For the channel's archive and a VOD it is Twitch's chat replay, the one under the VOD: it downloads "
                            + "along with the video. For a stream without an archive the mod joins the channel's chat "
                            + "for the recording. A busy chat is about 4 MB an hour. To watch: Recordings > a recording > "
                            + "\"Watch with the chat\".")
            .asNew("0.5.15-beta");
    public static final Toggle VOD_CHAT_SYNC = new Toggle("vod_chat_sync", "Смещение чата", "Chat offset", false)
            .about("Сдвигает чат записи относительно видео, своё для каждой",
                    "Shifts a VOD's chat against the video, per VOD")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_CHAT_SYNC);
    /** Sub-only VODs without a sub, after TwitchNoSub (SubVodsPatch, ads/SubVods). */
    public static final Toggle SUB_VODS = new Toggle("sub_vods", "Записи для подписчиков", "Sub-only VODs", false)
            .about("Смотреть их без подписки",
                    "Watch them without a sub")
            .look(SettingsPreview.VODS, SettingsPreview.V_SUB).asNew("0.3.8-alpha");

    public static final Toggle MIRROR_BUTTON = new Toggle("mirror_button", "Отражение видео", "Mirror video", false)
            .about("Зеркалит картинку по горизонтали",
                    "Flips the picture horizontally")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_MIRROR);

    public static final Toggle LATENCY_LABEL = new Toggle("latency_label", "Задержка на плеере", "Latency on the player", false)
            .about("Показывается вместе с кнопками",
                    "Shown with the controls")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_LATENCY);
    /** Feedback idea #82. */
    public static final Toggle BATTERY_LABEL = new Toggle("battery_label", "Заряд в полном экране", "Battery in full screen", false)
            .about("Процент батареи на плеере, вместе с кнопками",
                    "The battery percentage on the player, shown with the controls")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_BATTERY)
            .note("Только в горизонтальном режиме: в вертикальном он и так в строке состояния. ⚡ - идёт зарядка.",
                    "Landscape only: in portrait the status bar shows it anyway. ⚡ means it's charging.")
            .asNew("0.5.27-beta");
    public static final Toggle SHOW_CHATTERS_COUNT = new Toggle("show_chatters_count", "Зрители в чате", "Viewers in chat", false)
            .about("В скобках после числа зрителей",
                    "In brackets after the viewer count")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_CHATTERS)
            .note("Число из Twitch, обновляется раз в минуту. Если Twitch его не отдаёт, скобок нет.",
                    "The number comes from Twitch and updates once a minute. If Twitch doesn't give it, there are no brackets.")
            .asNew("0.5.2-beta");
    public static final Toggle LATENCY_HINT = new Toggle("latency_hint", "Задержка в поле ввода", "Latency in the chat input", false)
            .about("Вместо «Отправить сообщение»",
                    "Instead of \"Send a message\"")
            .look(SettingsPreview.INPUT, SettingsPreview.I_LATENCY);
    /** StreamSwitch: ⏮ ⏭ beside the live pause; the mode is PlayerSettings.getStreamArrowsMode. */
    public static final Toggle STREAM_ARROWS = new Toggle("stream_arrows", "Предыдущий и следующий стрим", "Previous and next stream", false)
            .about("Кнопки ⏮ ⏭ у паузы в плеере эфира, как на YouTube",
                    "⏮ ⏭ beside the pause in the live player, as on YouTube")
            .note("«Недавние»: каналы, которые вы открывали, назад и вперёд по истории; кто сейчас не в эфире, пропускается. "
                            + "«Отслеживаемые»: каналы в эфире по кругу, в порядке «Отслеживаемого».",
                    "Recent: the channels you opened, back and forward through the history; anyone offline now is skipped. "
                            + "Followed: the live channels in a loop, in Following's order.")
            .asNew("0.5.18-beta");

    public static final Toggle STATS_BUTTON = new Toggle("stats_button", "Статистика", "Stats", false)
            .about("Качество, битрейт, fps, задержка, буфер, сеть",
                    "Quality, bitrate, fps, latency, buffer, network")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_STATS);

    public static final Toggle HIDE_HYPE_TRAIN = new Toggle("hide_hype_train", "Hype Train", "Hype Train", false)
            .look(SettingsPreview.BANNERS, SettingsPreview.B_HYPE);
    public static final Toggle HIDE_PREDICTIONS = new Toggle("hide_predictions", "Прогнозы и опросы", "Predictions and polls", false)
            .look(SettingsPreview.BANNERS, SettingsPreview.B_POLL);
    public static final Toggle HIDE_RAID_BANNER = new Toggle("hide_raid_banner", "Рейды", "Raids", false)
            .look(SettingsPreview.BANNERS, SettingsPreview.B_RAID);
    public static final Toggle HIDE_SHOUTOUT = new Toggle("hide_shoutout", "Shoutout", "Shoutouts", false)
            .about("Рекомендации других каналов",
                    "Recommendations of other channels")
            .look(SettingsPreview.BANNERS, SettingsPreview.B_SHOUTOUT);
    public static final Toggle HIDE_DROPS_BANNER = new Toggle("hide_drops_banner", "Drops", "Drops", false)
            .look(SettingsPreview.BANNERS, SettingsPreview.B_DROPS);
    public static final Toggle HIDE_GIFT_BANNERS = new Toggle("hide_gift_banners", "Подарочные подписки и акции", "Gift subs and promotions", false)
            .look(SettingsPreview.BANNERS, SettingsPreview.B_GIFTS);
    public static final Toggle HIDE_SHARED_CHAT = new Toggle("hide_shared_chat", "Shared Chat и Stream Together", "Shared Chat and Stream Together", false)
            .look(SettingsPreview.BANNERS, SettingsPreview.B_SHARED);
    public static final Toggle HIDE_PINNED_CHEERS = new Toggle("hide_pinned_cheers", "Платные закрепы", "Paid pins", false)
            .about("Серая полоса над сообщениями, даже пустая",
                    "The grey strip above the messages, even when empty")
            .look(SettingsPreview.BANNERS, SettingsPreview.B_PAID_PIN);

    public static final Toggle HIDE_PLAYER_CAST = new Toggle("hide_player_cast", "Cast", "Cast", false)
            .about("Вывод на телевизор",
                    "Send to a TV")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_CAST);
    public static final Toggle HIDE_PLAYER_SHARE = new Toggle("hide_player_share", "Поделиться", "Share", false)
            .look(SettingsPreview.PLAYER, SettingsPreview.P_SHARE);
    public static final Toggle HIDE_PLAYER_CLIP = new Toggle("hide_player_clip", "Клип", "Clip", false)
            .about("Создание клипа",
                    "Create a clip")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_CLIP);
    /** Bottom bar of the landscape player: Full screen and Show chat (chat mode). */
    /** "Vertical view" is Compose: the row of buttons isn't a view, {@code VerticalOverlayPatch} skips it. */
    public static final Toggle HIDE_VERTICAL_BUTTONS = new Toggle("hide_vertical_buttons", "Кнопки сверху", "Buttons on top", false)
            .about("Аватар, отслеживание, подписка, рейтинг",
                    "Avatar, follow, subscribe, leaderboard")
            .look(SettingsPreview.VERTICAL, SettingsPreview.W_BUTTONS)
            .note("Применится при следующем открытии стрима", "Applies the next time a stream opens")
            .asNew("0.5.2-beta");
    public static final Toggle HIDE_VERTICAL_PANEL = new Toggle("hide_vertical_panel", "Панель снизу", "Bottom panel", false)
            .about("Поле ввода, кнопки, Drops и закреп. Сообщения остаются",
                    "The input, buttons, Drops and the pin. Messages stay")
            .look(SettingsPreview.VERTICAL, SettingsPreview.W_PANEL)
            .note("Применится при следующем открытии стрима. Писать в чат нельзя, опросов и прогнозов не будет. Свёрнутый чат возвращается кнопками плеера.",
                    "Applies the next time a stream opens. No writing in chat, no polls or predictions. A folded chat comes back from the player buttons.")
            .asNew("0.5.2-beta");
    public static final Toggle HIDE_PLAYER_FULLSCREEN = new Toggle("hide_player_fullscreen", "На весь экран и режим чата", "Full screen and chat mode", false)
            .about("Кнопки внизу плеера",
                    "The buttons at the bottom of the player")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_FULLSCREEN).asNew("0.3.2-alpha");
    /** Feedback idea #76. Squashed, not GONE: the mod's button strip anchors to these and follows their visibility. */
    public static final Toggle HIDE_PLAYER_ROTATE = new Toggle("hide_player_rotate", "Поворот экрана", "Rotate", false)
            .about("Кнопка полного экрана в углу плеера",
                    "The full screen button in the player's corner")
            .note("Свайп по видео на весь экран и выход «Назад» работают и без неё",
                    "The full screen swipe on the video and Back still work without it")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_ROTATE).asNew("0.5.27-beta");
    public static final Toggle HIDE_PLAYER_MUTE = new Toggle("hide_player_mute", "Звук", "Sound", false)
            .about("Кнопка выключения звука внизу плеера",
                    "The mute button at the bottom of the player")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_MUTE).asNew("0.5.27-beta");
    /** Player metadata, theatre overlay and One Chat; the chat header strip is ChatHeaderSetting. */
    public static final Toggle HIDE_FOLLOW_SUB = new Toggle("hide_follow_sub", "Отслеживать и Подписаться", "Follow and Subscribe", false)
            .look(SettingsPreview.PLAYER, SettingsPreview.P_FOLLOW);
    public static final Toggle HIDE_UNFOLLOW = new Toggle("hide_unfollow", "Перестать отслеживать", "Unfollow", false)
            .about("В плеере и на странице канала",
                    "In the player and on the channel page")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_UNFOLLOW);
    /** The channel page's action row (UiHider.applyUnfollow); Unfollow is HIDE_UNFOLLOW. */
    public static final Toggle HIDE_PROFILE_FOLLOW = new Toggle("hide_profile_follow", "Отслеживать", "Follow", false)
            .about("Кнопка на странице канала, пока вы его не отслеживаете", "The channel page's button while you don't follow it")
            .compare("Под описанием канала кнопки «Отслеживать» и «Подписаться»", "Без «Отслеживать», «Подписаться» на всю ширину",
                    "Under the channel's description: Follow and Subscribe", "No Follow, Subscribe takes the full width")
            .asNew("0.5.22-beta");
    public static final Toggle HIDE_PROFILE_SUBSCRIBE = new Toggle("hide_profile_subscribe", "Подписаться", "Subscribe", false)
            .about("Кнопка на странице канала", "The channel page's button")
            .compare("Под описанием канала кнопка «Подписаться»", "Кнопки нет",
                    "Under the channel's description: Subscribe", "No button")
            .asNew("0.5.22-beta");
    public static final Toggle HIDE_PROFILE_GIFT = new Toggle("hide_profile_gift", "Сделать подарок", "Gift A Sub", false)
            .about("Кнопка на странице канала, если вы уже подписаны", "The channel page's button once you're subscribed")
            .compare("Под описанием канала кнопка «Сделать подарок»", "Кнопки нет",
                    "Under the channel's description: Gift A Sub", "No button")
            .asNew("0.5.22-beta");
    /** Shelves of the channel page's Home tab (React Native, RnScript). */
    public static final Toggle HIDE_PROFILE_RECAPS = new Toggle("hide_profile_recaps", "Записи эфиров", "Stream recaps", true)
            .about("Полка последних записей трансляций на главной вкладке канала", "The latest stream recaps shelf on the channel's Home tab")
            .compare("Под вкладками канала - карточки записей прошлых эфиров", "Полки нет, ниже идут остальные",
                    "Under the channel's tabs: cards of past stream recaps", "No shelf, the rest moves up")
            .asNew("0.5.27-beta");
    public static final Toggle HIDE_PROFILE_FEATURED_CLIPS = new Toggle("hide_profile_featured_clips", "Избранные клипы", "Featured clips", true)
            .about("Полка избранных клипов на главной вкладке канала", "The featured clips shelf on the channel's Home tab")
            .compare("Фиолетовая карточка «Избранные клипы» и клипы рядом", "Полки нет",
                    "A purple Featured Clips card with clips next to it", "No shelf")
            .note("Клипы, сделанные вами на своём канале, остаются", "Clips you made on your own channel stay")
            .asNew("0.5.27-beta");
    public static final Toggle HIDE_PROFILE_CATEGORIES = new Toggle("hide_profile_categories", "Эфиры по категориям", "Streams by category", true)
            .about("Сетка категорий, в которых стримит канал", "The grid of categories the channel streams in")
            .compare("Обложки категорий канала сеткой", "Сетки нет",
                    "The channel's category covers in a grid", "No grid")
            .asNew("0.5.27-beta");
    public static final Toggle HIDE_PROFILE_STREAMERS = new Toggle("hide_profile_streamers", "Советует стримеров", "Suggested streamers", true)
            .about("Список стримеров, которых советует канал", "The list of streamers the channel suggests")
            .compare("Аватарки стримеров со зрителями или «Не в сети»", "Списка нет",
                    "Streamer avatars with viewers or Offline", "No list")
            .note("У каналов из команды на этом месте другие каналы команды, они тоже скрываются",
                    "A team's channel shows its team's other channels there, hidden as well")
            .asNew("0.5.27-beta");
    /** Presses Twitch's own "Start watching"; never an age verification. */
    public static final Toggle HIDE_MATURE_WARNING = new Toggle("hide_mature_warning", "Предупреждение 18+", "Mature content warning", false)
            .about("Стрим запускается сразу. Проверку возраста не обходит",
                    "The stream starts right away. Doesn't skip age verification")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_MATURE);
    public static final Toggle HIDE_BITS_BUTTON = new Toggle("hide_bits_button", "Кнопка Bits", "Bits button", false)
            .look(SettingsPreview.INPUT, SettingsPreview.I_BITS);
    public static final Toggle HIDE_CHAT_SETTINGS_BUTTON = new Toggle("hide_chat_settings_button", "Кнопка настроек чата (⋮)", "Chat settings button (⋮)", false)
            .look(SettingsPreview.INPUT, SettingsPreview.I_SETTINGS);
    public static final Toggle HIDE_EXTENSIONS = new Toggle("hide_extensions", "Расширения канала", "Channel extensions", false)
            .about("Значок в шапке и строка под ней",
                    "The header icon and the row under it")
            .look(SettingsPreview.HEADER, SettingsPreview.T_EXTENSIONS);
    public static final Toggle HIDE_LEADERBOARDS = new Toggle("hide_leaderboards", "Таблицы лидеров", "Leaderboards", false)
            .about("Значок в шапке и строка с топом",
                    "The header icon and the top supporters row")
            .look(SettingsPreview.HEADER, SettingsPreview.T_LEADERBOARD);

    public static final Toggle HIDE_TAB_ACTIVITY = new Toggle("hide_tab_activity", "Вкладка «Активность»", "Activity tab", false)
            .look(SettingsPreview.HOME, SettingsPreview.H_ACTIVITY);
    public static final Toggle HIDE_TAB_BROWSE = new Toggle("hide_tab_browse", "Вкладка «Просмотр»", "Browse tab", false)
            .look(SettingsPreview.HOME, SettingsPreview.H_BROWSE);
    public static final Toggle HIDE_TAB_CREATE = new Toggle("hide_tab_create", "Кнопка «Создать» (+)", "Create (+) button", false)
            .look(SettingsPreview.HOME, SettingsPreview.H_CREATE);
    public static final Toggle COMPACT_LIVE_TAB = new Toggle("compact_live_tab", "Компактный список", "Compact list", true)
            .about("Превью слева, ник и название справа. Категории остаются",
                    "Preview on the left, nick and title on the right. Categories stay")
            .look(SettingsPreview.HOME, SettingsPreview.H_COMPACT).asNew("0.5.1-beta");
    public static final Toggle STREAM_UPTIME = new Toggle("stream_uptime", "Сколько идёт эфир", "Stream uptime", true, false)
            .about("Время с начала трансляции в углу превью",
                    "Time since the stream started in the preview's corner")
            .compare("На превью только число зрителей",
                    "В правом верхнем углу превью - «2:15», сколько уже идёт эфир",
                    "A preview shows only the viewer count",
                    "The preview's top right corner shows \"2:15\", how long the stream has been on")
            .note("На превью главной, «Отслеживаемого», «В эфире» и других экранов на React Native; нативные экраны Twitch не покрыты. Время начала - анонимный запрос к Twitch раз в 10 минут на канал",
                    "On the previews of Home, Following, Live and other React Native screens; Twitch's native screens aren't covered. The start time is an anonymous Twitch request once per 10 minutes per channel")
            .asNew("0.5.22-beta");
    public static final Toggle HIDE_LIVE_FOLLOWED_SHELF = new Toggle("hide_live_followed_shelf", "Полка «From channels you follow»", "\"From channels you follow\" shelf", true)
            .look(SettingsPreview.HOME, SettingsPreview.H_SHELF_FOLLOWED).asNew("0.5.1-beta");
    public static final Toggle HIDE_LIVE_CLIPS_SHELF = new Toggle("hide_live_clips_shelf", "Полка «Clips for you»", "\"Clips for you\" shelf", true)
            .look(SettingsPreview.HOME, SettingsPreview.H_SHELF_CLIPS).asNew("0.5.1-beta");
    public static final Toggle FRIENDS_HOME = new Toggle("friends_home", "Друзья RTE", "RTE friends", true, false)
            .about("Кто из друзей смотрит, под разделом «В эфире»",
                    "Which friends are watching, under the Live section")
            .look(SettingsPreview.HOME, SettingsPreview.H_FRIENDS)
            .note("Первые 5 онлайн, остальные по кнопке. Тап открывает канал, который смотрит друг. Друзей добавляют на ext.rte.net.ru/friends.",
                    "The first 5 online, the rest behind a button. A tap opens the channel the friend watches. Friends are added on ext.rte.net.ru/friends.")
            .asNew("0.5.2-beta");
    /** retwitch-rn.js; the list is FavoriteChannels. */
    public static final Toggle FAVORITE_CHANNELS = new Toggle("favorite_channels", "Избранные каналы", "Favorite channels", true)
            .about("Свой раздел над «Каналами в эфире»", "A section of their own above Live channels")
            .look(SettingsPreview.HOME, SettingsPreview.H_FAVORITES)
            .note("Добавить или убрать канал - долгое нажатие на него в «Отслеживаемом» или ⋮ на странице канала. Кто не в эфире, показан "
                            + "бледным кружком, кто в эфире без подписки - кружком с красной рамкой; нажатие открывает канал.",
                    "To add or remove a channel, long-press it in Following, or use ⋮ on its channel page. Channels that are offline show as "
                            + "a faded circle, live ones you don't follow as a circle with a red ring; a tap opens the channel.")
            .asNew("0.5.22-beta");
    public static final Toggle FOLLOWING_STREAK_TAG = new Toggle("following_streak_tag", "Значок серии просмотров", "Watch streak tag", true)
            .about("На каналах, где серия ещё не продлена",
                    "On channels where the streak isn't extended yet")
            .look(SettingsPreview.HOME, SettingsPreview.H_STREAK).asNew("0.4.11-alpha");
    /** RnFollowingHook.strip: the server sends them oldest follow first. */
    public static final Toggle SORT_FOLLOWED_CATEGORIES = new Toggle("sort_followed_categories", "Категории по алфавиту", "Categories A to Z", true)
            .about("Отслеживаемые категории в «Отслеживаемом»", "Followed categories in Following")
            .compare("В порядке подписки, сначала самые старые", "По названию, от А до Я",
                    "In follow order, oldest first", "By name, from A to Z")
            .asNew("0.5.7-beta");
    /** UiHider.drawerSortKey (DrawerSortPatch): the drawer's own preference key. */
    public static final Toggle SEPARATE_DRAWER_SORT = new Toggle("separate_drawer_sort", "Своя сортировка у левой колонки",
            "Own sort for the left column", true)
            .about("Планшет: отслеживаемые каналы слева и список справа сортируются отдельно",
                    "Tablet: the followed channels on the left and the list on the right sort separately")
            .compare("Смена сортировки слева меняет и список справа", "Слева, например, «Рекомендовано», справа «По зрителям»",
                    "Changing the sort on the left changes the list on the right too",
                    "Say, Recommended on the left and By viewers on the right")
            .asNew("0.5.22-beta");
    public static final Toggle HIDE_FOLLOWING_CATEGORIES = new Toggle("hide_following_categories", "Категории", "Categories", true)
            .look(SettingsPreview.HOME, SettingsPreview.H_CATEGORIES).asNew("0.4.11-alpha");
    public static final Toggle HIDE_FOLLOWING_CONTINUE = new Toggle("hide_following_continue", "Продолжить просмотр", "Continue watching", true)
            .look(SettingsPreview.HOME, SettingsPreview.H_CONTINUE).asNew("0.4.11-alpha");
    public static final Toggle HIDE_FOLLOWING_OFFLINE = new Toggle("hide_following_offline", "Каналы не в эфире", "Offline channels", true)
            .look(SettingsPreview.HOME, SettingsPreview.H_OFFLINE).asNew("0.4.11-alpha");
    /** The "stories will appear here" card, with the empty shelf only; RnScript. */
    public static final Toggle HIDE_STORIES_EMPTY = new Toggle("hide_stories_empty", "Табличка об историях", "Stories placeholder", true)
            .about("Пока у каналов нет историй. Сами истории остаются",
                    "While channels have no stories. Stories themselves stay")
            .compare("«Здесь будут появляться истории авторов, которых вы отслеживаете!» над вкладками",
                    "Таблички нет, истории появятся на её месте, как только будут",
                    "\"Stories from creators you follow will appear here!\" above the tabs",
                    "No card, stories take its place once there are any")
            .asNew("0.5.4-beta");
    /** FeedTopChrome's StoriesShelf and CollapsedStoriesIcons; RnScript. */
    public static final Toggle HIDE_HOME_STORIES = new Toggle("hide_home_stories", "Истории", "Stories", true)
            .about("Полка историй над вкладками и их аватарки в строке вкладок",
                    "The stories shelf above the tabs and their avatars in the tab row")
            .compare("Оттяжка ленты вниз раскрывает полку историй", "Историй нет, оттяжка сразу обновляет ленту",
                    "Pulling the feed down opens the stories shelf", "No stories, a pull refreshes the feed right away")
            .asNew("0.5.18-beta");
    /** FeedTopChrome's FeedTabsRow (and the launch cover's placeholder tabs); RnScript. */
    public static final Toggle HIDE_FEED_TABS = new Toggle("hide_feed_tabs", "Вкладки главной", "Home tabs", true)
            .about("Строка «Отслеживаемое · В эфире · Клипы» над лентой", "The Following · Live · Clips row above the feed")
            .compare("Вкладки над лентой", "Ленты листаются свайпом вбок, начинается с вкладки из настройки «Открывать «Отслеживаемое»»",
                    "Tabs above the feed", "Feeds switch with a sideways swipe, starting from the tab set by Open Following")
            .asNew("0.5.18-beta");

    public static final Toggle HIDE_PROMOTIONS = new Toggle("hide_promotions", "Предложения Turbo и скидки", "Turbo offers and discounts", false)
            .about("Реклама Turbo и баннеры скидок на подписку",
                    "Turbo ads and subscription discount banners")
            .look(SettingsPreview.HOME, SettingsPreview.H_PROMO);
    /** UiHider: social_gift_subs_banner_compose (Twitch 32.0.0), in the player metadata and the profile card. */
    public static final Toggle HIDE_SOCIAL_GIFT_SUBS = new Toggle("hide_social_gift_subs", "Баннер подарочных подписок", "Gift subs banner", false)
            .about("«Tap Follow!» под плеером и на странице канала",
                    "\"Tap Follow!\" under the player and on the channel page")
            .compare("Под кнопкой «Отслеживать» баннер: подпишитесь на канал и получите подарочную подписку",
                    "Баннера нет, кнопка «Отслеживать» на месте",
                    "Under the Follow button a banner: follow the channel to get a gifted sub",
                    "No banner, the Follow button stays")
            .asNew("0.5.22-beta");

    /** Header row elements (UiHider); a row left with nothing visible hides the whole header. */
    public static final Toggle HIDE_CHAT_TITLE = new Toggle("hide_chat_header_title", "Заголовок «Чат»", "The \"Chat\" title", false)
            .look(SettingsPreview.HEADER, SettingsPreview.T_TITLE).asNew("0.3.5-alpha");
    /** One Compose view: Subscribe and Gift a Sub go together (Gift alone is HIDE_GIFT_BUTTON). */
    public static final Toggle HIDE_HEADER_BUTTONS = new Toggle("hide_chat_header_buttons", "Подписаться и Подарок", "Subscribe and Gift", false)
            .look(SettingsPreview.HEADER, SettingsPreview.T_BUTTONS)
            .asNew("0.3.5-alpha");
    /** Gate in the header button builder (PromoHook.giftButton); Subscribe stays. Was a ChatHeaderSetting mode. */
    public static final Toggle HIDE_GIFT_BUTTON = new Toggle("chat_header_no_gift", "Только Подарок", "Only Gift", false)
            .look(SettingsPreview.HEADER, SettingsPreview.T_GIFT)
            .asNew("0.3.3-alpha");
    /** Bug fix: Twitch centres each badge on the font's box, smaller ones end higher (EmoteChatHook.imageTop). */
    public static final Toggle BADGES_ON_BASELINE = new Toggle("badges_on_baseline",
            "Значки на одной линии", "Badges on one line", false, true)
            .about("Для новых сообщений", "For new messages")
            .compare("Значки разной высоты стоят вразнобой", "Низ у всех значков на одной линии, ник по центру",
                    "Badges of different heights sit at different levels", "All badges end on one line, the name stays centred")
            .asNew("0.5.7-beta");
    public static final Toggle UPDATE_CHECK = new Toggle("update_check",
            "Проверять обновления", "Check for updates", false, true)
            .about("При запуске. Перед установкой мод спросит, а после «Позже» не будет напоминать сутки",
                    "At launch. The mod asks before installing, and after Later it stays quiet for a day")
            .asNew("0.5.1-beta");
    public static final Toggle HIDE_LANDSCAPE_CHAT_INPUT = new Toggle("hide_landscape_chat_input", "Поле ввода в полном экране", "Input in fullscreen", false)
            .look(SettingsPreview.INPUT, SettingsPreview.I_FULLSCREEN);
    /** The input of the chat laid over the video in fullscreen ("One Chat"). */
    public static final Toggle HIDE_ONE_CHAT_INPUT = new Toggle("hide_one_chat_input", "Поле ввода в чате поверх видео", "Input in the chat over the video", false)
            .look(SettingsPreview.INPUT, SettingsPreview.I_OVERLAY);
    /** Landscape snackbar "Changed to No Chat / Column Chat / Chat Overlay" on every chat mode switch. */
    public static final Toggle HIDE_CHAT_MODE_POPUP = new Toggle("hide_chat_mode_popup", "Подсказка о режиме чата", "Chat mode popup", false)
            .about("«Режим чата: колонка» при смене режима в полном экране",
                    "\"Chat mode: column\" when switching in fullscreen")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_MODE_POPUP);
    /** The points button beside the chat input, per orientation (UiHider); auto-claim works off the state, not the button. */
    public static final Toggle HIDE_POINTS_PORTRAIT = new Toggle("hide_points_portrait", "Баллы в портрете", "Points in portrait", false)
            .about("Кнопка баллов канала у поля ввода",
                    "The channel points button by the input")
            .look(SettingsPreview.INPUT, SettingsPreview.I_POINTS_PORTRAIT).asNew("0.5.4-beta");
    public static final Toggle HIDE_POINTS_LANDSCAPE = new Toggle("hide_points_landscape", "Баллы в полном экране", "Points in fullscreen", false)
            .about("Кнопка баллов канала у поля ввода",
                    "The channel points button by the input")
            .look(SettingsPreview.INPUT, SettingsPreview.I_POINTS_LANDSCAPE).asNew("0.5.4-beta");
    /** Twitch shows only the points icon in landscape; the balance stays hidden only while typing (UiHider). */
    public static final Toggle POINTS_BALANCE_LANDSCAPE = new Toggle("points_balance_landscape", "Баланс баллов в полном экране", "Points balance in fullscreen", false)
            .about("Число рядом со значком баллов",
                    "The number next to the points icon")
            .look(SettingsPreview.INPUT, SettingsPreview.I_BALANCE).asNew("0.5.2-beta");
    /** The fullscreen balance under the icon instead of beside it (UiHider.stackPoints). */
    public static final Toggle POINTS_BALANCE_UNDER_ICON = new Toggle("points_balance_under_icon", "Баланс под значком", "Balance under the icon", false)
            .about("Поле ввода станет шире",
                    "Leaves the input more room")
            .look(SettingsPreview.INPUT, SettingsPreview.I_BALANCE_UNDER).asNew("0.5.3-beta");
    /** Side chat: the emote button leaves its place to the field until the chat widens for typing (UiHider). */
    public static final Toggle EMOTE_BUTTON_TYPING_ONLY = new Toggle("emote_button_typing_only",
            "Смайлик только при наборе", "Emote button only while typing", false)
            .about("Кнопка появляется, когда вы пишете: открыта клавиатура или в поле есть текст. Поле ввода получает её место",
                    "The button shows while you write: the keyboard is up or the field has text. The input gets its room")
            .note("В портрете и в боковом чате; в боковом - вместе с исправлением «Поле ввода над клавиатурой». До 0.5.21 работало только в боковом чате",
                    "In portrait and in the side chat; in the side chat together with the Input above the keyboard fix. Before 0.5.21 it worked only in the side chat")
            .look(SettingsPreview.INPUT, SettingsPreview.I_EMOTE_IDLE).asNew("0.5.1-beta");
    /** Bug fix: in landscape the keyboard covered the chat input, and the field was ~23 px wide. */
    public static final Toggle LANDSCAPE_INPUT_VISIBLE = new Toggle("landscape_input_visible", "Поле ввода над клавиатурой", "Input above the keyboard", false, true)
            .about("На время набора в полном экране, и шире обычного",
                    "While typing in fullscreen, and wider than usual")
            .look(SettingsPreview.INPUT, SettingsPreview.I_RAISED);

    /** Bug fix: Twitch keeps the chat input focused after the keyboard closes, its cursor blinks on. */
    public static final Toggle IDLE_INPUT_CURSOR = new Toggle("idle_input_cursor", "Без мигающего курсора", "No blinking cursor", false, true)
            .about("После закрытия клавиатуры. Android 11+",
                    "After the keyboard closes. Android 11+")
            .look(SettingsPreview.INPUT, SettingsPreview.I_CURSOR).asNew("0.3.2-alpha");

    /** Bug fix: 31.9.0 defers the one global chat badge load on a cold start until a launch trace the RN home may never end. */
    public static final Toggle GLOBAL_BADGES_NOW = new Toggle("global_badges_now", "Значки сразу после запуска", "Badges right after launch", true, true)
            .about("VIP, модератор, стример, Prime",
                    "VIP, moderator, broadcaster, Prime")
            .compare("После холодного старта в чате нет общих значков, только сабовые",
                    "Значки на месте с первого сообщения",
                    "After a cold start chat has no global badges, only sub ones",
                    "Badges are there from the first message")
            .asNew("0.3.3-alpha");

    /** Bug fix: the layout picker's labels (Classic split, Vertical view) take the taps meant for pause (VerticalOverlayPatch). */
    public static final Toggle LAYOUT_PICKER_ICONS = new Toggle("layout_picker_icons",
            "Пауза под кнопками макета", "Pause under the layout buttons", false, true)
            .about("Стримы с вертикальным форматом", "Streams with a vertical format")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_LAYOUT)
            .asNew("0.5.10-beta");
    /** VOD chat emote ranges from UTF-8 bytes to chars (VodChatEmotes). */
    public static final Toggle VOD_CHAT_EMOTES = new Toggle("vod_chat_emotes",
            "Эмоуты Twitch в чате VOD", "Twitch emotes in VOD chat", false, true)
            .about("Эмоут после русского текста или эмодзи оставался словом",
                    "An emote after Cyrillic text or an emoji stayed a word")
            .compare("«Привет Kappa» - Kappa словом", "Kappa картинкой, как в браузере",
                    "\"Привет Kappa\": Kappa as a word", "Kappa as a picture, as in the browser")
            .note("Сервер Twitch даёт место эмоута в байтах, а приложение считает символы. Ошибка есть и в Twitch без мода.",
                    "Twitch's server gives an emote's place in bytes, the app counts characters. The stock app has the bug too.")
            .asNew("0.5.17-beta");

    /** Landscape side chat at half the screen while a poll or prediction is open in it (UiHider). */
    public static final Toggle WIDE_CHAT_PANELS = new Toggle("wide_chat_panels", "Широкий чат для опросов", "Wide chat for polls", false)
            .about("Боковой чат расширяется, пока открыт опрос или прогноз",
                    "The side chat widens while a poll or prediction is open")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_WIDE_PANEL);
    /** Landscape side chat as a column over a full-width video, background opacity from TheatreSettings (TheatreDividerHook). */
    public static final Toggle CHAT_OVER_VIDEO = new Toggle("chat_over_video", "Чат поверх видео", "Chat over the video", false)
            .about("Видео на всю ширину, боковой чат лежит на нём полупрозрачной колонкой",
                    "The video takes the full width, the side chat lies on it as a see-through column")
            .compare("Видео сжато слева, справа колонка чата на чёрном", "Видео на весь экран, чат справа поверх него",
                    "The video is squeezed to the left, the chat column on black", "The video fills the screen, the chat on top of it on the right")
            .note("Режим «Чат рядом». Нажатие на полоску между видео и чатом открывает этот переключатель и прозрачность прямо в плеере. Пока видны кнопки плеера, они поверх чата. Во время рекламы «картинка в картинке» и в записях - обычная колонка.",
                    "The Chat next to video mode. A tap on the strip between the video and the chat opens this switch and the opacity right in the player. While the player buttons show, they're above the chat. During picture-by-picture ads and in VODs it's the usual column.")
            .asNew("0.5.17-beta");

    /**
     * Bug fix: IVS only treats a master URL containing "://usher.ttvnw.net" as
     * a Twitch channel (low latency, prefetch); VAFT's local URL lost that.
     */
    public static final Toggle VAFT_LOW_LATENCY = new Toggle("vaft_low_latency", "Низкая задержка без рекламы", "Low latency with ad blocking", false, true)
            .about("Выключите, если стримы перестали запускаться",
                    "Turn it off if streams stop starting")
            .compare("С блокировкой рекламы стрим идёт на несколько секунд позже",
                    "Задержка такая же, как без блокировки",
                    "With ad blocking the stream runs a few seconds late",
                    "Latency is the same as without ad blocking");

    /** A label on the video while VAFT replaces or cuts an ad break (ads/AdBreakLabel). */
    public static final Toggle VAFT_BLOCKING_LABEL = new Toggle("vaft_blocking_label", "Плашка «Реклама блокируется»", "\"Blocking ads\" label", false)
            .about("Надпись на видео, пока идёт подмена",
                    "A label on the video during an ad break")
            .look(SettingsPreview.PLAYER, SettingsPreview.P_AD_LABEL);

    /** Diagnostics: the detailed VAFT log file (ads/VaftLog), for freeze reports. */
    public static final Toggle VAFT_LOG = new Toggle("vaft_log", "Лог блокировки рекламы", "Ad blocking log", false)
            .about("Для отчёта о фризах. Файл в Загрузках, без токенов",
                    "For freeze reports. A file in Downloads, no tokens");

    /** Diagnostics: UI frames, our hooks' cost and main-thread stalls to a file (UiStallLog). */
    public static final Toggle BAN_DIAG_LOG = new Toggle("ban_diag_log", "Лог банов и удалений", "Bans and deletions log", false)
            .about("Для отчёта о пропавших сообщениях. Файл в Загрузках",
                    "For reports of vanished messages. A file in Downloads")
            .asNew("0.4.7-alpha");
    public static final Toggle UI_STALL_LOG = new Toggle("ui_stall_log", "Лог подвисаний", "UI stall log", false)
            .about("Для отчёта о тормозах. Файл в Загрузках",
                    "For lag reports. A file in Downloads");

    /** Bug fix: a re-signed app fails Play Integrity, and Twitch then refuses login (FixLoginPatch). */
    public static final Toggle FIX_LOGIN = new Toggle("fix_login", "Вход с изменённым приложением", "Signing in on a modified app", true, true)
            .about("Убирает ошибку «This app version/OS is not currently supported»",
                    "Removes the \"This app version/OS is not currently supported\" error")
            .compare("Twitch проверяет приложение через Play Integrity и может отказать во входе",
                    "Проверка не отправляется",
                    "Twitch checks the app through Play Integrity and can refuse the login",
                    "The check is not sent");

    public static final Toggle BLOCK_DISPLAY_ADS = new Toggle("block_display_ads", "Баннеры и спонсорские карточки", "Banners and sponsored cards", false)
            .about("Реклама в ленте и спонсорское оформление каналов",
                    "Ads in the feed and sponsored channel skins")
            .look(SettingsPreview.HOME, SettingsPreview.H_ADS)
            .note("Display-реклама, headliner над «Отслеживаемым», промо-карточки, скины каналов и плашка «Includes Paid Promotion» на плеере.",
                    "Display ads, the headliner above Following, sponsored cards, channel skins and the \"Includes Paid Promotion\" label on the player.");

    public static final Toggle HIDE_BOTS = new Toggle("hide_bots", "Боты", "Bots", false)
            .about("Nightbot, StreamElements и боты канала из BTTV",
                    "Nightbot, StreamElements and channel bots from BTTV")
            .look(SettingsPreview.CHAT, SettingsPreview.C_BOTS);

    public static final Toggle RTE_FRIENDS = new Toggle("rte_friends", "Сообщать, что смотрю", "Share what I watch", false)
            .about("Друзья RTE видят ваш стрим",
                    "RTE friends see your stream")
            .asNew("0.4.0-alpha");
    public static final Toggle FRIEND_BUTTON = new Toggle("friend_button", "Кнопка в карточке зрителя", "Button on the viewer card", false)
            .about("Добавить в друзья RTE из чата",
                    "Add an RTE friend from the chat")
            .compare("В друзья добавляют только на сайте",
                    "В карточке зрителя кнопка «Добавить в друзья» и статус заявки",
                    "Friends are added on the website only",
                    "The viewer card has an \"Add friend\" button and the request status")
            .note("Кнопка открывает ext.rte.net.ru/friends, заявка подтверждается там.",
                    "The button opens ext.rte.net.ru/friends, the request is confirmed there.")
            .asNew("0.5.2-beta");
    public static final Toggle CHAT_PROXY = new Toggle("chat_proxy", "Чат через прокси", "Chat through a proxy", true)
            .about("Если чат Twitch не подключается",
                    "If Twitch chat won't connect")
            .compare("Чат подключается напрямую к Twitch",
                    "Чат идёт через прокси ReYohoho, а если он недоступен - напрямую",
                    "Chat connects to Twitch directly",
                    "Chat goes through the ReYohoho proxy, and directly while it is down")
            .asNew("0.4.0-alpha");
    /** Twitch's "Cookies and ad preferences" dialog at start (PromotionsPatch: ConsentDataUpdated). */
    public static final Toggle HIDE_COOKIE_CONSENT = new Toggle("hide_cookie_consent", "Окно про cookie", "Cookie dialog", false)
            .about("«Файлы cookie и предпочтения рекламы» при запуске",
                    "\"Cookies and ad preferences\" at start")
            .compare("При запуске окно с кнопками «Принять», «Настроить», «Отклонить»",
                    "Окна нет, Twitch не получает ни согласия, ни отказа",
                    "At start a dialog with Accept, Customize and Decline",
                    "No dialog, Twitch gets neither consent nor refusal")
            .note("Выбор, сделанный раньше, остаётся. Где Twitch это показывает, выбор есть и в его настройках: «Файлы cookie и предпочтения рекламы».",
                    "A choice made earlier stays. Where Twitch shows it, the choice is also in its settings: \"Cookies and ad preferences\".")
            .asNew("0.5.14-beta");

    public static final Toggle BLOCK_TRACKERS = new Toggle("block_trackers", "Трекеры", "Trackers", true)
            .about("Comscore, Sentry, Crashlytics, Open Measurement",
                    "Comscore, Sentry, Crashlytics, Open Measurement")
            .compare("Приложение отправляет статистику сторонним сервисам",
                    "Эти отправки заблокированы",
                    "The app sends statistics to third-party services",
                    "These are blocked");

    /** Chat group in the menu. */
    public static final Toggle FIRST_MESSAGE = new Toggle("first_message", "Первое сообщение зрителя", "A viewer's first message", false)
            .about("Выделяет того, кто пишет на канале впервые",
                    "Marks someone writing on the channel for the first time")
            .look(SettingsPreview.CHAT, SettingsPreview.C_FIRST);

    public static final Toggle FFZ_BADGES = new Toggle("ffz_badges", "FrankerFaceZ", "FrankerFaceZ", false)
            .look(SettingsPreview.CHAT, SettingsPreview.C_BADGE_FFZ);
    public static final Toggle CHATTERINO_BADGES = new Toggle("chatterino_badges", "Chatterino", "Chatterino", false)
            .look(SettingsPreview.CHAT, SettingsPreview.C_BADGE_CHATTERINO);
    public static final Toggle DANKCHAT_BADGES = new Toggle("dankchat_badges", "DankChat", "DankChat", false)
            .look(SettingsPreview.CHAT, SettingsPreview.C_BADGE_DANKCHAT);
    public static final Toggle CHATSEN_BADGES = new Toggle("chatsen_badges", "Chatsen", "Chatsen", false)
            .look(SettingsPreview.CHAT, SettingsPreview.C_BADGE_CHATSEN);
    public static final Toggle HOMIES_BADGES = new Toggle("homies_badges", "Chatterino Homies", "Chatterino Homies", false)
            .about("Личные значки зрителей",
                    "Viewers' personal badges")
            .look(SettingsPreview.CHAT, SettingsPreview.C_BADGE_HOMIES);

    public static final Toggle EMOTE_INPUT_IMAGES = new Toggle("emote_input_images", "Картинки в поле ввода", "Images in the chat input", false)
            .about("Эмоуты видно ещё до отправки",
                    "Emotes show before you send")
            .look(SettingsPreview.INPUT, SettingsPreview.I_IMAGES)
            .note("7TV, BTTV, FFZ и эмоуты Twitch, которые можно отправить с этого аккаунта.",
                    "7TV, BTTV, FFZ and the Twitch emotes this account can send.")
            .asNew("0.5.2-beta");

    /** The global 7TV/BTTV/FFZ sets stay loaded, lookups just skip them (EmoteCache.globalSets). */
    public static final Toggle NO_GLOBAL_EMOTES = new Toggle("no_global_emotes", "Только эмоуты канала", "Channel emotes only", false)
            .about("Без глобальных 7TV, BTTV и FFZ: в чате, подсказках и панели только наборы канала",
                    "No global 7TV, BTTV and FFZ: chat, suggestions and the panel show the channel's sets only")
            .note("Канал без своих 7TV, BTTV или FFZ остаётся без этих разделов в панели. Панель обновится при следующем открытии канала.",
                    "A channel without its own 7TV, BTTV or FFZ emotes has no such sections in the panel. The panel updates on the next channel you open.")
            .look(SettingsPreview.PICKER, SettingsPreview.K_NO_GLOBAL)
            .asNew("0.5.14-beta");

    public static final Toggle FFZ_GIANT_EMOTES = new Toggle("ffz_giant_emotes", "Гигантские эмоуты", "Giant emotes", false)
            .about("Эмоут за награду FFZ:GE крупным планом",
                    "An emote from an FFZ:GE reward, large")
            .look(SettingsPreview.CHAT, SettingsPreview.C_GIANT);

    public static final Toggle EMOTE_FAVORITES = new Toggle("emote_favorites", "Избранные эмоуты", "Favorite emotes", false)
            .about("Долгое нажатие на эмоут в панели, сабовые тоже",
                    "Long press an emote in the panel, sub ones too")
            .look(SettingsPreview.PICKER, SettingsPreview.K_FAVORITES);
    public static final Toggle EMOTE_FAVORITES_PER_CHANNEL = new Toggle("emote_favorites_per_channel", "Своё избранное на каждом канале", "Separate favorites per channel", false)
            .compare("Один список на все каналы",
                    "На каждом канале свой список",
                    "One list for all channels",
                    "Each channel has its own list");
    public static final Toggle FAVORITES_FIRST_AUTOCOMPLETE = new Toggle("favorites_first_autocomplete", "Избранные первыми в подсказках", "Favorites first in suggestions", false)
            .look(SettingsPreview.PICKER, SettingsPreview.K_FIRST);
    public static final Toggle FREQUENT_EMOTES = new Toggle("frequent_emotes", "Учитывать подсказки в «Часто используемых»", "Count suggestions in Frequently Used", false)
            .about("Эмоуты Twitch, отправленные из подсказки, тоже попадут в этот раздел",
                    "Twitch emotes sent from a suggestion land there too")
            .look(SettingsPreview.PICKER, SettingsPreview.K_FREQUENT);
    public static final Toggle PICKER_NO_STICKY_HEADERS = new Toggle("picker_no_sticky_headers", "Без закреплённых заголовков", "No sticky headers", false)
            .about("Заголовок раздела уезжает вместе с эмоутами",
                    "A section title scrolls away with its emotes")
            .look(SettingsPreview.PICKER, SettingsPreview.K_STICKY);
    /** Bug fix: with the picker sheet open, a tap on Send or the chat input only closed the sheet. */
    public static final Toggle PICKER_OUTSIDE_TAP = new Toggle("picker_outside_tap", "Кнопки при открытых эмоутах", "Buttons with emotes open", false, true)
            .about("«Отправить» и поле ввода работают с первого нажатия",
                    "Send and the chat input work on the first tap")
            .compare("Первое нажатие только закрывает панель эмоутов",
                    "Сообщение уходит сразу, поле ввода открывает клавиатуру",
                    "The first tap only closes the emote panel",
                    "The message goes right away, the input opens the keyboard");

    public static final Toggle AUTOMOD_CHAT = new Toggle("automod_chat", "AutoMod в чате", "AutoMod in chat", false)
            .about("Для модераторов: задержанные сообщения прямо в чате, с кнопками",
                    "For moderators: held messages right in chat, with buttons")
            .look(SettingsPreview.CHAT, SettingsPreview.C_AUTOMOD).asNew("0.4.2-alpha");

    public static final Toggle MOD_ACTIONS = new Toggle("mod_actions", "Модерация из меню сообщения", "Moderation from the message menu", false)
            .about("Для модераторов: удалить, таймаут, бан и разбан по долгому нажатию",
                    "For moderators: delete, timeout, ban and unban on a long press")
            .look(SettingsPreview.MENU, SettingsPreview.M_MOD)
            .note("Как в reFrosty. Что можно удалить или забанить, решает Twitch: при отказе будет подсказка с причиной.",
                    "As in reFrosty. Twitch decides what may be deleted or banned: a refusal shows the reason.")
            .asNew("0.5.10-beta");

    public static final Toggle VOD_STICKY_SCROLL = new Toggle("vod_sticky_scroll", "Не сбрасывать прокрутку в записях", "Keep scroll position in VODs", false)
            .about("Чат записи не уезжает вниз, пока вы читаете выше",
                    "A VOD's chat stays put while you read higher up")
            .compare("Каждая пачка сообщений возвращает чат записи вниз",
                    "Автопрокрутка работает только у конца",
                    "Each batch of messages takes a VOD's chat back down",
                    "Autoscroll only works near the bottom").asNew("0.4.2-alpha");

    /** Twitch's chat SDK hands messages to the UI in batches once a second (ChatBatchingPatch). */
    public static final Toggle CHAT_REALTIME = new Toggle("chat_realtime", "Сообщения без пачек", "Messages without batches", false)
            .about("Сразу, по одному, как в веб-версии",
                    "Right away, one by one, as on the web")
            .look(SettingsPreview.CHAT, SettingsPreview.C_REALTIME)
            .asNew("0.4.11-alpha");

    public static final Toggle NEW_MESSAGES_COUNT = new Toggle("new_messages_count", "Счётчик новых сообщений", "New message count", false)
            .about("Сколько пришло, пока вы читаете выше", "How many came in while you read higher up")
            .look(SettingsPreview.CHAT, SettingsPreview.C_NEW_COUNT)
            .note("Считаются сообщения канала, пришедшие по IRC: скрытые фильтрами тоже.",
                    "Counts the channel's messages as they come over IRC, filtered ones included.")
            .asNew("0.5.10-beta");

    public static final Toggle REWARD_STATUS = new Toggle("reward_status", "Кулдаун наград за баллы", "Reward cooldowns", false)
            .about("Таймер на иконке награды, ❌ если она закончилась",
                    "A timer on the reward's icon, ❌ if it's out of stock")
            .compare("Что награда недоступна, видно только после нажатия",
                    "Видно сразу, ещё до нажатия",
                    "You only learn a reward is unavailable by tapping it",
                    "You see it right away, before tapping")
            .asNew("0.5.6-beta");

    /** DeletedMessagesHook.tintRow; colour and opacity in ChatSettings. */
    public static final Toggle DELETED_BACKGROUND = new Toggle("deleted_background", "Фон удалённых", "Deleted message background", false)
            .about("Строка закрашивается целиком, вместе с ником, как в браузере",
                    "The whole row is tinted, name included, as on the web")
            .look(SettingsPreview.CHAT, SettingsPreview.C_DELETED_BG)
            .asNew("0.5.7-beta");

    public static final Toggle CHAT_SYNC = new Toggle("chat_sync", "Чат вровень со стримом", "Chat in step with the stream", false)
            .about("Сообщения ждут задержку видео",
                    "Messages wait for the video's delay")
            .compare("Чат реагирует на то, чего на видео ещё не было",
                    "Сообщение появляется, когда видео дошло до того же момента",
                    "Chat reacts to what the video hasn't shown yet",
                    "A message shows up when the video reaches the same moment")
            .note("Задержка берётся у плеера и добавляется к задержке чата канала. Свои сообщения не ждут. Только трансляции.",
                    "The delay comes from the player and adds to the channel's chat delay. Your own messages don't wait. Live only.")
            .asNew("0.5.2-beta");

    public static final Toggle COLORED_ME = new Toggle("colored_me", "Цветные /me", "Coloured /me", false)
            .about("Текст /me цветом ника",
                    "/me text in the nick's colour")
            .look(SettingsPreview.CHAT, SettingsPreview.C_ME).asNew("0.4.11-alpha");
    public static final Toggle USER_HISTORY = new Toggle("user_history", "История сообщений зрителя", "A viewer's message history", false)
            .about("В его карточке: что он писал, пока чат открыт",
                    "On their card: what they wrote while the chat was open")
            .look(SettingsPreview.MENU, SettingsPreview.M_HISTORY)
            .asNew("0.4.18-alpha");
    public static final Toggle REPLY_THREADS = new Toggle("reply_threads", "Ветка ответов", "Reply threads", false)
            .about("Нажатие на «В ответ ...» над сообщением показывает всю ветку",
                    "A tap on \"Replying to ...\" above a message shows the whole thread")
            .compare("Нажатие на ответ открывает карточку автора",
                    "Нажатие на «В ответ ...» открывает первое сообщение и все ответы на него",
                    "A tap on a reply opens its author's card",
                    "A tap on \"Replying to ...\" opens the first message and every reply to it")
            .note("Открывается ветка самого Twitch, та же, что по долгому нажатию > «Ответить». В горизонтальном режиме ответ - плашка с ником внутри сообщения, нажимать на неё. В оверлее чата не работает",
                    "It opens Twitch's own thread, the same as a long press > Reply. In landscape a reply is a pill with the name inside the message: tap it. Doesn't work in the chat overlay")
            .asNew("0.5.22-beta");
    public static final Toggle ROBOTTY_HISTORY = new Toggle("robotty_history", "Больше истории чата", "More chat history", false)
            .about("Сообщения до вашего входа - из сервиса Robotty: в самом чате и в карточке зрителя",
                    "Messages from before you came in, from the Robotty service: in the chat and on a viewer card")
            .compare("Чат начинается с короткой истории Twitch, карточка помнит только то, что пришло при вас",
                    "Чат начинается с последних 200 сообщений, в карточке - до 800 последних сообщений канала",
                    "The chat starts with Twitch's short history, a card knows only what came while you were there",
                    "The chat starts with the last 200 messages, a card has up to the channel's last 800")
            .note("Имя канала уходит стороннему сервису recent-messages.robotty.de (через прокси эмоутов RTE, если он включён), когда вы открываете чат или карточку зрителя. Если сервис не ответил за 4 секунды, остаётся история Twitch",
                    "The channel's name goes to the third-party service recent-messages.robotty.de (through the RTE emote proxy when it's on) when you open a chat or a viewer card. If it doesn't answer within 4 seconds, Twitch's history stays")
            .asNew("0.5.22-beta");
    public static final Toggle CHAT_ALTERNATING = new Toggle("chat_alternating", "Чередование строк", "Alternating rows", false)
            .about("Строки через одну темнее",
                    "Every other row is darker")
            .look(SettingsPreview.CHAT, SettingsPreview.C_ALT).asNew("0.4.18-alpha");
    public static final Toggle CHAT_OFF = new Toggle("chat_off", "Чат выключен", "Chat off", false)
            .about("Для слабых телефонов: чат не рисуется, пока не попросите",
                    "For slow phones: the chat isn't drawn until you ask for it")
            .compare("Чат под плеером и сбоку в полном экране, со смайлами",
                    "На месте чата «Чат выключен · Показать», в полном экране видео на всю ширину",
                    "The chat under the player and beside it in full screen, with emotes",
                    "\"Chat is off · Show\" in the chat's place, the video takes the full width in full screen")
            .note("Включить чат до перезапуска приложения: «Показать» на его месте или кнопка чата на плеере. Чат при этом "
                            + "подключён: баллы, упоминания и мод-логи работают. Не действует в вертикальном виде",
                    "To turn the chat on until the app restarts: Show in its place or the chat button on the player. The chat "
                            + "stays connected: points, mentions and mod logs work. Vertical view isn't covered")
            .asNew("0.5.28-beta");
    public static final Toggle CHAT_PINCH_TEXT = new Toggle("chat_pinch_text", "Размер щипком", "Pinch to resize", false)
            .about("Двумя пальцами прямо в чате, как фото",
                    "With two fingers right in the chat, like a photo")
            .compare("Размер текста - только здесь, в настройках",
                    "Щипок по чату меняет размер на лету",
                    "Text size is set only here, in the settings",
                    "A pinch on the chat changes it on the fly")
            .note("Шаги те же, что у «Размера текста». Значки уже показанных сообщений меняются, когда сообщение перерисуется",
                    "The same steps as Text size. Badges of messages already shown change when the message is drawn again")
            .asNew("0.5.22-beta");
    public static final Toggle COLORED_MENTIONS = new Toggle("colored_mentions", "Цветные упоминания", "Coloured mentions", false)
            .about("@ник цветом и раскраской этого зрителя, если он уже писал",
                    "@nick in that viewer's colour and paint, once they have written")
            .look(SettingsPreview.CHAT, SettingsPreview.C_COLOR_MENTION)
            .asNew("0.4.11-alpha");
    public static final Toggle REPLY_ALERTS = new Toggle("reply_alerts", "Ответы вам", "Replies to you", false)
            .about("Как упоминание: подсветка, вибрация и звук, что из них включено",
                    "Like a mention: highlight, vibration and sound, whichever is on")
            .look(SettingsPreview.CHAT, SettingsPreview.C_REPLY).asNew("0.5.5-beta");

    public static final Toggle COPY_SEND = new Toggle("copy_send", "«Копировать и отправить»", "\"Copy and send\"", false)
            .about("Новый пункт в меню по долгому нажатию на сообщение",
                    "A new item in a message's long-press menu")
            .look(SettingsPreview.MENU, SettingsPreview.M_COPY_SEND);
    /** Twitch's "This link will take you to ..." dialog before opening a link. */
    public static final Toggle NO_LINK_DISCLAIMER = new Toggle("no_link_disclaimer", "Ссылки без предупреждения", "Links without a warning", false)
            .about("Не спрашивать «Эта ссылка ведёт на...»",
                    "Skip \"This link goes to...\"")
            .look(SettingsPreview.MENU, SettingsPreview.M_LINK);

    public static final Toggle PREVENT_MOD_CLEAR = new Toggle("prevent_mod_clear", "Не очищать чат", "Keep chat on /clear", false)
            .about("Сообщения остаются, когда модератор чистит чат",
                    "Messages stay when a moderator clears chat")
            .look(SettingsPreview.CHAT, SettingsPreview.C_NO_CLEAR);

    /** IRC lines for every viewer; moderators also get who acted (PubSub). */
    public static final Toggle MOD_LOGS = new Toggle("mod_logs", "Мод-логи", "Mod logs", false)
            .about("Баны, таймауты и удаления строкой в чате. Модераторы видят, кто это сделал",
                    "Bans, timeouts and deletions as chat lines. Moderators see who did it")
            .look(SettingsPreview.CHAT, SettingsPreview.C_MOD_LOG);

    public static final Toggle RAID_CARD = new Toggle("raid_card", "Строка о рейде", "Raid line", false)
            .about("Если вы пришли на канал вместе с рейдом",
                    "When you arrive with a raid")
            .look(SettingsPreview.CHAT, SettingsPreview.C_RAID);

    // Menu groups. Declared after every switch: an array initialised earlier would hold nulls.
    // The chat header switches became ChatHeaderSetting (keys migrated there).

    static final Toggle[] CHAT_FILTERS = {
            HIDE_BOTS, HIDE_NATIVE_GIFS, HIDE_ANNOUNCEMENTS, HIDE_SUB_NOTICES, HIDE_REDEMPTIONS, HIDE_MILESTONES,
    };

    /** Other clients' chat badges; order = ThirdPartyBadges.URLS. */
    public static final Toggle[] THIRD_PARTY_BADGES = {
            FFZ_BADGES, CHATTERINO_BADGES, DANKCHAT_BADGES, CHATSEN_BADGES, HOMIES_BADGES,
    };

    /** Banners above chat. */
    static final Toggle[] BANNERS = {
            HIDE_HYPE_TRAIN, HIDE_PREDICTIONS, HIDE_RAID_BANNER, HIDE_SHOUTOUT, HIDE_DROPS_BANNER, HIDE_SHARED_CHAT,
            HIDE_GIFT_BANNERS,
    };

    /** Interface page sections, by where the thing is on screen (top to bottom). */
    static final Toggle[] UI_PLAYER = {
            HIDE_PLAYER_CAST, HIDE_PLAYER_SHARE, HIDE_PLAYER_CLIP, HIDE_PLAYER_FULLSCREEN, HIDE_PLAYER_MUTE, HIDE_PLAYER_ROTATE,
            HIDE_FOLLOW_SUB, HIDE_UNFOLLOW,
            HIDE_MATURE_WARNING, HIDE_CHAT_MODE_POPUP,
    };
    /** After the whole-header choice (ChatHeaderSetting): the header row's elements, left to right, then their rows. */
    static final Toggle[] UI_CHAT_HEADER = {
            HIDE_CHAT_TITLE, HIDE_HEADER_BUTTONS, HIDE_GIFT_BUTTON, HIDE_EXTENSIONS, HIDE_LEADERBOARDS,
    };
    /** Only what the input page's live preview shows. */
    static final Toggle[] UI_CHAT_INPUT = {
            HIDE_BITS_BUTTON, HIDE_CHAT_SETTINGS_BUTTON, EMOTE_BUTTON_TYPING_ONLY, HIDE_POINTS_PORTRAIT, HIDE_POINTS_LANDSCAPE,
            POINTS_BALANCE_LANDSCAPE, POINTS_BALANCE_UNDER_ICON, LATENCY_HINT,
    };

    /** Bug fixes (on by default), on their own page so they aren't switched off by accident. */
    static final Toggle[] FIXES = {NO_MANIFEST_CACHE, FRESH_RN_PREVIEWS, FEED_TAB_INDICATOR, ALL_FOLLOWED_CATEGORIES, LANDSCAPE_INPUT_VISIBLE, IDLE_INPUT_CURSOR,
            GLOBAL_BADGES_NOW, BADGES_ON_BASELINE, LAYOUT_PICKER_ICONS, VOD_CHAT_EMOTES, VAFT_LOW_LATENCY, PICKER_OUTSIDE_TAP, FIX_LOGIN};

    private Toggles() {
    }

    public static boolean on(Toggle toggle) {
        try {
            Context context = AppContext.get();
            return context != null && prefs(context).getBoolean(toggle.key, toggle.defaultOn);
        } catch (Throwable t) {
            return false;
        }
    }

    static void set(Context context, Toggle toggle, boolean value) {
        prefs(context).edit().putBoolean(toggle.key, value).apply();
    }

    private static volatile SharedPreferences prefs;

    static SharedPreferences prefs(Context context) {
        // The same instance every Context returns (cached per file by the framework); skips its locked lookup.
        SharedPreferences p = prefs;
        if (p == null) {
            p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs = p;
        }
        return p;
    }
}
