# ReTwitch

An unofficial modification of the Twitch Android app, built with
[Morphe](https://github.com/MorpheApp). Not affiliated with or endorsed by Twitch.

This repository holds the source of the mod and the "kitchen" that builds it from
the original Twitch bundle. The Twitch app itself is not here: bring your own.

[Русский - ниже](#русский)

## What you need

- Linux or macOS, Python 3.10+, JDK 17+ (`java`, `javac`, `jar`, `keytool`)
- Android SDK with build-tools (`d8`, `apksigner`) and a platform (`android.jar`);
  set `ANDROID_HOME`
- The Twitch **31.9.0** bundle (versionCode 3109006) as a split `.apks` file

Check that the bundle is the original one before building:

```bash
unzip -p Twitch+31.9.0.apks base.apk > /tmp/base.apk
apksigner verify --print-certs /tmp/base.apk
```

The signer's SHA-256 digest must be
`1338f9b049893cc70b78432a177582f90bd4bc6296ea4ed35bcc7df59687ac53`.

## Build

```bash
cd kitchen
./rebuild.sh /path/to/Twitch+31.9.0.apks
```

The first run downloads the pinned tools and libraries into `kitchen/bin/` and
`kitchen/libs/` (`python3 -m kitchen.deps`, every file is checked by SHA-256).
The result is `kitchen/work/ReTwitch_<version>.apk`.

The first build also creates your signing key, `kitchen/keystore/retwitch-morphe.keystore`.
Keep it: later builds must be signed with the same key to install as updates. Your
build is signed with a different key than the official one, so uninstall the
official ReTwitch before installing it.

Crash reports are off in this source: `CrashReporter.API_KEY` is empty. Put your own
AppMetrica key there to get them.

## Bugs and ideas

[Open an issue](https://github.com/reyohoho/ReTwitch-src/issues/new/choose): the bug form says which of the mod's
logs to turn on, where the file lands and how to attach a screen recording and your settings.

## Layout

| Path | What |
|------|------|
| `kitchen/mod/src` | The mod's Java code, compiled into an extra dex |
| `kitchen/mod/test` | JVM self-checks for the pure logic |
| `kitchen/retwitch-patches` | Bytecode and resource patches (Kotlin, morphe-patcher) |
| `kitchen/kitchen` | Build helpers: bundle merge, extension dex, pinned downloads |
| `kitchen/i18n` | Translations; the key is the English text |

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). [NOTICE](NOTICE) lists the code this
project builds on and the additional terms under GPLv3 section 7: modified
versions must be marked as such and must not be distributed under the names
"ReTwitch" or "ReYohoho".

---

## Русский

Неофициальный мод приложения Twitch для Android, собирается на
[Morphe](https://github.com/MorpheApp). Twitch к нему отношения не имеет.

Здесь исходники мода и «кухня», которая собирает его из оригинального бандла Twitch.
Самого Twitch тут нет: бандл нужен свой.

**Что нужно:** Linux или macOS, Python 3.10+, JDK 17+, Android SDK с build-tools и
platform (`ANDROID_HOME`), бандл Twitch **31.9.0** (versionCode 3109006) в виде `.apks`.
Перед сборкой проверьте подпись (команда выше): SHA-256 подписанта -
`1338f9b049893cc70b78432a177582f90bd4bc6296ea4ed35bcc7df59687ac53`.

**Сборка:** `cd kitchen && ./rebuild.sh /путь/к/Twitch+31.9.0.apks`. Первый запуск скачает
инструменты и библиотеки (версии и SHA-256 зафиксированы в `kitchen/kitchen/deps.py`).
Результат - `kitchen/work/ReTwitch_<версия>.apk`.

Первая сборка создаёт ключ подписи `kitchen/keystore/retwitch-morphe.keystore`. Сохраните его:
обновления ставятся поверх только с тем же ключом. Ваша сборка подписана не тем ключом,
что официальная, поэтому официальный ReTwitch перед установкой нужно удалить.

Отчёты о сбоях в этих исходниках выключены (`CrashReporter.API_KEY` пустой).

**Баги и идеи** - в [Issues](https://github.com/reyohoho/ReTwitch-src/issues/new/choose): форма бага подскажет,
какой лог мода включить, где лежит файл и как приложить запись экрана и настройки.

**Лицензия:** GPL-3.0-or-later ([LICENSE](LICENSE)). В [NOTICE](NOTICE) - на чём построен
проект и дополнительные условия по §7 GPLv3: изменённые версии должны быть помечены как
изменённые и не могут распространяться под именами «ReTwitch» и «ReYohoho».
