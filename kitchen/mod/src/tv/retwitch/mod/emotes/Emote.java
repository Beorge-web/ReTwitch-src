package tv.retwitch.mod.emotes;

/** A single resolved third-party emote: its chat code and where to fetch its image. */
public final class Emote {
    public final String code;
    public final String imageUrl;
    public final EmoteSource source;
    public final boolean animated;
    /**
     * Whether this emote is meant to be overlaid, centered, on top of the
     * emote immediately preceding it in the message rather than occupying its
     * own slot (7TV ZERO_WIDTH flag; a hardcoded set of BTTV codes). FFZ and
     * Twitch emotes are never zero-width. Drives the emote-stacking render in
     * {@link EmoteChatHook}/{@link LayeredEmoteDrawable}.
     */
    public final boolean zeroWidth;
    /** Biggest image (7TV 4x, BTTV 3x, FFZ 4x when it has one), for giant emotes. */
    public final String largeImageUrl;
    /** The provider's own emote id (the Twitch emote id for {@link EmoteSource#TWITCH}), or null. */
    public String id;
    /** Who uploaded it (7TV owner, BTTV/FFZ user), or null when the API doesn't say. */
    public String author;
    /** From the provider's global set rather than the channel's. */
    public boolean global;

    public Emote(String code, String imageUrl, EmoteSource source, boolean animated) {
        this(code, imageUrl, source, animated, false);
    }

    public Emote(String code, String imageUrl, EmoteSource source, boolean animated, boolean zeroWidth) {
        this(code, imageUrl, source, animated, zeroWidth, largeUrl(imageUrl, source));
    }

    public Emote(String code, String imageUrl, EmoteSource source, boolean animated, boolean zeroWidth,
            String largeImageUrl) {
        this.code = code;
        this.imageUrl = imageUrl;
        this.source = source;
        this.animated = animated;
        this.zeroWidth = zeroWidth;
        this.largeImageUrl = largeImageUrl;
    }

    /** A Twitch emote by id, for favourites: the picker shows it by its real id, like Twitch's own. */
    static Emote twitch(String code, String twitchId) {
        Emote emote = new Emote(code, "https://static-cdn.jtvnw.net/emoticons/v2/" + twitchId + "/default/dark/"
                + EmoteSettings.twitchSize(), EmoteSource.TWITCH, false);
        emote.id = twitchId;
        return emote;
    }

    private static String largeUrl(String url, EmoteSource source) {
        if (source == EmoteSource.SEVENTV || source == EmoteSource.HOMIES) {
            return url.replaceFirst("/[1-4]x\\.", "/4x.");
        }
        if (source == EmoteSource.BTTV) {
            return url.replaceFirst("/[1-3]x(\\.webp)?$", "/3x$1");
        }
        return url;
    }
}
