package tv.retwitch.mod;

import android.content.Context;

/**
 * "About": what the mod is built on, with links, and the notices its licences ask for. GPL section 5(d)
 * wants the app to show a copyright notice, the lack of warranty and how to read the licence; Apache and
 * MIT want their text with the program ({@link LicenseTexts}).
 */
final class AboutPage {

    private static final String GITHUB = "https://github.com/";

    /** Name, what we took (ru, en), licence, repository under github.com (null: none known). */
    private static final String[][] BASE = {
            {"Morphe", "Патчер и библиотека патчей", "Patcher and patch library", "GPL-3.0", "MorpheApp"},
            {"ReVanced", "Откуда вырос Morphe", "What Morphe grew out of", "GPL-3.0", "ReVanced"},
            {"De-Vanced", "Патчи ReVanced на Morphe", "ReVanced patches on Morphe", "GPL-3.0", "RookieEnough/De-Vanced"},
    };
    private static final String[][] CODE = {
            {"PurpleTV", "Таймер, перезагрузка плеера, главы записей", "Timer, player reload, VOD chapters", "Apache-2.0",
                    "NyanArchive/PurpleTV"},
            {"PurpleTV ReVive", "Значки других клиентов, резервная копия", "Other clients' badges, backup", "Apache-2.0",
                    "alienware377/PurpleTV-ReVive"},
            {"twitch-patched", "Реклама в ленте, предложения Turbo, свой JS в React Native",
                    "Feed ads, Turbo offers, own JS in React Native", "GPL-3.0", "ryykitty/twitch-patched"},
            {"TwitchAdSolutions", "Подмена рекламы в потоке (VAFT)", "Swapping ads out of the stream (VAFT)", "MIT",
                    "ryanbr/TwitchAdSolutions"},
            {"TwitchNoSub", "Записи для подписчиков", "Sub-only VODs", "Apache-2.0", "besuper/TwitchNoSub"},
            {"TTV LOL PRO", "Формат запросов к прокси", "Proxy request format", "GPL-3.0", "younesaassila/ttv-lol-pro"},
            {"uyu", "Разбор патчей и данмаку", "Study of patches and danmaku", "GPL-3.0", "bakwudo/uyu"},
            {"sabopura-morphe-patches", "Разбор патчей и данмаку", "Study of patches and danmaku", "GPL-3.0",
                    "sabopura/sabopura-morphe-patches"},
            {"hoomans-morphe-patches", "Разбор патчей чата и входа", "Study of chat and login patches", "GPL-3.0",
                    "arandomhooman/hoomans-morphe-patches"},
            {"lurk-morphe-patches", "Разбор патчей", "Study of patches", "GPL-3.0", "jordanwoodson/lurk-morphe-patches"},
            {"overhypedcat-twitch-patches", "Разбор патчей", "Study of patches", "GPL-3.0",
                    "Overhypedcat/overhypedcat-twitch-patches"},
            {"Thream", "Карта мест для хуков", "A map of hook points", null, null},
    };
    private static final String[][] LIBRARIES = {
            {"OkHttp", "Сеть", "Networking", "Apache-2.0", "square/okhttp"},
            {"Okio", "Ввод-вывод", "I/O", "Apache-2.0", "square/okio"},
            {"Kotlin", "Стандартная библиотека", "Standard library", "Apache-2.0", "JetBrains/kotlin"},
            {"AppMetrica", "Отчёты о падениях", "Crash reports", "MIT", "appmetrica/appmetrica-sdk-android"},
    };
    private static final String[][] TOOLS = {
            {"APKEditor", "Сборка бандла в один APK", "Merging the bundle into one APK", "Apache-2.0", "REAndroid/APKEditor"},
            {"Apktool", "Разбор APK", "Taking APKs apart", "Apache-2.0", "iBotPeaches/Apktool"},
            {"hermes-dec", "Чтение JS Twitch, в мод не входит", "Reading Twitch's JS, not in the mod", "AGPL-3.0", "P1sec/hermes-dec"},
    };

    private AboutPage() {
    }

    private static String t(String ru, String en) {
        return Strings.pick(ru, en);
    }

    static void build(SettingsScreen s) {
        final Context c = s.context;
        s.action(Strings.changelogTitle(), t("Версия ", "Version ") + BuildInfo.VERSION_NAME, () -> SettingsHook.showChangelog(c));
        s.toggle(Toggles.UPDATE_CHECK);
        s.action(t("Проверить обновления сейчас", "Check for updates now"), null, () -> ModUpdate.checkNow(c));
        s.section(t("Основа", "Foundation"));
        credits(s, BASE);
        s.section(t("Код и идеи", "Code and ideas"));
        credits(s, CODE);
        s.section(t("Библиотеки", "Libraries"));
        credits(s, LIBRARIES);
        s.section(t("Инструменты", "Tools"));
        credits(s, TOOLS);
        s.section(t("Спасибо", "Thanks"));
        s.action("ReYohoho", t("Сообщество и тестеры", "The community and testers"), () -> SettingsHook.openLink(c, SettingsHook.LINK_SITE));
        s.action("@FredMan95", t("Перевод на испанский", "Spanish translation"), null);
        s.action("@GoatKou", t("Перевод на французский", "French translation"), null);
        s.action("Claude AI", t("Помогает писать код", "Helps writing the code"), null);
        s.section(t("Лицензия", "License"));
        s.hint(t("© 2026 ReYohoho и участники проекта. ReTwitch - свободная программа: её можно распространять и изменять "
                        + "на условиях GNU GPL версии 3 или (по вашему выбору) любой более поздней. Программа поставляется без каких-либо гарантий.\n\n"
                        + "Неофициальная модификация: не связана с Twitch и не одобрена им. Собрана с помощью Morphe; "
                        + "проект Morphe к ReTwitch отношения не имеет.",
                "© 2026 ReYohoho and contributors. ReTwitch is free software: you may redistribute and modify it under "
                        + "the terms of the GNU GPL version 3 or (at your option) any later version. It comes with no warranty of any kind.\n\n"
                        + "An unofficial modification, not affiliated with or endorsed by Twitch. Built with Morphe; "
                        + "the Morphe project has nothing to do with ReTwitch."));
        s.external(t("Исходный код", "Source code"), "github.com/reyohoho/ReTwitch-src",
                () -> SettingsHook.openLink(c, SettingsHook.LINK_GITHUB));
        s.nav("GNU GPL 3.0", t("Лицензия мода и Morphe", "The mod's and Morphe's license"), screen -> screen.document(LicenseTexts.GPL_3));
        s.nav("Apache 2.0", "PurpleTV, ReVive, TwitchNoSub, OkHttp, Okio, Kotlin", screen -> screen.document(
                "Copyright 2022 TwitchMod (PurpleTV, PurpleTV ReVive)\n\n" + LicenseTexts.APACHE_2));
        s.nav("MIT", "TwitchAdSolutions, AppMetrica", screen -> screen.document(
                "Copyright (c) 2020-present TwitchAdSolutions Contributors\nCopyright (c) 2023 YANDEX LLC\n\n" + LicenseTexts.MIT));
    }

    private static void credits(SettingsScreen s, String[][] rows) {
        final Context c = s.context;
        for (String[] row : rows) {
            String what = t(row[1], row[2]);
            String summary = row[3] == null ? what : what + " · " + row[3];
            if (row[4] == null) {
                s.action(row[0], summary, null);
            } else {
                final String url = GITHUB + row[4];
                s.external(row[0], summary, () -> SettingsHook.openLink(c, url));
            }
        }
    }
}
