package dev.voicewarden.core.ipc;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class RecordingResultListener implements EngineResultListener {

    final BlockingQueue<IpcMessage.TranscriptResult> transcripts = new LinkedBlockingQueue<>();
    final BlockingQueue<IpcMessage.EngineStatusResponse> statuses = new LinkedBlockingQueue<>();
    final BlockingQueue<Long> pongs = new LinkedBlockingQueue<>();
    final CountDownLatch disconnected = new CountDownLatch(1);

    @Override
    public void onTranscript(IpcMessage.TranscriptResult result) {
        transcripts.add(result);
    }

    @Override
    public void onPong(long nonce, double roundTripMs) {
        pongs.add(nonce);
    }

    @Override
    public void onStatus(IpcMessage.EngineStatusResponse status) {
        statuses.add(status);
    }

    @Override
    public void onDisconnected(Throwable cause) {
        disconnected.countDown();
    }

    IpcMessage.TranscriptResult nextTranscript() throws InterruptedException {
        IpcMessage.TranscriptResult result = transcripts.poll(5, TimeUnit.SECONDS);
        if (result == null) {
            throw new AssertionError("No transcript within 5 s");
        }
        return result;
    }
}
