package tv.retwitch.mod;

import android.net.Uri;

/**
 * Small Uri helpers shared between {@link UsherHook} and {@link ProxyBackend}.
 */
final class UriUtils {

    private UriUtils() {
    }

    /**
     * Rebuilds {@code uri} with {@code allow_source} forced to {@code true},
     * regardless of what it was originally (or whether it was present at
     * all), maximizing the chance the response -- whether served straight
     * by Twitch or relayed through a proxy that just forwards this URL --
     * includes the Source quality rendition.
     */
    static Uri withAllowSourceTrue(Uri uri) {
        if (uri == null) {
            return null;
        }
        Uri.Builder builder = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if ("allow_source".equals(name)) {
                continue;
            }
            for (String value : uri.getQueryParameters(name)) {
                builder.appendQueryParameter(name, value);
            }
        }
        builder.appendQueryParameter("allow_source", "true");
        return builder.build();
    }
}
