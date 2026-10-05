package dev.danielmillar.voicewarden.moderation.rules;

/** What a rule hit feeds into. Mirrors the PROFANITY / MUTE sections of wordlist.txt. */
public enum RuleAction {
    /** Flag only: alert staff and run profanity commands, never counts towards an auto-mute. */
    FLAG,
    /** Counts towards the flag threshold that triggers an auto-mute. */
    MUTE
}
