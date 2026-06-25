/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexing.SchemaFields;

public final class KnowledgeServerSafeMetrics {
  private KnowledgeServerSafeMetrics() {}

  public static long safeJobQueueDepth(JobQueue jobQueue) {
    try {
      return jobQueue == null ? 0L : Math.max(0L, jobQueue.queueDepth());
    } catch (Exception ignored) {
      return 0L;
    }
  }

  public static long safePendingJobs(JobQueue jobQueue, long fallbackDepth) {
    try {
      if (jobQueue == null) {
        return fallbackDepth;
      }
      return Math.max(0L, jobQueue.jobStateCounts().pendingCount());
    } catch (Exception ignored) {
      return 0L;
    }
  }

  public static long safeProcessingJobs(JobQueue jobQueue) {
    try {
      if (jobQueue == null) {
        return 0L;
      }
      return Math.max(0L, jobQueue.jobStateCounts().processingCount());
    } catch (Exception ignored) {
      return 0L;
    }
  }

  public static long safePendingReadyJobs(JobQueue jobQueue) {
    try {
      if (jobQueue == null) {
        return 0L;
      }
      return Math.max(0L, jobQueue.jobStateCounts().pendingReadyCount());
    } catch (Exception ignored) {
      return 0L;
    }
  }

  public static long safePendingBackoffJobs(JobQueue jobQueue) {
    try {
      if (jobQueue == null) {
        return 0L;
      }
      return Math.max(0L, jobQueue.jobStateCounts().pendingBackoffCount());
    } catch (Exception ignored) {
      return 0L;
    }
  }

  public static long safeSwitchBufferDepth(JobQueue jobQueue) {
    try {
      if (!(jobQueue instanceof SwitchBufferCapableQueue sbq)) {
        return 0L;
      }
      return Math.max(0L, sbq.switchBufferDepth());
    } catch (Exception ignored) {
      return 0L;
    }
  }

  public static int safePendingEmbeddings(IndexCountOps countOps) {
    try {
      if (countOps == null) {
        return 0;
      }
      return Math.max(
          0,
          countOps.countByField(
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    } catch (Exception ignored) {
      return 0;
    }
  }

  public static int safePendingVdu(IndexCountOps countOps) {
    try {
      if (countOps == null) {
        return 0;
      }
      return Math.max(
          0, countOps.countByField(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING));
    } catch (Exception ignored) {
      return 0;
    }
  }
}
