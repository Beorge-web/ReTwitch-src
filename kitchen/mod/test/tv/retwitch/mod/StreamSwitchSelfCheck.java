package tv.retwitch.mod;

import java.util.Arrays;
import java.util.List;

/**
 * JVM self-check for {@link StreamHistory}. Run from kitchen/:
 *   javac -nowarn -cp "$(find ~/.gradle/caches/modules-2/files-2.1/org.json -name 'json-*.jar' | head -1)" -d work/selfcheck mod/src/tv/retwitch/mod/StreamHistory.java mod/test/tv/retwitch/mod/StreamSwitchSelfCheck.java
 *   java -ea -cp "work/selfcheck:$(find ~/.gradle/caches/modules-2/files-2.1/org.json -name 'json-*.jar' | head -1)" tv.retwitch.mod.StreamSwitchSelfCheck
 */
public final class StreamSwitchSelfCheck {

    public static void main(String[] args) {
        StreamHistory h = new StreamHistory();
        assert h.back().isEmpty() && h.forward().isEmpty();
        assert h.opened("a") && h.opened("b") && h.opened("c");
        assert !h.opened("c"); // the same channel again: nothing
        assert h.back().equals(Arrays.asList("b", "a"));
        assert h.forward().isEmpty();

        // Back with an arrow: the cursor moves, the history stays.
        h.pending = "b";
        assert !h.opened("b");
        assert h.back().equals(List.of("a")) && h.forward().equals(List.of("c"));
        // The old channel reports once more while the target loads: still a move back, not a new open.
        h.pending = "a";
        assert !h.opened("b");
        assert !h.opened("a");
        assert h.back().isEmpty() && h.forward().equals(Arrays.asList("b", "c"));
        h.pending = "b";
        assert !h.opened("b");
        // Forward again.
        h.pending = "c";
        assert !h.opened("c");
        assert h.forward().isEmpty() && h.back().equals(Arrays.asList("b", "a"));

        // Back twice, then a channel opened by hand drops what was ahead.
        h.pending = "b";
        h.opened("b");
        h.pending = "a";
        h.opened("a");
        assert h.opened("d");
        assert h.logins.equals(Arrays.asList("a", "d")) && h.forward().isEmpty();

        // A channel opened by hand that was already in the list moves to the end.
        h.opened("e");
        h.opened("a");
        assert h.logins.equals(Arrays.asList("d", "e", "a"));

        // An arrow target that never started (offline, opened something else): treated as opened by hand.
        h.pending = "d";
        assert h.opened("x");
        assert h.logins.equals(Arrays.asList("d", "e", "a", "x")) && h.pending == null;

        // The list keeps the last MAX.
        StreamHistory big = new StreamHistory();
        for (int i = 0; i < StreamHistory.MAX + 5; i++) {
            big.opened("c" + i);
        }
        assert big.logins.size() == StreamHistory.MAX && big.logins.get(0).equals("c5");
        assert big.cursor == StreamHistory.MAX - 1;

        // Restore: duplicates and blanks out, cursor at the end.
        StreamHistory r = new StreamHistory();
        r.restore(Arrays.asList("a", "", "b", "a", "c"));
        assert r.logins.equals(Arrays.asList("a", "b", "c")) && r.cursor == 2 && r.forward().isEmpty();

        // Followed channels go round.
        List<String> live = Arrays.asList("p", "q", "r");
        assert "q".equals(StreamHistory.neighbour(live, "p", true));
        assert "p".equals(StreamHistory.neighbour(live, "r", true));
        assert "r".equals(StreamHistory.neighbour(live, "p", false));
        assert "p".equals(StreamHistory.neighbour(live, "zz", true));
        assert "r".equals(StreamHistory.neighbour(live, "zz", false));
        assert StreamHistory.neighbour(List.of("p"), "p", true) == null;
        assert "p".equals(StreamHistory.neighbour(List.of("p"), "zz", true));
        assert StreamHistory.neighbour(List.of(), "p", true) == null;

        // Round from the neighbour, without the current one; from an end when it isn't in the list.
        assert StreamHistory.round(live, "p", true).equals(Arrays.asList("q", "r"));
        assert StreamHistory.round(live, "p", false).equals(Arrays.asList("r", "q"));
        assert StreamHistory.round(live, "q", true).equals(Arrays.asList("r", "p"));
        assert StreamHistory.round(live, "zz", true).equals(Arrays.asList("p", "q", "r"));
        assert StreamHistory.round(live, "zz", false).equals(Arrays.asList("r", "q", "p"));
        assert StreamHistory.round(List.of("p"), "p", true).isEmpty();

        // Following's answer: edges or nodes, logins lowercased, blanks out.
        StreamHistory.noteFollowing(new org.json.JSONObject("{\"followedLiveUsers\":{\"edges\":[{\"node\":{\"login\":\"KaiCenat\"}},"
                + "{\"node\":{\"login\":\"\"}},{\"node\":null},{\"node\":{\"id\":\"1\",\"stream\":{\"broadcaster\":{\"login\":\"lofigirl\"}}}}]}}"));
        assert StreamHistory.following.equals(Arrays.asList("kaicenat", "lofigirl"));
        StreamHistory.noteFollowing(new org.json.JSONObject("{\"followedLiveUsers\":{\"nodes\":[{\"login\":\"a\"}]}}"));
        assert StreamHistory.following.equals(List.of("a"));
        StreamHistory.noteFollowing(new org.json.JSONObject("{\"follows\":{}}"));
        assert StreamHistory.following.equals(List.of("a")); // no live list: the old one stays

        System.out.println("StreamSwitchSelfCheck: ok");
    }
}
