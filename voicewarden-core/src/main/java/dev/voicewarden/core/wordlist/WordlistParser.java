package dev.voicewarden.core.wordlist;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WordlistParser {

    public static final String DEFAULT_RESOURCE = "/default-wordlist.txt";
    public static final String CONFIG_LANGUAGE = "config";

    private static final Pattern SECTION = Pattern.compile("^\\[\\s*([A-Za-z]{2,8})\\s*-\\s*([A-Za-z]+)\\s*]$");
    private static final char BYTE_ORDER_MARK = '﻿';
    private static final String DEFAULT_WORDLIST = loadDefaultWordlist();

    private WordlistParser() {
    }

    public static ParsedWordlist parse(String content) {
        return parse(content, WordlistConfig.defaults());
    }

    public static ParsedWordlist parse(String content, WordlistConfig config) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(config, "config");
        Accumulator acc = new Accumulator(config.caseSensitive());
        Section section = null;
        boolean skipping = false;
        String[] lines = stripBom(content).split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            int lineNo = index + 1;
            String line = lines[index].trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            if (line.charAt(0) == '[') {
                section = parseSection(line, lineNo, acc.warnings);
                skipping = section != null && !config.isLanguageEnabled(section.language);
                continue;
            }
            if (section == null) {
                acc.warnings.add("line " + lineNo + ": entry '" + line + "' is outside a valid [XX-TIER] section");
                continue;
            }
            if (!skipping) {
                acc.add(line, section.tier, section.language, "line " + lineNo);
            }
        }
        for (WordlistTier tier : WordlistTier.values()) {
            for (String word : config.extraWords(tier)) {
                acc.add(word, tier, CONFIG_LANGUAGE, "config.yml");
            }
        }
        return new ParsedWordlist(acc.entries, acc.includedLanguages, acc.warnings);
    }

    public static String defaultWordlistResource() {
        return DEFAULT_WORDLIST;
    }

    private static String loadDefaultWordlist() {
        try (InputStream in = WordlistParser.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled wordlist " + DEFAULT_RESOURCE + " is missing from the jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled wordlist " + DEFAULT_RESOURCE, e);
        }
    }

    public static ParsedWordlist parseDefault(WordlistConfig config) {
        return parse(defaultWordlistResource(), config);
    }

    private static Section parseSection(String line, int lineNo, List<String> warnings) {
        Matcher matcher = SECTION.matcher(line);
        if (!matcher.matches()) {
            warnings.add("line " + lineNo + ": malformed section header '" + line
                    + "', expected [XX-PROFANITY], [XX-MODERATE], [XX-MUTE] or [XX-ALLOW]");
            return null;
        }
        Optional<WordlistTier> tier = WordlistTier.fromSectionName(matcher.group(2));
        if (tier.isEmpty()) {
            warnings.add("line " + lineNo + ": unknown tier '" + matcher.group(2)
                    + "', expected PROFANITY, MODERATE, MUTE or ALLOW");
            return null;
        }
        return new Section(matcher.group(1).toLowerCase(Locale.ROOT), tier.get());
    }

    private static String stripBom(String content) {
        return !content.isEmpty() && content.charAt(0) == BYTE_ORDER_MARK ? content.substring(1) : content;
    }

    private record Section(String language, WordlistTier tier) {
    }

    private static final class Accumulator {
        private final boolean caseSensitive;
        private final List<WordlistEntry> entries = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final Set<String> includedLanguages = new TreeSet<>();
        private final List<String> warnings = new ArrayList<>();

        private Accumulator(boolean caseSensitive) {
            this.caseSensitive = caseSensitive;
        }

        private void add(String raw, WordlistTier tier, String language, String origin) {
            String term = TextNormalizer.normalizeTerm(raw, true);
            if (term.isEmpty()) {
                return;
            }
            String key = language + '\u0000' + tier + '\u0000' + TextNormalizer.normalizeTerm(term, caseSensitive);
            if (!seen.add(key)) {
                warnings.add(origin + ": duplicate entry '" + term + "' in [" + language.toUpperCase(Locale.ROOT)
                        + "-" + tier + "]");
                return;
            }
            entries.add(new WordlistEntry(term, tier, language));
            includedLanguages.add(language);
        }
    }
}
