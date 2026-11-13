package tv.retwitch.mod;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannedString;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tv.retwitch.mod.emotes.Emote;
import tv.retwitch.mod.emotes.EmoteCache;
import tv.retwitch.mod.emotes.EmoteChatHook;
import tv.retwitch.mod.emotes.EmoteSource;

/**
 * DIAGNOSTIC-ONLY probe for the "emote drawing crashes on Android 10/11"
 * report.
 *
 * <p>Twitch blocks username/password login on non-standard clients
 * ("This app version/OS is not currently supported."), so on an emulator we
 * can't reach a real chat to see whether third-party emotes crash while
 * rendering. This probe sidesteps that: it overlays a small bar on the
 * login screen (the one screen reachable without auth -- its class name
 * {@code tv.twitch.android.login.LoginActivity} survives R8 because it's in
 * the manifest) and draws a handful of real 7TV <em>global</em> emotes into
 * it through the <em>exact same</em> production pipeline chat uses:
 *
 * <ol>
 *   <li>{@link EmoteCache#preloadGlobal()} fetches the 7TV/BTTV/FFZ global
 *       sets (no login needed);</li>
 *   <li>a {@link TextView} is filled with the plain-text codes of a few
 *       global emotes drawn from <em>all three</em> providers (animated first
 *       -- the suspect path), finishing with a base emote immediately followed
 *       by a zero-width one so the overlay/stacking render
 *       ({@code LayeredEmoteDrawable}) is exercised too;</li>
 *   <li>{@link EmoteChatHook#onTextBound(android.text.Spanned, TextView)} --
 *       the real per-row chat bind hook -- registers the view for the refresh
 *       machinery and wires animated-emote hosts, so
 *       {@code EmoteImageCache}'s decode + {@code AnimatedEmoteDrawable}'s
 *       draw run identically to live chat.</li>
 * </ol>
 *
 * If the emote decode/draw genuinely crashes on API 29/30, it reproduces here
 * with the same stack trace (logcat tags {@code RTEmotes}/{@code RTProbe}),
 * with no account and no channel.
 *
 * <p>Everything is wrapped in {@code try/catch(Throwable)} + crash reporter
 * per the mod's fail-soft rule (the smali that calls {@link #attach} does not
 * guard it). Remove this class and {@code EmoteRenderProbePatch.kt} (or flip
 * that patch's {@code default} to {@code false}) before shipping a release.
 */
public final class EmoteRenderProbe {

    private static final String TAG = "RTProbe";
    private static final long POLL_MS = 500L;
    private static final int MAX_POLLS = 40; // ~20s
    /** Emotes to draw per provider (7TV/BTTV/FFZ) before the overlay-test pair. */
    private static final int MAX_PER_PROVIDER = 2;

    /** Once per process: guards against re-adding the bar if LoginActivity is recreated. */
    private static volatile boolean attached = false;

    private EmoteRenderProbe() {
    }

    /**
     * Injected at the top of {@code LoginActivity.onCreate(Bundle)} (see
     * {@code EmoteRenderProbePatch.kt}); the actual UI work is deferred to the
     * main looper so it runs after {@code onCreate}/{@code setContentView}
     * finish. Never throws.
     */
    public static void attach(final Activity activity) {
        try {
            if (activity == null || attached) {
                return;
            }
            attached = true;
            final Handler main = new Handler(Looper.getMainLooper());
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        install(activity, main);
                    } catch (Throwable t) {
                        Log.e(TAG, "install failed", t);
                        CrashReporter.report(TAG, "install failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
            CrashReporter.report(TAG, "attach failed", t);
        }
    }

    private static void install(final Activity activity, final Handler main) {
        final TextView bar = new TextView(activity);
        bar.setTextColor(Color.WHITE);
        bar.setBackgroundColor(0xDD101010);
        int pad = dp(activity, 8);
        // Extra top padding to clear the status bar so the bar is fully visible.
        bar.setPadding(pad, dp(activity, 44), pad, pad);
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        bar.setText("RTProbe: loading 7TV/BTTV/FFZ globals\u2026");

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        ((ViewGroup) activity.getWindow().getDecorView()).addView(bar, lp);
        Log.i(TAG, "probe bar added to " + activity.getClass().getName());

        EmoteCache.preloadGlobal();

        main.postDelayed(new Runnable() {
            private int polls = 0;

            @Override
            public void run() {
                try {
                    polls++;
                    List<String> codes = pickGlobalCodes(MAX_PER_PROVIDER);
                    if (codes.isEmpty()) {
                        if (polls < MAX_POLLS) {
                            main.postDelayed(this, POLL_MS);
                        } else {
                            bar.setText("RTProbe: no globals loaded (network/proxy?)");
                            Log.w(TAG, "no global emotes after " + polls + " polls");
                        }
                        return;
                    }

                    StringBuilder sb = new StringBuilder("RT ");
                    for (String code : codes) {
                        sb.append(code).append(' ');
                    }
                    SpannedString spanned = new SpannedString(sb.toString());
                    bar.setText(spanned);
                    Log.i(TAG, "probe rendering codes: " + codes);

                    // Drive the real chat pipeline: onTextBound registers this
                    // view for the refresh machinery and attaches animated
                    // hosts, and each finished image decode calls
                    // requestRefresh() -> re-injection over this view. The
                    // plain codes become ImageSpans (and animate) exactly as
                    // they would in live chat.
                    EmoteChatHook.onTextBound(spanned, bar);
                } catch (Throwable t) {
                    Log.e(TAG, "probe render step crashed", t);
                    CrashReporter.report(TAG, "probe render step crashed", t);
                }
            }
        }, POLL_MS);
    }

    /**
     * Builds the ordered list of emote codes the probe draws. It takes up to
     * {@code maxPerProvider} <em>non</em>-zero-width emotes from each provider
     * (7TV, BTTV, FFZ), animated first -- that's the
     * {@code ImageDecoder}/{@code AnimatedImageDrawable} path we suspect on
     * Android 10/11 -- then appends a base emote immediately followed by a
     * zero-width emote so the overlay/stacking render
     * ({@code LayeredEmoteDrawable}) is exercised as well.
     *
     * <p>Order is load-bearing: a zero-width emote only stacks onto the emote
     * token that immediately precedes it in the text (see {@code EmoteChatHook}),
     * so the base+zero-width pair must stay adjacent and last.
     */
    private static List<String> pickGlobalCodes(int maxPerProvider) {
        List<EmoteCache.EmoteGroup> groups = EmoteCache.groupedMatches(null, 200);
        List<String> tokens = new ArrayList<>();
        Set<String> used = new HashSet<>();

        // 1. A few normal (non-zero-width) emotes from each provider, animated
        //    first so the suspect decode path is covered for every provider.
        for (EmoteSource provider : EmoteSource.values()) {
            int taken = 0;
            for (boolean wantAnimated : new boolean[] {true, false}) {
                for (EmoteCache.EmoteGroup group : groups) {
                    for (Emote emote : group.emotes) {
                        if (taken >= maxPerProvider) {
                            break;
                        }
                        if (emote.source != provider || emote.zeroWidth || emote.animated != wantAnimated) {
                            continue;
                        }
                        if (used.add(emote.code)) {
                            tokens.add(emote.code);
                            taken++;
                        }
                    }
                }
            }
            if (taken == 0) {
                Log.w(TAG, "no global emotes for provider " + provider);
            }
        }

        // 2. Overlay test: a base emote immediately followed by a zero-width
        //    one, so the stacking path (base + centered overlay) is drawn. The
        //    base may repeat an earlier token -- that's fine, it just renders
        //    again, this time with the overlay on top.
        String base = firstCode(groups, false, true);      // non-zero-width, animated preferred
        String zeroWidth = firstCode(groups, true, true);  // zero-width, animated preferred
        if (base != null && zeroWidth != null) {
            tokens.add(base);
            tokens.add(zeroWidth);
            Log.i(TAG, "overlay test pair: base='" + base + "' + zeroWidth='" + zeroWidth + "'");
        } else {
            Log.w(TAG, "overlay not tested (base=" + base + ", zeroWidth=" + zeroWidth + ")");
        }
        return tokens;
    }

    /**
     * First loaded global emote code matching {@code zeroWidth}, preferring an
     * animated one when {@code preferAnimated} is set (falling back to a static
     * match). Returns {@code null} if none is loaded.
     */
    private static String firstCode(List<EmoteCache.EmoteGroup> groups, boolean zeroWidth, boolean preferAnimated) {
        String fallback = null;
        for (EmoteCache.EmoteGroup group : groups) {
            for (Emote emote : group.emotes) {
                if (emote.zeroWidth != zeroWidth) {
                    continue;
                }
                if (preferAnimated && emote.animated) {
                    return emote.code;
                }
                if (fallback == null) {
                    fallback = emote.code;
                }
            }
        }
        return fallback;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
