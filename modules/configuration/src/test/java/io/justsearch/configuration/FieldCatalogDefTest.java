package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FieldCatalogDefTest {

    @Test
    void forTesting_createsMinimalCatalog() {
        FieldCatalogDef catalog = FieldCatalogDef.forTesting(4);

        assertNotNull(catalog);
        assertEquals("test", catalog.version());
        // This factory is intentionally hermetic (no SSOT IO) but should still include core fields
        // that most subsystems rely on (kept broadly aligned with SSOT).
        assertNotNull(catalog.field("doc_id"));
        assertNotNull(catalog.field("doc_uid"));
        assertNotNull(catalog.field("path"));
        assertNotNull(catalog.field("title"));
        assertNotNull(catalog.field("content"));
        assertNotNull(catalog.field("vector"));
    }

    @Test
    void forTesting_hasCorrectVectorDimension() {
        FieldCatalogDef catalog = FieldCatalogDef.forTesting(128);

        assertEquals(Integer.valueOf(128), catalog.vectorDimension());
    }

    @Test
    void forTesting_hasPrimaryKeyField() {
        FieldCatalogDef catalog = FieldCatalogDef.forTesting(4);

        FieldCatalogDef.FieldDef docId = catalog.field("doc_id");
        assertNotNull(docId);
        assertTrue(docId.hasRole("id"));
        assertTrue(docId.docValues());
    }

    @Test
    void forTesting_hasDocUidField() {
        FieldCatalogDef catalog = FieldCatalogDef.forTesting(4);

        FieldCatalogDef.FieldDef docUid = catalog.field("doc_uid");
        assertNotNull(docUid);
        assertTrue(docUid.hasRole("tiebreak"));
        assertTrue(docUid.docValues());
    }

    @Test
    void byId_returnsCorrectField() {
        FieldCatalogDef catalog = FieldCatalogDef.forTesting(4);

        assertEquals("vector", catalog.byId().get("vector").id());
        assertEquals("text", catalog.byId().get("content").type());
    }

    @Test
    void forChunkTestingWithVduRetryCount_addsRetryFieldWithoutChangingVectorDimension() {
        FieldCatalogDef base = FieldCatalogDef.forChunkTesting(16);
        FieldCatalogDef extended = FieldCatalogDef.forChunkTestingWithVduRetryCount(16);

        assertNull(base.field("vdu_retry_count"));

        FieldCatalogDef.FieldDef retry = extended.field("vdu_retry_count");
        assertNotNull(retry);
        assertEquals("long", retry.type());
        assertTrue(retry.stored());
        assertTrue(retry.docValues());
        assertTrue(retry.hasRole("filter"));
        assertTrue(retry.hasRole("sort"));

        assertEquals(Integer.valueOf(16), extended.vectorDimension());
        assertEquals(base.fields().size() + 1, extended.fields().size());
    }
}
