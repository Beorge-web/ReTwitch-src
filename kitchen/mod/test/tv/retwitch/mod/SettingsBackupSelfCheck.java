package tv.retwitch.mod;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** JVM self-check for the settings backup value checks. Build/run like ads/PlaylistSelfCheck. */
public final class SettingsBackupSelfCheck {

    public static void main(String[] args) {
        // Only our files, never the launch counter or other apps' prefs.
        assert SettingsBackup.fileAllowed("retwitch_toggles");
        assert SettingsBackup.fileAllowed("retwitch_emote_settings");
        assert !SettingsBackup.fileAllowed("retwitch_launch_promo");
        assert !SettingsBackup.fileAllowed("twitch_user_prefs");
        assert !SettingsBackup.fileAllowed("retwitch_../twitch_user_prefs");
        assert !SettingsBackup.fileAllowed(null);

        assert "b".equals(SettingsBackup.tag(true));
        assert "i".equals(SettingsBackup.tag(5));
        assert "l".equals(SettingsBackup.tag(5L));
        assert "f".equals(SettingsBackup.tag(1.5f));
        assert "s".equals(SettingsBackup.tag("x"));
        assert "ss".equals(SettingsBackup.tag(new HashSet<>(Collections.singleton("a"))));
        assert SettingsBackup.tag(1.5d) == null;

        // Values as org.json parses them: small numbers Integer, large Long, fractions Double.
        assert Boolean.TRUE.equals(SettingsBackup.decode("b", true));
        assert SettingsBackup.decode("b", "true") == null;
        assert Integer.valueOf(42).equals(SettingsBackup.decode("i", 42));
        assert Integer.valueOf(42).equals(SettingsBackup.decode("i", 42.0d));
        assert SettingsBackup.decode("i", 42.5d) == null;
        assert SettingsBackup.decode("i", 3_000_000_000L) == null;
        assert Long.valueOf(3_000_000_000L).equals(SettingsBackup.decode("l", 3_000_000_000L));
        assert Long.valueOf(7L).equals(SettingsBackup.decode("l", 7));
        assert SettingsBackup.decode("l", 1e30d) == null;
        assert Float.valueOf(0.1f).equals(SettingsBackup.decode("f", 0.1d));
        assert Float.valueOf(3f).equals(SettingsBackup.decode("f", 3));
        assert SettingsBackup.decode("f", Double.NaN) == null;
        assert SettingsBackup.decode("f", "1") == null;
        assert "x".equals(SettingsBackup.decode("s", "x"));
        assert SettingsBackup.decode("s", 1) == null;
        Set<String> set = new HashSet<>(Arrays.asList("a", "b"));
        assert set.equals(SettingsBackup.decode("ss", Arrays.asList("a", "b")));
        assert SettingsBackup.decode("ss", Arrays.asList("a", 1)) == null;
        assert SettingsBackup.decode("ss", "a") == null;
        assert SettingsBackup.decode("x", true) == null;
        assert SettingsBackup.decode(null, true) == null;

        // A value never replaces one of another type stored here.
        assert !SettingsBackup.conflicts(null, 5);
        assert !SettingsBackup.conflicts(1, 5);
        assert SettingsBackup.conflicts(true, 5);
        assert SettingsBackup.conflicts(1, 5L);
        assert !SettingsBackup.conflicts(new HashSet<String>(), set);
        assert SettingsBackup.conflicts(new HashSet<String>(), "a");

        System.out.println("SettingsBackupSelfCheck OK");
    }
}
