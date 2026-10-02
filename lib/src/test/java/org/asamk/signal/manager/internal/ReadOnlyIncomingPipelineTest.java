package org.asamk.signal.manager.internal;

import org.asamk.signal.manager.Settings;
import org.asamk.signal.manager.api.Contact;
import org.asamk.signal.manager.api.MessageEnvelope;
import org.asamk.signal.manager.api.ReceiveConfig;
import org.asamk.signal.manager.api.ServiceEnvironment;
import org.asamk.signal.manager.helper.Context;
import org.asamk.signal.manager.storage.SignalAccount;
import org.asamk.signal.manager.util.KeyUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.signal.core.models.ServiceId.ACI;
import org.signal.core.models.ServiceId.PNI;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipherResult;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.push.ServiceIdType;
import org.whispersystems.signalservice.internal.push.Content;
import org.whispersystems.signalservice.internal.push.DataMessage;
import org.whispersystems.signalservice.internal.push.Envelope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;

import okio.ByteString;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises validation, real recipient/SQLite stores and output after a stubbed crypto boundary. */
class ReadOnlyIncomingPipelineTest {

    private static final String BODY = "synthetic-private-body-f4c73e6b";
    private static final ACI SELF = ACI.from(UUID.fromString("00000000-0000-4000-8000-000000000001"));
    private static final ACI SENDER = ACI.from(UUID.fromString("00000000-0000-4000-8000-000000000002"));
    private static final long TIMESTAMP = 123456789L;

    @TempDir
    Path directory;

    @Test
    void receivedTextReachesHandlerWithoutPersistingBodyOrProfileOrSchedulingAReceipt() throws Exception {
        try (final var account = newAccount()) {
            final var recipientId = account.getRecipientResolver().resolveRecipient(SENDER);
            try (final var context = new Context(account, null, fakeDependencies(), null, null, null)) {
                final var delivered = new ArrayList<MessageEnvelope>();
                final var result = context.getIncomingMessageHandler().handleEnvelope(envelope(),
                        new ReceiveConfig(false, false, false, false, true),
                        (message, error) -> {
                            assertNull(error);
                            delivered.add(message);
                        });

                assertNull(result.second());
                assertTrue(result.first().isEmpty(), "No delivery/read receipt or sync action may be scheduled");
                assertEquals(1, delivered.size());
                assertEquals(BODY, delivered.getFirst().data().orElseThrow().body().orElseThrow());
                assertNull(account.getProfileStore().getProfileKey(recipientId));
                assertNull(account.getContactStore().getContact(recipientId));
                assertTrue(account.getGroupStore().getGroups().isEmpty());
                assertFalse(account.getMessageCache().getCachedMessages().iterator().hasNext());
                assertNoBodyInAnyFile();
            }
        }
        // Scan after SQLite closes as well, so a deferred flush cannot conceal retained text.
        assertNoBodyInAnyFile();
    }

    @Test
    void locallyBlockedSenderRemainsBlockedWithoutUpdatingItsProfile() throws Exception {
        try (final var account = newAccount()) {
            final var recipientId = account.getRecipientResolver().resolveRecipient(SENDER);
            account.getContactStore().storeContact(recipientId, Contact.newBuilder().withIsBlocked(true).build());
            try (final var context = new Context(account, null, fakeDependencies(), null, null, null)) {
                final var result = context.getIncomingMessageHandler().handleEnvelope(envelope(),
                        new ReceiveConfig(false, false, false, false, true),
                        (message, error) -> fail("Blocked sender must not be emitted"));
                assertNull(result.second());
                assertTrue(result.first().isEmpty());
                assertTrue(account.getContactStore().getContact(recipientId).isBlocked());
                assertNull(account.getProfileStore().getProfileKey(recipientId));
            }
        }
        assertNoBodyInAnyFile();
    }

    private SignalAccount newAccount() throws Exception {
        final var account = SignalAccount.create(directory.toFile(),
                "synthetic-account",
                "+12025550123",
                ServiceEnvironment.LIVE,
                KeyUtils.generateIdentityKeyPair(),
                KeyUtils.generateIdentityKeyPair(),
                KeyUtils.createProfileKey(),
                Settings.DEFAULT);
        account.initDatabase();
        account.setAci(SELF);
        account.setPni(PNI.from(UUID.fromString("00000000-0000-4000-8000-000000000003")));
        return account;
    }

    private SignalServiceEnvelope envelope() {
        return new SignalServiceEnvelope(new Envelope.Builder()
                .type(Envelope.Type.DOUBLE_RATCHET)
                .sourceServiceId(SENDER.toString())
                .sourceDeviceId(1)
                .destinationServiceId(SELF.toString())
                .clientTimestamp(TIMESTAMP)
                .serverTimestamp(TIMESTAMP + 1)
                .serverGuid("00000000-0000-4000-8000-000000000004")
                .build(), TIMESTAMP + 2);
    }

    private SignalDependencies fakeDependencies() {
        final var content = new Content.Builder().dataMessage(new DataMessage.Builder()
                .body(BODY)
                .timestamp(TIMESTAMP)
                .expireTimer(60)
                .profileKey(ByteString.of(new byte[32]))
                .build()).build();
        final var metadata = new EnvelopeMetadata(SENDER, null, 1, true, null, SELF,
                CiphertextMessage.WHISPER_TYPE);
        final var cipher = new SignalServiceCipher(null, 0, null, null, null) {
            @Override
            public SignalServiceCipherResult decrypt(final Envelope ignored, final long deliveredAt) {
                return new SignalServiceCipherResult(content, metadata);
            }
        };
        return new SignalDependencies(null, null, null, null, 0, null, new ReentrantSignalSessionLock()) {
            @Override
            public SignalServiceCipher getCipher(final ServiceIdType type) {
                return cipher;
            }
        };
    }

    private void assertNoBodyInAnyFile() throws Exception {
        try (final var paths = Files.walk(directory)) {
            for (final var file : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(BODY),
                        "Message body found in " + file.getFileName());
            }
        }
    }
}
