package tv.retwitch.mod;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import io.appmetrica.analytics.AppMetrica;
import io.appmetrica.analytics.AppMetricaConfig;

/**
 * App-wide crash reporting and usage statistics via AppMetrica.
 *
 * <p>Installed once per process from the injected {@code
 * TwitchApplication.onCreate} (see {@code CrashReporterPatch.kt}), the same way
 * {@link DisplayCutoutHook} is. {@code AppMetrica.activate} with the
 * Application registers a process-wide uncaught-exception handler (chained to
 * the previous one, so Twitch's own handling still runs) and tracks sessions
 * from activity lifecycle, so the dashboard shows active users per mod version
 * without any events of our own. Crashes and errors do not count towards the
 * AppMetrica event limits (Bugsnag's free plan ran out).
 *
 * <p>Only Java/Kotlin crashes: native crashes need the ndk-crashes module's
 * {@code .so}, which this dex-only build can't ship. Which AppMetrica modules
 * are vendored and why: {@code APPMETRICA_JAR_NAMES} in
 * {@code kitchen/mod_builder.py}. No advertising id and no location: those
 * modules are not in the build, and location tracking is off in the config.
 *
 * <p>Reached from injected smali with no surrounding try/catch, so
 * {@link #install(Application)} swallows every {@link Throwable}: a throw on
 * launch would take down the whole app.
 *
 * <p>{@link #report(String, String, Throwable)} forwards exceptions the mod's
 * own {@code try/catch(Throwable)} guards silence; they never reach the uncaught
 * handler. AppMetrica keeps them under "Errors", apart from crashes, grouped by
 * the {@code "tag: where"} identifier.
 */
public final class CrashReporter {

    private static final String TAG = "RTcrash";

    /**
     * AppMetrica application API key. Client-side key (it only lets the app
     * send to this AppMetrica application), safe to embed. Empty disables
     * reporting.
     */
    private static final String API_KEY = "";

    /** Set once {@link #install(Application)} runs (regardless of outcome). */
    private static volatile boolean installed;

    /** Set only after {@code AppMetrica.activate} succeeded; {@link #report} is a no-op before. */
    private static volatile boolean started;

    private CrashReporter() {
    }

    /**
     * Called once from the injected {@code TwitchApplication.onCreate}.
     *
     * @param application the process Application (smali passes {@code p0})
     */
    public static void install(Application application) {
        try {
            if (installed || application == null) {
                return;
            }
            installed = true;

            if (API_KEY.length() != 36) {
                Log.w(TAG, "no AppMetrica API key; crash reporting disabled");
                return;
            }

            String twitchVersion = hostVersionName(application);
            AppMetricaConfig config = AppMetricaConfig.newConfigBuilder(API_KEY)
                    // Users per mod version is what the dashboard is for; Twitch's goes below.
                    .withAppVersion(BuildInfo.VERSION_NAME)
                    .withCrashReporting(true)
                    .withLocationTracking(false)
                    .withAdvIdentifiersTracking(false)
                    .withAppEnvironmentValue("twitchVersion", twitchVersion)
                    .withErrorEnvironmentValue("twitchVersion", twitchVersion)
                    // Device vs. resource locale: several crashes (e.g. the annotation-
                    // span NoSuchElementException, see AnnotationSpanGuard) are gated on
                    // which values-<locale>/strings.xml the app resolves, not on the
                    // hardware. AppMetrica records the device locale itself.
                    .withErrorEnvironmentValue("resourceLocale", resourceLocale(application))
                    .build();
            AppMetrica.activate(application, config);
            started = true;
            // AppMetrica profiles and crashes show the Twitch login. Read once per process:
            // a login or account switch shows from the next start.
            String login = TwitchAuth.getUsername();
            if (!login.isEmpty()) {
                AppMetrica.setUserProfileID(login);
            }

            Log.i(TAG, "AppMetrica started (modVersion=" + BuildInfo.VERSION_NAME + ")");
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
        }
    }

    /**
     * Forward an exception that one of our own {@code try/catch(Throwable)}
     * guards <em>silenced</em> (swallowed to keep Twitch alive) to AppMetrica's
     * errors, so it shows up even though it never reached the uncaught handler.
     *
     * <p>Call this <em>alongside</em> the existing {@code Log.w(TAG, "...", t)}
     * in a catch block; it never throws and is a no-op before AppMetrica has
     * started, so it is always safe to call from anywhere in the mod.
     *
     * @param tag   the calling class's log {@code TAG} (or any short scope
     *              label); prefix of the error group. May be {@code null}.
     * @param where a short human description of what failed -- reuse the same
     *              message passed to {@code Log.w}. May be {@code null}.
     * @param t     the silenced throwable; {@code null} is ignored.
     */
    public static void report(String tag, String where, Throwable t) {
        try {
            if (t == null || !started) {
                return;
            }
            String scope = tag != null ? tag : TAG;
            // The group name drops URLs and long numbers (timings, ids): each URL was its own group. The message keeps them.
            String identifier = where != null
                    ? scope + ": " + where.replaceAll("https?://\\S+", "<url>").replaceAll("\\d{2,}", "N") : scope;
            AppMetrica.reportError(identifier, where, t);
        } catch (Throwable ignored) {
            // Crash reporting must never itself take down the app.
        }
    }

    /**
     * Convenience overload for callers that don't have (or don't want to pass)
     * a separate {@code tag} -- {@code where} carries the full context.
     */
    public static void report(String where, Throwable t) {
        report(null, where, t);
    }

    /** Host (Twitch) APK versionName, e.g. {@code 31.9.0}; {@code "unknown"} if unreadable. */
    private static String hostVersionName(Application application) {
        try {
            PackageInfo info = application.getPackageManager()
                    .getPackageInfo(application.getPackageName(), 0);
            return info != null && info.versionName != null ? info.versionName : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * The locale the app's resources currently resolve to (which
     * {@code values-<locale>/strings.xml} {@code getText}/{@code getString}
     * read from), e.g. {@code pt_BR}. This is the config locale, not
     * {@link java.util.Locale#getDefault()} -- they can differ, and it's the
     * config one that decides which styled-string resource (and thus which
     * annotation span) is loaded. {@code "unknown"} if unreadable.
     */
    private static String resourceLocale(Application application) {
        try {
            android.content.res.Configuration config =
                    application.getResources().getConfiguration();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.os.LocaleList locales = config.getLocales();
                if (locales != null && !locales.isEmpty()) {
                    return locales.get(0).toString();
                }
            }
            if (config.locale != null) {
                return config.locale.toString();
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }
}
