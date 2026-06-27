package com.agi.assistant.service.document;

import com.agi.assistant.domain.document.Document;
import com.agi.assistant.domain.document.WriteRequest;
import com.agi.assistant.domain.document.WriteResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryLibraryRepoTest {

    @Test
    void firstWriteCreatesDocAndVersion1() {
        InMemoryLibraryRepo repo = new InMemoryLibraryRepo();
        WriteRequest req = new WriteRequest("title", "report", Document.SOURCE_AGENT,
                "tester", "正文", "summary", new LinkedHashMap<>());
        WriteResult r = repo.write(req);

        assertTrue(r.created);
        assertEquals(1, r.version.getVersion());
        assertNotNull(r.document.getId());
        assertEquals(r.document.getId(), r.version.getDocumentId());
        assertEquals(r.version.getId(), r.document.getLatestVersionId());

        var got = repo.get(r.document.getId());
        assertEquals("正文", got.version.getContentMd());
    }

    @Test
    void secondWriteAppendsVersion2() {
        InMemoryLibraryRepo repo = new InMemoryLibraryRepo();
        WriteResult r1 = repo.write(new WriteRequest("title", "report", Document.SOURCE_AGENT,
                "tester", "v1", "", new LinkedHashMap<>()));

        WriteRequest req2 = new WriteRequest("title-updated", "report", Document.SOURCE_AGENT,
                "tester", "v2", "", new LinkedHashMap<>());
        req2.setDocumentId(r1.document.getId());
        WriteResult r2 = repo.write(req2);

        assertFalse(r2.created);
        assertEquals(2, r2.version.getVersion());
        assertEquals(r1.document.getId(), r2.document.getId());
        assertEquals("v2", repo.get(r1.document.getId()).version.getContentMd());
    }
}
