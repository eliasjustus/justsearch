package io.justsearch.ipc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.Test;

final class IndexingProtoDeprecationsTest {

  @Test
  void vduStatusFieldIsDeprecated() {
    Descriptors.FieldDescriptor field =
        UpdateVduResultRequest.getDescriptor().findFieldByName("vdu_status");
    assertNotNull(field, "Expected UpdateVduResultRequest.vdu_status to exist");
    assertTrue(field.getOptions().getDeprecated(), "Expected vdu_status to be marked deprecated");
  }

  @Test
  void pruneMissingRpcIsDeprecated() {
    Descriptors.FileDescriptor file = UpdateVduResultRequest.getDescriptor().getFile();
    Descriptors.ServiceDescriptor service = file.findServiceByName("IngestService");
    assertNotNull(service, "Expected IngestService to exist in indexing.proto");

    Descriptors.MethodDescriptor method = service.findMethodByName("PruneMissing");
    assertNotNull(method, "Expected IngestService.PruneMissing to exist");
    assertTrue(method.getOptions().getDeprecated(), "Expected PruneMissing to be marked deprecated");
  }
}
