package tv.retwitch.mod;

/**
 * JVM self-check for the pure player logic (audio EQ curves, seek steps, chat
 * offset clamp, stats formatting). No app context here: settings read as defaults.
 * Run from kitchen/ with the classpath of PlaylistSelfCheck's header, main class
 * tv.retwitch.mod.PlayerSelfCheck (add mod/test/tv/retwitch/mod/PlayerSelfCheck.java to javac).
 */
public final class PlayerSelfCheck {

    /** The IVS getters PlayerStats reads by reflection. */
    public static final class FakeQuality {
        private final String name;
        private final int height;
        private final int bitrate;

        FakeQuality(String name, int height, int bitrate) {
            this.name = name;
            this.height = height;
            this.bitrate = bitrate;
        }

        public String getName() {
            return name;
        }

        public int getHeight() {
            return height;
        }

        public int getBitrate() {
            return bitrate;
        }
    }

    public static final class FakePlayer {
        FakeQuality quality;
        long net = 5_100_000L;
        long position = 10_000L;

        public FakeQuality getQuality() {
            return quality;
        }

        public long getBandwidthEstimate() {
            return net;
        }

        public long getBufferedPosition() {
            return 11_500L;
        }

        public long getPosition() {
            return position;
        }

        public long getLiveLatency() {
            return 3200L;
        }
    }

    public static void main(String[] args) throws Exception {
        // VOD download: every rendition with Twitch's name, absolute; source and audio named.
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1920x1080,FRAME-RATE=60.000,VIDEO=\"chunked\"\n"
                + "chunked/index-dvr.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720,VIDEO=\"720p60\"\n"
                + "720p60/index-dvr.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"mp4a\",VIDEO=\"audio_only\"\n"
                + "audio_only/index-dvr.m3u8\n";
        java.util.List<String[]> vs = VodDownload.variants("https://cdn.example/a/master.m3u8", master);
        assert vs.size() == 3;
        assert vs.get(0)[0].endsWith("(1080p60) · 1920x1080") && vs.get(0)[1].equals("https://cdn.example/a/chunked/index-dvr.m3u8");
        assert VodDownload.sourceName("1664x936", "30.000").equals("936p") && VodDownload.sourceName("1920x1080", null).equals("1080p");
        assert LiveRecord.height(vs.get(0)[0]) == 1080; // a saved auto-record quality still matches by height
        assert vs.get(1)[0].equals("720p60 · 1280x720");
        assert vs.get(2)[1].endsWith("/audio_only/index-dvr.m3u8");
        assert VodDownload.vodIdOf("http://127.0.0.1:1/s/vod?u=https%3A%2F%2Fusher.ttvnw.net%2Fvod%2F2918309402.m3u8").equals("2918309402");
        assert VodDownload.fileName(new String[]{"xQc", "a/b: c?"}).startsWith("xQc - a b c (");
        assert "https://cdn.example/a/720p60/12.ts".equals(
                VodDownload.resolve("https://cdn.example/a/720p60/index-dvr.m3u8", "12.ts"));
        assert "https://other.example/x.ts".equals(
                VodDownload.resolve("https://cdn.example/a/index.m3u8", "https://other.example/x.ts"));

        // Flat settings: a flat equaliser.
        for (float g : AudioCompressor.eqGains(0, 0, 0)) {
            assert g == 0f : g;
        }
        // Bass +12: full at 30..100 Hz, gone from 500 Hz up.
        float[] bass = AudioCompressor.eqGains(12, 0, 0);
        assert bass[0] == 12f && bass[3] == 12f;
        assert bass[4] > 0f && bass[4] < 12f;
        assert bass[6] == 0f && bass[15] == 0f;
        // Treble -12: only the high bands, full at 8 kHz and up.
        float[] treble = AudioCompressor.eqGains(0, -12, 0);
        assert treble[0] == 0f && treble[7] == 0f;
        assert treble[13] == -12f && treble[15] == -12f;
        // Voice 100%: speech up (2..5 kHz), mud down (100..300 Hz).
        float[] voice = AudioCompressor.eqGains(0, 0, 100);
        assert voice[8] == 5f && voice[11] == 5f;
        assert voice[3] == -2.5f && voice[5] == -2.5f;
        assert voice[0] == 0f;
        // Limiter: the compressor alone keeps its -1 dB clip guard; the chosen ceiling wins.
        assert AudioCompressor.ceiling(true, 0) == -1f && AudioCompressor.ceiling(false, 0) == -0.5f;
        assert AudioCompressor.ceiling(true, 100) == -12f;

        // No settings: Twitch's own steps (-10 / +30 s), media session in ms.
        assert VodSeek.stepSeconds(-10) == -10;
        assert VodSeek.stepSeconds(30) == 30;
        assert VodSeek.stepMillis(-10000) == -10000;
        assert VodSeek.stepMillis(30000) == 30000;

        // Chat offset range is PurpleTV's -30..+90 s; no offset without a VOD.
        assert VodChatSync.clamp(-100) == VodChatSync.MIN && VodChatSync.clamp(500) == VodChatSync.MAX;
        assert VodChatSync.clamp(15) == 15;
        assert VodChatSync.shift(new Object(), 42) == 42;

        assert "-".equals(PlayerStats.mbps(0));
        assert "6.5 Mbps".equals(PlayerStats.mbps(6_500_000L));

        // VAFT_LOG player lines: quality changes only, buffering with its length, nothing from another player.
        FakePlayer player = new FakePlayer();
        assert PlayerStats.qualityLine(player) == null;
        player.quality = new FakeQuality("", 0, 0);
        assert PlayerStats.qualityLine(player) == null;
        player.quality = new FakeQuality("720p60", 720, 2_900_000);
        String snapshot = "720p60 (720p, 2.9 Mbps), net 5.1 Mbps, buffer 1500 ms, latency 3200 ms";
        assert ("player quality - -> " + snapshot).equals(PlayerStats.qualityLine(player));
        assert PlayerStats.qualityLine(player) == null;
        player.quality = new FakeQuality("160p30", 160, 300_000);
        assert PlayerStats.qualityLine(player).startsWith("player quality 720p60 -> 160p30 (160p, 0.3 Mbps), net ");
        player.quality = new FakeQuality("720p60", 720, 2_900_000);
        PlayerStats.qualityLine(player);
        assert PlayerStats.bufferingLine(player, "PLAYING", 500L) == null;
        assert ("player buffering: " + snapshot).equals(PlayerStats.bufferingLine(player, "BUFFERING", 1000L));
        assert ("player buffering ended (PLAYING) after 2345 ms: " + snapshot)
                .equals(PlayerStats.bufferingLine(player, "PLAYING", 3345L));
        assert PlayerStats.bufferingLine(player, "PLAYING", 4000L) == null;
        PlayerStats.bufferingLine(player, "BUFFERING", 5000L);
        assert PlayerStats.bufferingLine(new FakePlayer(), "READY", 60000L) == null;
        assert PlayerStats.bufferingLine(player, "PLAYING", 61000L) == null;
        // No estimate yet and a position zeroed by a manual switch: unknown, not 1 Mbps and the whole timeline.
        player.net = 1_000_000L;
        player.position = 0L;
        assert PlayerStats.bufferingLine(player, "BUFFERING", 70000L).endsWith(", net ?, buffer ?, latency 3200 ms");
        PlayerStats.bufferingLine(player, "PLAYING", 71000L);
        player.net = 5_100_000L;
        player.position = 10_000L;

        // VOD dates: Twitch's UTC timestamps, with or without a fraction.
        assert VodDates.parseUtc("2026-10-28T17:59:41Z") == 1793210381000L;
        assert VodDates.parseUtc("2026-10-28T17:59:41.5Z") == 1793210381000L;
        assert VodDates.parseUtc("") == -1 && VodDates.parseUtc(null) == -1;

        // VOD segments fetched in parallel land in playlist order; a failed request is retried.
        java.util.concurrent.atomic.AtomicInteger failOnce = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String n = exchange.getRequestURI().getPath().substring(1);
            try {
                Thread.sleep((long) (Math.random() * 30));
            } catch (InterruptedException ignored) {
                // just answer sooner
            }
            byte[] body = ("seg" + n + ";").getBytes();
            boolean fail = n.equals("3") && failOnce.getAndIncrement() == 0;
            exchange.sendResponseHeaders(fail ? 500 : 200, fail ? -1 : body.length);
            if (!fail) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        java.util.List<String> urls = new java.util.ArrayList<>();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            urls.add("http://127.0.0.1:" + server.getAddress().getPort() + "/" + i);
            expected.append("seg").append(i).append(';');
        }
        java.io.File dir = java.nio.file.Files.createTempDirectory("rtdl").toFile();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicLong net = new java.util.concurrent.atomic.AtomicLong();
        final TestGate gate = new TestGate();
        int[] calls = {0};
        long written = VodDownload.appendSegments(new okhttp3.OkHttpClient(), urls, dir, out, net, gate, (done, bytes) -> {
            assert done == ++calls[0];
        });
        assert expected.toString().equals(out.toString()) : out;
        assert written == expected.length() && calls[0] == 40 && failOnce.get() == 2;
        assert dir.listFiles().length == 0;

        // Pause holds the output and resume finishes it; cancel ends it, whoever is waiting.
        final java.io.ByteArrayOutputStream held = new java.io.ByteArrayOutputStream();
        gate.set(true, false);
        Thread paused = new Thread(() -> {
            try {
                VodDownload.appendSegments(new okhttp3.OkHttpClient(), urls, dir, held, net, gate, (done, bytes) -> { });
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        paused.start();
        Thread.sleep(300);
        assert paused.isAlive() && held.size() == 0;
        gate.set(false, false);
        paused.join(5000);
        assert expected.toString().equals(held.toString()) : held;
        boolean ended = false;
        java.io.ByteArrayOutputStream stopped = new java.io.ByteArrayOutputStream();
        try {
            VodDownload.appendSegments(new okhttp3.OkHttpClient(), urls, dir, stopped, net, gate, (done, bytes) -> {
                if (done == 5) {
                    gate.set(false, true);
                }
            });
        } catch (java.util.concurrent.CancellationException e) {
            ended = true;
        }
        assert ended && stopped.size() < expected.length();
        gate.set(false, false);
        server.stop(0);
        dir.delete();

        System.out.println("PlayerSelfCheck OK");
    }

    /** Pause and stop for {@link VodDownload#appendSegments}, as a recording gives them ({@code LiveRecord.Rec#check}). */
    private static final class TestGate implements VodDownload.Gate {
        private boolean paused;
        private boolean stopped;

        synchronized void set(boolean pause, boolean stop) {
            paused = pause;
            stopped = stop;
            notifyAll();
        }

        @Override
        public synchronized void check() throws InterruptedException {
            while (paused && !stopped) {
                wait();
            }
            if (stopped) {
                throw new java.util.concurrent.CancellationException();
            }
        }
    }
}
