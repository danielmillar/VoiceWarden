package dev.voicewarden.plugin.scheduler;

public interface TaskHandle {

    TaskHandle NONE = new TaskHandle() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    void cancel();

    boolean isCancelled();
}
