package org.asamk.signal.manager.storage.messageCache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadOnlyMessageCacheTest {

    @TempDir
    Path directory;

    @Test
    void cachingFailsClosedWithoutCreatingADirectory() {
        final var cachePath = directory.resolve("cache");
        final var cache = new MessageCache(cachePath.toFile());
        assertThrows(UnsupportedOperationException.class, () -> cache.cacheMessage(null, null));
        assertFalse(cache.getCachedMessages().iterator().hasNext());
        assertFalse(Files.exists(cachePath));
    }

    @Test
    void oldCachesAreNotReadRetriedMovedOrDeleted() throws Exception {
        final var old = directory.resolve("previous-cached-envelope");
        Files.writeString(old, "synthetic old envelope");
        final var cache = new MessageCache(directory.toFile());
        assertFalse(cache.getCachedMessages().iterator().hasNext());
        cache.deleteMessages(null);
        cache.mergeRecipients(null, null);
        assertEquals("synthetic old envelope", Files.readString(old));
        try (final var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
    }
}
