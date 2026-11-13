package tv.retwitch.mod.emotes;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JVM self-check for {@link SevenTvPersonal} (entitlements, set updates, row owner) and the zero-width flags of
 * {@link SevenTvClient#parseEmoteArray}. Build/run like ads/PlaylistSelfCheck, with a real org.json jar first on
 * the classpath (android.jar only has stubs), main class tv.retwitch.mod.emotes.SevenTvPersonalSelfCheck.
 */
public final class SevenTvPersonalSelfCheck {

    public static void main(String[] args) throws Exception {
        // As seen on yui2d: lux753357's set with the zero-width `wa` (entry flags 1, data flags 0).
        JSONObject wa = emote("wa", 1, 0, "PERSONAL", "LISTED");
        assert SevenTvClient.parseEmoteArray(new JSONArray().put(wa)).get("wa").zeroWidth;
        assert SevenTvClient.parseEmoteArray(new JSONArray().put(emote("x", 0, 256))).get("x").zeroWidth;
        assert !SevenTvClient.parseEmoteArray(new JSONArray().put(emote("y", 0, 0))).get("y").zeroWidth;

        String set = "01KHE6KPG9N7VHAKTSF8ZFSN9T";
        SevenTvPersonal.onSetUpdate(new JSONObject().put("id", set).put("pushed", new JSONArray()
                .put(new JSONObject().put("key", "emotes").put("value", wa))
                .put(new JSONObject().put("key", "emotes").put("value", emote("notPersonal", 0, 0, "LISTED")))));
        assert SevenTvPersonal.lookup("819327538", "wa") == null; // no entitlement yet
        SevenTvPersonal.onEntitlement("entitlement.create", entitlement(set));
        assert SevenTvPersonal.lookup("819327538", "wa") != null;
        assert SevenTvPersonal.lookup("819327538", "notPersonal") == null;
        assert SevenTvPersonal.lookup("1", "wa") == null;

        assert "819327538".equals(SevenTvPersonal.ownerOfRow("12:34 Lux753357: hi wa"));
        assert "819327538".equals(SevenTvPersonal.ownerOfRow("Люкс (lux753357): wa"));
        assert SevenTvPersonal.ownerOfRow("other: lux753357: wa") == null;
        assert SevenTvPersonal.ownerOfRow("xlux753357: wa") == null;
        assert SevenTvPersonal.ownerOfRow("lux753357 does /me wa") == null;

        SevenTvPersonal.onSetUpdate(new JSONObject().put("id", set).put("pulled", new JSONArray()
                .put(new JSONObject().put("key", "emotes").put("old_value", wa))));
        assert SevenTvPersonal.lookup("819327538", "wa") == null;

        SevenTvPersonal.onEntitlement("entitlement.delete", entitlement(set));
        assert SevenTvPersonal.ownerOfRow("lux753357: wa") == null;
        SevenTvPersonal.clear();
        System.out.println("SevenTvPersonalSelfCheck OK");
    }

    private static JSONObject entitlement(String set) throws Exception {
        return new JSONObject().put("kind", "EMOTE_SET").put("ref_id", set).put("user", new JSONObject()
                .put("username", "lux753357").put("connections", new JSONArray().put(new JSONObject()
                        .put("platform", "TWITCH").put("id", "819327538").put("username", "lux753357")
                        .put("display_name", "Lux753357"))));
    }

    private static JSONObject emote(String name, int flags, int dataFlags, String... state) throws Exception {
        JSONArray states = new JSONArray();
        for (String s : state) {
            states.put(s);
        }
        return new JSONObject().put("id", name + "-id").put("name", name).put("flags", flags)
                .put("data", new JSONObject().put("flags", dataFlags).put("animated", false).put("state", states)
                        .put("host", new JSONObject().put("url", "//cdn.7tv.app/emote/" + name)
                                .put("files", new JSONArray().put(new JSONObject().put("name", "2x.webp")
                                        .put("format", "WEBP")))));
    }
}
