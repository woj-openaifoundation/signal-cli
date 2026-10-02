package org.asamk.signal;

import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import org.asamk.signal.commands.Commands;
import org.asamk.signal.commands.exceptions.UserErrorException;
import org.asamk.signal.logging.LogConfigurator;
import org.asamk.signal.util.CommandUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyCliTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void onlyReviewedCommandsAreAvailable() {
        assertEquals(Set.of("receive", "listAccounts", "listContacts", "listGroups", "listIdentities", "version"),
                Commands.getCommandSubparserAttachers().keySet());
        assertNull(Commands.getCommand(null));
        assertThrows(UnsupportedOperationException.class,
                () -> Commands.getCommandSubparserAttachers().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"send", "sendReaction", "sendReceipt", "sendTyping", "sendStory", "remoteDelete",
            "updateAccount", "updateProfile", "updateContact", "updateGroup", "updateConfiguration", "trust",
            "joinGroup", "quitGroup", "block", "unblock", "register", "verify", "unregister", "link", "startLink",
            "finishLink", "addDevice", "removeDevice", "deleteLocalAccountData", "sendContacts", "sendSyncRequest",
            "sendPaymentNotification", "sendMessageRequestResponse", "addStickerPack", "uploadStickerPack",
            "startCall", "acceptCall", "rejectCall", "hangupCall", "daemon", "jsonRpc", "getAttachment", "getAvatar",
            "getSticker", "getUserStatus", "submitRateLimitChallenge", "setPin", "removePin", "startChangeNumber",
            "finishChangeNumber", "updateDevice", "listDevices", "listCalls", "listStickerPacks"})
    void mutatingAndAlternateTransportCommandsAreDeniedBeforeAccountsOpen(final String command) {
        assertNull(Commands.getCommand(command));
        assertThrows(ArgumentParserException.class,
                () -> App.buildArgumentParser(null).parseArgs(new String[]{command}));
        assertThrows(UserErrorException.class,
                () -> new App(new Namespace(Map.of("command", command,
                        "data-dir", temporaryDirectory.resolve("unused").toString()))).init());
        assertFalse(Files.exists(temporaryDirectory.resolve("unused")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--dbus", "--dbus-system", "--log-file", "-v", "--verbose",
            "--trust-new-identities", "--service-environment"})
    void removedGlobalOptionsAreRejected(final String option) {
        assertThrows(ArgumentParserException.class,
                () -> App.buildArgumentParser(null).parseArgs(new String[]{option, "receive"}));
    }

    @Test
    void readReceiptsCannotBeEnabled() {
        assertThrows(ArgumentParserException.class,
                () -> App.buildArgumentParser(null).parseArgs(new String[]{"receive", "--send-read-receipts"}));
        assertThrows(UserErrorException.class,
                () -> App.rejectUnsafeNamespace(new Namespace(Map.of("send-read-receipts", true))));
    }

    @Test
    void maliciousConfigurationCannotChangePolicyOrDestination() throws Exception {
        final var config = new GlobalConfig(5, temporaryDirectory.resolve("log").toString(), false,
                temporaryDirectory.resolve("account-copy").toString(), "org.untrusted.Bus", true, true,
                OutputType.PLAIN_TEXT, ServiceEnvironmentCli.STAGING, TrustNewIdentityCli.ALWAYS, false, "+1234");
        final var parsed = App.buildArgumentParser(config).parseArgs(new String[]{"receive"});
        assertEquals(OutputType.JSON, parsed.get("output"));
        assertNull(parsed.get("data-dir"));
        assertNull(parsed.get("log-file"));
        assertNull(parsed.get("global-dbus"));
        assertNull(parsed.get("verbose"));
        assertNull(parsed.get("account"));
        assertFalse(Files.exists(temporaryDirectory.resolve("log")));
    }

    @Test
    void unsafeInjectedNamespacesAreRejected() {
        for (final var option : Map.<String, Object>of("global-dbus", true,
                "global-dbus-system", true, "verbose", 1, "log-file", "message.log").entrySet()) {
            assertThrows(UserErrorException.class,
                    () -> App.rejectUnsafeNamespace(new Namespace(Map.of(option.getKey(), option.getValue()))));
        }
    }

    @Test
    void receivingAlwaysDisablesMediaAndReceipts() {
        final var overrides = new HashMap<String, Object>();
        for (final var flag : Set.of("ignore-attachments", "ignore-stories", "ignore-avatars", "ignore-stickers")) {
            overrides.put(flag, false);
        }
        overrides.put("send-read-receipts", true);
        final var config = CommandUtil.getReceiveConfig(new Namespace(overrides));
        assertTrue(config.ignoreAttachments());
        assertTrue(config.ignoreStories());
        assertTrue(config.ignoreAvatars());
        assertTrue(config.ignoreStickers());
        assertFalse(config.sendReadReceipts());
    }

    @Test
    void versionNeedsNeitherAnAccountNorNativeLibrary() throws Exception {
        final var unusedState = temporaryDirectory.resolve("does-not-exist");
        final var parsed = App.buildArgumentParser(null)
                .parseArgs(new String[]{"--data-dir", unusedState.toString(), "version"});
        final var output = new ByteArrayOutputStream();
        final var previousOut = System.out;
        try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(stream);
            new App(parsed).init();
        } finally {
            System.setOut(previousOut);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"version\""));
        assertFalse(Files.exists(unusedState));
    }

    @Test
    void loggerCannotPersistOrPrintSignalContent() {
        final var context = new LoggerContext();
        try {
            new LogConfigurator().configure(context);
            final var root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            assertEquals(Level.OFF, root.getLevel());
            assertFalse(root.iteratorForAppenders().hasNext());
        } finally {
            context.stop();
        }
    }
}
