package tv.retwitch.mod;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.net.URLConnection;
import java.util.Map;

/**
 * Lets push notifications register in the re-signed, renamed app.
 *
 * <p>Firebase Installations (the first step of FCM registration) checks the
 * {@code X-Android-Package} / {@code X-Android-Cert} request headers against
 * the API-key restrictions of Twitch's Firebase project. A re-signed app under
 * another package fails that check, so no push token is ever issued.
 * {@code PushRegistrationPatch} routes the Firebase Installations request
 * builder's header calls through {@link #addRequestProperty}, which presents
 * the original package and signing-certificate SHA-1 (same idea as
 * twitch-patched's notification patch).
 */
public final class PushRegistration {

    private static final String TWITCH_PACKAGE = "tv.twitch.android.app";
    /** SHA-1 of Twitch's release signing certificate (CN=Alec Lee, OU=Twitch). */
    private static final String TWITCH_CERT_SHA1 = "8C68C13822723A2B1FA844BED340031BEB1F9463";

    private PushRegistration() {
    }

    /** Drop-in for {@code connection.addRequestProperty(name, value)}; keeps its exceptions. */
    public static void addRequestProperty(URLConnection connection, String name, String value) {
        if ("X-Android-Cert".equals(name)) {
            value = TWITCH_CERT_SHA1;
        } else if ("X-Android-Package".equals(name)) {
            value = TWITCH_PACKAGE;
        }
        connection.addRequestProperty(name, value);
    }

    /**
     * Human-readable push status for the settings menu, read straight from
     * Firebase's own on-disk state (no hooks): Play services, notification
     * permission, the Firebase Installations registration
     * ({@code PersistedInstallation.*.json} in no-backup or files storage) and the FCM token
     * ({@code com.google.android.gms.appid} prefs).
     */
    static String statusText(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            String gms = context.getPackageManager().getPackageInfo("com.google.android.gms", 0).versionName;
            line(sb, "Google Play Services", true, gms);
        } catch (Throwable t) {
            line(sb, "Google Play Services", false, Strings.pick("не найдены", "not found"));
        }
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean enabled = nm != null && nm.areNotificationsEnabled();
            line(sb, Strings.pick("Разрешение на уведомления", "Notification permission"), enabled, null);
        } catch (Throwable t) {
            line(sb, Strings.pick("Разрешение на уведомления", "Notification permission"), false, t.toString());
        }
        try {
            JSONObject fis = readInstallation(context);
            if (fis == null) {
                line(sb, "Firebase Installations", false, Strings.pick("нет данных", "no data"));
            } else {
                int status = fis.optInt("Status", -1);
                String error = fis.optString("FisError", "");
                line(sb, "Firebase Installations", status == 3,
                        installationStatus(status) + (error.isEmpty() ? "" : " - " + error));
            }
        } catch (Throwable t) {
            line(sb, "Firebase Installations", false, t.toString());
        }
        try {
            String token = readFcmToken(context);
            line(sb, Strings.pick("FCM-токен", "FCM token"), token != null,
                    token != null ? token.substring(0, Math.min(12, token.length())) + "..."
                            : Strings.pick("не получен", "not issued"));
        } catch (Throwable t) {
            line(sb, Strings.pick("FCM-токен", "FCM token"), false, t.toString());
        }
        return sb.toString();
    }

    /** Whether Android 13+ still needs the runtime notification permission. */
    static boolean needsNotificationPermission(Context context) {
        return android.os.Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Shows the system notification-permission prompt from the given screen. */
    static void requestNotificationPermission(Context context) {
        Context c = context;
        while (c instanceof android.content.ContextWrapper && !(c instanceof android.app.Activity)) {
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        if (c instanceof android.app.Activity && android.os.Build.VERSION.SDK_INT >= 33) {
            ((android.app.Activity) c).requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"}, 0x7e70);
        }
    }

    private static void line(StringBuilder sb, String what, boolean ok, String detail) {
        sb.append(ok ? "\u2713 " : "\u2717 ").append(what);
        if (detail != null && !detail.isEmpty()) {
            sb.append(": ").append(detail);
        }
        sb.append('\n');
    }

    /** Firebase's PersistedInstallation.RegistrationStatus ordinal -> label. */
    private static String installationStatus(int status) {
        switch (status) {
            case 0: return "ATTEMPT_MIGRATION";
            case 1: return "NOT_GENERATED";
            case 2: return "UNREGISTERED";
            case 3: return "REGISTERED";
            case 4: return "REGISTER_ERROR";
            default: return "?";
        }
    }

    private static JSONObject readInstallation(Context context) throws Exception {
        // Newer Firebase keeps it in no-backup storage, older in files/.
        for (File dir : new File[]{context.getNoBackupFilesDir(), context.getFilesDir()}) {
            JSONObject found = readInstallation(dir);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static JSONObject readInstallation(File dir) throws Exception {
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (file.getName().startsWith("PersistedInstallation") && file.getName().endsWith(".json")) {
                byte[] data = new byte[(int) Math.min(file.length(), 64 * 1024)];
                FileInputStream in = new FileInputStream(file);
                try {
                    int read = in.read(data);
                    return new JSONObject(new String(data, 0, Math.max(0, read), "UTF-8"));
                } finally {
                    in.close();
                }
            }
        }
        return null;
    }

    private static String readFcmToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("com.google.android.gms.appid", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith("|T|") || !(entry.getValue() instanceof String)) {
                continue;
            }
            String value = (String) entry.getValue();
            try {
                String token = new JSONObject(value).optString("token", "");
                if (!token.isEmpty()) {
                    return token;
                }
            } catch (Throwable notJson) {
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }
}
