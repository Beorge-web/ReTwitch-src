package tv.retwitch.mod;

/**
 * Single source of truth for the ReTwitch mod version.
 *
 * <p>This is intentionally <em>separate</em> from the host APK's
 * {@code versionName} (which stays pinned to the Twitch release the build was
 * cooked from, e.g. {@code 30.0.0}, so the client keeps reporting a version
 * Twitch's own backend recognises). {@link #VERSION_NAME} tracks the mod
 * itself.
 *
 * <p>Bump {@link #VERSION_NAME} here on every release -- it is the only place
 * the mod version is defined -- and add the release notes to {@link Changelog}.
 * It is consumed by:
 * <ul>
 *   <li>the settings screen (shown in the ReTwitch menu / "About" dialog), and
 *   <li>the (planned) self-updater, which compares this against the latest
 *       published release via {@link #isNewer(String, String)}.
 * </ul>
 *
 * <p>The version follows a {@code MAJOR.MINOR.PATCH[-prerelease]} shape so it
 * can be compared semantically (a build with no {@code -suffix} is considered
 * newer than the same numbers with one, i.e. {@code 0.0.7} &gt;
 * {@code 0.0.7-alpha}).
 */
public final class BuildInfo {

    /** Human-readable mod version. The single place to bump on release. */
    public static final String VERSION_NAME = "0.5.29-beta";

    private BuildInfo() {
    }

    /**
     * @return {@code true} if {@code candidate} is a strictly newer version
     *         than {@code current}, per the {@code MAJOR.MINOR.PATCH[-pre]}
     *         ordering described on this class. Unparseable input yields
     *         {@code false} (treated as "not newer") so a malformed remote
     *         version can never trigger a spurious update prompt.
     */
    public static boolean isNewer(String candidate, String current) {
        long[] c = parse(candidate);
        long[] base = parse(current);
        if (c == null || base == null) {
            return false;
        }
        for (int i = 0; i < c.length; i++) {
            if (c[i] != base[i]) {
                return c[i] > base[i];
            }
        }
        return false;
    }

    /**
     * Parses {@code MAJOR.MINOR.PATCH[-pre]} into a comparable tuple
     * {@code [major, minor, patch, releaseRank]}, where {@code releaseRank} is
     * {@code 1} for a final release and {@code 0} for a pre-release, so that a
     * final release sorts after its own pre-releases.
     *
     * @return the tuple, or {@code null} if {@code version} is malformed.
     */
    static long[] parse(String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int dash = trimmed.indexOf('-');
        long releaseRank = dash < 0 ? 1L : 0L;
        String core = dash < 0 ? trimmed : trimmed.substring(0, dash);

        String[] parts = core.split("\\.");
        if (parts.length == 0 || parts.length > 3) {
            return null;
        }
        long[] out = new long[]{0L, 0L, 0L, releaseRank};
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Long.parseLong(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
