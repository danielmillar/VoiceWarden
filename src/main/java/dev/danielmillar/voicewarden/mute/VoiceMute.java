package dev.danielmillar.voicewarden.mute;

/**
 * One set of Simple Voice Chat permissions a mute denies through the LuckPerms mirror. An empty
 * {@code listenPermission} means listening was not denied; an empty {@code serverContext} means every server.
 */
public record VoiceMute(String speakPermission, String listenPermission, String serverContext) {
}
