package dev.ipv6ctrl;

/** Client-only decisions kept free of Android types so host JVM asserts cover each branch. */
final class ClientRules {
    static final long STATUS_FRESH_MS = 60_000L;
    static final long SWEEP_FRESH_MS = 60_000L;
    static final long ONLINE_FRESH_MS = 5 * 60_000L;

    static final String HEALTH_UNKNOWN = "unknown";
    static final String HEALTH_CONFIRMED = "confirmed";
    static final String HEALTH_PENDING = "pending";
    static final String HEALTH_FAILED = "failed";
    static final String HEALTH_BOOT_FAILED = "boot-failed";
    static final String HEALTH_INTERRUPTED = "interrupted";
    static final String HEALTH_UNMANAGED = "unmanaged";
    static final String HEALTH_OTHER = "other";

    static final String LOCK_LOCKED = "locked";
    static final String LOCK_NONE = "none";
    static final String LOCK_UNKNOWN = "unknown";
    static final String LOCK_UNSUPPORTED = "unsupported";

    private ClientRules() {}

    static boolean isFresh(long nowElapsed, long readElapsed, long maxAgeMs) {
        return readElapsed > 0 && nowElapsed >= readElapsed && nowElapsed - readElapsed <= maxAgeMs;
    }

    // Re-render keeps the pending old position until the new tree lays out; navigation never carries it.
    static int scrollToRestore(boolean hasTree, boolean samePage, boolean laidOut, int currentY, int pendingY) {
        if (hasTree && !samePage) return 0;
        return Math.max(0, hasTree && laidOut ? currentY : pendingY);
    }

    // Age at read plus elapsed since that read; unknown or a backwards clock never looks fresh.
    static long sweepAgeNow(long nowElapsed, long readElapsed, long sweepAgeAtRead) {
        if (sweepAgeAtRead < 0 || readElapsed <= 0 || nowElapsed < readElapsed) return -1;
        return sweepAgeAtRead + (nowElapsed - readElapsed);
    }

    static String lockTarget(String desired, boolean supported) {
        if (!supported) return LOCK_UNSUPPORTED;
        if (LOCK_LOCKED.equals(desired)) return LOCK_LOCKED;
        if (LOCK_NONE.equals(desired)) return LOCK_NONE;
        return LOCK_UNKNOWN;
    }

    static boolean shouldAutoRead(boolean rootReady, boolean busy, boolean hasStatus, boolean hasError, long ageMs) {
        if (!rootReady || busy || hasError) return false;
        return !hasStatus || ageMs > STATUS_FRESH_MS;
    }

    static boolean shouldStartOnlineCheck(boolean running, boolean hasSnapshot, boolean finished, boolean timedOut,
                                          boolean currentGeneration, boolean currentSystem, boolean fresh) {
        if (running) return false;
        if (!hasSnapshot) return true;
        if (!finished) return false;
        // A timed-out attempt is retried only after a real network or system change, never in a loop.
        if (timedOut) return !currentGeneration || !currentSystem;
        return !(currentGeneration && currentSystem && fresh);
    }

    static final int RESULT_IGNORE = 0;
    static final int RESULT_CURRENT = 1;
    static final int RESULT_STALE = 2;

    static int familyResult(int attempt, int snapshotAttempt, int generation, int currentGeneration) {
        if (attempt != snapshotAttempt) return RESULT_IGNORE;
        return generation == currentGeneration ? RESULT_CURRENT : RESULT_STALE;
    }

    static String health(String state, String daemon, boolean managed, String desired, String bootApply,
                         String lastSweep, boolean fresh, long sweepAgeNow) {
        if (!fresh || state == null) return HEALTH_UNKNOWN;
        boolean lockIntent = !LOCK_NONE.equals(desired);
        // A recorded applied/manual result supersedes the worker's summary from before that success.
        boolean bootSettled = "applied".equals(bootApply) || "manual".equals(bootApply);
        boolean bootProblem = "failed".equals(bootApply) || "interrupted".equals(bootApply)
                || (!bootSettled && ("boot-apply-failed".equals(lastSweep) || "boot-apply-interrupted".equals(lastSweep)));
        if (lockIntent && bootProblem) return HEALTH_BOOT_FAILED;
        if (!"blocked".equals(state)) return HEALTH_OTHER;
        if (!managed) return HEALTH_UNMANAGED;
        if ("unknown".equals(daemon)) return HEALTH_UNKNOWN;
        if (!"running".equals(daemon)) return HEALTH_INTERRUPTED;
        if ("write-error".equals(lastSweep) || "snapshot-error".equals(lastSweep)) return HEALTH_FAILED;
        if (!"ok".equals(lastSweep)) return HEALTH_PENDING;
        if (sweepAgeNow < 0 || sweepAgeNow > SWEEP_FRESH_MS) return HEALTH_PENDING;
        return HEALTH_CONFIRMED;
    }

    static boolean refreshEnabled(boolean busy) { return !busy; }

    static boolean blockEnabled(boolean busy, String state, String daemon, boolean managed) {
        return !busy && "running".equals(daemon) && !("blocked".equals(state) && managed);
    }

    static boolean lockEnabled(boolean busy, String state, String daemon, boolean managed,
                               String desired, String bootApply, boolean supported) {
        boolean bootFailed = "failed".equals(bootApply) || "interrupted".equals(bootApply);
        return supported && !busy && "running".equals(daemon)
                && !("blocked".equals(state) && managed && LOCK_LOCKED.equals(desired) && !bootFailed);
    }

    static boolean restoreEnabled(boolean busy, boolean restorable) { return !busy && restorable; }

    static boolean pauseEnabled(boolean busy, boolean managed, boolean lockIntent) {
        return !busy && (managed || lockIntent);
    }

    static boolean detectEnabled(boolean running) { return !running; }
}
