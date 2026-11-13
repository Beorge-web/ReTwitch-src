package tv.retwitch.mod.emotes;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.LinkPreviewSettings;

/**
 * Inline image previews for links in chat messages, ported from the browser
 * extension ({@code utils/link_preview.js}): direct image links, imgur, 7TV
 * emote pages, giphy and kappa.lol, with the extension's modes (hide media
 * links / preview only / both), size limits, blur-until-tapped and enlarge.
 * The preview is appended on its own line under the message.
 */
final class LinkPreview {

    private static final String TAG = "RTLinkPreview";

    private static final Pattern IMAGE_PATH =
            Pattern.compile("\\.(?:jpe?g|png|gif|bmp|webp|jfif)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9]+");
    /** Image hosts the extension loads through the RTE proxy (often blocked directly). */
    private static final Pattern PROXIED_IMAGE_HOST = Pattern.compile(
            "^(cdn\\.discordapp\\.com|media\\.discordapp\\.net|images-ext-\\d+\\.discordapp\\.net"
                    + "|i\\.ytimg\\.com|i\\.ibb\\.co|s\\d+\\.gifyu\\.com|cdn\\.7tv\\.app)$");

    private LinkPreview() {
    }

    /**
     * Applies the link-preview mode ({@link LinkPreviewSettings}) to the first
     * previewable link in {@code original}: hides it, and/or appends its image
     * on its own line once decoded (a later refresh pass retries until then).
     * shortcut: one preview per message, so a half-loaded multi-link message
     * never gets stuck with only some of its previews; show all if asked.
     *
     * @return {@code builder} (created from {@code original} if null) with the
     *         change applied, or {@code builder} unchanged when there is
     *         nothing (yet) to do
     */
    static SpannableStringBuilder append(Spanned original, SpannableStringBuilder builder) {
        int mode = LinkPreviewSettings.getMode();
        if (mode == LinkPreviewSettings.MODE_OFF) {
            return builder;
        }
        Spanned current = builder != null ? builder : original;
        if (current.getSpans(0, current.length(), PreviewSpan.class).length > 0) {
            return builder;
        }
        String text = original.toString();
        int i = 0;
        int length = text.length();
        while (i < length) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < length && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            String word = text.substring(start, i);
            String image = imageUrl(word);
            if (image == null) {
                continue;
            }
            boolean hidden = current.getSpans(start, i, ZeroWidthCollapseSpan.class).length > 0;
            if (mode == LinkPreviewSettings.MODE_HIDE_ALL) {
                if (hidden) {
                    return builder;
                }
                SpannableStringBuilder out = builder != null ? builder : new SpannableStringBuilder(original);
                out.setSpan(new ZeroWidthCollapseSpan(), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return out;
            }
            Drawable drawable = EmoteImageCache.getPreviewIfReady(image,
                    LinkPreviewSettings.getMaxHeightDp(), LinkPreviewSettings.getMaxWidthDp(),
                    useProxy(cleanLink(word), image));
            if (drawable == null) {
                return builder;
            }
            SpannableStringBuilder out = builder != null ? builder : new SpannableStringBuilder(original);
            if (mode == LinkPreviewSettings.MODE_PREVIEW_ONLY && !hidden) {
                out.setSpan(new ZeroWidthCollapseSpan(), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            BlurredDrawable shown = new BlurredDrawable(drawable, LinkPreviewSettings.isBlurEnabled());
            out.append('\n');
            int at = out.length();
            out.append('\uFFFC');
            out.setSpan(new ImageSpan(shown, ImageSpan.ALIGN_BOTTOM), at, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new PreviewSpan(cleanLink(word), image, shown), at, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return out;
        }
        return builder;
    }

    /** Same hosts the extension routes through its proxy (kappa.lol, 7TV, Discord, ...). */
    static boolean useProxy(String link, String image) {
        if (image.contains(".rte.net.ru")) {
            return false;
        }
        try {
            String linkHost = new URI(link).getHost().toLowerCase(Locale.ROOT);
            if (linkHost.equals("kappa.lol") || linkHost.endsWith(".kappa.lol")
                    || linkHost.equals("7tv.app") || linkHost.equals("www.7tv.app")) {
                return true;
            }
            return PROXIED_IMAGE_HOST.matcher(new URI(image).getHost().toLowerCase(Locale.ROOT)).matches();
        } catch (Exception e) {
            return false;
        }
    }

    /** @return the direct image URL behind a chat link, or null if it isn't previewable. */
    static String imageUrl(String word) {
        String link = cleanLink(word);
        if (link == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(link);
        } catch (Exception e) {
            return null;
        }
        String host = uri.getHost();
        String path = uri.getPath() != null ? uri.getPath() : "";
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        String[] segments = path.replaceAll("^/+|/+$", "").split("/+");

        if (host.equals("giphy.com") || host.endsWith(".giphy.com")) {
            String id = giphyId(segments);
            return id != null ? "https://i.giphy.com/" + id + ".webp" : null;
        }
        if ((host.equals("7tv.app") || host.equals("www.7tv.app"))
                && segments.length >= 2 && segments[0].equals("emotes") && ID.matcher(segments[1]).matches()) {
            return "https://cdn.7tv.app/emote/" + segments[1] + "/4x.webp";
        }
        if ((host.equals("imgur.com") || host.equals("www.imgur.com"))
                && segments.length == 1 && ID.matcher(segments[0]).matches()) {
            return "https://i.imgur.com/" + segments[0] + ".jpg";
        }
        if (host.equals("kappa.lol") || host.endsWith(".kappa.lol")) {
            return segments.length >= 1 && ID.matcher(segments[0]).matches() ? link : null;
        }
        return IMAGE_PATH.matcher(path).find() ? link : null;
    }

    /** Giphy media id from /media/{id}/..., /gifs/slug-{id}, /embed/{id} or /{id}.webp. */
    private static String giphyId(String[] segments) {
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].equals("media")) {
                for (int j = i + 1; j < segments.length; j++) {
                    String candidate = stripExtension(segments[j]);
                    if (ID.matcher(candidate).matches() && !candidate.equals("giphy")) {
                        return candidate;
                    }
                }
                return null;
            }
            if (segments[i].equals("gifs") || segments[i].equals("embed")) {
                String slug = stripExtension(segments[i + 1]);
                String candidate = slug.substring(slug.lastIndexOf('-') + 1);
                return ID.matcher(candidate).matches() ? candidate : null;
            }
        }
        String last = stripExtension(segments[segments.length - 1]);
        return ID.matcher(last).matches() ? last : null;
    }

    private static String stripExtension(String segment) {
        int dot = segment.indexOf('.');
        return dot < 0 ? segment : segment.substring(0, dot);
    }

    /** The http(s) link in a chat word without trailing punctuation, or null. */
    private static String cleanLink(String word) {
        if (word == null) {
            return null;
        }
        String w = word.trim();
        if (!w.startsWith("http://") && !w.startsWith("https://")) {
            return null;
        }
        while (!w.isEmpty() && ").,!?;:'\"".indexOf(w.charAt(w.length() - 1)) >= 0) {
            w = w.substring(0, w.length() - 1);
        }
        return w;
    }

    /**
     * Tap target on a preview: first tap un-blurs a blurred preview, then a tap
     * enlarges it (if enabled) or opens the original link.
     */
    static final class PreviewSpan extends ClickableSpan {
        private final String link;
        private final String image;
        private final BlurredDrawable shown;

        PreviewSpan(String link, String image, BlurredDrawable shown) {
            this.link = link;
            this.image = image;
            this.shown = shown;
        }

        @Override
        public void onClick(View widget) {
            try {
                if (shown.isBlurred()) {
                    shown.reveal();
                    widget.invalidate();
                } else if (LinkPreviewSettings.isZoomEnabled()) {
                    showZoom(widget.getContext(), image, link);
                } else {
                    openLink(widget.getContext(), link);
                }
            } catch (Throwable t) {
                Log.w(TAG, "preview tap failed", t);
                CrashReporter.report(TAG, "preview tap failed", t);
            }
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setUnderlineText(false);
        }
    }

    private static void openLink(Context context, String link) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Full-width view of the image; tapping it opens the link. */
    private static void showZoom(final Context context, String image, final String link) {
        final ImageView view = new ImageView(context);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final AlertDialog dialog = new AlertDialog.Builder(context).setView(view).create();
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                try {
                    openLink(context, link);
                } catch (Throwable t) {
                    Log.w(TAG, "open link failed", t);
                    CrashReporter.report(TAG, "open link failed", t);
                }
            }
        });
        int height = context.getResources().getDisplayMetrics().heightPixels / 2;
        EmoteImageCache.loadLarge(image, height, new EmoteImageCache.LargeImageCallback() {
            @Override
            public void onResult(Drawable drawable) {
                if (drawable != null) {
                    view.setImageDrawable(drawable);
                }
            }
        });
        dialog.show();
    }
}
