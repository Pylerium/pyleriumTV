package com.cleantv.launcher;

import android.content.Context;
import android.net.Uri;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.interfaces.IMedia;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Shared libVLC engine. Software decoders cover the AVI/XviD/AC3/E-AC3/DTS media Android's own player cannot handle. */
final class Vlc {
    private static LibVLC instance;

    static synchronized LibVLC get(Context context) {
        if (instance == null) instance = new LibVLC(context.getApplicationContext(), new ArrayList<>(Arrays.asList(
                "--file-caching=1500", "--network-caching=1500", "--audio-time-stretch", "--avcodec-skiploopfilter=1", "--no-spu", "--no-osd", "--no-sub-autodetect-file", "--no-stats")));
        return instance;
    }

    /** Container duration in milliseconds, or 0 when unreadable. Blocks up to a few seconds. */
    static long probeDuration(Context context, String uri) {
        Media media = null;
        try {
            media = new Media(get(context), Uri.parse(uri)); CountDownLatch done = new CountDownLatch(1);
            media.setEventListener(event -> { if (event.type == IMedia.Event.ParsedChanged) done.countDown(); });
            if (!media.parseAsync(IMedia.Parse.ParseLocal, 6000)) return 0;
            done.await(7, TimeUnit.SECONDS); return Math.max(0, media.getDuration());
        } catch (Exception ignored) { return 0; }
        finally { if (media != null) media.release(); }
    }
}
