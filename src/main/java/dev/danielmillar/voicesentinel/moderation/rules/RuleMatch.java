package dev.danielmillar.voicesentinel.moderation.rules;

/**
 * A single rule hit inside a transcript.
 *
 * @param ruleId      id of the rule (its key in rules.yml, or a generated id for wordlist.txt sections)
 * @param category    human readable category, e.g. "Profanity"
 * @param action      whether this hit only flags or counts towards an auto-mute
 * @param weight      how many flags this hit counts as (>= 1)
 * @param matchedText the exact substring of the ORIGINAL transcript that matched ({@code transcript.substring(start, end)})
 * @param start       inclusive char offset into the original transcript
 * @param end         exclusive char offset into the original transcript
 */
public record RuleMatch(String ruleId, String category, RuleAction action, int weight, String matchedText, int start, int end) {
}
