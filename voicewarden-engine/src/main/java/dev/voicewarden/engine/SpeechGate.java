package dev.voicewarden.engine;

public interface SpeechGate extends AutoCloseable {

    SpeechGate OPEN = new SpeechGate() {
        @Override
        public boolean hasSpeech(float[] samples) {
            return true;
        }

        @Override
        public String describe() {
            return "off";
        }

        @Override
        public void close() {
        }
    };

    boolean hasSpeech(float[] samples);

    String describe();

    @Override
    void close();
}
