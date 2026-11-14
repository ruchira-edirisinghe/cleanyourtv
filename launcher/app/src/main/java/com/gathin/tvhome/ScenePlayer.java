package com.gathin.tvhome;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The live screensaver's video: one decoder for the whole screensaver, fed one scene after another, each going round
 * and round, all on one clock that never goes back. On this TV closing a video decoder and opening another moves the
 * sound's lip-sync delay (to none and back), which cuts the music that plays; MediaPlayer did that at every scene.
 * A scene in another format than the one playing gets a decoder of its own. No sound is read.
 */
final class ScenePlayer {
    interface Listener {
        /** The scene's picture size, before its first frame. On the main thread, as all of these. */
        void onSize(int width, int height);

        /** The scene's first frame is on the screen. */
        void onShown(File file);

        /** The scene cannot be played. */
        void onError(File file);
    }

    private static final long TIMEOUT_US = 10_000;
    /** How far ahead of its time a frame goes to the screen, and the most a frame waits for its time. */
    private static final long EARLY_NS = 12_000_000L, MAX_WAIT_MS = 200;

    private final Surface surface;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final Thread thread;
    private volatile boolean running = true;
    /** The scene to go on with next (guarded by lock). */
    private File next;

    // The decoder thread's own.
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private MediaCodec codec;
    private String codecMime;
    private MediaExtractor extractor;
    private File current;
    /** Added to the scene's own times, so the clock goes on across rounds and scenes. */
    private long offsetUs;
    /** The latest time queued, on that clock, and a frame's length. */
    private long lastQueuedUs = Long.MIN_VALUE, frameUs = 33_333;
    /** The new scene's first time on the clock, until a frame of it is shown. */
    private long sceneStartUs;
    private File toShow;
    /** System.nanoTime() of time 0 on the clock (unset: the next frame shown sets it). */
    private long baseNs;
    private boolean clockSet;

    ScenePlayer(Surface surface, Listener listener) {
        this.surface = surface;
        this.listener = listener;
        thread = new Thread(this::loop, "LiveSaver scenes");
        thread.start();
    }

    /** Goes on with this scene after the frames already on their way. */
    void play(File file) {
        synchronized (lock) {
            next = file;
            lock.notifyAll();
        }
    }

    /** Stops and lets the decoder go (waits for it briefly, so the surface can go right after). */
    void release() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        try {
            while (running) {
                File file;
                synchronized (lock) {
                    file = next;
                    next = null;
                    if (file == null && codec == null) {
                        lock.wait(200);
                        continue;
                    }
                }
                if (file != null) open(file);
                if (codec == null) continue;
                try {
                    feed();
                    drain();
                } catch (RuntimeException | InterruptedException e) {
                    if (!running) break;
                    File broken = current;
                    closeCodec();
                    main.post(() -> listener.onError(broken));
                }
            }
        } catch (InterruptedException ignored) {
            // released
        } finally {
            closeCodec();
        }
    }

    private void open(File file) throws InterruptedException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(file.getPath());
            MediaFormat format = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    ex.selectTrack(i);
                    format = f;
                    break;
                }
            }
            if (format == null) throw new IOException("no video in " + file.getName());
            String mime = format.getString(MediaFormat.KEY_MIME);
            int w = format.getInteger(MediaFormat.KEY_WIDTH), h = format.getInteger(MediaFormat.KEY_HEIGHT);
            main.post(() -> listener.onSize(w, h));
            frameUs = frameLength(format);
            if (codec != null && !mime.equals(codecMime)) closeCodec();
            if (codec == null) {
                format.setInteger(MediaFormat.KEY_MAX_WIDTH, Math.max(w, 3840));
                format.setInteger(MediaFormat.KEY_MAX_HEIGHT, Math.max(h, 2160));
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(format, surface, null, 0);
                codec.start();
                codecMime = mime;
            } else {
                // The same decoder goes on: this scene's setup first, then its frames after the last one queued.
                for (String key : new String[]{"csd-0", "csd-1", "csd-2"}) {
                    ByteBuffer csd = format.containsKey(key) ? format.getByteBuffer(key) : null;
                    if (csd != null) queueConfig(csd);
                }
            }
            if (extractor != null) extractor.release();
            extractor = ex;
            current = file;
            long first = Math.max(0, ex.getSampleTime());
            offsetUs = (lastQueuedUs == Long.MIN_VALUE ? 0 : lastQueuedUs + frameUs) - first;
            sceneStartUs = offsetUs + first;
            toShow = file;
        } catch (IOException | RuntimeException e) {
            ex.release();
            if (codec != null && extractor == null) closeCodec();
            main.post(() -> listener.onError(file));
        }
    }

    private void queueConfig(ByteBuffer csd) throws InterruptedException {
        while (running) {
            int index = codec.dequeueInputBuffer(TIMEOUT_US);
            if (index >= 0) {
                ByteBuffer in = codec.getInputBuffer(index);
                ByteBuffer data = csd.duplicate();
                data.rewind();
                int size = data.remaining();
                if (in != null) in.put(data);
                codec.queueInputBuffer(index, 0, size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                return;
            }
            drain();
        }
    }

    /** One frame of the scene to the decoder; at its end the scene starts over, on the same clock. */
    private void feed() {
        if (extractor == null) return;
        int index = codec.dequeueInputBuffer(TIMEOUT_US);
        if (index < 0) return;
        ByteBuffer in = codec.getInputBuffer(index);
        if (in == null) return;
        int size = extractor.readSampleData(in, 0);
        if (size < 0) {
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            offsetUs = lastQueuedUs + frameUs - Math.max(0, extractor.getSampleTime());
            size = extractor.readSampleData(in, 0);
            if (size < 0) throw new IllegalStateException("empty scene");
        }
        long time = extractor.getSampleTime() + offsetUs;
        codec.queueInputBuffer(index, 0, size, time, 0);
        lastQueuedUs = Math.max(lastQueuedUs, time);
        extractor.advance();
    }

    /** The next decoded frame to the screen at its time on the clock. */
    private void drain() throws InterruptedException {
        int index = codec.dequeueOutputBuffer(info, TIMEOUT_US);
        if (index < 0) return;
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            codec.releaseOutputBuffer(index, false);
            return;
        }
        long now = System.nanoTime();
        long at = baseNs + info.presentationTimeUs * 1000L;
        if (!clockSet || at < now - 100_000_000L) {
            // The first frame, or far behind after a stall: the clock starts (again) here.
            baseNs = now + 40_000_000L - info.presentationTimeUs * 1000L;
            clockSet = true;
            at = baseNs + info.presentationTimeUs * 1000L;
        }
        long waitMs = (at - EARLY_NS - now) / 1_000_000L;
        if (waitMs > 0) Thread.sleep(Math.min(waitMs, MAX_WAIT_MS));
        codec.releaseOutputBuffer(index, at);
        if (toShow != null && info.presentationTimeUs >= sceneStartUs) {
            File shown = toShow;
            toShow = null;
            main.postDelayed(() -> listener.onShown(shown), Math.max(0, (at - System.nanoTime()) / 1_000_000L));
        }
    }

    private void closeCodec() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // already broken
            }
            codec.release();
            codec = null;
            codecMime = null;
        }
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }
        clockSet = false;
        lastQueuedUs = Long.MIN_VALUE;
    }

    /** A frame's length from the scene's frame rate (stored as an int or a float), else 30 a second. */
    private static long frameLength(MediaFormat format) {
        float fps = 0f;
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
            } catch (ClassCastException e) {
                fps = format.getFloat(MediaFormat.KEY_FRAME_RATE);
            }
        }
        return fps >= 1f ? Math.round(1_000_000.0 / fps) : 33_333;
    }
}
