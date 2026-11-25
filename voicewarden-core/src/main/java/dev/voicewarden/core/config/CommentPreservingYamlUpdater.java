package dev.voicewarden.core.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

public final class CommentPreservingYamlUpdater {

    public static final String DEFAULT_VERSION_KEY = "config-version";

    private final String versionKey;
    private final Set<String> userOwnedPaths;

    public CommentPreservingYamlUpdater(String versionKey, Set<String> userOwnedPaths) {
        this.versionKey = versionKey;
        this.userOwnedPaths = Set.copyOf(userOwnedPaths);
    }

    public static CommentPreservingYamlUpdater forConfig() {
        return new CommentPreservingYamlUpdater(DEFAULT_VERSION_KEY, ConfigFiles.CONFIG_USER_OWNED_PATHS);
    }

    public static CommentPreservingYamlUpdater forMessages() {
        return new CommentPreservingYamlUpdater(DEFAULT_VERSION_KEY, Set.of());
    }

    public YamlUpdateResult merge(String template, String user) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(user, "user");
        if (user.isBlank()) {
            return new YamlUpdateResult(template, false, true, List.of(), false, Optional.empty());
        }
        Optional<String> userProblem = yamlProblem(user);
        if (userProblem.isPresent()) {
            return YamlUpdateResult.skipped(user, "existing file is not valid YAML: " + userProblem.get());
        }
        String lineSeparator = user.contains("\r\n") ? "\r\n" : "\n";
        boolean endsWithNewline = user.isEmpty() || user.endsWith("\n");
        YamlOutline userOutline = YamlOutline.parse(splitLines(user));
        YamlOutline templateOutline = YamlOutline.parse(splitLines(template));

        List<String> added = new ArrayList<>();
        Indentation indentation = new Indentation(indentStep(userOutline.entries), indentStep(templateOutline.entries));
        mergeLevel(userOutline.entries, templateOutline.entries, -1, "", added, true, indentation);
        boolean versionUpdated = updateVersion(userOutline, templateOutline);
        if (added.isEmpty() && !versionUpdated) {
            return new YamlUpdateResult(user, false, false, List.of(), false, Optional.empty());
        }

        String merged = joinLines(userOutline.emit(), lineSeparator, endsWithNewline);
        Optional<String> mergedProblem = yamlProblem(merged).or(() -> semanticProblem(user, merged, added));
        if (mergedProblem.isPresent()) {
            return YamlUpdateResult.skipped(user, "the merged file would not be safe to write: " + mergedProblem.get());
        }
        return new YamlUpdateResult(merged, false, true, added, versionUpdated, Optional.empty());
    }

    public YamlUpdateResult update(Path file, String template) {
        try {
            if (Files.notExists(file)) {
                writeAtomically(file, template);
                return new YamlUpdateResult(template, true, true, List.of(), false, Optional.empty());
            }
            String existing = Files.readString(file, StandardCharsets.UTF_8);
            YamlUpdateResult result = merge(template, existing);
            if (result.changed()) {
                writeAtomically(file, result.content());
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not update " + file, e);
        }
    }

    static void writeAtomically(Path file, String content) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path directory = absolute.getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void mergeLevel(
            List<YamlOutline.Entry> userEntries,
            List<YamlOutline.Entry> templateEntries,
            int userParentIndent,
            String parentPath,
            List<String> added,
            boolean root,
            Indentation indentation) {
        if (templateEntries.isEmpty()) {
            return;
        }
        int userIndent;
        if (!userEntries.isEmpty()) {
            userIndent = userEntries.get(0).indent;
        } else if (root) {
            userIndent = 0;
        } else {
            userIndent = userParentIndent + indentation.user();
        }
        for (int t = 0; t < templateEntries.size(); t++) {
            YamlOutline.Entry template = templateEntries.get(t);
            String path = parentPath.isEmpty() ? template.key : parentPath + "." + template.key;
            int existing = indexOf(userEntries, template.key);
            if (existing >= 0) {
                YamlOutline.Entry user = userEntries.get(existing);
                boolean descend = template.isMapping()
                        && !user.isInserted()
                        && !userOwnedPaths.contains(path)
                        && (user.isMapping() || user.hasNoValue());
                if (descend) {
                    mergeLevel(user.children, template.children, user.indent, path, added, false, indentation);
                }
                continue;
            }
            int position = insertionIndex(userEntries, templateEntries, t);
            boolean atTop = root && position == 0;
            List<String> lines = insertionLines(template, userIndent, indentation, atTop);
            if (atTop && !userEntries.isEmpty()) {
                lines = withHeaderMovedAbove(userEntries.get(0), lines);
            }
            userEntries.add(position, YamlOutline.Entry.inserted(template.key, userIndent, lines));
            added.add(path);
        }
    }

    private static int indexOf(List<YamlOutline.Entry> entries, String key) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private static int insertionIndex(List<YamlOutline.Entry> userEntries, List<YamlOutline.Entry> templateEntries, int templateIndex) {
        for (int p = templateIndex - 1; p >= 0; p--) {
            int found = indexOf(userEntries, templateEntries.get(p).key);
            if (found >= 0) {
                return found + 1;
            }
        }
        for (int n = templateIndex + 1; n < templateEntries.size(); n++) {
            int found = indexOf(userEntries, templateEntries.get(n).key);
            if (found >= 0) {
                return found;
            }
        }
        return userEntries.size();
    }

    private static List<String> insertionLines(YamlOutline.Entry template, int userIndent, Indentation indentation, boolean atTop) {
        List<String> emitted = template.emit();
        int leadingCount = template.leading.size();
        int lastBlank = -1;
        for (int i = 0; i < leadingCount; i++) {
            if (emitted.get(i).isBlank()) {
                lastBlank = i;
            }
        }
        List<String> out = new ArrayList<>();
        if (lastBlank >= 0 && !atTop) {
            out.add("");
        }
        for (int i = lastBlank + 1; i < emitted.size(); i++) {
            out.add(reindent(emitted.get(i), template.indent, userIndent, indentation));
        }
        return out;
    }

    private static List<String> withHeaderMovedAbove(YamlOutline.Entry first, List<String> inserted) {
        int lastBlank = -1;
        for (int i = 0; i < first.leading.size(); i++) {
            if (first.leading.get(i).isBlank()) {
                lastBlank = i;
            }
        }
        if (lastBlank < 0) {
            List<String> out = new ArrayList<>(inserted);
            out.add("");
            return out;
        }
        List<String> header = new ArrayList<>(first.leading.subList(0, lastBlank + 1));
        first.leading.subList(0, lastBlank + 1).clear();
        List<String> out = new ArrayList<>(header);
        out.addAll(inserted);
        out.add("");
        return out;
    }

    private static String reindent(String line, int templateBase, int userBase, Indentation indentation) {
        if (line.isBlank()) {
            return line;
        }
        int indent = YamlOutline.indentOf(line);
        int relative = indent - templateBase;
        int scaled = relative > 0 && relative % indentation.template() == 0
                ? relative / indentation.template() * indentation.user()
                : relative;
        return " ".repeat(Math.max(0, userBase + scaled)) + line.substring(indent);
    }

    private static int indentStep(List<YamlOutline.Entry> entries) {
        int step = smallestStep(entries);
        return step == Integer.MAX_VALUE || step <= 0 ? 2 : step;
    }

    private static int smallestStep(List<YamlOutline.Entry> entries) {
        int step = Integer.MAX_VALUE;
        for (YamlOutline.Entry entry : entries) {
            if (!entry.children.isEmpty()) {
                step = Math.min(step, entry.children.get(0).indent - entry.indent);
                step = Math.min(step, smallestStep(entry.children));
            }
        }
        return step;
    }

    private record Indentation(int user, int template) {
    }

    private boolean updateVersion(YamlOutline user, YamlOutline template) {
        if (versionKey == null) {
            return false;
        }
        int userIndex = indexOf(user.entries, versionKey);
        int templateIndex = indexOf(template.entries, versionKey);
        if (userIndex < 0 || templateIndex < 0) {
            return false;
        }
        YamlOutline.Entry userEntry = user.entries.get(userIndex);
        if (userEntry.isInserted()) {
            return false;
        }
        String templateValue = template.entries.get(templateIndex).inlineValue();
        String userValue = userEntry.inlineValue();
        if (!isNewer(templateValue, userValue)) {
            return false;
        }
        userEntry.replaceInlineValue(templateValue);
        return true;
    }

    private static boolean isNewer(String candidate, String current) {
        Optional<BigDecimal> candidateNumber = number(candidate);
        if (candidateNumber.isEmpty()) {
            return false;
        }
        Optional<BigDecimal> currentNumber = number(current);
        return currentNumber.isEmpty() || candidateNumber.get().compareTo(currentNumber.get()) > 0;
    }

    private static Optional<BigDecimal> number(String value) {
        String unquoted = value.replace("\"", "").replace("'", "").strip();
        try {
            return Optional.of(new BigDecimal(unquoted));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static Optional<String> yamlProblem(String text) {
        try {
            new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(text).forEach(document -> { });
            return Optional.empty();
        } catch (YAMLException e) {
            return Optional.of(e.getMessage().lines().findFirst().orElse("parse error"));
        }
    }

    private Optional<String> semanticProblem(String user, String merged, List<String> added) {
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        flatten("", loadMap(user), before);
        flatten("", loadMap(merged), after);
        for (Map.Entry<String, Object> leaf : before.entrySet()) {
            if (leaf.getValue() != null && !leaf.getKey().equals(versionKey)
                    && !Objects.equals(leaf.getValue(), after.get(leaf.getKey()))) {
                return Optional.of("the existing value of " + leaf.getKey() + " would change");
            }
        }
        Map<?, ?> mergedTree = loadMap(merged);
        for (String path : added) {
            if (!resolves(mergedTree, path)) {
                return Optional.of("the new key " + path + " would not land where expected");
            }
        }
        return Optional.empty();
    }

    private static Map<?, ?> loadMap(String text) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        return root instanceof Map<?, ?> map ? map : Map.of();
    }

    private static void flatten(String prefix, Map<?, ?> map, Map<String, Object> out) {
        map.forEach((key, value) -> {
            String path = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                flatten(path, nested, out);
            } else {
                out.put(path, value);
            }
        });
    }

    private static boolean resolves(Map<?, ?> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return false;
            }
            Optional<? extends Map.Entry<?, ?>> entry = map.entrySet().stream()
                    .filter(candidate -> String.valueOf(candidate.getKey()).equals(part))
                    .findFirst();
            if (entry.isEmpty()) {
                return false;
            }
            current = entry.get().getValue();
        }
        return true;
    }

    private static List<String> splitLines(String text) {
        String normalized = text.replace("\r\n", "\n");
        if (normalized.isEmpty()) {
            return new ArrayList<>();
        }
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
    }

    private static String joinLines(List<String> lines, String separator, boolean trailingNewline) {
        String joined = String.join(separator, lines);
        return trailingNewline && !lines.isEmpty() ? joined + separator : joined;
    }
}
