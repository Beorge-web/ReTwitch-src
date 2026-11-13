package tv.retwitch.mod;

import android.text.TextUtils;

/**
 * Mod changelog shown in the ReTwitch "About" dialog.
 *
 * <p>Add a new entry at the top of {@link #ENTRIES} with every
 * {@link BuildInfo#VERSION_NAME} bump: {version, Russian items, English
 * items}, one item per line. Not every version reaches the testers: versions
 * since the last one handed out are merged into the current entry.
 */
public final class Changelog {

    private static final String[][] ENTRIES = {
            {"0.5.27-beta",
                    "«Идеи и баги» переехали на GitHub: строка в настройках открывает Issues, а форма бага подсказывает, какой лог приложить\n"
                            + "Сбор на Boosty теперь сверху настроек мода\n"
                            + "Иконка приложения: новый вариант - чёрная (тёмная до 0.5.20)\n"
                            + "Прокрутка чата записи в полном экране больше не сворачивает плеер, когда чат лежит поверх видео",
                    "Ideas and bugs moved to GitHub: the row in settings opens Issues, and the bug form tells which log to attach\n"
                            + "The Boosty fundraiser is now on top of the mod's settings\n"
                            + "App icon: a new option, black (the dark one before 0.5.20)\n"
                            + "Scrolling a VOD's chat in full screen no longer minimizes the player when the chat lies over the video"},
            {"0.5.26-beta",
                    "Нажатие на полоску «В эфире с:» под плеером совместной трансляции больше не открывает стрим из ленты, который лежит под ней\n"
                            + "Свайп в полный экран больше не срабатывает на мини-плеере, в том числе растянутом почти на всю ширину; яркость и громкость на мини-плеере тоже не меняются",
                    "A tap on the \"Live with:\" strip under a shared stream's player no longer opens the stream from the feed that lies under it\n"
                            + "The swipe to full screen no longer fires on the mini-player, including one stretched to almost full width; brightness and volume don't change there either"},
            {"0.5.25-beta", "", ""},
            {"0.5.24-beta",
                    "В меню ⋮ на нативной странице канала тоже можно добавить канал в избранное (раньше - только на странице React Native)\n"
                            + "«Идеи и баги» запоминают сортировку «Топ» или «Новые»\n"
                            + "Заряд батареи в полном экране: процент на плеере вместе с кнопками (Плеер > Кнопки на плеере > Показания)",
                    "The ⋮ menu on the native channel page can add the channel to favorites too (before, only the React Native page)\n"
                            + "Ideas and Bugs remember the Top or New sort\n"
                            + "Battery in full screen: the percentage on the player, shown with the controls (Player > Player buttons > Readouts)"},
            {"0.5.23-beta",
                    "«Сколько идёт эфир» теперь видно на превью: раньше настройка ничего не делала\n"
                            + "Полки на главной вкладке канала можно скрыть: записи эфиров, избранные клипы, эфиры по категориям, рекомендуемые стримеры (Интерфейс > Страница канала)\n"
                            + "Вид страницы канала можно выбрать: нативная или React Native (Интерфейс > Страница канала)\n"
                            + "Свайп вниз по видео в полном экране, в том числе в зонах яркости и громкости, больше не начинает сворачивать плеер: на планшете он прыгал\n"
                            + "В экспорт настроек попадают эксперименты Twitch, включённые на телефоне: так проще понять, чем он отличается от других\n"
                            + "Ветка ответов открывает ветку самого Twitch со всеми ответами, как долгое нажатие > «Ответить». В горизонтальном режиме - нажатием на плашку ответа в сообщении: раньше там открывался профиль\n"
                            + "Поле ввода на вкладке «Чат» страницы канала стоит прямо над клавиатурой: нижняя панель больше не влезает между ними\n"
                            + "Размер текста чата меняется шагом 5 %, и в настройках, и щипком\n"
                            + "В «Идеях и багах» вкладки «Идеи» и «Баги» переключаются свайпом\n"
                            + "В меню ⋮ на странице канала (React Native) можно добавить канал в избранное\n"
                            + "Обновление скачивается через прокси RTE, если хранилище Яндекса напрямую не отвечает\n"
                            + "Кнопки поворота экрана и звука в плеере можно скрыть (Интерфейс > Плеер)\n"
                            + "С чатом поверх видео кнопки плеера у края чата снова нажимаются: их перехватывала ручка делителя",
                    "Stream uptime now shows on the previews: the setting did nothing before\n"
                            + "Shelves on a channel's Home tab can be hidden: stream recaps, featured clips, streams by category, suggested streamers (Interface > Channel page)\n"
                            + "The channel page type can be picked: native or React Native (Interface > Channel page)\n"
                            + "A swipe down on the video in full screen, the brightness and volume zones included, no longer starts minimizing the player: on a tablet it jumped\n"
                            + "The settings export carries the Twitch experiments on the phone: it's easier to see how it differs from others\n"
                            + "Reply threads open Twitch's own thread with every reply, like a long press > Reply. In landscape a tap on the reply pill in the message opens it: it opened the profile before\n"
                            + "The input on a channel page's Chat tab sits right above the keyboard: the bottom bar no longer gets between them\n"
                            + "Chat text size goes in 5% steps, in the settings and with a pinch\n"
                            + "In Ideas and Bugs a swipe switches between the Ideas and Bugs tabs\n"
                            + "The ⋮ menu on a channel page (React Native) can add the channel to favorites\n"
                            + "The update downloads through the RTE proxy when Yandex's storage doesn't answer directly\n"
                            + "The player's rotate and sound buttons can be hidden (Interface > Player)\n"
                            + "With the chat over the video, player buttons at the chat's edge work again: the divider handle took their taps"},
            {"0.5.22-beta",
                    "Больше истории чата теперь работает: Robotty спрашивается через прокси эмоутов RTE, а история не пропадает при переключении канала\n"
                            + "Прокрутка чата в горизонтальном режиме больше не сворачивает плеер\n"
                            + "Чат больше не замирает после обрыва сети или VPN: если 40 секунд от чата ничего нет и он не отвечает, мод переподключает его сам",
                    "More chat history works now: Robotty is asked through the RTE emote proxy, and the history no longer vanishes on a channel switch\n"
                            + "Scrolling the chat in landscape no longer minimizes the player\n"
                            + "The chat no longer freezes after the network or VPN drops: when nothing comes from it for 40 seconds and it doesn't answer, the mod reconnects it"},
            {"0.5.21-beta",
                    "Сколько идёт эфир: в углу превью стримов время с начала трансляции (Главная > Превью стримов)\n"
                            + "Ветка ответов: нажатие на «В ответ ...» над сообщением показывает первое сообщение и все ответы на него (Чат > Сообщения > Ветка ответов)\n"
                            + "Больше истории чата: чат начинается с последних 200 сообщений из сервиса Robotty, а в карточке зрителя - его сообщения из последних 800 сообщений канала (Чат > Сообщения)\n"
                            + "Долгое нажатие на кнопку компрессора открывает панель звука: компрессор, усиление, басы, верх, голос и лимитер меняются прямо во время стрима\n"
                            + "Перезапуск при зависании: можно выбрать, сколько ждать загрузку, от 3 до 30 секунд (Плеер > Воспроизведение)\n"
                            + "«Смайлик только при наборе» работает и в портретном чате, а не только в боковом\n"
                            + "В «Идеях и багах» у каждой записи виден её номер, например #45\n"
                            + "Испанский перевод дополнен: переведены все строки, добавленные с 0.5.15",
                    "Stream uptime: the preview's corner shows the time since the stream started (Home > Stream previews)\n"
                            + "Reply threads: a tap on \"Replying to ...\" above a message shows the first message and every reply to it (Chat > Messages > Reply threads)\n"
                            + "More chat history: the chat starts with the last 200 messages from the Robotty service, and a viewer card has their messages from the channel's last 800 (Chat > Messages)\n"
                            + "A long press on the compressor button opens the audio panel: compressor, boost, bass, treble, voice and limiter change while the stream plays\n"
                            + "Restart when stuck: how long to wait for loading can be picked, from 3 to 30 seconds (Player > Playback)\n"
                            + "Emote button only while typing works in the portrait chat too, not only in the side chat\n"
                            + "Ideas and bugs show every entry's number, for example #45\n"
                            + "The Spanish translation is complete again: every string added since 0.5.15 is translated"},
            {"0.5.20-beta",
                    "Быстрая настройка: при первом запуске мод спрашивает о главном (чистый интерфейс, смайлы, реклама, чат, плеер, прокси трансляций для исходного качества, главная, скачивание) и показывает, как это выглядит. Пройти её ещё раз можно в настройках мода\n"
                            + "Тёмная иконка приложения теперь с фиолетовым контуром, как в бренд-ките Twitch для тёмного фона: чёрный контур на тёмном фоне не было видно\n"
                            + "Личные эмоуты 7TV: эмоуты из личного набора зрителя видны в его сообщениях\n"
                            + "Эмоуты 7TV, которые канал сделал наложением на своём наборе, рисуются поверх предыдущего эмоута\n"
                            + "Награда FFZ «гигантский эмоут» увеличивает и обычные эмоуты Twitch, а не только 7TV, BTTV и FFZ\n"
                            + "Кнопки «Отслеживать», «Подписаться» и «Сделать подарок» на странице канала можно скрыть по отдельности (Интерфейс > Страница канала)\n"
                            + "Планшет: у колонки отслеживаемых слева может быть своя сортировка, отдельно от списка справа (Главная > Своя сортировка у левой колонки)\n"
                            + "Фильтры звука (усиление, эквалайзер) снова включаются сразу при входе в стрим, а не после нажатия на компрессор или через несколько минут\n"
                            + "Компактный список «В эфире» больше не прыгает сам по себе при прокрутке, особенно со стримом в мини-плеере\n"
                            + "У «Писать из архива канала» появилось пояснение: в архиве звук как в записи канала на Twitch, без того, что стример пускает только в эфир\n"
                            + "Фрагмент видео: при скачивании VOD можно взять только кусок между двумя метками времени, поля сразу заполнены вокруг текущего места. Фрагмент сохраняется в «Записи», вместе с чатом за этот отрезок\n"
                            + "⏮ ⏭ в полноэкранном режиме открывают следующий стрим тоже в полноэкранном\n"
                            + "Размер текста чата можно менять щипком прямо в чате (Чат > Внешний вид > Размер щипком), и появился шаг 70 %\n"
                            + "Свайп в полный экран больше не поворачивает экран, когда тянешь мини-плеер\n"
                            + "Чат короткого скачанного видео больше не теряется: загрузка заканчивалась раньше, чем начиналась запись чата\n"
                            + "Избранные каналы (Главная > Отслеживаемое): долгое нажатие на канал в «Отслеживаемом» > «Добавить в избранное». Избранные стоят своим разделом над «Каналами в эфире»: кто в эфире - строками, кто нет - кружками аватаров",
                    "Quick setup: on first launch the mod asks about the main things (a clean interface, emotes, ads, chat, player, a stream proxy for the source quality, home, downloads) and shows what they look like. It can be run again from the mod's settings\n"
                            + "The dark app icon now has a purple outline, as in Twitch's brand kit for dark backgrounds: the black outline was invisible on the dark tile\n"
                            + "7TV personal emotes: emotes from a viewer's personal set show in their messages\n"
                            + "7TV emotes a channel made zero-width in its set are drawn over the previous emote\n"
                            + "The FFZ \"giant emote\" reward also enlarges Twitch's own emotes, not only 7TV, BTTV and FFZ ones\n"
                            + "The Follow, Subscribe and Gift A Sub buttons on a channel page can be hidden one by one (Interface > Channel page)\n"
                            + "Tablet: the followed channels column on the left can keep its own sort, apart from the list on the right (Home > Own sort for the left column)\n"
                            + "Audio filters (boost, equalizer) apply again as soon as a stream opens, not after a tap on the compressor or a few minutes later\n"
                            + "The compact Live list no longer jumps on its own while scrolling, especially with a stream in the mini player\n"
                            + "Record from the channel's archive got a note: the archive has the sound of the channel's Twitch VOD, without what the streamer plays only live\n"
                            + "Video fragment: a VOD download can take only the part between two times, the fields start around where you are. The fragment goes to Recordings, with the chat of that stretch\n"
                            + "⏮ ⏭ in fullscreen open the next stream in fullscreen too\n"
                            + "Chat text size changes with a pinch right in the chat (Chat > Look > Pinch to resize), and there is a 70 % step\n"
                            + "The swipe to fullscreen no longer rotates the screen while you drag the mini player\n"
                            + "A short downloaded video no longer loses its chat: the download used to finish before the chat started saving\n"
                            + "Favorite channels (Home > Following): long-press a channel in Following > Add to favorites. Favorites get a section of their own above Live channels: the live ones as rows, the rest as avatar circles"},
            {"0.5.19-beta",
                    "Обновление внутри приложения больше не обрывается на плохой сети: при разрыве скачивание продолжается с того же места, а не падает с «Обновление не удалось». Отчёт о сбое обновления уходит разработчику",
                    "The in-app update no longer breaks on a poor connection: a dropped download goes on from where it stopped instead of failing with \"The update failed\". A failed update sends a crash report to the developer"},
            {"0.5.18-beta",
                    "Предыдущий и следующий стрим (Плеер > Кнопки на плеере): кнопки ⏮ ⏭ у паузы, как на YouTube. Листают недавно открытые каналы или отслеживаемые в эфире\n"
                            + "Шапку главной можно убрать (Главная > Шапка главной: скрыть): истории и строку вкладок «Отслеживаемое · В эфире · Клипы». Без вкладок ленты листаются свайпом вбок\n"
                            + "Нажатия по открытому прогнозу в панели наград под полем чата больше не проходят сквозь неё и не включают стрим с главной\n"
                            + "Курсор в поле чата больше не начинает снова мигать после закрытия клавиатуры в полном экране (Исправления > Без мигающего курсора)",
                    "Previous and next stream (Player > Player buttons): ⏮ ⏭ beside the pause, as on YouTube. They go through recently opened channels or the followed ones live\n"
                            + "The home header can go (Home > Home header: hide): stories and the Following · Live · Clips tab row. Without the tabs, feeds switch with a sideways swipe\n"
                            + "Taps on an opened prediction in the rewards panel under the chat input no longer go through it and start a stream from the home feed\n"
                            + "The chat input's cursor no longer starts blinking again after the keyboard closes in fullscreen (Fixes > No blinking cursor)"},
            {"0.5.17-beta",
                    "Чат поверх видео (Плеер > Полный экран > Боковой чат): в режиме «Чат рядом» видео на всю ширину, а чат лежит на нём колонкой с настраиваемой прозрачностью фона. Нажатие на полоску между видео и чатом открывает переключатель и прозрачность прямо в плеере. Оверлей чата Twitch берёт ту же прозрачность",
                    "Chat over the video (Player > Fullscreen > Side chat): in the Chat next to video mode the video takes the full width and the chat lies on it as a column with an adjustable background opacity. A tap on the strip between the video and the chat opens the switch and the opacity right in the player. Twitch's chat overlay takes the same opacity"},
            {"0.5.16-beta",
                    "Чат записи больше не повторяет каждое сообщение по несколько раз: при записи архива канала чат сохранялся заново каждые 30 секунд. Уже записанные чаты остаются с повторами\n"
                            + "Нажатие на заголовок наград канала под полем чата больше не проходит сквозь панель и не включает стрим с главной\n"
                            + "Плеер записей: картинка в картинке при выходе на рабочий стол, пока запись играет (с кнопкой паузы); без неё запись встаёт на паузу, а не играет звуком в фоне. Жесты яркости, громкости и свайп в полный экран, шаг перемотки из настроек плеера; чат в горизонтальном виде шириной как в плеере Twitch, полоску между видео и чатом можно тянуть\n"
                            + "Эмоуты Twitch в чате VOD после русского текста или эмодзи снова картинки, а не слова (ошибка самого Twitch; Исправления > Эмоуты Twitch в чате VOD)\n"
                            + "Кнопки 7TV, BTTV, FFZ в панели эмоутов больше не оставляют пустой экран под последним разделом",
                    "A recording's chat no longer repeats every message several times: with a channel archive recording the chat was saved again every 30 seconds. Chats recorded before keep the repeats\n"
                            + "A tap on the channel rewards header under the chat input no longer goes through the panel and starts a stream from the home feed\n"
                            + "Recording player: picture-in-picture when you go to the home screen while a recording plays (with a pause button); otherwise the recording pauses instead of playing sound in the background. Brightness and volume gestures and swipe to fullscreen, the seek step from the player settings; in landscape the chat is as wide as in Twitch's player, and the strip between video and chat can be dragged\n"
                            + "Twitch emotes in VOD chat after Cyrillic text or an emoji are pictures again, not words (a bug in Twitch itself; Fixes > Twitch emotes in VOD chat)\n"
                            + "The 7TV, BTTV, FFZ buttons in the emote panel no longer leave an empty screen under the last section"},
            {"0.5.15-beta",
                    "Записи (настройки > Запись и скачивание): записи трансляций и скачанные видео одним списком, идущие, недокачанные и готовые. Прерванную запись можно продолжить: архив канала и видео докачиваются с места обрыва без потерь, эфир без архива - с текущего момента. Запись удаляется вместе с файлом\n"
                            + "Плеер записей: запись играет прямо в моде, с чатом как в Twitch (значки, цвета ников, смайлы Twitch, 7TV, BTTV и FFZ), с пиками чата на перемотке, скоростью и сдвигом чата; в горизонтальном виде чат колонкой справа\n"
                            + "Чат можно сохранять вместе с записью и скачанным видео (Запись и скачивание > Записывать чат)\n"
                            + "Автозапись после закрытия приложения продолжает прерванную запись в том же файле, а не начинает новую\n"
                            + "Скачивание видео ставится на паузу и останавливается как запись, и можно качать несколько сразу\n"
                            + "Идеи и баги: к записям и ответам можно приложить картинки, видео и логи мода. Логи видите только вы и разработчик\n"
                            + "Всё, что сохраняет файлы на телефон, теперь в одном разделе «Запись и скачивание»: запись трансляций, автозапись, скачивание видео и клипов. Раздел про VOD называется «Видео и клипы»\n"
                            + "Автозапись: у каждого канала своё качество, если такого нет - берётся ближайшее ниже. В пояснении расписано, как она работает и что её ограничивает\n"
                            + "Исходное качество в списках подписано, какое оно: «Источник (1080p60)»",
                    "Recordings (settings > Recording and downloads): stream recordings and downloaded videos in one list, running, unfinished and finished. A recording cut short can go on: the channel's archive and videos continue where they stopped with nothing lost, a stream without an archive from the current moment. Deleting a recording deletes its file\n"
                            + "Recording player: a recording plays right in the mod, with the chat as in Twitch (badges, nick colours, Twitch, 7TV, BTTV and FFZ emotes), chat peaks on the seek bar, speed and chat offset; in landscape the chat is a column on the right\n"
                            + "The chat can be saved with a recording and a downloaded video (Recording and downloads > Record the chat)\n"
                            + "After the app closes, auto-record continues the cut recording in the same file instead of starting a new one\n"
                            + "Video downloads pause and stop like recordings, and several can run at once\n"
                            + "Ideas and bugs: pictures, videos and the mod's logs can be attached to posts and replies. Logs are seen only by you and the developer\n"
                            + "Everything that saves files to the phone is now in one section, Recording and downloads: stream recording, auto-record, VOD and clip downloads\n"
                            + "Auto-record: each channel has its own quality; if there's none such, the nearest lower one is taken. The explanation says how it works and what limits it\n"
                            + "The source quality in lists says which it is: \"Source (1080p60)\""},
            {"0.5.14-beta",
                    "Запись трансляций: кнопка в плеере live пишет стрим в файл в Загрузки/ReTwitch, можно с начала эфира и без рекламы (из архива канала), с остановкой по концу эфира или по таймеру. Автозапись: выбранные каналы пишутся сами, как только выходят в эфир (настройки > Запись и скачивание)\n"
                            + "Запись трансляций и скачивание видео идут в фоне через уведомление: Android их больше не обрывает, когда приложение свёрнуто или экран выключен\n"
                            + "Идеи и баги (настройки > Сервис): предложите идею или сообщите о баге, голосуйте и комментируйте; ответы разработчика отмечаются в настройках\n"
                            + "Язык и перевод (настройки > Сервис): язык мода можно выбрать отдельно от телефона, перевод скачать файлом для таблицы, поправить и загрузить обратно или сделать новый язык\n"
                            + "Глобальные смайлы 7TV, BTTV и FFZ можно отключить: останутся только смайлы канала, а канал без своих не покажет эти разделы в панели (эмоуты)\n"
                            + "Окно «Файлы cookie и предпочтения рекламы» при запуске можно отключить (реклама)\n"
                            + "Список изменений в окне обновления оформлен как в «О моде»: версии жирным, пункты списком\n"
                            + "Полоска под вкладками «Отслеживаемое», «В эфире», «Клипы» с крупным шрифтом стоит под выбранной вкладкой (исправления)\n"
                            + "После картинки в картинке чат в полноэкранном режиме больше не занимает весь экран, выталкивая видео",
                    "Stream recording: a button in the live player records the stream to a file in Downloads/ReTwitch, even from the stream's start and without ads (from the channel's archive), stopping when the stream ends or on a timer. Auto-record: chosen channels are recorded on their own as soon as they go live (settings > Recording and downloads)\n"
                            + "Recording and VOD downloads run in the background with a notification: Android no longer cuts them off when the app is minimized or the screen is off\n"
                            + "Ideas and bugs (settings > Service): suggest an idea or report a bug, vote and comment; the developer's replies are marked in the settings\n"
                            + "Language and translation (settings > Service): pick the mod's language apart from the phone's, download the translation as a file for a spreadsheet, fix it and load it back, or make a new language\n"
                            + "Global 7TV, BTTV and FFZ emotes can be turned off: only the channel's emotes stay, and a channel without its own has no such sections in the panel (emotes)\n"
                            + "The \"Cookies and ad preferences\" dialog at start can be turned off (ads)\n"
                            + "The changelog in the update dialog looks like the one in About: versions in bold, items as a list\n"
                            + "The underline under Following, Live and Clips sits under the selected tab with a large font (fixes)\n"
                            + "After picture-in-picture the chat in full screen no longer takes the whole screen and pushes the video out"},
            {"0.5.13-beta",
                    "Стримы с Drops снова идут через прокси трансляций: с ReYohoho и TTV LOL PRO Drops засчитываются, пункт «Drops без прокси» убран\n"
                            + "В списке прокси у Luminous и PerfProd написано, что Drops с ними не засчитываются",
                    "Streams with Drops go through the stream proxy again: Drops count with ReYohoho and TTV LOL PRO, the \"Drops without the proxy\" option is gone\n"
                            + "The proxy list says Drops don't count with Luminous and PerfProd"},
            {"0.5.12-beta",
                    "Если стрим с Drops идёт напрямую с Twitch в обход прокси, в чате об этом появляется строка: там же сказано, где это выключить (настройки сети)",
                    "When a stream with Drops comes straight from Twitch past the proxy, a line in chat says so and where to turn it off (network settings)"},
            {"0.5.11-beta",
                    "Новый прокси трансляций TTV LOL PRO (настройки сети): мастер-плейлист Twitch идёт через их сервер в другой стране, записи - напрямую\n"
                            + "Убраны отключённые серверы PerfProd (EU, NA, SA): они отвечали 403 или молчали у всех. Если был выбран один из них, теперь стоит «без прокси»",
                    "New stream proxy TTV LOL PRO (network settings): Twitch's master playlist goes through their server in another country, VODs go direct\n"
                            + "Removed the PerfProd servers that went offline (EU, NA, SA): they answered 403 or nothing for everyone. If one of them was selected, it's now \"no proxy\""},
            {"0.5.10-beta",
                    "Пункт «Модерация» в меню сообщения теперь появляется и не обрезается: удалить, таймаут, бан и разбан - в одном окне",
                    "The Moderation item in the message menu now shows up and isn't cut off: delete, timeout, ban and unban in one dialog"},
            {"0.5.9-beta",
                    "Для модераторов: удалить сообщение, таймаут, бан и разбан прямо из меню сообщения, в том числе свои сообщения и сообщения других модераторов (настройки модерации)\n"
                            + "Размер превью каналов в эфире в «Отслеживаемом»: средние или крупные (настройки главной)\n"
                            + "Счётчик новых сообщений, пока вы читаете чат выше (сообщения чата)\n"
                            + "Свой цвет для подсветки слов и ников из списка, отдельно от упоминаний\n"
                            + "С прокси трансляций снова начисляются Drops: стримы с Drops идут напрямую от Twitch (настройки сети)\n"
                            + "«Если вас забанили на канале» работает и при бане только в чате: вместо «Выполнено отключение от чата» чат читается анонимно\n"
                            + "У своих сообщений снова есть время\n"
                            + "Амбилайт с зеркальным видео больше не закрывает картинку\n"
                            + "«Вертикальный вид» в полном экране со скрытым чатом снова на всю ширину, без пустой полосы справа\n"
                            + "На стримах с вертикальным форматом кнопки макета больше не закрывают паузу: у них остаются только значки (исправления)",
                    "For moderators: delete a message, timeout, ban and unban right from the message menu, own messages and other moderators' included (moderation settings)\n"
                            + "The size of live channel previews in Following: medium or large (home settings)\n"
                            + "A count of new messages while you read chat higher up (chat messages)\n"
                            + "A colour of its own for the highlighted words and nicks, apart from mentions\n"
                            + "Drops count again with a stream proxy: streams with Drops come straight from Twitch (network settings)\n"
                            + "\"If you're banned on a channel\" also works with a chat-only ban: instead of \"Disconnected from chat\" the chat is read anonymously\n"
                            + "Your own messages have their time again\n"
                            + "Ambilight with a mirrored video no longer covers the picture\n"
                            + "Vertical view in full screen with the chat hidden takes the whole width again, no empty strip on the right\n"
                            + "On streams with a vertical format the layout buttons no longer cover pause: only their icons stay (fixes)"},
            {"0.5.8-beta",
                    "Отчёты о сбоях переведены на другой сервис: у прежнего кончались лимиты, и часть сбоев не доходила",
                    "Crash reports moved to a different service: the old one kept hitting its limits and some crashes never arrived"},
            {"0.5.7-beta",
                    "Удалённые сообщения можно закрасить целиком, вместе с ником, как в браузере: цвет и насыщенность настраиваются (сообщения чата)\n"
                            + "Отслеживаемые категории можно показывать по алфавиту (настройки главной)\n"
                            + "Раскраска ника RTE и 7TV появляется уже на первом сообщении, а не со следующего\n"
                            + "Ники снова по центру значков, а низ у значков разной высоты на одной линии\n"
                            + "Со скрытой табличкой об историях «Отслеживаемое» обновляется с первой оттяжки\n"
                            + "В логе блокировки рекламы теперь видны зависания плеера и смена качества, со скоростью сети и буфером",
                    "Deleted messages can be tinted whole, name included, as on the web: colour and strength are adjustable (chat messages)\n"
                            + "Followed categories can be shown A to Z (home settings)\n"
                            + "RTE and 7TV nick paints show on the first message, not from the next one\n"
                            + "Names are centred on badges again, and badges of different heights end on one line\n"
                            + "With the stories placeholder hidden, Following refreshes on the first pull\n"
                            + "The ad blocking log now shows player stalls and quality changes, with network speed and buffer"},
            {"0.5.6-beta",
                    "На наградах за баллы можно показать кулдаун: таймер на иконке или ❌, если награда закончилась",
                    "Channel points rewards can show their cooldown: a timer on the icon, or ❌ if out of stock"},
            {"0.5.5-beta",
                    "Ответы на ваши сообщения могут подсвечиваться и вибрировать как упоминания (настройки упоминаний)\n"
                            + "При отказе live стрима или VOD (403, недоступно в вашем регионе) будет попытка загрузить поток минуя прокси. Если это не помогло - вам поможет только IP в стране, где разрешён просмотр канала"
                            + "\nВ компактном списке «В эфире» под первым стримом больше нет пустого экрана на ~20 секунд"
                            + "\nИнтерфейс мода на испанском, перевод - @FredMan95"
                            + "\nАнимированные смайлы в стриках, подписках и объявлениях больше не замирают"
                            + "\nСмайлы 7TV, BTTV и FFZ в стриках, подписках и объявлениях больше не остаются текстом и анимируются"
                            + "\nС настройкой «Открывать Отслеживаемое» приложение сразу стартует на этой вкладке: без «В эфире» и быстрее"
                            + "\nС «Сообщениями без пачек» и анимацией новых сообщений строки на быстром чате больше не наезжают друг на друга"
                            + "\nУ анимации новых сообщений появилось предупреждение: на быстром чате её лучше не включать",
                    "Replies to your messages can be highlighted and vibrate like mentions (mention settings)\n"
                            + "If a live stream or VOD fails (e.g., a 403 error or \"not available in your region\"), the system will attempt to load the stream by bypassing the proxy. If that does not work, the only solution is to use an IP address from a country where viewing the channel is permitted."
                            + "\nThe compact Live list no longer shows a blank screen below the first stream for ~20 seconds"
                            + "\nThe mod's interface in Spanish, translated by @FredMan95"
                            + "\nAnimated emotes in watch streaks, subs and announcements no longer freeze"
                            + "\n7TV, BTTV and FFZ emotes in watch streaks, subs and announcements no longer stay as text, and they animate"
                            + "\nWith \"Open Following\" the app starts right on that tab: no Live first, and faster"
                            + "\nWith messages without batches and new message animation, rows no longer pile up on a fast chat"
                            + "\nNew message animation now warns that it's better left off in a fast chat"},
            {"0.5.4-beta",
                    "Скачивание записи можно поставить на паузу или отменить: в настройках мода и в уведомлении\n"
                            + "Кнопку баллов у поля ввода можно скрыть совсем, отдельно в портрете и в полном экране\n"
                            + "Можно убрать табличку «Здесь будут появляться истории», сами истории остаются",
                    "A VOD download can be paused or cancelled: in the mod settings and in the notification\n"
                            + "The points button by the chat input can be hidden entirely, separately in portrait and in fullscreen\n"
                            + "The \"Stories will appear here\" card can be removed, stories themselves stay"},
            {"0.5.3-beta",
                    "Баланс баллов в полном экране снова рядом со значком, положение под значком стало отдельной настройкой (настройки поля ввода)\n"
                            + "В «Отслеживаемом» снова все отслеживаемые категории, а не первые 10\n"
                            + "Кнопки перемотки записи показывают выбранный шаг, а не 10 и 30\n"
                            + "Прогресс скачивания записи: в настройках мода и при повторном нажатии на кнопку скачивания\n"
                            + "Исправлено: друзья RTE не видели, какой стрим вы смотрите\n"
                            + "Исправлено: стрим зависал на минуты при смене качества в режиме «Авто», особенно на слабом интернете",
                    "The fullscreen points balance is back next to the icon, placing it under the icon is now a separate setting (input settings)\n"
                            + "Following shows all followed categories again, not just the first 10\n"
                            + "VOD seek buttons show the chosen step instead of 10 and 30\n"
                            + "VOD download progress: in the mod settings and on another tap of the download button\n"
                            + "Fixed: RTE friends couldn't see which stream you were watching\n"
                            + "Fixed: the stream froze for minutes when Auto quality switched, especially on a weak connection"},
            {"0.5.2-beta",
                    "Исправлено: после возврата из мини-плеера не работали смайлы канала, если в это время открывался чат другого канала\n"
                            + "Значок RTE в карточке зрителя больше не теряется при сбое сети\n"
                            + "При смене иконки приложения ярлык на рабочем столе больше не пропадает\n"
                            + "Плашка «Includes Paid Promotion» на плеере скрывается вместе с баннерами\n"
                            + "Баланс баллов в полном экране стоит под значком, поле ввода стало шире\n"
                            + "В «Вертикальном виде» можно скрыть кнопки сверху и панель снизу (настройки кнопок плеера)\n"
                            + "Без аккаунта настройки мода открываются кнопкой над нижней панелью\n"
                            + "Картинки в поле ввода: теперь и для эмоутов Twitch, и для ещё не скачанных\n"
                            + "Исправлено: стрим, закрытый во время загрузки, продолжал играть звуком на фоне\n"
                            + "Эмоуты за подписку добавляются в избранное\n"
                            + "Обход бана на канале: стрим запускается, чат идёт анонимно\n"
                            + "Друзья RTE на главной: кто что смотрит, под разделом «В эфире» в «Отслеживаемом»\n"
                            + "Число зрителей в чате рядом с онлайном на плеере (настройки кнопок плеера)\n"
                            + "Звук на упоминание: громкость и любой звук из системного списка\n"
                            + "Кнопка «Добавить в друзья» RTE в карточке зрителя\n"
                            + "Эмоуты можно обновить кнопкой в панели эмоутов, не сбрасывая картинки\n"
                            + "Чат вровень со стримом: сообщения ждут задержку видео (настройки чата)\n"
                            + "Убрано окно о моде, которое показывалось раз в 20 запусков",
                    "Fixed: channel emotes stopped working after returning from the mini-player if another channel's chat had been opened meanwhile\n"
                            + "The RTE badge on the viewer card no longer goes missing after a network failure\n"
                            + "The home screen shortcut no longer goes away when the app icon changes\n"
                            + "The \"Includes Paid Promotion\" label on the player is hidden along with banners\n"
                            + "The fullscreen points balance sits under the icon, leaving the input more room\n"
                            + "Vertical view can hide the buttons on top and the bottom panel (player buttons settings)\n"
                            + "Without an account the mod settings open from a button above the bottom bar\n"
                            + "Images in the input: now for Twitch emotes too, and for ones not downloaded yet\n"
                            + "Fixed: a stream closed while loading kept playing its sound in the background\n"
                            + "Sub emotes can be added to favorites\n"
                            + "Channel ban bypass: the stream starts, the chat runs anonymously\n"
                            + "RTE friends on the home screen: who watches what, under the Live section of Following\n"
                            + "Viewers in chat next to the viewer count on the player (player buttons settings)\n"
                            + "A sound on mentions: its volume and any sound from the system list\n"
                            + "An RTE \"Add friend\" button on the viewer card\n"
                            + "Emotes can be refreshed from the emote panel button without clearing the images\n"
                            + "Chat in step with the stream: messages wait for the video's delay (chat settings)\n"
                            + "Removed the dialog about the mod that showed up every 20 launches"},
            {"0.5.1-beta",
                    "Проверка автообновления: новых функций нет",
                    "An auto-update check: no new features"},
            {"0.5.0-beta",
                    "Обновления прямо из приложения: мод сам находит новую версию, показывает, что изменилось, и обновляется после подтверждения\n"
                            + "Настройки мода переделаны: разделы по задачам вместо длинных списков, короткие понятные названия и пояснения\n"
                            + "Превью «до» и «после»: нажмите на название настройки, а нажатие на картинку сразу её переключает\n"
                            + "На страницах вида чата, кнопок плеера и данмаку сверху живой макет, который меняется вместе с настройками\n"
                            + "Ползунки и кнопки выбора прямо в строке вместо диалогов, зависимые настройки прячутся вместе с главной\n"
                            + "Настройки, которым нужен перезапуск, отмечены значком, а плашка перезапуска пропадает, если вернуть всё как было\n"
                            + "Обновлена кнопка мода в настройках Twitch, на главном экране мода появились ссылки на новости и поддержку\n"
                            + "В «О моде» список проектов, на которых основан мод, со ссылками и тексты лицензий\n"
                            + "Значки в чате стоят на линии текста, как в браузере, а не свисают ниже\n"
                            + "Смайлик у поля ввода бокового чата можно показывать только при наборе, тогда поле получает его место (настройки интерфейса)\n"
                            + "Исправлено: в узком боковом чате с балансом баллов поле ввода схлопывалось после закрытия клавиатуры, при открытой панели эмоутов и при неотправленном тексте",
                    "Updates right from the app: the mod finds a new version by itself, shows what changed and updates once you confirm\n"
                            + "The mod settings are redone: sections by task instead of long lists, short clear names and explanations\n"
                            + "Before and after previews: tap a setting's name, and tapping a picture switches it right away\n"
                            + "Chat look, player buttons and danmaku pages have a live mock-up on top that follows the settings\n"
                            + "Sliders and choice chips right in the row instead of dialogs, dependent settings fold away with their main one\n"
                            + "Settings that need a restart carry a mark, and the restart bar goes away if you put things back\n"
                            + "The mod's button in Twitch's settings is updated, and the mod's main screen has links to news and support\n"
                            + "\"About\" lists the projects the mod is built on, with links and the license texts\n"
                            + "Chat badges sit on the text line, as on the web, instead of hanging below it\n"
                            + "The side chat's emote button can show only while typing, giving its room to the input (interface settings)\n"
                            + "Fixed: in a narrow side chat with the points balance, the input collapsed after the keyboard closed, with the emote panel open and with unsent text"},
            {"0.4.19-alpha",
                    "Компактный список трансляций на вкладке «В эфире»: превью слева, ник, название и категория справа, а полки «From channels you follow» и «Clips for you» там можно скрыть (настройки интерфейса)\n"
                            + "Серая заглушка вместо превью трансляции больше не остаётся навсегда, даже с выключенным обновлением превью",
                    "Compact stream list on the Live tab: preview on the left, nick, title and category on the right, and the \"From channels you follow\" and \"Clips for you\" shelves there can be hidden (interface settings)\n"
                            + "A grey placeholder instead of a stream preview no longer stays forever, even with preview refresh off"},
            {"0.4.18-alpha",
                    "Эмоуты 7TV, добавленные, удалённые или переименованные на канале, меняются сразу, без переподключения к чату, а в чате появляется строка об этом\n"
                            + "Данмаку: сообщения чата летят поверх видео в горизонтальном режиме, с ником и эмоутами (настройки плеера)\n"
                            + "В карточке зрителя: история его сообщений в этом чате, значок RTE и раскраска ника, подписи кнопок больше не наезжают друг на друга\n"
                            + "Значок ReYohoho Staff у команды ReYohoho, в чате и в карточке зрителя\n"
                            + "Чередование фона строк чата (настройки чата)\n"
                            + "Нажатие на эмоут в чате открывает его карточку, а не профиль, в том числе у эмоутов с zero-width\n"
                            + "Бан на канале: чат виден под плашкой «Вы забанены», трансляция играет без привязки к аккаунту, если канал запретил просмотр забаненным (настройки плеера)\n"
                            + "Вход необязателен: кнопка «продолжить без аккаунта» на экране входа, до 1080p и без функций аккаунта\n"
                            + "Жесты яркости и громкости работают по краям видео, свайп вниз по центру сворачивает плеер, в «Вертикальном виде» тоже; свайп вверх по плееру в портрете - полный экран, вниз по центру в полном экране - выход (настройки плеера)\n"
                            + "Спонсорское оформление каналов скрывается вместе с display-рекламой\n"
                            + "Резервные потоки на время рекламы: сперва типы, которые реже бывают с рекламой",
                    "7TV emotes added, removed or renamed on the channel change right away, with no chat reconnect, and a line in chat says so\n"
                            + "Danmaku: chat messages fly over the video in landscape, with nicks and emotes (player settings)\n"
                            + "On the viewer card: their message history in this chat, the RTE badge and nick paint; button labels no longer run into each other\n"
                            + "A ReYohoho Staff badge for the ReYohoho team, in chat and on the viewer card\n"
                            + "Alternating chat row backgrounds (chat settings)\n"
                            + "Tapping an emote in chat opens its card rather than the profile, zero-width stacks included\n"
                            + "Banned on a channel: the chat shows under the \"You are banned\" notice, and the stream plays not tied to the account when the channel stops banned viewers from watching (player settings)\n"
                            + "Signing in is optional: a \"continue without an account\" button on the login screen, up to 1080p and no account features\n"
                            + "Brightness and volume gestures work at the sides of the video, a swipe down in the middle minimizes the player, in Vertical view too; a swipe up on the portrait player goes fullscreen, and down in the middle of fullscreen comes back (player settings)\n"
                            + "Sponsored channel skins are hidden with display ads\n"
                            + "Backup streams during ads: the types that carry ads less often come first"},
            {"0.4.17-alpha",
                    "«Эксперименты Twitch» (Диагностика): все флаги функций Twitch с поиском, любой можно переопределить - например, включить то, что ещё не раскатили на всех\n"
                            + "Новая иконка по умолчанию - Twitch со звёздами; в настройках интерфейса можно выбрать стоковую или тёмную",
                    "\"Twitch experiments\" (Diagnostics): all of Twitch's feature flags with a filter, any of them can be overridden, e.g. to turn on what isn't rolled out to everyone yet\n"
                            + "New default icon: Twitch with stars; the interface settings can switch to the stock or the dark one"},
            {"0.4.16-alpha",
                    "Превью трансляций в «Отслеживаемом» обновляются сами раз в 2 минуты, а оттяжка вниз обновляет их сразу: раньше CDN Twitch по 5 минут отдавал старый кадр, и превью почти не менялись",
                    "Stream previews on Following refresh on their own every 2 minutes, and pull to refresh updates them right away: Twitch's CDN used to serve a stale frame for 5 minutes, so previews barely changed"},
            {"0.4.15-alpha",
                    "В настройках мода появился поиск: ищет по названиям и описаниям на русском и английском сразу",
                    "The mod settings have a search field: it matches titles and descriptions in both Russian and English"},
            {"0.4.14-alpha",
                    "Эмоуты 7TV, BTTV и FFZ больше не пропадают надолго после сбоя сети: загрузка повторяется через 30 секунд, а панель эмоутов подхватывает наборы, загруженные после её открытия\n"
                            + "Если сервер прокси эмоутов перестал отвечать, мод сразу переключается на другой и повторяет загрузку",
                    "7TV, BTTV and FFZ emotes no longer go missing for a long time after a network hiccup: loading retries after 30 seconds, and the emote picker picks up sets that loaded after it was opened\n"
                            + "If the emote proxy server stops answering, the mod switches to another one right away and retries the load"},
            {"0.4.12-alpha",
                    "Боковой чат больше не скачет при нажатии на плеер на устройствах, где это ещё оставалось: ширина чата держится и пока появляются кнопки",
                    "The side chat no longer jumps when tapping the player on devices that still had it: its width holds while the controls appear"},
            {"0.4.11-alpha",
                    "«Отслеживаемое»: при скрытии промо кнопка «Попробуйте без рекламы» больше не оставляет пустое место, а кнопка сортировки стоит рядом с «В эфире»\n"
                            + "Размер текста чата применяется и к наградам за баллы, стрикам, подпискам и строкам мод-логов, а значки меняются вместе с ним\n"
                            + "Подсветка FIRST MESSAGE больше не цепляет следующее сообщение того же зрителя, даже если оно такое же\n"
                            + "Можно скрыть разделы «Отслеживаемого» под каналами в эфире: категории, «Продолжить просмотр», неактивные каналы (настройки интерфейса)\n"
                            + "В «Отслеживаемом» можно включить значок серии просмотров на каналах, где она ещё не продлена\n"
                            + "Тёмная иконка теперь и в лаунчерах, которые показывали старую фиолетовую\n"
                            + "Боковая панель навигации не просвечивает у выреза камеры, пока открыта клавиатура\n"
                            + "При запуске «Отслеживаемое» открывается сразу: на медленных телефонах сначала на пару секунд показывалось «В эфире»\n"
                            + "Цветные /me и упоминания @ника цветом и раскраской зрителя, как в RTE (настройки чата, по умолчанию выключено)\n"
                            + "Картинки в чате загружаются с User-Agent браузера: некоторые хостинги не отдают их приложениям\n"
                            + "Оттяжка вниз в «Отслеживаемом» обновляет превью трансляций, а не только перезапуск приложения\n"
                            + "Сообщения в чате могут появляться сразу и по одному, как в веб-версии: Twitch собирает их и показывает пачкой раз в секунду, с задержкой до секунды (настройки чата, по умолчанию выключено)\n"
                            + "«Плавное появление сообщений» переименовано в «Анимация новых сообщений» и теперь по умолчанию выключено: на быстром чате анимации вставали в очередь и сообщения шли рывками",
                    "Following: with promotions hidden, the \"Try Ad-Free\" button no longer leaves an empty gap and the sort button stays next to \"Live Now\"\n"
                            + "Chat text size also applies to channel point rewards, streaks, subs and mod log lines, and badges scale with it\n"
                            + "The FIRST MESSAGE highlight no longer catches the viewer's next message even if it reads the same\n"
                            + "Sections of Following below the live channels can be hidden: categories, Continue Watching, offline channels (interface settings)\n"
                            + "Following can show the watch streak tag on channels where the streak isn't extended yet\n"
                            + "The dark icon also shows in launchers that kept the old purple one\n"
                            + "The navigation rail no longer shows through by the camera cutout while the keyboard is open\n"
                            + "Following opens right away at launch: on slow phones Live showed for a couple of seconds first\n"
                            + "Coloured /me and @mentions in the viewer's colour and paint, as in RTE (chat settings, off by default)\n"
                            + "Chat images load with a browser User-Agent: some image hosts refuse apps\n"
                            + "Pull to refresh on Following updates stream previews, not only an app restart\n"
                            + "Chat messages can show up right away and one by one, as on the web: Twitch collects them and shows a batch once a second, up to a second late (chat settings, off by default)\n"
                            + "\"Smooth message appearance\" is now \"Animate new messages\" and is off by default: in a fast chat the animations queued up and messages arrived in jerks"},
            {"0.4.8-alpha",
                    "«Расширять боковой чат, пока открыт опрос или прогноз» работает и на экране итога прогноза (выигрыш или проигрыш), и в подробностях ставок; «Назад» закрывает и их",
                    "\"Widen the side chat while a poll or prediction is open\" also covers the prediction result screen (won or lost) and the bet details; Back closes them too"},
            {"0.4.7-alpha",
                    "«Назад» (и жест назад) в полноэкранном режиме закрывает прогноз или опрос, открытый в боковом чате в окне баллов канала (с «‹ Прогноз ✕» сверху); раньше «Назад» в этом окне не делал ничего. Если закрывать нечего, «Назад» работает как в Twitch. Работает вместе с «Расширять боковой чат, пока открыт опрос или прогноз» (настройки плеера)\n"
                            + "Сообщения за баллы канала и другие сообщения-плашки больше не пропадают из чата целиком, когда автора банят или сообщение удаляют: текст остаётся серым или зачёркнутым, как у обычных удалённых (кроме режима «как в Twitch»)\n"
                            + "Диагностика > «Запись банов и удалённых сообщений в файл»: баны, отстранения и удаления в чате и что после них стало со строками этого пользователя - включите, если сообщение забаненного пропадает из чата целиком, и пришлите файл из «Загрузок»",
                    "Back (and the back gesture) in fullscreen closes a prediction or poll opened in the side chat's channel points window (\"‹ Prediction ✕\" on top); Back did nothing there before. With nothing to close, Back works as in Twitch. Works together with \"Widen the side chat while a poll or prediction is open\" (player settings)\n"
                            + "Channel points messages and other notice-style messages no longer vanish from chat when their author is banned or the message is deleted: the text stays grey or struck through like other deleted messages (except in Twitch's own mode)\n"
                            + "Diagnostics, \"Log bans and deleted messages to a file\": chat bans, timeouts and deletions and what became of that user's lines after them -- turn it on if a banned user's message vanishes from chat entirely, and send the file from Downloads"},
            {"0.4.6-alpha",
                    "AutoMod в чате как в режиме модератора: причина, задержанное сообщение на красном фоне и кнопки «Разрешить • Отклонить» прямо в строке, без отдельного окна; после решения (вашего или другого модератора) кнопки зачёркиваются\n"
                            + "AutoMod в чате: причина по-человечески («запрещённое слово канала») вместо BlockedTermCaughtMessageReason\n"
                            + "Модификаторы смайлов (BTTV w!, h!, v!, z!, c!, l!, r!, p!, s! и FFZ ffzW, ffzX, ffzY, ffzCursed) больше не показываются отдельной картинкой, пока грузится смайл, к которому они относятся, а нарисованные так раньше (например, в истории чата при входе) встают на место, когда смайл загрузится\n"
                            + "Полоска между плеером и чатом больше не остаётся поверх параметров трансляции, если открыть их, пока видны кнопки плеера (в 0.4.2 это не было исправлено до конца)\n"
                            + "Вернулись фильтры звука (настройки плеера > Аудио, Android 9+, по умолчанию выключено): усиление громкости, басы, верхние частоты, разборчивость голоса и лимитер; работают с компрессором и без него, изменения и кнопка компрессора срабатывают сразу",
                    "AutoMod in chat like Twitch's mod mode: the reason, the held message on a red tint and Allow / Deny buttons right in the line, no dialog; once resolved (by you or another moderator) the buttons are struck through\n"
                            + "AutoMod in chat: a readable reason (\"the channel's blocked term\") instead of BlockedTermCaughtMessageReason\n"
                            + "Emote modifiers (BTTV w!, h!, v!, z!, c!, l!, r!, p!, s! and FFZ ffzW, ffzX, ffzY, ffzCursed) no longer show as a separate image while the emote they modify is loading, and ones already drawn that way (chat history on join, for example) join their emote once it loads\n"
                            + "The handle between the player and chat no longer stays on top of the stream settings when they are opened while the player controls are shown (0.4.2 didn't fully fix it)\n"
                            + "Audio filters are back (player settings, Audio; Android 9+, off by default): volume boost, bass, treble, voice clarity and a limiter; they work with or without the compressor, and changes and the compressor button apply at once"},
            {"0.4.3-alpha",
                    "AutoMod в чате: нажатие на строку задержанного сообщения открывает «Разрешить / Отклонить» (в 0.4.2 нажатие не срабатывало)\n"
                            + "AutoMod в чате: у сообщений с запрещёнными словами больше нет «(unknown 0)»",
                    "AutoMod in chat: tapping a held message's line opens Allow / Deny (the tap did nothing in 0.4.2)\n"
                            + "AutoMod in chat: messages held for blocked terms no longer say \"(unknown 0)\""},
            {"0.4.2-alpha",
                    "VOD скачивается в несколько раз быстрее: части видео качаются по 8 одновременно, оборвавшаяся часть перекачивается\n"
                            + "В уведомлении о скачивании VOD - сколько скачано, примерный размер файла и скорость\n"
                            + "Дата и время VOD в списках и на полках канала вместо «1 нед. назад» (настройки плеера > VOD, по умолчанию выключено)\n"
                            + "Фильтр по категории во вкладке «Видео» канала, как в веб-версии: кнопка над списком, поиск по всем категориям Twitch (до ввода - категории канала); фильтруются прошедшие трансляции, яркие моменты, загрузки и «Просмотреть все» (настройки плеера > VOD, по умолчанию выключено)\n"
                            + "Чат VOD не прокручивается вниз сам, пока вы читаете сообщения выше (настройки чата, по умолчанию выключено)\n"
                            + "Полоска между плеером и чатом больше не рисуется поверх параметров трансляции; если её выключить, выбранная ширина чата остаётся\n"
                            + "Качество по умолчанию не переставляется заново, если уже играет нужное, а скорость VOD сохраняется при смене качества\n"
                            + "Качество по умолчанию больше не перебивает «Только звук» и не меняется, пока приложение в фоне (фоновое воспроизведение)\n"
                            + "AutoMod в чате для модераторов: задержанные сообщения появляются строкой, нажатие - разрешить или отклонить, итог тоже пишется в чат (настройки чата, по умолчанию выключено)",
                    "VODs download several times faster: 8 parts of the video at once, and a part that fails is fetched again\n"
                            + "The VOD download notification shows how much is done, the estimated file size and the speed\n"
                            + "VOD date and time in lists and channel shelves instead of \"1 week ago\" (player settings, VOD; off by default)\n"
                            + "Category filter in a channel's Videos tab, as on the web: a button above the list, a search over all Twitch categories (the channel's own before you type); filters past broadcasts, highlights, uploads and \"View all\" (player settings, VOD; off by default)\n"
                            + "VOD chat no longer scrolls down by itself while you read older messages (chat settings, off by default)\n"
                            + "The handle between the player and chat no longer covers the stream settings; turning it off keeps the chat width you picked\n"
                            + "The default quality isn't set again when it is already playing, and the VOD speed is kept when the quality changes\n"
                            + "The default quality no longer overrides \"Audio only\" and isn't changed while the app is in the background (background playback)\n"
                            + "AutoMod in chat for moderators: held messages show up as chat lines, tap to allow or deny, and the outcome is posted too (chat settings, off by default)"},
            {"0.4.1-alpha",
                    "«Display-реклама, промо-карточки и headliner в ленте» (Реклама и прокси) убирает и рекламный баннер над лентой «Отслеживаемое», и дисплейную рекламу в ленте «В эфире» новой главной",
                    "\"Display ads, sponsored cards and the Following headliner\" (Ads & proxy) now also removes the ad banner above the Following feed and the display ads in the Live feed of the new home screen"},
            {"0.4.0-alpha",
                    "Чат через прокси ReYohoho: «Реклама и прокси», по умолчанию выключено. Если прокси не отвечает, чат на 10 минут подключается напрямую, и об этом пишется в чат\n"
                            + "Скачивание VOD и клипов: кнопка в плеере VOD и в ленте клипов (настройки плеера, по умолчанию выключено). Сразу после нажатия - выбор качества; имя файла - канал и название; файлы - в Загрузки/ReTwitch\n"
                            + "Раскраски ников: раскраски-картинки (в том числе анимированные) и тени; у кого нет раскраски RTE, берётся раскраска 7TV\n"
                            + "Модификаторы и zero-width эмоуты (w!, h!, наложения) работают и в истории чата при входе, и в VOD\n"
                            + "Анимированные эмоуты с конечным числом повторов останавливаются на последнем кадре, а не начинаются заново с каждым новым сообщением\n"
                            + "Удалённые сообщения (в том числе забаненных) по умолчанию видны серым. Кто уже выбрал режим, у того ничего не меняется\n"
                            + "Страница «RTE» в настройках: статус подписки RTE и друзья RTE - список друзей и где они смотрят, сообщение друзьям, какой стрим вы смотрите (по умолчанию выключено). Регистрация в RTE отправляется при каждом запуске\n"
                            + "Кнопка «Сбросить все настройки мода» (Резервная копия настроек); избранные эмоуты и смещения чата VOD сохраняются\n"
                            + "«Назад» (и жест назад) в полноэкранном режиме сворачивает открытый в боковом чате прогноз или опрос и возвращает обычную ширину чата\n"
                            + "Тёмная иконка приложения и тёмный экран запуска с надписью ReTwitch; стрелка «назад» в настройках мода - в стиле Twitch",
                    "Chat through the ReYohoho proxy: Ads & proxy, off by default. While the proxy doesn't answer, chat connects directly for 10 minutes and says so in the chat\n"
                            + "VOD and clip download: a button in the VOD player and the clip feed (player settings, off by default). The quality is picked right after the tap; the file is named after the channel and the title, in Downloads/ReTwitch\n"
                            + "Nick paints: image paints (animated ones too) and shadows; users without an RTE paint get their 7TV one\n"
                            + "Modifiers and zero-width emotes (w!, h!, overlays) also work in the chat history on join and in VODs\n"
                            + "Animated emotes with a finite loop stop on their last frame instead of restarting with every new message\n"
                            + "Deleted messages (banned users' too) show in grey by default; a mode you already picked stays\n"
                            + "An RTE settings page: the RTE subscription status and RTE friends -- your friends and where they watch, telling friends which stream you watch (off by default). RTE registration is sent on every start\n"
                            + "A \"Reset all mod settings\" button (Settings backup); favorite emotes and VOD chat offsets are kept\n"
                            + "Back (and the back gesture) in fullscreen collapses a prediction or poll opened in the side chat and gives the chat its width back\n"
                            + "Dark app icon and a dark launch screen saying ReTwitch; the back arrow in the mod settings in Twitch's style"},
            {"0.3.9-alpha",
                    "Аудиокомпрессор перенастроен: громкое и тихое выравниваются мягче (раньше давил громкое сильнее и поднимал тихое)\n"
                            + "Кнопка компрессора снова выключает и включает его сразу, без перезахода на стрим\n"
                            + "Убраны фильтры звука из настроек плеера (усиление, басы, верхние частоты, голос, лимитер) - остался только компрессор\n"
                            + "Баланс баллов канала можно показывать рядом с иконкой и в полноэкранном режиме (Интерфейс > поле ввода, по умолчанию выключено)\n"
                            + "Задержка в поле ввода чата - просто секунды («5.5 с»), без слова «Задержка»: в узкой колонке чата оно обрезалось\n"
                            + "С включённым делителем чата в «Вертикальном виде» кнопки и меню плеера снова на всю ширину (делитель сжимал их в левую треть экрана), а в мини-плеере снова есть кнопки «развернуть», «пауза» и «закрыть»\n"
                            + "Окно со ссылками на Boosty и Telegram при первом запуске больше не пропадает под экраном входа Twitch - показывается на нём заново\n"
                            + "Жесты яркости и громкости можно включить и в «Вертикальном виде» (вертикальные стримы в портрете) - отдельный пункт в настройках плеера, по умолчанию выключен",
                    "The audio compressor is retuned: loud and quiet parts are evened out more gently (it used to squash loud parts harder and lift quiet ones)\n"
                            + "The compressor button turns it on and off right away again, no need to reopen the stream\n"
                            + "Removed the audio filters from the player settings (boost, bass, treble, voice, limiter): only the compressor is left\n"
                            + "The channel points balance can show next to the icon in fullscreen too (Interface > chat input, off by default)\n"
                            + "The latency in the chat input is just the seconds (\"5.5 s\"), without the word \"Latency\" that got cut off in the narrow side chat\n"
                            + "With the chat divider on, the player buttons and menu in Vertical view span the full width again (the divider squeezed them into the left third), and the mini player has its expand, pause and close buttons back\n"
                            + "The Boosty / Telegram window on the first launch no longer disappears under Twitch's sign-in screen: it comes up again on it\n"
                            + "Brightness and volume gestures can also be turned on in Vertical view (portrait streams): a separate switch in the player settings, off by default"},
            {"0.3.8-alpha",
                    "VOD для подписчиков: исходное качество называется как у Twitch (например, 1080p60) и выбирается качеством по умолчанию - раньше вместо него включалось 720p60\n"
                            + "TwitchNoSub - в благодарностях в «О моде»",
                    "Sub-only VODs: the source quality is named like Twitch's (1080p60, for example) and is picked as the default quality, where 720p60 played before\n"
                            + "TwitchNoSub is in the acknowledgements under About"},
            {"0.3.7-alpha",
                    "VOD «только для подписчиков» можно смотреть без подписки: замок в списках пропадает, а если Twitch отказывает в плейлисте, мод собирает его из CDN записей (настройки плеера > VOD, по умолчанию выключено). Не работает в списках VOD на экранах React Native\n"
                            + "Гигантские эмоуты FFZ за баллы срабатывают и в наградах, полученных до того, как мод загрузил награды канала (история чата при входе)",
                    "Sub-only VODs play without a sub: the lock in VOD lists goes away, and when Twitch refuses the playlist the mod builds one from the VOD CDN (player settings > VODs, off by default). Not in VOD lists on React Native screens\n"
                            + "FFZ giant emote rewards also work on redemptions that arrived before the mod loaded the channel's rewards (chat history on join)"},
            {"0.3.6-alpha",
                    "«Отслеживаемое» по умолчанию: после возврата на главную (например, из настроек) она больше не перескакивает на «В эфире»",
                    "Following by default: coming back to the home screen (from settings, for example) no longer jumps to Live"},
            {"0.3.5-alpha",
                    "Шапка чата: отдельно скрываются заголовок «Чат» и кнопки «Подписаться» / «Сделать подарок» (подарок - и отдельно); если в шапке ничего не осталось, она прячется целиком вместе с тонкой линией под ней - отдельный пункт для линии из 0.3.4 убран",
                    "Chat header: the \"Chat\" title and the Subscribe / Gift a Sub buttons hide on their own (Gift also alone); a header left with nothing in it goes away whole with the thin line under it, so the 0.3.4 line switch is gone"},
            {"0.3.4-alpha",
                    "Шапка чата в настройках разобрана по порядку на экране: шапка целиком, «Сделать подарок», расширения, таблицы лидеров и новый пункт - тонкая линия под шапкой\n"
                            + "Серая полоса над сообщениями - это платные закреплённые сообщения: пункт первым в новом разделе «Над сообщениями: закрепы и баннеры», убирает и пустую полосу\n"
                            + "Убран пункт 0.3.3 «серая полоса под шапкой»: он дублировал «таблицы лидеров» и «расширения»",
                    "The chat header settings follow the screen order: whole header, Gift a Sub, extensions, leaderboards, and a new switch for the thin line under the header\n"
                            + "The grey strip above the messages is paid pinned messages: its switch comes first in the new \"Above the messages: pins and banners\" section and removes the strip even when empty\n"
                            + "Removed the 0.3.3 \"grey strip under the header\" switch: it duplicated leaderboards and extensions"},
            {"0.3.3-alpha",
                    "Значки VIP, модератора, стримера, Prime и другие глобальные снова видны в чате: Twitch 31.9.0 откладывал их загрузку при запуске до сигнала, который с модом не приходил («Исправления», включено)\n"
                            + "Настройки «Интерфейс» разложены по местам на экране: плеер, шапка чата (с полосой под ней), баннеры над чатом (переехали со страницы «Чат»), поле ввода, навигация, промо\n"
                            + "«Сделать подарок» - отдельный переключатель рядом с шапкой (прежний вариант «Без кнопки „Сделать подарок“» переносится сам)\n"
                            + "Скрытие всей серой полосы под шапкой чата (таблицы лидеров и расширения)",
                    "VIP, moderator, broadcaster, Prime and other global badges show in chat again: Twitch 31.9.0 put off loading them at launch until a signal that never came with the mod (Fixes, on)\n"
                            + "Interface settings are grouped by where things are on screen: player, chat header (with the strip under it), banners above chat (moved from the Chat page), chat input, navigation, promotions\n"
                            + "Gift a Sub is its own switch next to the header (the old \"without Gift a Sub\" choice carries over)\n"
                            + "Hide the whole grey strip under the chat header (leaderboards and extensions)"},
            {"0.3.2-alpha",
                    "Скрытие кнопок «На весь экран» и «Режим чата» в плеере (настройки интерфейса)\n"
                            + "Курсор в поле ввода чата больше не мигает после закрытия клавиатуры (Android 11+, «Исправления»)\n"
                            + "Метка NEW в настройках - только у пунктов текущей версии",
                    "Hide the Full screen and Chat mode buttons in the player (interface settings)\n"
                            + "The chat input cursor no longer blinks after the keyboard is closed (Android 11+, Fixes)\n"
                            + "The NEW badge in settings marks only what the current version added"},
            {"0.3.0-alpha",
                    "Обновление на Twitch 31.9.0\n"
                            + "Прыжок VOD к началу после рекламы Twitch 31.9.0 исправил сам - исправление мода для него больше не нужно",
                    "Updated to Twitch 31.9.0\n"
                            + "Twitch 31.9.0 fixed VODs jumping back to the start after an ad on its own, so the mod's fix is no longer needed there"},
            {"0.2.4-alpha",
                    "Сборка на новом патчере Morphe 1.20: ресурсы Twitch (разметка, строки) остаются как в оригинале, а не перекодируются при сборке",
                    "Built with the newer Morphe 1.20 patcher: Twitch's resources (layouts, strings) stay as in the original app instead of being re-encoded at build time"},
            {"0.2.3-alpha",
                    "Боковой чат в полноэкранном режиме расширяется до половины экрана, пока открыт опрос или прогноз - видно варианты и проценты (настройки плеера, по умолчанию выключено)",
                    "The fullscreen side chat widens to half the screen while a poll or prediction is open, so options and percentages fit (player settings, off by default)"},
            {"0.2.2-alpha",
                    "Мод-логи в чате заработали: баны, отстранения (с длительностью) и удалённые сообщения видны всем зрителям, модераторы видят, кто это сделал\n"
                            + "Мод-логи - один переключатель вместо трёх (включите заново, если включали в 0.2.1)\n"
                            + "Заработали и другие строки мода в чате: «Модератор очистил чат» и строка о рейде\n"
                            + "Меньше нагрузки на интерфейс: хуки мода не срабатывают дважды и не обходят всё дерево экрана на каждом проходе - меньше подтормаживаний при активном чате",
                    "Mod logs in chat now work: bans, timeouts (with the length) and deleted messages for every viewer, moderators also see who did it\n"
                            + "Mod logs are one switch instead of three (turn it on again if you used them in 0.2.1)\n"
                            + "Other mod lines in chat work too: \"A moderator cleared the chat\" and the raid line\n"
                            + "Less UI load: mod hooks no longer run twice or walk the whole screen tree on every layout pass, so fewer stutters in busy chats"},
            {"0.2.1-alpha",
                    "Строки в чате о банах, отстранениях (с длительностью) и удалённых сообщениях - для всех зрителей\n"
                            + "Кнопка отражения видео по горизонтали в плеере (live и VOD)\n"
                            + "«Диагностика» > «Запись подвисаний интерфейса»: лог для отчётов о тормозах\n"
                            + "Превью стримов в «Отслеживаемом» обновляются: по pull-to-refresh и не реже раза в минуту",
                    "Chat lines about bans, timeouts (with the length) and deleted messages for every viewer\n"
                            + "Mirror video (horizontal flip) button in the player (live and VOD)\n"
                            + "Diagnostics > Log UI stalls: a log file for lag reports\n"
                            + "Live stream previews on Following refresh: on pull-to-refresh and at least once a minute"},
            {"0.2.0-alpha",
                    "Настройки перестроены: разделы «Реклама и прокси», «Исправления», «Диагностика», «Резервная копия»; всё новое отмечено NEW\n"
                            + "Шапка чата - один выбор: показывать, без кнопки «Сделать подарок», скрывать в вертикальном, в полноэкранном или везде\n"
                            + "VAFT: нет фриза, когда часы резервного потока отстают; поток другой сессии встаёт без шва; больше признаков рекламы; HEVC-стримы\n"
                            + "Плашка «Реклама блокируется» на видео и подробный лог блокировки рекламы в файл\n"
                            + "Если прокси трансляций не отвечает, стрим открывается напрямую с Twitch\n"
                            + "Чат: метки времени, значки FFZ/Chatterino/DankChat/Chatsen/Homies, раскраска ников RTE, формат ника\n"
                            + "Удалённые сообщения сразу текстом: зачёркнутые, серые или обычные\n"
                            + "Упоминания: свой цвет подсветки, длительность вибрации, подсветка по своим словам и никам\n"
                            + "Чат не очищается по /clear модератора, чёрный список слов и пользователей, скрытие служебных сообщений\n"
                            + "Закреплённые сообщения: скрывать сразу или через 30 с; скрытие платных закреплённых сообщений\n"
                            + "Строка о рейде для пришедших с рейдом, мод-логи для модераторов\n"
                            + "Плеер: статистика (качество, битрейт, fps, буфер), задержка в поле ввода чата, обычная задержка\n"
                            + "VOD: смещение чата, шаг перемотки, без автоплея следующего видео\n"
                            + "Звук: усиление, бас, верхние частоты, разборчивость голоса, лимитер\n"
                            + "Таймер сна может ставить на паузу вместо закрытия\n"
                            + "Пикер: отправка и ввод при открытом пикере, Backspace удаляет эмоут целиком, избранное с эмоутами Twitch и по каналам\n"
                            + "Эмоуты: карточка с автором и ссылкой, «Часто используется» для эмоутов мода, качество, BTTV в WEBP, Homies\n"
                            + "Без предупреждения о ссылках; скрытие Follow/Subscribe, «Контент для взрослых», расширений, «Обзора», ввода One Chat, кнопки ⋮\n"
                            + "Исправлен сдвиг видео и чёрная полоса на VOD после смены ширины чата; скрытие тоста смены режима чата\n"
                            + "Исправлен вход «This app version/OS is not currently supported» (Play Integrity)\n"
                            + "Резервная копия настроек: сохранение в файл и загрузка",
                    "Settings regrouped: Ads and proxy, Fixes, Diagnostics, Backup pages; everything new is marked NEW\n"
                            + "Chat header is one choice: show, without Gift a Sub, hide in portrait, in fullscreen or everywhere\n"
                            + "VAFT: no freeze when the backup stream's clock lags; another session's stream joins without a seam; more ad markers; HEVC streams\n"
                            + "\"Blocking ads\" label on the video and a detailed ad-blocking log file\n"
                            + "If the stream proxy doesn't answer, the stream opens straight from Twitch\n"
                            + "Chat: timestamps, FFZ/Chatterino/DankChat/Chatsen/Homies badges, RTE nickname paints, nickname format\n"
                            + "Deleted messages shown as text right away: struck through, grey or plain\n"
                            + "Mentions: highlight colour, vibration length, highlight for your own words and nicknames\n"
                            + "Chat survives a moderator's /clear, word and user blacklist, hide service messages\n"
                            + "Pinned messages: hide at once or after 30 s; hide pinned cheers\n"
                            + "Raid line for viewers arriving with a raid, mod logs for moderators\n"
                            + "Player: stats (quality, bitrate, fps, buffer), latency in the chat input, normal latency\n"
                            + "VOD: chat offset, seek step, no autoplay of the next video\n"
                            + "Audio: gain, bass, treble, voice clarity, limiter\n"
                            + "The sleep timer can pause instead of closing the app\n"
                            + "Picker: send and type with the picker open, Backspace removes a whole emote, favorites with Twitch emotes and per channel\n"
                            + "Emotes: card with uploader and link, Frequently Used for mod emotes, quality, BTTV as WEBP, Homies\n"
                            + "No link warning; hide Follow/Subscribe, Mature content, extensions, Browse, One Chat input, the chat ⋮ button\n"
                            + "Fixed the shifted video and black band on VODs after resizing the chat; hide the chat mode toast\n"
                            + "Fixed the \"This app version/OS is not currently supported\" login error (Play Integrity)\n"
                            + "Settings backup: save to a file and restore"},
            {"0.1.4-alpha",
                    "Скрытие полосы над чатом с кнопкой «Подписаться» в вертикальном режиме",
                    "Hide the bar with the Subscribe button above chat in portrait"},
            {"0.1.3-alpha",
                    "Избранные эмоуты - в самом верху пикера и появляются без перезапуска приложения\n"
                            + "«Копировать и отправить» закрывает меню и сразу отправляет сообщение",
                    "Favorite emotes sit at the very top of the picker and show up without restarting the app\n"
                            + "\"Copy and send\" closes the menu and sends the message right away"},
            {"0.1.2-alpha",
                    "Подсветка и вибрация при упоминании через @ снова работают (сломались в 0.1.0)\n"
                            + "VAFT с низкой задержкой: картинка больше не замирает (при идущем звуке) при переключении на резервный поток и после вырезанной рекламы\n"
                            + "Скрытие кнопки «Сделать подарок» над чатом\n"
                            + "Размер эмоутов 7TV/BTTV/FFZ в чате\n"
                            + "Избранные эмоуты 7TV/BTTV/FFZ наверху пикера: долгое нажатие добавляет или убирает\n"
                            + "«Копировать и отправить» в меню сообщения по долгому нажатию\n"
                            + "У раскрытого удалённого сообщения снова видны превью ссылок и эмоуты",
                    "@mention highlight and vibration work again (broken since 0.1.0)\n"
                            + "VAFT with low latency: the picture no longer freezes (with the audio going on) on a switch to a backup stream and after stripped ads\n"
                            + "Hide the Gift a Sub button above chat\n"
                            + "7TV/BTTV/FFZ emote size in chat\n"
                            + "Favorite 7TV/BTTV/FFZ emotes on top of the picker: a long press adds or removes one\n"
                            + "\"Copy and send\" in the message long-press menu\n"
                            + "A revealed deleted message shows its link previews and emotes again"},
            {"0.1.1-alpha",
                    "Низкая задержка трансляции с VAFT: плеер снова узнаёт поток Twitch, нет ложной «рекламы» после смены источника и повтора ~30 с на каждое переключение на резервный поток и обратно\n"
                            + "Задержка трансляции на плеере\n"
                            + "Качество по умолчанию больше не сбрасывается на «Авто», а меню качества в плеере отмечает то, что реально играет\n"
                            + "Ширина чата сбоку не прыгает при показе кнопок плеера\n"
                            + "Скрытие кнопок Cast, «Поделиться», клипа, Bits и лидербордов снова работает; «Создать» и «Активность» скрываются без пустого места\n"
                            + "Предложения Turbo скрываются и в ленте («Попробуйте без рекламы»), и в «Отслеживаемом»\n"
                            + "Жесты плеера не срабатывают сквозь настройки\n"
                            + "Скрытие шапки и поля ввода чата в полноэкранном режиме; на время набора там чат шире и над клавиатурой\n"
                            + "Размер текста сообщений чата\n"
                            + "Курсор в поле ввода снова проходит через картинки эмоутов\n"
                            + "Каналы всегда открываются в классическом театре: переключатель нового театра на React Native убран (патчи мода в нём не работают)",
                    "Low stream latency with VAFT: the player recognises the Twitch stream again, no false \"ad break\" after a source switch and no ~30 s replay on each switch to a backup stream and back\n"
                            + "Stream latency on the player\n"
                            + "The default quality no longer falls back to Auto, and the player's quality menu marks what actually plays\n"
                            + "The side chat no longer jumps in width when the player controls show\n"
                            + "Hiding Cast, Share, clip, Bits and leaderboards works again; Create and Activity hide without leaving a gap\n"
                            + "Turbo offers hide in the feed (\"Try ad-free\") and on Following too\n"
                            + "Player gestures no longer fire through the settings sheet\n"
                            + "Hide the chat header and input in fullscreen; while typing there the chat is wider and above the keyboard\n"
                            + "Chat message text size\n"
                            + "The cursor in the chat input moves past emote images again\n"
                            + "Channels always open in the classic theatre: the React Native theatre switch is gone (mod patches don't work there)"},
            {"0.1.0-alpha",
                    "Обновление на Twitch 31.8.0\n"
                            + "Блокировка рекламы в потоке (VAFT) прямо на устройстве: резервный поток или вырезание рекламы, выбор типа плеера\n"
                            + "Push-уведомления в пересобранном приложении (экспериментально) и статус FCM в настройках\n"
                            + "Превью картинок по ссылкам: режимы, размер, размытие, увеличение\n"
                            + "Фильтры чата: объявления, подписки, награды за баллы, серии просмотров, нативные GIF\n"
                            + "Скрытие баннеров над чатом (Hype Train, прогнозы, рейды, Shoutout, Drops, подарки, Shared Chat) и кнопок (Cast, клип, поделиться, Bits, лидерборды)\n"
                            + "Display-реклама и headliner в ленте\n"
                            + "Плеер: качество по умолчанию для Wi-Fi и мобильной сети, автоперезагрузка при зависании, жесты яркости и громкости\n"
                            + "Аудиокомпрессор с кнопкой в плеере, ambilight, главы VOD\n"
                            + "Эмоуты 7TV/BTTV/FFZ снова в пикере, скрытие сообщений ботов, вкладок «Активность» и «Создать», блокировка трекеров\n"
                            + "Исправлены модификаторы эмоутов BTTV (w! h! v! z! c! l! r! p! s!) и FFZ (ffzW ffzX ffzY ffzCursed)\n"
                            + "Zero-width эмоуты больше не налезают на никнейм\n"
                            + "Каналы открываются в классическом театре вместо нового на React Native, где не работали эмоуты, значки, кнопки плеера, прокси и VAFT (вернуть новый - в «Интерфейс»)\n"
                            + "Вкладка «Отслеживаемое» по умолчанию на новой главной и автодополнение эмоутов 7TV/BTTV/FFZ в поле ввода\n"
                            + "Прокси трансляций и анлок качества снова работают в Twitch 31.x\n"
                            + "Значки RTE снова видны в чате (31.x хранит id отправителя иначе)\n"
                            + "Новый экран настроек мода вместо цепочки диалогов\n"
                            + "Подсветка первого сообщения зрителя и гигантские эмоуты за награды FFZ:GE\n"
                            + "Удалённые сообщения снова раскрываются по нажатию\n"
                            + "VAFT: правильная нумерация сегментов и разрывы при вырезании рекламы (меньше зависаний после рекламы)\n"
                            + "Скрытие предложений Turbo и баннеров скидок на подписку, картинки эмоутов в поле ввода\n"
                            + "Перезапущенный стрим сразу играет новый поток (Twitch держал старый мастер-плейлист до 5 минут)\n"
                            + "Эмоуты 7TV/BTTV/FFZ в сообщениях с наградами за баллы\n"
                            + "История изменений в «О моде»",
                    "Updated to Twitch 31.8.0\n"
                            + "On-device in-stream ad blocking (VAFT): backup stream or ad stripping, player type choice\n"
                            + "Push notifications in the re-signed app (experimental) and FCM status in settings\n"
                            + "RTE-style link image previews: modes, size, blur, enlarge\n"
                            + "Chat filters: announcements, sub notices, channel points redemptions, watch streaks, native GIFs\n"
                            + "Hide banners above chat (Hype Train, predictions, raids, shoutouts, Drops, gifts, Shared Chat) and buttons (Cast, clip, share, Bits, leaderboards)\n"
                            + "Display ads and the Following headliner\n"
                            + "Player: default quality for Wi-Fi and cellular, auto-reload when stuck, brightness and volume gestures\n"
                            + "Audio compressor with a player button, ambilight, VOD chapters\n"
                            + "7TV/BTTV/FFZ emotes back in the picker, hide bot messages, the Activity and Create tabs, tracker blocking\n"
                            + "Fixed BTTV (w! h! v! z! c! l! r! p! s!) and FFZ (ffzW ffzX ffzY ffzCursed) emote modifiers\n"
                            + "Zero-width emotes no longer overlap the username\n"
                            + "Channels open in the classic theatre instead of the new React Native one, where emotes, badges, player buttons, proxies and VAFT didn't work (the new one can be restored under Interface)\n"
                            + "Following tab by default on the new home screen and 7TV/BTTV/FFZ emote autocomplete in the chat input\n"
                            + "Stream proxies and quality unlock work again on Twitch 31.x\n"
                            + "RTE badges show in chat again (31.x stores the sender id differently)\n"
                            + "New mod settings screen instead of a chain of dialogs\n"
                            + "First-message highlight and giant emotes for FFZ:GE rewards\n"
                            + "Deleted messages reveal on tap again\n"
                            + "VAFT: correct segment numbering and discontinuities when stripping ads (fewer stalls after ads)\n"
                            + "Hide Turbo offers and subscription discount banners, emote images in the chat input\n"
                            + "A restarted stream plays the new broadcast right away (Twitch reused the old master playlist for up to 5 minutes)\n"
                            + "7TV/BTTV/FFZ emotes in channel-points reward messages\n"
                            + "Changelog in \"About\""},
            {"0.0.13-alpha",
                    "Прокси эмоутов: сначала ext.rte\n"
                            + "Исправлен краш рендера эмоутов на Android 10/11\n"
                            + "Исправлены стили и краши спанов после пересборки",
                    "Emote proxy: ext.rte first\n"
                            + "Fixed emote rendering crash on Android 10/11\n"
                            + "Fixed span styles and crashes after rebuild"},
            {"0.0.12-alpha",
                    "Исправлены тормоза плеера с разделителем чата\n"
                            + "Исправлены ошибки PiP",
                    "Fixed slow player with the chat divider\n"
                            + "Fixed PiP errors"},
            {"0.0.11-alpha",
                    "Разделитель плеер/чат в театральном режиме\n"
                            + "Таймер сна\n"
                            + "Кнопка перезагрузки плеера\n"
                            + "Значки RTE в чате",
                    "Player/chat divider in theatre mode\n"
                            + "Sleep timer\n"
                            + "Player reload button\n"
                            + "RTE chat badges"},
            {"0.0.10-alpha",
                    "Отчёты о сбоях (Bugsnag)",
                    "Crash reporting (Bugsnag)"},
            {"0.0.9-alpha",
                    "Перевод настроек\n"
                            + "Вкладка «Отслеживаемые» по умолчанию\n"
                            + "Автосбор бонусных баллов канала\n"
                            + "Показ удалённых сообщений\n"
                            + "Фиксированная прокрутка чата и анимация новых сообщений\n"
                            + "Подсветка и вибрация при упоминании\n"
                            + "Карточка эмоута по нажатию\n"
                            + "Автодополнение без двоеточия\n"
                            + "Исправлены прокси качества и VOD",
                    "Translated settings\n"
                            + "Following tab by default\n"
                            + "Auto-claim bonus channel points\n"
                            + "Show deleted messages\n"
                            + "Sticky chat scroll and animated new messages\n"
                            + "Mention highlight and vibration\n"
                            + "Emote info card on tap\n"
                            + "Autocomplete without a colon\n"
                            + "Fixed quality proxies and VODs"},
            {"0.0.8-alpha",
                    "Zero-width эмоуты\n"
                            + "Управление кэшем эмоутов\n"
                            + "Исправлен сброс позиции VOD после рекламы\n"
                            + "Плеер под вырез камеры в альбомной ориентации",
                    "Zero-width emotes\n"
                            + "Emote cache management\n"
                            + "Fixed VOD position reset after an ad\n"
                            + "Player under the camera cutout in landscape"},
            {"0.0.7-alpha",
                    "Первый релиз: эмоуты 7TV/BTTV/FFZ в чате, пикере и автодополнении, прокси для трансляций",
                    "First release: 7TV/BTTV/FFZ emotes in chat, picker and autocomplete, stream proxies"},
    };

    private Changelog() {
    }

    /** @return the changelog as HTML for {@code Html.fromHtml}. */
    public static String html() {
        StringBuilder sb = new StringBuilder();
        for (String[] entry : ENTRIES) {
            sb.append("<br>");
            entry(sb, entry[0], Strings.pick(entry[1], entry[2]), entry[0].equals(BuildInfo.VERSION_NAME));
            sb.append("<br>");
        }
        return sb.toString();
    }

    /** A version in bold and its items, one per line, as bullets; the update dialog shows them the same way. */
    static void entry(StringBuilder sb, String version, String items, boolean isNew) {
        sb.append("<b>").append(TextUtils.htmlEncode(version)).append("</b>");
        if (isNew) {
            sb.append(" <b><font color=\"#9147FF\">NEW</font></b>");
        }
        for (String item : items.split("\n")) {
            if (!item.trim().isEmpty()) { // a version can go out with nothing to tell
                sb.append("<br>• ").append(TextUtils.htmlEncode(item));
            }
        }
    }
}
