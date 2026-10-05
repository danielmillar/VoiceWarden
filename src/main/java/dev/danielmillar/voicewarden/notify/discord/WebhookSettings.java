package dev.danielmillar.voicewarden.notify.discord;

/**
 * One webhook target. Empty strings mean "not set / use default".
 *
 * @param enabled             master switch for this webhook
 * @param url                 webhook URL (https://discord.com/api/webhooks/...)
 * @param username            overrides the webhook display name
 * @param avatarUrl           overrides the webhook avatar
 * @param color               embed colour as 0xRRGGBB; -1 = use the built-in colour for the event type
 * @param pingRoleId          role id (digits) to mention, "" = none
 * @param includeAudio        attach the utterance / report audio as a WAV file
 * @param includeTranscript   include the transcript text
 * @param includeContext      include the speaker's preceding utterances
 * @param includeServerInfo   include server name and the player's world/coordinates
 * @param spoilerFlaggedWords wrap flagged words in ||spoilers||
 * @param title               embed title template, "" = built-in. Placeholders: {player} {action} {duration} {id}
 * @param footer              embed footer template, "" = built-in. Placeholders: {server} {id}
 */
public record WebhookSettings(
        boolean enabled,
        String url,
        String username,
        String avatarUrl,
        int color,
        String pingRoleId,
        boolean includeAudio,
        boolean includeTranscript,
        boolean includeContext,
        boolean includeServerInfo,
        boolean spoilerFlaggedWords,
        String title,
        String footer
) {
    public static WebhookSettings disabled() {
        return new WebhookSettings(false, "", "VoiceWarden", "", -1, "", true, true, true, true, true, "", "");
    }

    public boolean usable() {
        return enabled && !url.isBlank();
    }
}
