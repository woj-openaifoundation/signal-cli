package org.asamk.signal.manager.internal;

import org.asamk.signal.manager.helper.Context;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadOnlyBoundariesTest {

    @Test
    void senderIsRejectedBeforeAnyNetworkDependencyCanBeCreated() {
        final var dependencies = new SignalDependencies(null, null, null, null, 0, null, null);
        assertThrows(UnsupportedOperationException.class, dependencies::getMessageSender);
    }

    @Test
    void incomingMediaCannotOpenAStreamOrCreateAFile() {
        try (final var context = new Context(null, null, null, null, null, null)) {
            final var attachmentHelper = context.getAttachmentHelper();
            final var consumed = new AtomicBoolean();
            assertThrows(IOException.class,
                    () -> attachmentHelper.retrieveAttachment(null, input -> consumed.set(true)));
            assertThrows(UnsupportedOperationException.class, () -> attachmentHelper.downloadAttachment(null));
            assertFalse(consumed.get());
        }
    }

    @Test
    void backgroundJobsNeverRunEvenWhenTheExecutorCloses() {
        final var ran = new AtomicBoolean();
        try (final var executor = new JobExecutor(null)) {
            executor.enqueueJob(ignored -> ran.set(true));
        }
        assertFalse(ran.get());
    }
}
