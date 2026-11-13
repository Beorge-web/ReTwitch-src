package tv.retwitch.mod;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JVM self-check for {@link RnFollowingHook#strip} (hiding and sorting), {@link RnFollowingHook#withAllCategories}
 * and {@link FavoriteChannels#clean}. Build/run like ads/PlaylistSelfCheck, with a real
 * org.json jar first on the classpath (android.jar only has stubs), main class
 * tv.retwitch.mod.RnFollowingSelfCheck.
 */
public final class RnFollowingSelfCheck {

    private static final String SECTIONS = "{\"data\":{\"user\":{\"id\":\"1\","
            + "\"follows\":{\"edges\":[{\"cursor\":\"a\"}]},"
            + "\"followedGames\":{\"nodes\":[{\"id\":\"g\"}]},"
            + "\"viewedVideos\":{\"edges\":[{\"node\":{\"id\":\"v\"}}]},"
            + "\"followedLiveUsers\":{\"edges\":[{\"node\":{\"id\":\"s\"}}]}}}}";
    private static final String OFFLINE = "{\"data\":{\"user\":{\"id\":\"1\",\"follows\":{"
            + "\"pageInfo\":{\"hasNextPage\":true,\"endCursor\":\"c\"},\"edges\":[{\"cursor\":\"a\"}]}},"
            + "\"currentUser\":{\"id\":\"1\",\"followedVideos\":{\"edges\":[]}}}}";

    public static void main(String[] args) throws Exception {
        // Nothing of ours: the same instance, not reparsed.
        String other = "{\"data\":{\"user\":{\"follows\":{\"edges\":[{\"cursor\":\"a\"}]}}}}";
        assert RnFollowingHook.strip(other, true, true, true, true) == other;

        JSONObject user = user(RnFollowingHook.strip(SECTIONS, true, false, false, true));
        assert user.getJSONObject("followedGames").getJSONArray("nodes").length() == 0;
        assert user.getJSONObject("viewedVideos").getJSONArray("edges").length() == 1;
        // The live list and hasAnyFollows come from this same answer.
        assert user.getJSONObject("followedLiveUsers").getJSONArray("edges").length() == 1;
        assert user.getJSONObject("follows").getJSONArray("edges").length() == 1;

        user = user(RnFollowingHook.strip(SECTIONS, false, true, true, false));
        assert user.getJSONObject("followedGames").getJSONArray("nodes").length() == 1;
        assert user.getJSONObject("viewedVideos").getJSONArray("edges").length() == 0;
        assert user.getJSONObject("follows").getJSONArray("edges").length() == 1;

        JSONObject follows = user(RnFollowingHook.strip(OFFLINE, false, false, true, false)).getJSONObject("follows");
        assert follows.getJSONArray("edges").length() == 0;
        assert !follows.getJSONObject("pageInfo").getBoolean("hasNextPage");
        assert RnFollowingHook.strip(OFFLINE, true, true, false, true) == OFFLINE;

        // A batch keeps its order and only touches the matching answers.
        String batch = "[" + other + "," + SECTIONS + ",{\"errors\":[]}," + OFFLINE + "]";
        JSONArray out = new JSONArray(RnFollowingHook.strip(batch, true, true, true, false));
        assert out.length() == 4;
        assert out.getJSONObject(0).getJSONObject("data").getJSONObject("user").getJSONObject("follows")
                .getJSONArray("edges").length() == 1;
        assert out.getJSONObject(1).getJSONObject("data").getJSONObject("user").getJSONObject("followedGames")
                .getJSONArray("nodes").length() == 0;
        assert out.getJSONObject(3).getJSONObject("data").getJSONObject("user").getJSONObject("follows")
                .getJSONArray("edges").length() == 0;

        // Null sections (a user with none) stay as they are.
        String nulls = "{\"data\":{\"user\":{\"followedLiveUsers\":{},\"followedGames\":null,\"viewedVideos\":null}}}";
        assert RnFollowingHook.strip(nulls, true, true, true, true) == nulls;

        // Sorted by the shown name, case and accents aside; hiding wins over sorting.
        String games = "{\"data\":{\"user\":{\"followedLiveUsers\":{},\"followedGames\":{\"nodes\":["
                + "{\"id\":\"1\",\"name\":\"zz\",\"displayName\":\"Dota 2\"},"
                + "{\"id\":\"2\",\"name\":\"aa\",\"displayName\":\"the Elder Scrolls\"},"
                + "{\"id\":\"3\",\"name\":\"Blade & Soul\",\"displayName\":\"\"},"
                + "{\"id\":\"4\",\"name\":\"x\",\"displayName\":\"Écran\"}]}}}}";
        JSONArray sorted = user(RnFollowingHook.strip(games, false, false, false, true))
                .getJSONObject("followedGames").getJSONArray("nodes");
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < sorted.length(); i++) {
            order.append(sorted.getJSONObject(i).getString("id"));
        }
        assert order.toString().equals("3142") : order;
        assert RnFollowingHook.strip(games, false, false, false, false) == games;
        assert user(RnFollowingHook.strip(games, true, false, false, true))
                .getJSONObject("followedGames").getJSONArray("nodes").length() == 0;

        // The limit goes into the tab's query only; a batched neighbour with the field stays.
        String request = "[{\"query\":\"query Other { user { followedGames {\\n id } } }\"},"
                + "{\"query\":\"query MwebFollowingTabSections($s: StreamSort) {\\n user {\\n followedGames {\\n nodes\"}]";
        String wide = RnFollowingHook.withAllCategories(request);
        assert wide.contains("query Other { user { followedGames {");
        assert wide.contains("user {\\n followedGames(first: 100) {\\n nodes");
        assert RnFollowingHook.withAllCategories(wide) == wide;
        String unrelated = "{\"query\":\"query Other { followedGames { id } }\"}";
        assert RnFollowingHook.withAllCategories(unrelated) == unrelated;
        assert RnFollowingHook.withAllCategories(null) == null;
        // Favorites from the script: well-formed entries once each, a missing or long name falls back to the login.
        JSONArray favorites = FavoriteChannels.clean(new JSONArray("[{\"id\":\"12\",\"login\":\"lasqa\",\"name\":\"Lasqa\"},"
                + "{\"id\":\"12\",\"login\":\"again\"},{\"id\":\"x1\",\"login\":\"bad\"},"
                + "{\"id\":\"13\",\"login\":\"no spaces\"},5,null,{\"id\":\"14\",\"login\":\"hiimsky\"}]"));
        assert favorites.length() == 2 : favorites;
        assert favorites.getJSONObject(0).getString("name").equals("Lasqa");
        assert favorites.getJSONObject(1).getString("name").equals("hiimsky");
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 300; i++) {
            many.append(i == 0 ? "" : ",").append("{\"id\":\"").append(i).append("\",\"login\":\"c").append(i).append("\"}");
        }
        assert FavoriteChannels.clean(new JSONArray(many.append("]").toString())).length() == 200;
        System.out.println("RnFollowingSelfCheck OK");
    }

    private static JSONObject user(String json) throws Exception {
        return new JSONObject(json).getJSONObject("data").getJSONObject("user");
    }
}
