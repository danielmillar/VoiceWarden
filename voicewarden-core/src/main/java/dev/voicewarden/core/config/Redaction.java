package dev.voicewarden.core.config;

final class Redaction {

    private Redaction() {
    }

    static String secret(String value) {
        return value == null || value.isEmpty() ? "<unset>" : "<redacted>";
    }
}
