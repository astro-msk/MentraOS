package com.mentra.asg_client.io.direct;

import static org.junit.Assert.*;
import org.junit.Test;

public class PcmPlaybackDrainTest {
    @Test public void waitsUntilFinalBufferedFramePlays() throws Exception {
        long[] position = {0}, clock = {0};
        PcmPlaybackDrain.await(2400, () -> position[0], () -> false, () -> clock[0],
                ms -> { clock[0] += ms; position[0] += 240; });
        assertEquals(2400, position[0]);
        assertEquals(100, clock[0]);
    }

    @Test public void abortReleasesWaitWithoutClaimingCompletion() throws Exception {
        long[] clock = {0};
        PcmPlaybackDrain.await(2400, () -> 0, () -> clock[0] >= 30, () -> clock[0],
                ms -> clock[0] += ms);
        assertEquals(30, clock[0]);
    }

    @Test public void stalledDeviceFailsInsteadOfHanging() throws Exception {
        long[] clock = {0};
        try {
            PcmPlaybackDrain.await(2400, () -> 0, () -> false, () -> clock[0],
                    ms -> clock[0] += ms);
            fail("Expected stalled playback failure");
        } catch (IllegalStateException expected) {
            assertEquals("audio_playback_drain_stalled", expected.getMessage());
        }
    }
}
