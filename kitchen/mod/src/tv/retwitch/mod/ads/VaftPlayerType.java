package tv.retwitch.mod.ads;

import tv.retwitch.mod.AdSettings;

/**
 * VAFT's {@code ForceAccessTokenPlayerType}: optionally requests the live
 * access token as another player type (off by default -- the stream stays
 * Twitch's own outside ad breaks). Injected by
 * {@code VaftPlayerTypePatch} into the live StreamAccessToken request builders.
 */
public final class VaftPlayerType {

    /** Twitch's own player type for normal in-app playback. */
    private static final String MOBILE_PLAYER = "mobile_player";

    private VaftPlayerType() {
    }

    /**
     * Only ordinary playback ({@code mobile_player}) is swapped: background
     * audio, PiP, picture-by-picture and clips keep their own types (an
     * audio-only request must stay audio-only). Never throws.
     */
    public static String onTokenRequest(String playerType) {
        try {
            if (!MOBILE_PLAYER.equals(playerType) || !AdSettings.isBlockSsaiAdsEnabled()) {
                return playerType;
            }
            String forced = AdSettings.getInitialPlayerType();
            return forced == null || forced.isEmpty() ? playerType : forced;
        } catch (Throwable t) {
            return playerType;
        }
    }
}
