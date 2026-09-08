package com.mentra.asg_client.io.direct;

import com.mentra.asg_client.AsgConstants;

/** Bounded PCM energy endpoint; not speech recognition or a wake-word listener. */
public final class VoiceEndpoint {
    private final int mRate;
    private int mTotal;
    private int mSilence;
    private int mVoiced;

    /** Use the recorder's PCM sample rate. */
    public VoiceEndpoint(int rate) { mRate = rate; }

    /** Consume a PCM16 little-endian chunk and report whether recording should stop. */
    public boolean accept(byte[] data, int offset, int length) {
        int samples = length / 2;
        if (samples == 0) return false;
        double energy = 0;
        for (int i = offset; i + 1 < offset + length; i += 2) {
            int value = (short) ((data[i] & 255) | (data[i + 1] << 8));
            energy += (double) value * value;
        }
        mTotal += samples;
        if (Math.sqrt(energy / samples) >= AsgConstants.DIRECT_VOICE_RMS_THRESHOLD) {
            mVoiced += samples;
            mSilence = 0;
        } else {
            mSilence += samples;
        }
        return hasSpeech()
                ? mSilence >= mRate * AsgConstants.DIRECT_VOICE_SILENCE_MS / 1000
                : mTotal >= mRate * AsgConstants.DIRECT_VOICE_INITIAL_SILENCE_MS / 1000;
    }

    /** Require at least 100 ms above the energy threshold. */
    public boolean hasSpeech() { return mVoiced >= mRate / 10; }
}
