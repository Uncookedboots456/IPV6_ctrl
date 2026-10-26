package dev.ipv6ctrl;

public final class ClientRulesTest {
    private static final long MINUTE = 60_000L;

    public static void main(String[] args) {
        freshnessUsesElapsedTime();
        reRenderKeepsPendingScrollUntilLayout();
        autoReadSkipsRotationAndUnsafeRetries();
        onlineDedupAndNetworkGeneration();
        healthDoesNotConfirmUnknownSweep();
        sweepAgeAccumulatesAndExpires();
        lockIntentAndBootFailureStayExplicit();
        mainLockButtonUpgradesCurrentTakeover();
        buttonPreconditionsFollowContract();
        System.out.println("Client rules checks passed (no Android or network access)");
    }

    private static void freshnessUsesElapsedTime() {
        assert ClientRules.isFresh(10_000, 5_000, 10_000);
        assert !ClientRules.isFresh(20_001, 5_000, 10_000) : "stale result must expire";
        assert !ClientRules.isFresh(1_000, 0, 10_000) : "no reading is never fresh";
        assert !ClientRules.isFresh(500, 1_000, 10_000) : "clock cannot run backwards";
    }

    private static void reRenderKeepsPendingScrollUntilLayout() {
        assert ClientRules.scrollToRestore(true, true, false, 0, 200) == 200 : "new tree's pre-layout 0 must not replace pending scroll";
        assert ClientRules.scrollToRestore(true, true, true, 120, 200) == 120 : "a laid-out tree owns the latest scroll";
        assert ClientRules.scrollToRestore(true, true, true, 0, 200) == 0 : "an intentional scroll to top clears the pending position";
        assert ClientRules.scrollToRestore(false, true, false, 0, 200) == 200 : "recreate restores the saved position";
        assert ClientRules.scrollToRestore(true, false, true, 200, 0) == 0 : "navigation does not carry another page's scroll";
        assert ClientRules.scrollToRestore(true, true, true, -1, 200) == 0 : "negative offsets clamp to top";
        System.out.println("  pre-layout re-render keeps 200px instead of resetting to 0");
    }

    private static void autoReadSkipsRotationAndUnsafeRetries() {
        assert ClientRules.shouldAutoRead(true, false, false, false, Long.MAX_VALUE) : "first authorized launch reads";
        assert !ClientRules.shouldAutoRead(true, false, true, false, MINUTE / 2) : "rotation keeps a fresh reading";
        assert ClientRules.shouldAutoRead(true, false, true, false, MINUTE + 1) : "return to foreground refreshes an old reading";
        assert !ClientRules.shouldAutoRead(false, false, false, false, Long.MAX_VALUE) : "never request Root before authorization";
        assert !ClientRules.shouldAutoRead(true, true, false, false, Long.MAX_VALUE) : "one Root operation at a time";
        assert !ClientRules.shouldAutoRead(true, false, false, true, Long.MAX_VALUE) : "no silent retry loop after a failed read";
    }

    private static void onlineDedupAndNetworkGeneration() {
        assert ClientRules.shouldStartOnlineCheck(false, false, false, false, false, false, false) : "cold start detects";
        assert !ClientRules.shouldStartOnlineCheck(true, true, false, false, true, true, false) : "in-flight check is not duplicated";
        assert !ClientRules.shouldStartOnlineCheck(false, true, true, false, true, true, true) : "fresh result survives rotation/return";
        assert ClientRules.shouldStartOnlineCheck(false, true, true, false, false, true, true) : "new network invalidates an old result";
        assert ClientRules.shouldStartOnlineCheck(false, true, true, false, true, false, true) : "Root change invalidates an old result";
        assert !ClientRules.shouldStartOnlineCheck(false, true, true, true, true, true, false) : "timeout is not retried in a loop";
        assert ClientRules.shouldStartOnlineCheck(false, true, true, true, false, true, false) : "timeout retries once the network changes";
        assert ClientRules.shouldStartOnlineCheck(false, true, true, true, true, false, false) : "timeout retries after a system change";
        assert ClientRules.familyResult(3, 3, 7, 7) == ClientRules.RESULT_CURRENT : "current attempt publishes";
        assert ClientRules.familyResult(3, 4, 7, 7) == ClientRules.RESULT_IGNORE : "late result cannot replace a newer attempt";
        assert ClientRules.familyResult(3, 3, 7, 8) == ClientRules.RESULT_STALE : "previous-network result cannot look current";
    }

    private static String health(String state,String daemon,boolean managed,String desired,String bootApply,
                                 String sweep,boolean fresh,long age) {
        return ClientRules.health(state,daemon,managed,desired,bootApply,sweep,fresh,age);
    }

    private static void healthDoesNotConfirmUnknownSweep() {
        assert ClientRules.HEALTH_CONFIRMED.equals(health("blocked", "running", true, "none", "none", "ok", true, 0));
        assert ClientRules.HEALTH_CONFIRMED.equals(health("blocked", "running", true, "locked", "applied", "ok", true, 1_000));
        assert ClientRules.HEALTH_FAILED.equals(health("blocked", "running", true, "none", "none", "snapshot-error", true, 0));
        assert ClientRules.HEALTH_FAILED.equals(health("blocked", "running", true, "none", "none", "write-error", true, 0));
        assert ClientRules.HEALTH_PENDING.equals(health("blocked", "running", true, "none", "none", "idle", true, 0));
        assert ClientRules.HEALTH_PENDING.equals(health("blocked", "running", true, "none", "none", "unknown", true, 0));
        assert ClientRules.HEALTH_PENDING.equals(health("blocked", "running", true, "locked", "applied", "ok", true, -1)) : "unknown sweep age cannot confirm";
        assert ClientRules.HEALTH_PENDING.equals(health("blocked", "running", true, "locked", "applied", "ok", true, ClientRules.SWEEP_FRESH_MS + 1)) : "old sweep cannot confirm";
        assert ClientRules.HEALTH_INTERRUPTED.equals(health("blocked", "stopped", true, "locked", "applied", "ok", true, 0));
        assert ClientRules.HEALTH_UNKNOWN.equals(health("blocked", "unknown", true, "locked", "applied", "ok", true, 0)) : "unreadable service state is not reported as stopped";
        assert ClientRules.HEALTH_UNMANAGED.equals(health("blocked", "running", false, "none", "none", "ok", true, 0));
        assert ClientRules.HEALTH_UNKNOWN.equals(health("blocked", "running", true, "none", "none", "ok", false, 0)) : "old reading needs confirmation";
        assert ClientRules.HEALTH_UNKNOWN.equals(health(null, null, false, null, null, null, true, -1));
        assert ClientRules.HEALTH_OTHER.equals(health("mixed", "running", false, "none", "none", "ok", true, 0));
        assert ClientRules.HEALTH_OTHER.equals(health("enabled", "running", false, "none", "none", "ok", true, 0));
    }

    private static void sweepAgeAccumulatesAndExpires() {
        assert ClientRules.sweepAgeNow(10_000, 8_000, 5_000) == 7_000 : "age includes time since the read";
        assert ClientRules.sweepAgeNow(10_000, 10_000, 60_000) == 60_000;
        assert ClientRules.sweepAgeNow(10_000, 8_000, -1) == -1 : "missing age stays unknown";
        assert ClientRules.sweepAgeNow(10_000, 10_001, 5_000) == -1 : "backwards clock is not fresh";
        System.out.println("  sweep age at read 5s + 2s since read = 7s");
    }

    private static void lockIntentAndBootFailureStayExplicit() {
        assert ClientRules.LOCK_LOCKED.equals(ClientRules.lockTarget("locked", true));
        assert ClientRules.LOCK_NONE.equals(ClientRules.lockTarget("none", true));
        assert ClientRules.LOCK_UNKNOWN.equals(ClientRules.lockTarget("corrupt", true)) : "unknown is neither locked nor unlocked";
        assert ClientRules.LOCK_UNSUPPORTED.equals(ClientRules.lockTarget("locked", false)) : "old Runtime cannot claim a lock";
        assert ClientRules.HEALTH_BOOT_FAILED.equals(health("blocked", "running", true, "locked", "failed", "ok", true, 0));
        assert ClientRules.HEALTH_BOOT_FAILED.equals(health("enabled", "running", false, "locked", "interrupted", "idle", true, 0));
        assert ClientRules.HEALTH_BOOT_FAILED.equals(health("blocked", "running", true, "locked", "unknown", "boot-apply-failed", true, 0));
        assert ClientRules.HEALTH_CONFIRMED.equals(health("blocked", "running", true, "locked", "manual", "ok", true, 0)) : "a verified manual retry clears the current boot failure";
        assert ClientRules.HEALTH_PENDING.equals(health("blocked", "running", true, "locked", "manual", "boot-apply-failed", true, 0)) : "a stale worker summary after manual success is pending, not failed";
        assert ClientRules.HEALTH_CONFIRMED.equals(health("blocked", "running", true, "none", "failed", "ok", true, 0)) : "stale boot marker after unlock is not a lock failure";
    }

    private static void mainLockButtonUpgradesCurrentTakeover() {
        assert ClientRules.lockEnabled(false, "blocked", "running", true, "none", "none", true) : "current-blocked takeover can be upgraded to a lock";
        assert ClientRules.lockEnabled(false, "blocked", "running", true, "locked", "failed", true) : "failed boot application can be retried";
        assert !ClientRules.lockEnabled(false, "blocked", "running", true, "locked", "manual", true) : "a resolved manual lock is not retried";
        assert !ClientRules.lockEnabled(false, "blocked", "running", true, "locked", "applied", true) : "an already applied lock is not repeated";
        assert !ClientRules.lockEnabled(false, "blocked", "stopped", true, "none", "none", true) : "no worker blocks locking";
        assert !ClientRules.lockEnabled(false, "enabled", "running", false, "none", "none", false) : "old Runtime cannot lock";
        assert !ClientRules.lockEnabled(true, "enabled", "running", false, "none", "none", true) : "one operation at a time";
    }

    private static void buttonPreconditionsFollowContract() {
        assert ClientRules.blockEnabled(false, "enabled", "running", false) : "worker alive allows fallback";
        assert !ClientRules.blockEnabled(false, "enabled", "stopped", false) : "no worker blocks fallback";
        assert !ClientRules.blockEnabled(false, "enabled", "missing", false) : "missing Runtime blocks fallback";
        assert !ClientRules.blockEnabled(false, "blocked", "running", true) : "already maintained state is not re-enabled";
        assert ClientRules.blockEnabled(false, "blocked", "running", false) : "unmanaged blocked state can be re-taken over";
        assert !ClientRules.blockEnabled(true, "enabled", "running", false) : "busy Root operation blocks fallback";
        assert ClientRules.restoreEnabled(false, true);
        assert !ClientRules.restoreEnabled(false, false) : "no snapshot blocks restore";
        assert !ClientRules.restoreEnabled(true, true) : "busy Root operation blocks restore";
        assert ClientRules.pauseEnabled(false, true, false);
        assert !ClientRules.pauseEnabled(false, false, false) : "nothing to pause without takeover or intent";
        assert ClientRules.pauseEnabled(false, false, true) : "persistent intent can be cleared after a failed boot apply";
        assert !ClientRules.pauseEnabled(true, false, true) : "busy Root operation blocks pause";
        assert ClientRules.refreshEnabled(false);
        assert !ClientRules.refreshEnabled(true);
        assert ClientRules.detectEnabled(false);
        assert !ClientRules.detectEnabled(true) : "a running detection is not duplicated";
    }
}
