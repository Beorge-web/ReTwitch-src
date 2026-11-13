package tv.retwitch.mod;

/**
 * JVM self-check for {@link BoostyGoal#parse}: the open goal of Boosty's answer (finishTime null), its title and the
 * rest of the description, the percentage. Build/run like ads/PlaylistSelfCheck with a real org.json jar first on the
 * classpath (android.jar only has stubs), main class tv.retwitch.mod.BoostyGoalSelfCheck.
 */
public final class BoostyGoalSelfCheck {

    /** Boosty's answer of 2026-11-12, trimmed: two finished goals and the open one. */
    private static final String ANSWER = "{\"data\":["
            + "{\"id\":816162,\"type\":\"money\",\"priority\":0,\"finishTime\":1785525662,\"createdAt\":1784995354,"
            + "\"currentSum\":22098.18,\"targetSum\":18150,\"bloggerCurrency\":\"RUB\",\"description\":\"Сбор (август)\\nСпасибо\"},"
            + "{\"id\":838826,\"type\":\"money\",\"priority\":0,\"finishTime\":null,\"createdAt\":1793460000,"
            + "\"currentSum\":11467.15,\"targetSum\":25000,\"bloggerCurrency\":\"RUB\","
            + "\"currencyCurrentSums\":{\"USD\":130.5},\"currencyTargetSums\":{\"USD\":321},"
            + "\"description\":\"Сбор на аренду серверов(ноябрь) и на claude :)\\nВыражаю огромную благодарность всем поддержавшим! <3\"},"
            + "{\"id\":824951,\"type\":\"money\",\"priority\":0,\"finishTime\":1788192456,\"createdAt\":1788170446,"
            + "\"currentSum\":20070.16,\"targetSum\":17500,\"bloggerCurrency\":\"RUB\",\"description\":\"Сбор (сентябрь)\"}]}";

    public static void main(String[] args) throws Exception {
        BoostyGoal goal = BoostyGoal.parse(ANSWER);
        assert goal != null && goal.id == 838826 : "the open one";
        assert goal.title.equals("Сбор на аренду серверов(ноябрь) и на claude :)") : goal.title;
        assert goal.more.equals("Выражаю огромную благодарность всем поддержавшим! <3") : goal.more;
        assert goal.percent() == 46 : goal.percent();
        assert goal.currentUsd == 130.5 && goal.targetUsd == 321;

        // Every goal finished: nothing to show.
        assert BoostyGoal.parse("{\"data\":[{\"id\":1,\"finishTime\":5,\"targetSum\":10,\"currentSum\":10}]}") == null;
        assert BoostyGoal.parse("{\"data\":[]}") == null;
        // Two open: the higher priority, then the newer.
        String two = "{\"data\":[{\"id\":1,\"finishTime\":null,\"priority\":0,\"createdAt\":9,\"targetSum\":10,\"description\":\"a\"},"
                + "{\"id\":2,\"finishTime\":null,\"priority\":1,\"createdAt\":1,\"targetSum\":10,\"description\":\"b\"},"
                + "{\"id\":3,\"finishTime\":null,\"priority\":0,\"createdAt\":10,\"targetSum\":10,\"description\":\"c\"}]}";
        assert BoostyGoal.parse(two).id == 2;
        // No finishTime key at all counts as open; no description: a placeholder title.
        BoostyGoal bare = BoostyGoal.parse("{\"data\":[{\"id\":7,\"targetSum\":100,\"currentSum\":150}]}");
        assert bare.id == 7 && bare.title.equals("Boosty") && bare.more.isEmpty() && bare.percent() == 150;
        System.out.println("BoostyGoalSelfCheck ok");
    }
}
