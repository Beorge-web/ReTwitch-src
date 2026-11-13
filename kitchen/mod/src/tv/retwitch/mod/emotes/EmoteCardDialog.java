package tv.retwitch.mod.emotes;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import tv.retwitch.mod.Strings;

/**
 * A small "emote card" shown when a user taps a third-party (7TV/BTTV/FFZ)
 * emote in a chat message -- the ReTwitch counterpart to Twitch's own native
 * emote card, which only knows about Twitch's first-party emotes.
 *
 * For a zero-width overlay stack (a base emote with one or more overlays drawn
 * on top, e.g. glasses over a face) the card shows the full composite exactly
 * as it renders in chat AND breaks the stack down into its individual
 * components (base + each overlay) so each one can be identified/copied.
 *
 * Deliberately self-contained (plain Android SDK only, no Twitch-internal
 * classes): our custom emotes are rendered as {@link android.text.style.ImageSpan}
 * overlays on plain text (see {@link EmoteChatHook}), not as real Twitch emote
 * tokens, so Twitch's native card flow never fires for them.
 */
public final class EmoteCardDialog {

    private static final int ACCENT_COLOR = 0xFF9146FF; // Twitch purple
    private static final int PREVIEW_HEIGHT_DP = 96;
    private static final int COMPONENT_HEIGHT_DP = 32;

    private EmoteCardDialog() {
    }

    public static void show(Context context, Emote emote) {
        show(context, emote, null);
    }

    public static void show(final Context context, final Emote base, final List<Emote> overlays) {
        if (context == null || base == null) {
            return;
        }

        // Full stack, base first: what the composite preview + component list use.
        final List<Emote> stack = new ArrayList<>();
        stack.add(base);
        if (overlays != null) {
            for (Emote overlay : overlays) {
                if (overlay != null) {
                    stack.add(overlay);
                }
            }
        }
        final boolean isStack = stack.size() > 1;

        int pad = dp(context, 20);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad / 2);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        final int previewPx = dp(context, PREVIEW_HEIGHT_DP);

        final ProgressBar spinner = new ProgressBar(context);
        root.addView(spinner, new LinearLayout.LayoutParams(previewPx, previewPx));

        final ImageView preview = new ImageView(context);
        preview.setAdjustViewBounds(true);
        preview.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, previewPx);
        previewParams.topMargin = dp(context, 4);
        previewParams.bottomMargin = dp(context, 4);
        root.addView(preview, previewParams);

        TextView code = new TextView(context);
        code.setText(base.code);
        code.setTextColor(Color.WHITE);
        code.setTypeface(Typeface.DEFAULT_BOLD);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        code.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeParams.topMargin = dp(context, 8);
        root.addView(code, codeParams);

        TextView meta = new TextView(context);
        meta.setText(metaLine(base, isStack));
        meta.setTextColor(0xFFB0B0B0);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        meta.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dp(context, 2);
        root.addView(meta, metaParams);

        final String page = pageUrl(base);
        if (page != null) {
            TextView link = new TextView(context);
            link.setText(Strings.pick("Открыть на ", "Open on ")
                    + providerLabel(base.source == EmoteSource.HOMIES ? EmoteSource.SEVENTV : base.source));
            link.setTextColor(ACCENT_COLOR);
            link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            link.setGravity(Gravity.CENTER);
            int linkPad = dp(context, 6);
            link.setPadding(0, linkPad, 0, linkPad);
            link.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openImage(context, page);
                }
            });
            root.addView(link, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Composite preview: load every layer large, then stack base + overlays.
        loadComposite(stack, previewPx, new CompositeCallback() {
            @Override
            public void onComposite(Drawable composite) {
                spinner.setVisibility(View.GONE);
                if (composite == null) {
                    return;
                }
                preview.setImageDrawable(composite);
                preview.setVisibility(View.VISIBLE);
            }
        });

        // For an overlay stack, break it down into its components so each
        // (base + overlays) can be seen and identified individually.
        if (isStack) {
            TextView header = new TextView(context);
            header.setText("Stack (" + stack.size() + ")");
            header.setTextColor(ACCENT_COLOR);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setAllCaps(true);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headerParams.topMargin = dp(context, 12);
            root.addView(header, headerParams);

            for (int i = 0; i < stack.size(); i++) {
                root.addView(buildComponentRow(context, stack.get(i), i == 0));
            }
        }

        new AlertDialog.Builder(context)
                .setView(wrapScrolling(context, root))
                .setPositiveButton("Copy code", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyToClipboard(context, base.code);
                    }
                })
                .setNeutralButton("Open image", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openImage(context, base.imageUrl);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** One "small image + code · provider (base/overlay)" row for a stack component. */
    private static View buildComponentRow(final Context context, final Emote emote, boolean isBase) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vpad = dp(context, 4);
        row.setPadding(0, vpad, 0, vpad);

        final int sizePx = dp(context, COMPONENT_HEIGHT_DP);
        final ImageView icon = new ImageView(context);
        icon.setAdjustViewBounds(true);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(sizePx, sizePx);
        iconParams.rightMargin = dp(context, 10);
        row.addView(icon, iconParams);

        TextView label = new TextView(context);
        StringBuilder text = new StringBuilder(emote.code)
                .append("  \u00b7  ").append(providerLabel(emote.source));
        text.append(isBase ? "  \u00b7  base" : "  \u00b7  overlay");
        label.setText(text.toString());
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EmoteImageCache.loadLarge(emote.imageUrl, sizePx, new EmoteImageCache.LargeImageCallback() {
            @Override
            public void onResult(Drawable drawable) {
                if (drawable != null) {
                    icon.setImageDrawable(drawable);
                }
            }
        });

        // Tapping a component drills into just that emote's card.
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(context, emote, null);
            }
        });
        return row;
    }

    private static ViewGroup wrapScrolling(Context context, View content) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private interface CompositeCallback {
        void onComposite(Drawable composite);
    }

    /**
     * Loads a large copy of every layer and, once all have resolved (or
     * failed), hands back a single {@link StackDrawable} that draws the base
     * with overlays centered on top -- the same stacking chat uses. Runs its
     * callback on the main thread (each {@link EmoteImageCache#loadLarge}
     * callback does).
     */
    private static void loadComposite(final List<Emote> stack, final int heightPx,
            final CompositeCallback callback) {
        final Drawable[] layers = new Drawable[stack.size()];
        final int[] remaining = {stack.size()};
        for (int i = 0; i < stack.size(); i++) {
            final int index = i;
            EmoteImageCache.loadLarge(stack.get(i).imageUrl, heightPx, new EmoteImageCache.LargeImageCallback() {
                @Override
                public void onResult(Drawable drawable) {
                    layers[index] = drawable;
                    remaining[0]--;
                    if (remaining[0] <= 0) {
                        if (layers[0] == null) {
                            callback.onComposite(null);
                        } else {
                            callback.onComposite(new StackDrawable(layers));
                        }
                    }
                }
            });
        }
    }

    private static String metaLine(Emote emote, boolean isStack) {
        StringBuilder sb = new StringBuilder(providerLabel(emote.source));
        sb.append(" \u00b7 ").append(emote.global ? Strings.pick("глобальный", "global") : Strings.pick("канала", "channel"));
        if (emote.author != null && !emote.author.isEmpty()) {
            sb.append("\n").append(Strings.pick("Загрузил: ", "Uploaded by ")).append(emote.author);
        }
        if (emote.animated) {
            sb.append(" \u00b7 animated");
        }
        if (isStack) {
            sb.append(" \u00b7 overlay stack");
        } else if (emote.zeroWidth) {
            sb.append(" \u00b7 overlay");
        }
        return sb.toString();
    }

    private static String providerLabel(EmoteSource source) {
        if (source == null) {
            return "Third-party emote";
        }
        switch (source) {
            case SEVENTV:
                return "7TV";
            case BTTV:
                return "BetterTTV";
            case FFZ:
                return "FrankerFaceZ";
            case HOMIES:
                return "Homies";
            default:
                return "Third-party emote";
        }
    }

    /** The emote's page on its provider's site (Homies ids are 7TV ids), or null. */
    private static String pageUrl(Emote emote) {
        if (emote.id == null || emote.source == null) {
            return null;
        }
        switch (emote.source) {
            case SEVENTV:
            case HOMIES:
                return "https://7tv.app/emotes/" + emote.id;
            case BTTV:
                return "https://betterttv.com/emotes/" + emote.id;
            case FFZ:
                return "https://www.frankerfacez.com/emoticon/" + emote.id;
            default:
                return null;
        }
    }

    private static void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("emote", text));
                Toast.makeText(context, "Copied: " + text, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void openImage(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    /**
     * Draws a base emote (layer 0) with any overlay emotes (layers 1..n)
     * centered on top of it -- the dialog-preview equivalent of
     * {@link LayeredEmoteDrawable}, but over eagerly-loaded large drawables
     * instead of the small chat-render cache. Null layers (a failed load) are
     * skipped.
     */
    private static final class StackDrawable extends Drawable {
        private final Drawable[] layers;

        StackDrawable(Drawable[] layers) {
            this.layers = layers;
            int width = 0;
            int height = 0;
            for (Drawable layer : layers) {
                if (layer != null) {
                    width = Math.max(width, layer.getBounds().width());
                    height = Math.max(height, layer.getBounds().height());
                }
            }
            setBounds(0, 0, Math.max(1, width), Math.max(1, height));
        }

        @Override
        public void draw(Canvas canvas) {
            try {
                Rect b = getBounds();
                for (Drawable layer : layers) {
                    if (layer == null) {
                        continue;
                    }
                    Rect lb = layer.getBounds();
                    float dx = b.left + (b.width() - lb.width()) / 2f;
                    float dy = b.top + (b.height() - lb.height()) / 2f;
                    canvas.save();
                    canvas.translate(dx, dy);
                    layer.draw(canvas);
                    canvas.restore();
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return getBounds().width();
        }

        @Override
        public int getIntrinsicHeight() {
            return getBounds().height();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
