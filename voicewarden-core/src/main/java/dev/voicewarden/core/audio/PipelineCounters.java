package dev.voicewarden.core.audio;

import java.util.concurrent.atomic.LongAdder;

final class PipelineCounters {

    final LongAdder framesSubmitted = new LongAdder();
    final LongAdder framesProcessed = new LongAdder();
    final LongAdder framesDroppedQueueFull = new LongAdder();
    final LongAdder framesRejectedInvalid = new LongAdder();
    final LongAdder liveChunksEmitted = new LongAdder();
    final LongAdder finalUtterancesEmitted = new LongAdder();
    final LongAdder acousticAbuseEmitted = new LongAdder();
    final LongAdder utterancesDiscardedTooShort = new LongAdder();
    final LongAdder utterancesDropped = new LongAdder();
    final LongAdder decodeErrors = new LongAdder();
    final LongAdder processingErrors = new LongAdder();
}
