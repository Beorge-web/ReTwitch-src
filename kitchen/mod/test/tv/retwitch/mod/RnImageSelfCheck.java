package tv.retwitch.mod;

/**
 * JVM self-check for the RN live preview URLs per generation ({@link RnImageHook#bucketed}).
 * Run from kitchen/ with the classpath of PlaylistSelfCheck's header, main class
 * tv.retwitch.mod.RnImageSelfCheck (add mod/test/tv/retwitch/mod/RnImageSelfCheck.java to javac).
 */
public final class RnImageSelfCheck {

    public static void main(String[] args) {
        String preview = "https://static-cdn.jtvnw.net/previews-ttv/live_user_xqc-440x248.jpg";
        assert RnImageHook.isPreview(preview);
        assert (preview + "?rt=7").equals(RnImageHook.withGeneration(preview, 7L));
        assert (preview + "?rt=29000000").equals(RnImageHook.bucketed(preview, 29000000L));
        // The size grows by 0..3 px each way (CDN cache key), an existing query keeps its parameters.
        String base = "https://static-cdn.jtvnw.net/previews-ttv/live_user_xqc-";
        assert (base + "441x249.jpg?w=1&rt=5").equals(RnImageHook.bucketed(preview + "?w=1", 5L));
        assert (base + "443x251.jpg?rt=15").equals(RnImageHook.bucketed(preview, 15L));
        // 16 generations in a row are 16 sizes; a new generation is a new URI (a Fresco cache miss).
        java.util.Set<String> paths = new java.util.HashSet<String>();
        for (long g = 100; g < 116; g++) {
            String u = RnImageHook.bucketed(preview, g);
            paths.add(u.substring(0, u.indexOf('?')));
        }
        assert paths.size() == 16;
        assert !RnImageHook.bucketed(preview, 1L).equals(RnImageHook.bucketed(preview, 17L));
        assert RnImageHook.bucketed(preview, 7L).equals(RnImageHook.bucketed(preview, 7L));
        // No WxH in the path: only rt.
        String odd = "https://static-cdn.jtvnw.net/previews-ttv/live_user_x.jpg";
        assert (odd + "?rt=3").equals(RnImageHook.bucketed(odd, 3L));
        // Everything else is returned as is (same instance: no copy downstream).
        String avatar = "https://static-cdn.jtvnw.net/jtv_user_pictures/x-profile_image-70x70.png";
        String boxart = "https://static-cdn.jtvnw.net/ttv-boxart/509658-285x380.jpg";
        assert !RnImageHook.isPreview(avatar) && RnImageHook.bucketed(avatar, 1L) == avatar;
        assert RnImageHook.bucketed(boxart, 1L) == boxart;
        assert RnImageHook.bucketed(null, 1L) == null && !RnImageHook.isPreview(null);
        assert RnImageHook.bucketed("", 1L).isEmpty();
        System.out.println("RnImageSelfCheck OK");
    }
}
