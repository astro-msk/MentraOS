package com.mentra.asg_client.io.direct;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import com.mentra.asg_client.service.core.AsgClientService;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Streams bounded mono PCM from Cally through the glasses' I2S speaker path. */
public final class DirectPcmPlayer {
    private static final String TAG = "DirectPcmPlayer";
    private static final int MAX_CHUNK_BYTES = 8192;
    private static final byte[] END = new byte[0];
    // 256 full chunks hold about 43 seconds of 24 kHz mono PCM while using 2 MiB.
    private final BlockingQueue<byte[]> mQueue = new ArrayBlockingQueue<>(256);
    private String mStreamId;
    private int mNextSequence;
    private Thread mWorker;
    private volatile boolean mAbort;
    private boolean mFinishing;
    private Consumer<Boolean> mCompletion;

    public synchronized boolean begin(String streamId, int sampleRate) {
        if (streamId == null || streamId.isEmpty() || sampleRate != 24000) return false;
        // Preserve the current reply. A newer reply may start after its final sample drains.
        if (mStreamId != null) return false;
        AsgClientService service = AsgClientService.getInstance();
        if (service == null) return false;
        mStreamId = streamId;
        mNextSequence = 0;
        mAbort = false;
        mFinishing = false;
        mCompletion = null;
        mQueue.clear();
        mWorker = new Thread(() -> play(service, sampleRate, streamId), "cally-direct-pcm");
        mWorker.setDaemon(true);
        mWorker.start();
        return true;
    }

    public synchronized boolean write(String streamId, int sequence, byte[] data) {
        if (mFinishing || mStreamId == null || !streamId.equals(mStreamId) || sequence != mNextSequence
                || data == null || data.length == 0 || data.length > MAX_CHUNK_BYTES
                || (data.length & 1) != 0) return false;
        try {
            if (!mQueue.offer(Arrays.copyOf(data, data.length), 2, TimeUnit.SECONDS)) return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        mNextSequence++;
        return true;
    }

    public synchronized boolean finish(String streamId, Consumer<Boolean> completion) {
        if (mStreamId == null || !streamId.equals(mStreamId)) return false;
        if (mFinishing) return false;
        try {
            mFinishing = mQueue.offer(END, 2, TimeUnit.SECONDS);
            if (mFinishing) mCompletion = completion;
            return mFinishing;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public synchronized void abort(String streamId) {
        if (streamId == null || "*".equals(streamId) || streamId.equals(mStreamId)) abortLocked();
    }

    public synchronized void close() {
        abortLocked();
    }

    private void abortLocked() {
        mAbort = true;
        mQueue.clear();
        mQueue.offer(END);
        if (mWorker != null) mWorker.interrupt();
        // Keep ownership until the old worker releases I2S. Otherwise a new worker can
        // start before this one shuts off the shared speaker path.
    }

    private void play(AsgClientService service, int sampleRate, String streamId) {
        AudioTrack track = null;
        boolean drained = false;
        try {
            service.handleI2SAudioState(true);
            int minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_NOTIFICATION, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(32768, minimum), AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("audio_track_unavailable");
            track.setStereoVolume(1.0f, 1.0f);
            track.play();
            long framesWritten = 0;
            while (!mAbort) {
                byte[] chunk = mQueue.take();
                if (chunk == END) break;
                int offset = 0;
                long writeProgressAt = android.os.SystemClock.elapsedRealtime();
                while (!mAbort && offset < chunk.length) {
                    int written = track.write(chunk, offset, chunk.length - offset);
                    if (written < 0) throw new IllegalStateException("audio_write_failed_code_" + written);
                    if (written == 0) {
                        if (android.os.SystemClock.elapsedRealtime() - writeProgressAt > 5000)
                            throw new IllegalStateException("audio_write_stalled");
                        Thread.sleep(10);
                        continue;
                    }
                    writeProgressAt = android.os.SystemClock.elapsedRealtime();
                    offset += written;
                    framesWritten += written / 2;
                }
            }
            // write() only queues samples. Keep the track and I2S alive until the
            // playback head reaches the final PCM frame, including the buffered tail.
            if (!mAbort) {
                // Prime very short replies too: streaming AudioTrack may wait for its
                // minimum buffer before it starts. A small silent tail also keeps the
                // I2S route open past the last audible sample.
                int paddingFrames = (int) Math.max(sampleRate / 10,
                        Math.max(32768, minimum) / 2L - framesWritten);
                byte[] padding = new byte[paddingFrames * 2];
                int paddingOffset = 0;
                while (!mAbort && paddingOffset < padding.length) {
                    int written = track.write(padding, paddingOffset, padding.length - paddingOffset);
                    if (written <= 0) throw new IllegalStateException("audio_tail_write_failed");
                    paddingOffset += written;
                    framesWritten += written / 2;
                }
                final AudioTrack playingTrack = track;
                PcmPlaybackDrain.await(framesWritten,
                        () -> Integer.toUnsignedLong(playingTrack.getPlaybackHeadPosition()),
                        () -> mAbort, () -> android.os.SystemClock.elapsedRealtime(),
                        Thread::sleep);
                if (!mAbort) Log.i(TAG, "Direct PCM drained frames=" + framesWritten);
                drained = !mAbort;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            Log.e(TAG, "Direct PCM playback failed", error);
        } finally {
            if (track != null) {
                try { track.stop(); } catch (IllegalStateException ignored) {}
                track.release();
            }
            service.handleI2SAudioState(false);
            Consumer<Boolean> completion = null;
            synchronized (this) {
                if (streamId.equals(mStreamId)) {
                    mStreamId = null;
                    mWorker = null;
                    completion = mCompletion;
                    mCompletion = null;
                }
            }
            if (completion != null) completion.accept(drained);
        }
    }
}
