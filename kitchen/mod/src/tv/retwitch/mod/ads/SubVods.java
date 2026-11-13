package tv.retwitch.mod.ads;

import android.util.Log;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONObject;

/**
 * Sub-only VODs, after TwitchNoSub (github.com/besuper/TwitchNoSub, Apache-2.0).
 *
 * <p>Usher refuses a sub-only VOD's master (403) to non-subscribers, but its
 * media playlists sit on the VOD CDN in the open, under the path of the VOD's
 * storyboards ({@code seekPreviewsURL} from GQL, given out anonymously). When
 * usher refuses, {@link VaftServer} serves a master built here from the
 * qualities that exist there; their playlists go through {@link #fixMedia}.
 * Pure helpers (no Android calls) are checked in {@code PlaylistSelfCheck}.
 */
final class SubVods {

    private static final String TAG = "RTSubVods";
    private static final String AVC = "avc1.4D001E";
    private static final String HEVC = "hev1.1.6.L93.B0";

    /** Quality key on the CDN, name, resolution (source: see {@link #source}), frame rate; best first. */
    static final String[][] QUALITIES = {
            {"chunked", null, null, "60"},
            {"1440p60", "1440p60", "2560x1440", "60"},
            {"1080p60", "1080p60", "1920x1080", "60"},
            {"720p60", "720p60", "1280x720", "60"},
            {"480p30", "480p", "854x480", "30"},
            {"360p30", "360p", "640x360", "30"},
            {"160p30", "160p", "284x160", "30"},
    };

    private SubVods() {
    }

    /** A master for the VOD from its CDN playlists, or null (not found, network error). */
    static String master(String vodId) {
        try {
            if (vodId == null || !vodId.matches("\\d+")) {
                return null;
            }
            JSONObject video = new JSONObject(gql(vodId)).optJSONObject("data");
            video = video != null ? video.optJSONObject("video") : null;
            String previews = video != null ? video.optString("seekPreviewsURL", "") : "";
            String[] cdn = cdn(previews);
            if (cdn == null) {
                Log.w(TAG, "no storyboards path for VOD " + vodId + ": " + previews);
                return null;
            }
            JSONObject owner = video.optJSONObject("owner");
            String login = owner != null ? owner.optString("login", "") : "";
            List<String[]> found = new ArrayList<>();
            for (String[] quality : QUALITIES) {
                String url = variantUrl(cdn[0], cdn[1], login, vodId, video.optString("broadcastType", ""),
                        video.optString("createdAt", ""), quality[0]);
                String codec = codec(url);
                if (codec != null) {
                    found.add(new String[]{quality[0], url, codec});
                }
            }
            Log.i(TAG, "VOD " + vodId + ": " + found.size() + " qualities on " + cdn[0]);
            return found.isEmpty() ? null : masterPlaylist(found, UUID.randomUUID().toString().replace("-", ""));
        } catch (Throwable t) {
            Log.w(TAG, "sub-only VOD master failed", t);
            return null;
        }
    }

    private static String gql(String vodId) throws Exception {
        String query = "query { video(id: \\\"" + vodId + "\\\") { broadcastType, createdAt, seekPreviewsURL, owner { login } } }";
        Request request = new Request.Builder()
                .url("https://gql.twitch.tv/gql")
                .header("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko")
                .post(RequestBody.create(MediaType.parse("application/json"), "{\"query\":\"" + query + "\"}"))
                .build();
        return body(request);
    }

    /** The playlist's codecs if the quality exists (HTTP 200 with segments), else null. */
    private static String codec(String playlistUrl) throws Exception {
        String playlist = get(playlistUrl);
        if (playlist == null) {
            return null;
        }
        if (playlist.contains(".ts")) {
            return AVC;
        }
        if (!playlist.contains(".mp4")) {
            return null;
        }
        // fMP4 is usually HEVC, sometimes H.264: the init segment tells.
        String init = get(playlistUrl.replace("index-dvr.m3u8", "init-0.mp4"));
        return init == null || init.contains("hev1") ? HEVC : AVC;
    }

    private static String get(String url) throws Exception {
        Response response = SsaiSwapEngine.client().newCall(new Request.Builder().url(url).build()).execute();
        try {
            ResponseBody rb = response.body();
            return response.code() == 200 && rb != null ? rb.string() : null;
        } finally {
            response.close();
        }
    }

    private static String body(Request request) throws Exception {
        Response response = SsaiSwapEngine.client().newCall(request).execute();
        try {
            ResponseBody rb = response.body();
            return rb != null ? rb.string() : "";
        } finally {
            response.close();
        }
    }

    /** {CDN host, VOD path id} from a seekPreviewsURL (the path segment before "storyboards"), or null. */
    static String[] cdn(String seekPreviewsUrl) {
        try {
            URI uri = new URI(seekPreviewsUrl);
            String[] path = uri.getPath().split("/");
            for (int i = 1; i < path.length; i++) {
                if (path[i].contains("storyboards")) {
                    return uri.getHost() != null && !path[i - 1].isEmpty()
                            ? new String[]{uri.getHost(), path[i - 1]} : null;
                }
            }
        } catch (Exception ignored) {
            // not a URL
        }
        return null;
    }

    static String variantUrl(String host, String pathId, String login, String vodId, String broadcastType,
                             String createdAt, String quality) {
        String type = broadcastType.toLowerCase(java.util.Locale.ROOT);
        if (type.equals("highlight")) {
            return "https://" + host + "/" + pathId + "/" + quality + "/highlight-" + vodId + ".m3u8";
        }
        // TwitchNoSub: only uploads from before 2023-02-03 (ISO dates compare as strings) keep this layout.
        if (type.equals("upload") && createdAt.compareTo("2023-02-03") < 0) {
            return "https://" + host + "/" + login + "/" + vodId + "/" + pathId + "/" + quality + "/index-dvr.m3u8";
        }
        return "https://" + host + "/" + pathId + "/" + quality + "/index-dvr.m3u8";
    }

    /**
     * Name and resolution for the source ("chunked") rendition, which the CDN
     * doesn't state: one step above the best transcode, as on Twitch's ladder
     * (a 1080p source gets 720p60 and below). Without a resolution IVS reports
     * height 0 and the default quality ({@code PlayerHook.pickQuality}) skips it.
     * shortcut: a guess (a 900p source shows as 1080p60), reading the SPS of a segment would be exact.
     */
    static String[] source(List<String[]> variants) {
        String best = "";
        for (String[] variant : variants) {
            if (!variant[0].equals("chunked")) {
                best = variant[0];
                break;
            }
        }
        switch (best) {
            case "1440p60":
                return new String[]{"2160p60", "3840x2160"};
            case "1080p60":
                return new String[]{"1440p60", "2560x1440"};
            case "480p30":
            case "360p30":
            case "160p30":
                return new String[]{"720p60", "1280x720"};
            default:
                return new String[]{"1080p60", "1920x1080"};
        }
    }

    /** Usher v1 style master; each variant is {quality key, playlist URL, video codec}, best first. */
    static String masterPlaylist(List<String[]> variants, String servingId) {
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-TWITCH-INFO:ORIGIN=\"s3\",B=\"false\",REGION=\"EU\","
                + "USER-IP=\"127.0.0.1\",SERVING-ID=\"" + servingId + "\",CLUSTER=\"cloudfront_vod\","
                + "USER-COUNTRY=\"BE\",MANIFEST-CLUSTER=\"cloudfront_vod\"\n");
        int bandwidth = 8534030;
        String[] source = source(variants);
        for (String[] variant : variants) {
            String[] quality = quality(variant[0]);
            if (quality[1] == null) {
                quality = new String[]{quality[0], source[0], source[1], quality[3]};
            }
            String selected = variant[0].equals("chunked") ? "YES" : "NO";
            out.append("#EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID=\"").append(variant[0]).append("\",NAME=\"")
                    .append(quality[1]).append("\",AUTOSELECT=").append(selected).append(",DEFAULT=").append(selected)
                    .append('\n')
                    .append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth).append(",CODECS=\"")
                    .append(variant[2]).append(",mp4a.40.2\"")
                    .append(",RESOLUTION=").append(quality[2])
                    .append(",VIDEO=\"").append(variant[0]).append("\",FRAME-RATE=").append(quality[3]).append('\n')
                    .append(variant[1]).append('\n');
            bandwidth -= 100;
        }
        return out.toString();
    }

    private static String[] quality(String key) {
        for (String[] quality : QUALITIES) {
            if (quality[0].equals(key)) {
                return quality;
            }
        }
        throw new IllegalArgumentException(key);
    }

    /**
     * A CDN media playlist served from the local server: relative segment and
     * init URIs made absolute, and muted parts played from their "-muted"
     * segments (the "-unmuted" ones are refused).
     */
    static String fixMedia(String playlistUrl, String playlist) {
        String base = playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1);
        String[] lines = playlist.replace("-unmuted", "-muted").split("\n", -1);
        StringBuilder out = new StringBuilder(playlist.length() + lines.length * base.length());
        for (String raw : lines) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#") && !line.contains("://")) {
                out.append(base).append(line);
            } else if (line.startsWith("#EXT-X-MAP:") && line.contains("URI=\"") && !line.contains("URI=\"https://")) {
                out.append(line.replace("URI=\"", "URI=\"" + base));
            } else {
                out.append(raw);
            }
            out.append('\n');
        }
        out.setLength(Math.max(0, out.length() - 1));
        return out.toString();
    }
}
