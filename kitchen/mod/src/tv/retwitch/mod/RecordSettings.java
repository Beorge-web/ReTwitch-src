package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The "Recording and downloads" settings page (stream recording, auto-record, VOD downloads) and the cards of running recordings. */
final class RecordSettings {

    /** Cards on a page: more recordings at once than this still show in the notification. */
    private static final int CARDS = 3;
    private static final Pattern LOGIN =
            Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?(?:twitch\\.tv/)?@?([A-Za-z0-9_]{2,25})/?");

    private RecordSettings() {
    }

    private static String t(String ru, String en) {
        return Strings.pick(ru, en);
    }

    /** One card per running recording, hidden while there is none. */
    static void cards(SettingsScreen s) {
        for (int i = 0; i < CARDS; i++) {
            final int n = i;
            LiveRecord.Rec now = LiveRecord.at(n);
            s.progress(now != null && now.isVod() ? t("Скачивание видео", "Video download")
                            : t("Запись трансляции", "Stream recording"), () -> LiveRecord.status(n),
                    () -> LiveRecord.statusPercent(n), () -> LiveRecord.pauseLabel(n), () -> {
                        LiveRecord.Rec rec = LiveRecord.at(n);
                        if (rec != null) {
                            LiveRecord.togglePause(rec);
                        }
                    }, () -> {
                        LiveRecord.Rec rec = LiveRecord.at(n);
                        if (rec != null) {
                            LiveRecord.stop(rec);
                        }
                    }, t("Стоп", "Stop"));
        }
    }

    static String summary() {
        int running = LiveRecord.ACTIVE.size();
        return running > 0 ? Strings.pickf("Идёт записей: %d", "Recording now: %d", running)
                : t("Запись эфиров, автозапись, скачивание видео", "Stream recording, auto-record, VOD downloads");
    }

    static void page(SettingsScreen s) {
        final Context c = s.context;
        s.section(t("Запись трансляций", "Stream recording"));
        s.group(Toggles.LIVE_RECORD, () -> {
            s.toggle(Toggles.LIVE_RECORD_LABEL);
            s.toggle(Toggles.LIVE_RECORD_ARCHIVE);
        });
        cards(s);

        s.section(t("Автозапись", "Auto-record"));
        s.markNew("0.5.14-beta").note(t("Как работает:\n"
                        + "• Раз в минуту мод смотрит, кто из списка в эфире, и сразу пишет трансляцию с начала.\n"
                        + "• Если у канала есть архив (включены записи трансляций), запись идёт из него: без рекламы, в "
                        + "исходном качестве и с самого начала, даже если эфир заметили позже.\n"
                        + "• Без архива пишется сам эфир с вырезанной рекламой, только с момента, когда мод его заметил.\n"
                        + "• Качество задаётся у каждого канала. Если такого нет, берётся ближайшее ниже, «Лучшее» - исходное.\n"
                        + "• Каждая трансляция пишется один раз, остановленная вручную больше не начнётся.\n"
                        + "• Запись кончается вместе с эфиром: файл в Загрузки/ReTwitch и уведомление «Запись сохранена».\n"
                        + "\n"
                        + "Ограничения:\n"
                        + "• Пока автозапись ждёт, висит уведомление «ReTwitch ждёт эфира»: без него Android не даст ждать в фоне.\n"
                        + "• Ожидание запускается только из открытого приложения: после перезагрузки телефона или если "
                        + "Android закрыл приложение, откройте его снова.\n"
                        + "• Когда телефон долго лежит без дела, проверка идёт реже, примерно раз в 9 минут. С архивом начало "
                        + "всё равно запишется, без архива эти минуты пропадут.\n"
                        + "• Если приложение закрылось посреди записи, автозапись продолжит её в том же файле, как только "
                        + "снова проверит каналы. Без архива время между ними пропадёт.\n"
                        + "• Во время записи телефон не засыпает до конца и держит Wi-Fi, это расходует батарею.\n"
                        + "• С «Только по Wi-Fi» в мобильной сети запись не начинается и ждёт Wi-Fi.\n"
                        + "• Нужна включённая «Запись трансляций».",
                "How it works:\n"
                        + "• Once a minute the mod looks at who on the list is live and records the stream from its start.\n"
                        + "• If the channel keeps an archive (VODs on), the recording comes from it: no ads, source quality "
                        + "and from the very start, even when the stream was noticed later.\n"
                        + "• Without an archive the stream itself is recorded with ads cut out, only from when the mod noticed it.\n"
                        + "• The quality is set per channel. If there's none such, the nearest lower one is taken; \"Best\" is the source.\n"
                        + "• Each stream is recorded once; one stopped by hand doesn't start again.\n"
                        + "• The recording ends with the stream: a file in Downloads/ReTwitch and a \"Recording saved\" notification.\n"
                        + "\n"
                        + "Limits:\n"
                        + "• While auto-record waits, a \"ReTwitch is waiting for streams\" notification stays up: Android "
                        + "won't let an app wait in the background without one.\n"
                        + "• The wait starts only from the open app: after a phone restart, or if Android closed the app, "
                        + "open it again.\n"
                        + "• When the phone lies idle for long, checks come less often, about every 9 minutes. With an archive "
                        + "the start is still recorded, without one those minutes are lost.\n"
                        + "• If the app closes mid-recording, auto-record continues it in the same file at its next look at "
                        + "the channels. Without an archive the time in between is lost.\n"
                        + "• While recording the phone doesn't fully sleep and keeps Wi-Fi on, which costs battery.\n"
                        + "• With \"Wi-Fi only\" it doesn't start on mobile data and waits for Wi-Fi.\n"
                        + "• Needs \"Stream recording\" on."))
                .group(t("Автозапись", "Auto-record"), t("Каналы из списка пишутся с начала каждой трансляции",
                        "Channels on the list are recorded from the start of every stream"), AutoRecord.isOn(), false,
                        on -> AutoRecord.setOn(c, on), () -> {
                            s.toggle(t("Только по Wi-Fi", "Wi-Fi only"), t("В мобильной сети не начинает и не догоняет",
                                    "Doesn't start or catch up on mobile data"), AutoRecord.wifiOnly(), false,
                                    AutoRecord::setWifiOnly);
                            List<String[]> channels = AutoRecord.channels();
                            String[] qualities = {t("Лучшее", "Best"), "1080p", "720p", "480p", "360p", "160p",
                                    Strings.downloadAudioOnly()};
                            for (final String[] channel : channels) {
                                int quality = AutoRecord.qualityIndex(channel[1]);
                                s.group(channel[0], AutoRecord.state(channel) + " · " + qualities[quality],
                                        "1".equals(channel[2]), false, on -> AutoRecord.setEnabled(c, channel[0], on),
                                        () -> s.choice(t("Качество", "Quality"), t("Если такого нет, ближайшее ниже",
                                                "If there's none, the nearest lower one"), qualities, quality, false,
                                                i -> AutoRecord.setQuality(c, channel[0], AutoRecord.QUALITIES[i])));
                            }
                            s.action(t("Добавить канал", "Add a channel"), t("Логин или ссылка twitch.tv/...",
                                    "A login or a twitch.tv/... link"), () -> addChannel(c));
                            if (!channels.isEmpty()) {
                                s.action(t("Убрать канал", "Remove a channel"), null, () -> removeChannel(c, channels));
                            }
                        });

        s.section(t("Скачивание видео и клипов", "VOD and clip downloads"));
        s.toggle(Toggles.DOWNLOAD_BUTTON);

        s.section(t("Записи", "Recordings"));
        s.toggle(Toggles.LIVE_RECORD_CHAT);
        s.markNew(NEW).nav(t("Записи", "Recordings"), recordsSummary(c), RecordSettings::recordsPage);
    }

    private static final String NEW = "0.5.15-beta";

    /** "Идёт 1 · не докачано 2 · готово 5", or what the page is for. */
    private static String recordsSummary(Context c) {
        int running = LiveRecord.ACTIVE.size();
        int cut = 0;
        int done = 0;
        for (JSONObject meta : Records.all(c)) {
            if (LiveRecord.byId(meta.optString("id")) == null) {
                if (resumable(meta)) {
                    cut++;
                } else {
                    done++;
                }
            }
        }
        if (running + cut + done == 0) {
            return t("Записи трансляций и скачанные видео: продолжить, смотреть с чатом, удалить",
                    "Stream recordings and downloaded videos: continue, watch with the chat, delete");
        }
        StringBuilder b = new StringBuilder();
        if (running > 0) {
            b.append(Strings.pickf("идёт %d", "running %d", running));
        }
        if (cut > 0) {
            b.append(b.length() > 0 ? " · " : "").append(Strings.pickf("не докачано %d", "not finished %d", cut));
        }
        if (done > 0) {
            b.append(b.length() > 0 ? " · " : "").append(Strings.pickf("готово %d", "finished %d", done));
        }
        return b.toString();
    }

    /**
     * Cut short and can go on: the archive and a VOD always (Twitch keeps every segment while the VOD lives), a live
     * stream only if it wasn't stopped by hand, and then only while it is still on air.
     */
    static boolean resumable(JSONObject meta) {
        String state = meta.optString("state");
        String kind = meta.optString("kind");
        if (Records.DONE.equals(state) || Records.KIND_LEGACY.equals(kind)) {
            return false;
        }
        boolean keeps = Records.KIND_ARCHIVE.equals(kind) || Records.KIND_VOD.equals(kind);
        return keeps || !Records.STOPPED.equals(state);
    }

    /** The recordings page: running cards, then what can go on, then the finished ones. */
    static void recordsPage(SettingsScreen s) {
        final Context c = s.context;
        List<JSONObject> cut = new ArrayList<>();
        List<JSONObject> done = new ArrayList<>();
        long bytes = 0;
        for (JSONObject meta : Records.all(c)) {
            bytes += meta.optLong("bytes");
            if (LiveRecord.byId(meta.optString("id")) != null) {
                continue; // a card
            }
            (resumable(meta) ? cut : done).add(meta);
        }
        if (!LiveRecord.ACTIVE.isEmpty()) {
            s.section(t("Идёт сейчас", "Running now"));
            cards(s);
        }
        if (!cut.isEmpty()) {
            s.section(t("Не докачаны", "Not finished"));
            for (JSONObject meta : cut) {
                cutRow(s, meta);
            }
        }
        s.section(t("Готовые", "Finished"));
        if (done.isEmpty()) {
            s.hint(t("Здесь появятся готовые записи и скачанные видео.",
                    "Finished recordings and downloaded videos show up here."));
        }
        for (JSONObject meta : done) {
            doneRow(s, meta);
        }
        s.hint(Strings.pickf("Файлы - Загрузки/ReTwitch · занято %s", "Files are in Downloads/ReTwitch · %s used",
                Formatter.formatShortFileSize(c, bytes)));
    }

    private static String name(JSONObject meta) {
        String display = meta.optString("display");
        String title = meta.optString("title");
        if (Records.KIND_LEGACY.equals(meta.optString("kind")) || display.isEmpty()) {
            return meta.optString("name");
        }
        return title.isEmpty() ? display : display + " - " + title;
    }

    private static String when(Context c, JSONObject meta) {
        return DateUtils.formatDateTime(c, meta.optLong("updated", meta.optLong("at")), DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_TIME);
    }

    private static void cutRow(final SettingsScreen s, final JSONObject meta) {
        final Context c = s.context;
        String kind = meta.optString("kind");
        boolean live = Records.KIND_LIVE.equals(kind);
        boolean vod = Records.KIND_VOD.equals(kind);
        String state = LiveRecord.interrupted(meta) || Records.FAILED.equals(meta.optString("state"))
                ? Strings.pickf("Прервана %s", "Cut short %s", when(c, meta))
                : Strings.pickf("Остановлена %s", "Stopped %s", when(c, meta));
        StringBuilder summary = new StringBuilder(state).append(" · ").append(LiveRecord.clock(meta.optLong("seconds")))
                .append(" · ").append(Formatter.formatShortFileSize(c, meta.optLong("bytes")));
        long behind = meta.optLong("behind");
        if (vod && behind > 0) {
            summary.append(" · ").append(100 * meta.optLong("seconds") / (meta.optLong("seconds") + behind)).append(" %");
        }
        if (!meta.optString("problem").isEmpty()) {
            summary.append(" · ").append(meta.optString("problem"));
        }
        String chip;
        int color;
        if (live) {
            long lastEnd = meta.optLong("lastEnd");
            long minutes = lastEnd > 0 ? Math.max(1, (System.currentTimeMillis() - lastEnd) / 60_000) : 0;
            chip = minutes > 0 ? Strings.pickf("при продолжении пропуск ~%d мин", "continuing skips ~%d min", minutes)
                    : t("эфир без архива: пропуск при продолжении", "no archive: continuing leaves a gap");
            color = 0xFFF0B44C;
        } else {
            chip = vod ? t("VOD · без потерь", "VOD · nothing lost") : t("архив · без потерь", "archive · nothing lost");
            color = 0xFF5CD08A;
        }
        final String button = live ? t("С эфира", "From now") : t("Продолжить", "Continue");
        s.item(name(meta), summary.toString(), chip, color, button, true, () -> resume(c, meta),
                () -> menu(c, meta, button));
    }

    private static void doneRow(final SettingsScreen s, final JSONObject meta) {
        final Context c = s.context;
        StringBuilder summary = new StringBuilder(LiveRecord.clock(meta.optLong("seconds"))).append(" · ")
                .append(Formatter.formatShortFileSize(c, meta.optLong("bytes"))).append(" · ").append(when(c, meta));
        if (meta.optInt("chatLines") > 0) {
            summary.append(" · ").append(t("чат", "chat"));
        }
        if (Records.STOPPED.equals(meta.optString("state"))) {
            summary.append(" · ").append(t("остановлена", "stopped"));
        }
        long lost = meta.optLong("lost");
        String chip = lost >= 60 ? Strings.pickf("пропуски %d мин", "gaps %d min", lost / 60) : null;
        s.item(name(meta), summary.toString(), chip, 0xFFF0B44C, "▶", false, () -> play(c, meta),
                () -> menu(c, meta, null));
    }

    private static void resume(Context c, JSONObject meta) {
        if (PushRegistration.needsNotificationPermission(c)) {
            PushRegistration.requestNotificationPermission(c);
        }
        LiveRecord.resume(c, meta);
        Toast.makeText(c, Records.KIND_LIVE.equals(meta.optString("kind"))
                ? t("Проверяю эфир и продолжаю...", "Checking the stream and continuing...")
                : t("Продолжаю с места обрыва", "Continuing where it stopped"), Toast.LENGTH_SHORT).show();
    }

    /** The mod's player when the recording has its segment list, else the phone's. */
    private static void play(Context c, JSONObject meta) {
        if (RecordPlayer.canPlay(c, meta)) {
            RecordPlayer.show(c, meta);
        } else {
            openExternal(c, meta);
        }
    }

    private static void openExternal(Context c, JSONObject meta) {
        if (!LiveRecord.open(c, meta)) {
            Toast.makeText(c, t("Файл удалён или его нечем открыть", "The file is gone or nothing plays it"),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void menu(final Context c, final JSONObject meta, String continueLabel) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (continueLabel != null) {
            labels.add(continueLabel);
            actions.add(() -> resume(c, meta));
        }
        if (RecordPlayer.canPlay(c, meta)) {
            labels.add(meta.optInt("chatLines") > 0 ? t("Смотреть с чатом", "Watch with the chat") : t("Смотреть", "Watch"));
            actions.add(() -> RecordPlayer.show(c, meta));
        }
        labels.add(t("Открыть в плеере телефона", "Open in the phone's player"));
        actions.add(() -> openExternal(c, meta));
        labels.add(t("Удалить", "Delete"));
        actions.add(() -> confirmDelete(c, meta));
        new AlertDialog.Builder(c)
                .setTitle(name(meta))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void confirmDelete(final Context c, final JSONObject meta) {
        new AlertDialog.Builder(c)
                .setTitle(t("Удалить запись?", "Delete the recording?"))
                .setMessage(Strings.pickf("«%s», %s. Файл и чат удалятся с телефона.",
                        "\"%s\", %s. The file and its chat are deleted from the phone.", name(meta),
                        Formatter.formatShortFileSize(c, meta.optLong("bytes"))))
                .setPositiveButton(t("Удалить", "Delete"), (d, w) -> {
                    if (!Records.delete(c, meta)) {
                        Toast.makeText(c, t("Android не дал удалить файл: удалите его в «Файлах», в Загрузки/ReTwitch",
                                "Android refused to delete the file: delete it in Files, in Downloads/ReTwitch"),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void addChannel(final Context c) {
        final EditText input = new EditText(c);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("twitch.tv/...");
        new AlertDialog.Builder(c)
                .setTitle(t("Канал для автозаписи", "A channel to auto-record"))
                .setView(input)
                .setPositiveButton(t("Добавить", "Add"), (d, w) -> {
                    String login = login(input.getText().toString());
                    if (login == null) {
                        Toast.makeText(c, t("Это не похоже на логин канала", "That doesn't look like a channel login"),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    AutoRecord.put(c, login, "");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void removeChannel(final Context c, final List<String[]> channels) {
        String[] logins = new String[channels.size()];
        for (int i = 0; i < logins.length; i++) {
            logins[i] = channels.get(i)[0];
        }
        new AlertDialog.Builder(c)
                .setTitle(t("Убрать из автозаписи", "Remove from auto-record"))
                .setItems(logins, (d, which) -> AutoRecord.put(c, logins[which], null))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "Name", "twitch.tv/name", "https://www.twitch.tv/name/" -> "name"; null otherwise. */
    static String login(String text) {
        Matcher m = LOGIN.matcher(text.trim());
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }
}
