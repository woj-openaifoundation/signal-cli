package org.asamk.signal.manager.storage.messageCache;

import org.asamk.signal.manager.storage.recipients.RecipientId;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Fail-closed cache boundary for the receive-only fork.
 * Existing caches are neither read nor modified; no envelope is persisted.
 */
public class MessageCache {

    public MessageCache(final File ignoredPath) {}

    public Iterable<CachedMessage> getCachedMessages() {
        return List.of();
    }

    public CachedMessage cacheMessage(SignalServiceEnvelope envelope, RecipientId recipientId) {
        throw new UnsupportedOperationException("Message caching is disabled in signal-cli-readonly");
    }

    public CachedMessage replaceSender(CachedMessage cachedMessage, RecipientId sender) throws IOException {
        return cachedMessage;
    }

    public void deleteMessages(final RecipientId recipientId) {}

    public void mergeRecipients(final RecipientId recipientId, final RecipientId toBeMergedRecipientId) {}
}
