package com.transcribemate.v2.fx;

/** Keeps per-job UI effects scoped to the window that owns the active job. */
final class JobEventScope {
    private JobEventScope() {
    }

    static boolean owns(String currentJobId, String eventJobId) {
        return eventJobId != null
                && !eventJobId.isBlank()
                && eventJobId.equals(currentJobId);
    }
}
