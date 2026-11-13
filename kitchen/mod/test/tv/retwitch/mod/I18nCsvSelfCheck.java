package tv.retwitch.mod;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM self-check for the in-app translation files. Run from kitchen/:
 *   javac -d work/selfcheck mod/src/tv/retwitch/mod/I18nCsv.java mod/test/tv/retwitch/mod/I18nCsvSelfCheck.java
 *   java -ea -cp work/selfcheck tv.retwitch.mod.I18nCsvSelfCheck
 */
public final class I18nCsvSelfCheck {

    public static void main(String[] args) {
        // A spreadsheet file: BOM, CRLF, quotes, a line break and a separator inside a cell.
        List<List<String>> rows = I18nCsv.parse("﻿en,ru,de,where\r\n"
                + "\"Ban %s\",\"Бан %s\",Bannen,A.java:1\r\n"
                + "\"Say \"\"hi\"\"\",Привет,\"Sag \"\"hallo\"\",\nja\",B.java:2\r\n");
        assert rows.size() == 3 : rows;
        assert rows.get(0).equals(Arrays.asList("en", "ru", "de", "where")) : rows.get(0);
        assert rows.get(2).get(0).equals("Say \"hi\"") : rows.get(2);
        assert rows.get(2).get(2).equals("Sag \"hallo\",\nja") : rows.get(2);

        // Excel with a Russian locale: semicolons; a last line without a line break.
        assert I18nCsv.parse("en;de\nSpeed;Tempo").get(1).equals(Arrays.asList("Speed", "Tempo"));
        // Written and read back unchanged.
        String written = I18nCsv.write(Arrays.asList(new String[]{"en", "de"}, new String[]{"a,b", "x\"y\nz"}));
        assert written.startsWith("﻿");
        assert I18nCsv.parse(written).get(1).equals(Arrays.asList("a,b", "x\"y\nz")) : I18nCsv.parse(written);

        assert I18nCsv.withEdgesOf("Update ", "Actualización").equals("Actualización ");
        assert I18nCsv.withEdgesOf(" (%s)", "(%s) ").equals(" (%s)");
        assert I18nCsv.placeholders("%s banned %s%s").equals(I18nCsv.placeholders("%s baneó a %s%s"));
        assert I18nCsv.placeholders("50%% de %d").equals(Arrays.asList("%%", "%d"));

        Map<String, String> russian = new HashMap<>();
        russian.put("Ban %s", "Бан %s");
        russian.put("Speed", "Скорость");
        russian.put("Update ", "Обновление ");
        russian.put("Text size", "Размер текста");
        Map<String, String> es = Collections.singletonMap("Speed", "Velocidad");
        I18nCsv.Result r = I18nCsv.check(I18nCsv.parse("check,en,ru,es,where\n"
                + "NEW,Ban %s,Бан %s,Banear,x\n"      // placeholder lost
                + ",Speed,Скорость,Velocidad,x\n"      // same as the build
                + ",Update ,Обновление ,Actualización,x\n" // the trailing space comes back
                + ",Text size,Размер текста,,x\n"      // empty
                + ",Gone,Ушло,Ido,x\n"),               // not in this build
                russian, language -> "es".equals(language) ? es : Collections.emptyMap());
        assert r.error == null && "es".equals(r.language) : r.error;
        assert r.badPlaceholders.size() == 1 && r.same == 1 && r.empty == 1 && r.stale == 1 && r.edges == 1;
        assert r.strings.equals(Collections.singletonMap("Update ", "Actualización ")) : r.strings;

        // Fixes to the Russian text: the ru column is the language when there is no other.
        I18nCsv.Result ru = I18nCsv.check(I18nCsv.parse("en,ru\nSpeed,Темп\nText size,Размер текста\n"),
                russian, language -> russian);
        assert "ru".equals(ru.language) && ru.strings.equals(Collections.singletonMap("Speed", "Темп")) && ru.same == 1;

        assert I18nCsv.ERROR_NO_EN.equals(I18nCsv.check(I18nCsv.parse("ru,de\nа,b\n"), russian, l -> es).error);
        assert I18nCsv.ERROR_NO_LANGUAGE.equals(I18nCsv.check(I18nCsv.parse("en,where\nSpeed,x\n"), russian, l -> es).error);
        assert I18nCsv.ERROR_MANY_LANGUAGES.equals(I18nCsv.check(I18nCsv.parse("en,de,fr\nSpeed,a,b\n"), russian, l -> es).error);
        System.out.println("I18nCsv self-check ok");
    }
}
