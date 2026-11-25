package dev.voicewarden.core.wordlist;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class WordlistRegistry {

    private final AtomicReference<RuleMatcher> current;

    public WordlistRegistry() {
        this(RuleMatcher.empty());
    }

    public WordlistRegistry(AhoCorasickMatcher initial) {
        this(RuleMatcher.fromWordlist(initial));
    }

    public WordlistRegistry(RuleMatcher initial) {
        current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public WordlistMatchResult match(String text) {
        return current.get().match(text);
    }

    public AhoCorasickMatcher current() {
        return current.get().wordlistMatcher();
    }

    public RuleMatcher matcher() {
        return current.get();
    }

    public AhoCorasickMatcher reload(AhoCorasickMatcher next) {
        return current.getAndSet(RuleMatcher.fromWordlist(next)).wordlistMatcher();
    }

    public RuleMatcher reload(RuleMatcher next) {
        return current.getAndSet(Objects.requireNonNull(next, "next"));
    }
}
