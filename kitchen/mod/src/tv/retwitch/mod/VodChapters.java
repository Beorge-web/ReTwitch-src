package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * VOD chapters (PurpleTV's "chapters"): a player button that lists the VOD's
 * game-change markers with box art (Twitch GQL {@code moments(VIDEO_CHAPTER_MARKERS)},
 * anonymous, web Client-ID) and seeks the player to the picked one.
 */
public final class VodChapters {

    private static final String TAG = "RTChapters";
    private static final String QUERY = "query ChapterSelectButton($videoID: ID) { video(id: $videoID) {"
            + " moments(momentRequestType: VIDEO_CHAPTER_MARKERS types: [GAME_CHANGE]) { edges { node {"
            + " positionMilliseconds description details { ... on GameChangeMomentDetails {"
            + " game { boxArtURL(width: 80, height: 106) } } } } } } } }";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static OkHttpClient client;

    private VodChapters() {
    }

    /** The player-strip button (see {@link ReloadHook}). */
    static View createButton(Context context) {
        ImageView button = new ImageView(context);
        int px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                context.getResources().getDisplayMetrics());
        button.setImageDrawable(new ListIcon(px));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(Strings.chaptersTitle());
        button.setClickable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(v.getContext());
            }
        });
        // 48dp: Android's minimum comfortable touch target.
        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                context.getResources().getDisplayMetrics());
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return button;
    }

    private static void show(final Context context) {
        final String vodId = ChannelTracker.getCurrentVodId();
        if (vodId == null) {
            Toast.makeText(context, Strings.chaptersUnavailable(), Toast.LENGTH_SHORT).show();
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<long[]> positions = new ArrayList<>();
                List<String> titles = new ArrayList<>();
                final List<String> boxArts = new ArrayList<>();
                try {
                    parse(fetch(vodId), positions, titles, boxArts);
                } catch (Throwable t) {
                    Log.w(TAG, "fetch failed", t);
                    CrashReporter.report(TAG, "fetch failed", t);
                }
                final List<long[]> p = positions;
                final List<String> l = titles;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            showList(context, p, l, boxArts);
                        } catch (Throwable t) {
                            Log.w(TAG, "show failed", t);
                            CrashReporter.report(TAG, "show failed", t);
                        }
                    }
                });
            }
        }, "RTChapters").start();
    }

    private static void showList(final Context context, final List<long[]> positions, final List<String> titles,
            final List<String> boxArts) {
        if (titles.isEmpty()) {
            Toast.makeText(context, Strings.chaptersEmpty(), Toast.LENGTH_SHORT).show();
            return;
        }
        final float density = context.getResources().getDisplayMetrics().density;
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return titles.size(); }
            @Override public Object getItem(int i) { return titles.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, android.view.ViewGroup parent) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                int pad = Math.round(12 * density);
                row.setPadding(pad * 2, pad / 2, pad, pad / 2);
                ImageView art = new ImageView(context);
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(art, new LinearLayout.LayoutParams(Math.round(40 * density), Math.round(53 * density)));
                android.widget.TextView text = new android.widget.TextView(context);
                text.setText(formatTime(positions.get(i)[0]) + "  " + titles.get(i));
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                text.setPadding(pad, 0, 0, 0);
                row.addView(text);
                loadBoxArt(boxArts.get(i), art);
                return row;
            }
        };
        new AlertDialog.Builder(context)
                .setTitle(Strings.chaptersTitle())
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!PlayerHook.seekCurrent(positions.get(which)[0])) {
                            Toast.makeText(context, Strings.chaptersUnavailable(), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private static final java.util.Map<String, android.graphics.Bitmap> BOX_ARTS =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, android.graphics.Bitmap>());

    /** Box art into the row's ImageView, fetched once per URL off the main thread. */
    private static void loadBoxArt(final String url, final ImageView into) {
        if (url == null) {
            return;
        }
        android.graphics.Bitmap cached = BOX_ARTS.get(url);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Response response = client().newCall(new Request.Builder().url(url).build()).execute();
                    try {
                        byte[] bytes = response.body() != null ? response.body().bytes() : null;
                        final android.graphics.Bitmap bitmap = bytes != null
                                ? android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length) : null;
                        if (bitmap != null) {
                            BOX_ARTS.put(url, bitmap);
                            into.post(new Runnable() {
                                @Override
                                public void run() {
                                    into.setImageBitmap(bitmap);
                                }
                            });
                        }
                    } finally {
                        response.close();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "box art failed", t);
                }
            }
        }, "RTChapterArt").start();
    }

    private static String fetch(String vodId) throws Exception {
        JSONObject body = new JSONObject()
                .put("query", QUERY)
                .put("variables", new JSONObject().put("videoID", vodId));
        Request request = new Request.Builder()
                .url("https://gql.twitch.tv/gql")
                .header("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko")
                .post(RequestBody.create(JSON, body.toString()))
                .build();
        Response response = client().newCall(request).execute();
        try {
            return response.body() != null ? response.body().string() : "";
        } finally {
            response.close();
        }
    }

    static void parse(String json, List<long[]> positions, List<String> titles, List<String> boxArts)
            throws Exception {
        JSONObject video = new JSONObject(json).optJSONObject("data");
        video = video != null ? video.optJSONObject("video") : null;
        JSONObject moments = video != null ? video.optJSONObject("moments") : null;
        JSONArray edges = moments != null ? moments.optJSONArray("edges") : null;
        for (int i = 0; edges != null && i < edges.length(); i++) {
            JSONObject node = edges.getJSONObject(i).optJSONObject("node");
            if (node != null) {
                positions.add(new long[]{node.optLong("positionMilliseconds", 0L)});
                titles.add(node.optString("description", ""));
                JSONObject details = node.optJSONObject("details");
                JSONObject game = details != null ? details.optJSONObject("game") : null;
                boxArts.add(game != null ? game.optString("boxArtURL", null) : null);
            }
        }
    }

    static String formatTime(long ms) {
        long s = ms / 1000L;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static synchronized OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
        }
        return client;
    }

    /** A bulleted-list glyph. */
    private static final class ListIcon extends Drawable {
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ListIcon(int size) {
            this.size = size;
            paint.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float unit = size / 8f;
            float left = b.exactCenterX() - size * 0.375f;
            for (int i = -1; i <= 1; i++) {
                float y = b.exactCenterY() + i * unit * 2.5f;
                canvas.drawCircle(left + unit / 2f, y, unit / 2f, paint);
                canvas.drawRect(left + unit * 1.6f, y - unit / 3f, left + size * 0.75f, y + unit / 3f, paint);
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
