package tv.retwitch.mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The translation files of {@link TranslationFiles}: the kitchen/i18n/&lt;lang&gt;.csv format (en, ru, &lt;lang&gt;, where)
 * and the checks a loaded file goes through. Pure Java, so {@code I18nCsvSelfCheck} runs it on the JVM.
 */
final class I18nCsv {

    /** Same as PLACEHOLDER in kitchen/i18n.py: a translation with another set would make String.format throw. */
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[-#+0,(]*\\d*(\\.\\d+)?[sdfxc%n]");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

    private I18nCsv() {
    }

    /** Rows of a CSV file: quotes, "" inside them and line breaks in a cell; the separator is guessed from the header. */
    static List<List<String>> parse(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1); // spreadsheets save a BOM
        }
        char separator = separator(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < n && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == separator) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Excel with a Russian or German locale saves CSV with semicolons. */
    private static char separator(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        int commas = count(header, ','), semicolons = count(header, ';'), tabs = count(header, '\t');
        return semicolons > commas && semicolons >= tabs ? ';' : tabs > commas ? '\t' : ',';
    }

    private static int count(String text, char c) {
        int found = 0;
        for (int i = 0; i < text.length(); i++) {
            found += text.charAt(i) == c ? 1 : 0;
        }
        return found;
    }

    /** A CSV file in the kitchen/i18n format: comma separated, quoted where needed, with a BOM for spreadsheets. */
    static String write(List<String[]> rows) {
        StringBuilder out = new StringBuilder("﻿");
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                String cell = row[i] != null ? row[i] : "";
                if (cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0) {
                    out.append('"').append(cell.replace("\"", "\"\"")).append('"');
                } else {
                    out.append(cell);
                }
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    static List<String> placeholders(String text) {
        List<String> found = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        Collections.sort(found);
        return found;
    }

    /** {@code text} with the spaces and line breaks at the ends of {@code en}: the code glues the string to others by them. */
    static String withEdgesOf(String en, String text) {
        int start = 0;
        while (start < en.length() && Character.isWhitespace(en.charAt(start))) {
            start++;
        }
        int end = en.length();
        while (end > start && Character.isWhitespace(en.charAt(end - 1))) {
            end--;
        }
        return en.substring(0, start) + text.trim() + en.substring(end);
    }

    /** What a loaded file gives: the language, the strings to keep, and counts for the report. */
    static final class Result {
        /** Why the file can't be used, or null. */
        String error;
        String language;
        /** English -> text, only those that differ from the build. */
        final Map<String, String> strings = new LinkedHashMap<>();
        int same;
        int empty;
        int stale;
        int edges;
        final List<String> badPlaceholders = new ArrayList<>();
    }

    static final String ERROR_NO_EN = "no_en";
    static final String ERROR_NO_LANGUAGE = "no_language";
    static final String ERROR_MANY_LANGUAGES = "many_languages";

    /**
     * Checks a loaded file against the strings of this build.
     *
     * @param russian English -> Russian of every UI string (the catalog)
     * @param shipped English -> translation the build already has for the file's language; for Russian, {@code russian}
     */
    static Result check(List<List<String>> rows, Map<String, String> russian, ShippedLookup shipped) {
        Result result = new Result();
        if (rows.isEmpty()) {
            result.error = ERROR_NO_EN;
            return result;
        }
        List<String> header = rows.get(0);
        int en = -1, column = -1, ru = -1;
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).trim().toLowerCase(java.util.Locale.ROOT);
            if (name.equals("en")) {
                en = i;
            } else if (name.equals("ru")) {
                ru = i;
            } else if (LANGUAGE.matcher(name).matches()) {
                if (column >= 0) {
                    result.error = ERROR_MANY_LANGUAGES;
                    return result;
                }
                column = i;
            }
        }
        if (en < 0) {
            result.error = ERROR_NO_EN;
            return result;
        }
        if (column < 0) {
            column = ru; // a file of fixes to the Russian text
        }
        if (column < 0) {
            result.error = ERROR_NO_LANGUAGE;
            return result;
        }
        result.language = header.get(column).trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, String> built = shipped.forLanguage(result.language);
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String key = en < row.size() ? row.get(en) : "";
            if (key.isEmpty()) {
                continue;
            }
            String text = column < row.size() ? row.get(column) : "";
            if (text.trim().isEmpty()) {
                result.empty++;
                continue;
            }
            if (!russian.containsKey(key)) {
                result.stale++;
                continue;
            }
            if (!placeholders(key).equals(placeholders(text))) {
                result.badPlaceholders.add(key + " > " + text);
                continue;
            }
            String fixed = withEdgesOf(key, text);
            if (!fixed.equals(text)) {
                result.edges++;
            }
            if (fixed.equals(built.get(key))) {
                result.same++;
            } else {
                result.strings.put(key, fixed);
            }
        }
        return result;
    }

    /** The build's translations of a language; the file's language is only known once its header is read. */
    interface ShippedLookup {
        Map<String, String> forLanguage(String language);
    }
}
