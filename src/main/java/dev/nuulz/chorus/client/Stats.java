package dev.nuulz.chorus.client;

import java.util.concurrent.atomic.AtomicLong;

/** Where the time goes: cheap counters, read by the tuner and by the benchmark. */
public final class Stats {
    static final AtomicLong convertNs = new AtomicLong(), converts = new AtomicLong();
    static final AtomicLong uploadNs = new AtomicLong();
    static final AtomicLong syncNs = new AtomicLong(), syncs = new AtomicLong();

    public static long convertNs() { return convertNs.get(); }
    public static long converts() { return converts.get(); }
    public static long uploadNs() { return uploadNs.get(); }
    public static long syncNs() { return syncNs.get(); }
    public static long syncs() { return syncs.get(); }

    private Stats() {}
}
