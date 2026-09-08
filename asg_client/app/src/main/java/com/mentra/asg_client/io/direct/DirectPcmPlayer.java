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

    public synchronized boolean begin(String streamId, int sampleRate) {
        if (streamId == null || streamId.isEmpty() || sampleRate != 24000) return false;
        // Preserve the current reply. A newer reply may start after its final sample drains.
        if (mStreamId != null) return false;
        AsgClientService service = AsgClientService.getInstance();
        if (service == null) return false;
        mStreamId = streamId;
        mNextSequence = 0;
        mAbort = false;
        mQueue.clear();
        mWorker = new Thread(() -> play(service, sampleRate, streamId), "cally-direct-pcm");
        mWorker.setDaemon(true);
        mWorker.start();
        return true;
    }

    public synchronized boolean write(String streamId, int sequence, byte[] data) {
        if (mStreamId == null || !streamId.equals(mStreamId) || sequence != mNextSequence
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

    public synchronized boolean finish(String streamId) {
        return mStreamId != null && streamId.equals(mStreamId) && mQueue.offer(END);
    }

    public synchronized void abort(String streamId) {
        if (streamId == null || streamId.equals(mStreamId)) abortLocked();
    }

    public synchronized void close() {
        abortLocked();
    }

    private void abortLocked() {
        mAbort = true;
        mQueue.clear();
        mQueue.offer(END);
        if (mWorker != null) mWorker.interrupt();
        mWorker = null;
        mStreamId = null;
    }

    private void play(AsgClientService service, int sampleRate, String streamId) {
        AudioTrack track = null;
        try {
            service.handleI2SAudioState(true);
            int minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_NOTIFICATION, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(32768, minimum), AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("audio_track_unavailable");
            track.setStereoVolume(0.78f, 0.78f);
            track.play();
            while (!mAbort) {
                byte[] chunk = mQueue.take();
                if (chunk == END) break;
                int offset = 0;
                while (!mAbort && offset < chunk.length) {
                    int written = track.write(chunk, offset, chunk.length - offset);
                    if (written <= 0) throw new IllegalStateException("audio_write_failed");
                    offset += written;
                }
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
            synchronized (this) {
                if (streamId.equals(mStreamId)) {
                    mStreamId = null;
                    mWorker = null;
                }
            }
        }
    }
}
