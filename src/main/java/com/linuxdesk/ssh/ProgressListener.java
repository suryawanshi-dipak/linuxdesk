package com.linuxdesk.ssh;

/** Reports cumulative bytes moved during an upload/download, for a determinate progress bar. */
@FunctionalInterface
public interface ProgressListener {
    void onProgress(long transferredBytes, long totalBytes);
}
