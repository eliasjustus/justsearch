package io.justsearch.app.api.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class SummaryRejectionTest {

  @Test
  void preservesMessageKeyCasing() {
    SummaryRejection rejection =
        SummaryRejection.newBuilder("summary_queue_full", "summary.toast.QueueFull").build();

    assertEquals("summary.toast.QueueFull", rejection.messageKey());
  }
}
