package com.mentra.asg_client.io.direct;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Waits for queued PCM to be played, with cancellation and a stalled-device deadline. */
final class PcmPlaybackDrain {
    interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }

    static void await(long frames, LongSupplier position, BooleanSupplier cancelled,
            LongSupplier clock, Sleeper sleeper) throws InterruptedException {
        long lastPosition = position.getAsLong();
        long progressAt = clock.getAsLong();
        while (!cancelled.getAsBoolean() && lastPosition < frames) {
            sleeper.sleep(10);
            long current = position.getAsLong();
            if (current > lastPosition) progressAt = clock.getAsLong();
            else if (clock.getAsLong() - progressAt >= 5000)
                throw new IllegalStateException("audio_playback_drain_stalled");
            lastPosition = current;
        }
    }

    private PcmPlaybackDrain() {}
}
