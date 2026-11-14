package dev.danielmillar.voicewarden.notify.discord;

/**
 * What a test send to one webhook did.
 *
 * @param webhook "flags", "mutes" or "reports", or "" when the test could not run at all
 * @param status  Discord's HTTP status, or 0 when there was none
 * @param detail  why it failed, or ""
 */
public record WebhookTestResult(String webhook, Outcome outcome, int status, String detail) {
    public enum Outcome { SENT, OFF, FAILED }

    static WebhookTestResult notRun(String detail) {
        return new WebhookTestResult("", Outcome.FAILED, 0, detail);
    }
}
