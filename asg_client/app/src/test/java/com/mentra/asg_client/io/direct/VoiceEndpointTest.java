package com.mentra.asg_client.io.direct;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceEndpointTest {
    @Test public void silenceNeverBecomesSpeechAndEndsAfterFourSeconds() {
        VoiceEndpoint gate = new VoiceEndpoint(16000);
        byte[] silence = new byte[640];
        for (int i = 0; i < 199; i++) assertFalse(gate.accept(silence, 0, silence.length));
        assertTrue(gate.accept(silence, 0, silence.length));
        assertFalse(gate.hasSpeech());
    }

    @Test public void speechEndsOnlyAfterTrailingSilence() {
        VoiceEndpoint gate = new VoiceEndpoint(16000);
        byte[] speech = new byte[640];
        for (int i = 0; i < speech.length; i += 2) { speech[i] = 0; speech[i + 1] = 8; }
        for (int i = 0; i < 10; i++) assertFalse(gate.accept(speech, 0, speech.length));
        assertTrue(gate.hasSpeech());
        byte[] silence = new byte[640];
        for (int i = 0; i < 59; i++) assertFalse(gate.accept(silence, 0, silence.length));
        assertTrue(gate.accept(silence, 0, silence.length));
    }
}
