package org.asamk.signal;

import net.sourceforge.argparse4j.inf.Namespace;

import org.asamk.signal.commands.exceptions.UserErrorException;
import org.asamk.signal.manager.ProvisioningManager;
import org.asamk.signal.output.JsonWriter;
import org.asamk.signal.output.PlainTextWriterImpl;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class LinkSetupTest {

    private static final URI LINK_URI = URI.create("sgnl://linkdevice?uuid=test-only&pub_key=test-only");

    private static final class ProvisioningStub implements ProvisioningManager {

        private final List<Object> events;
        private final boolean timeout;
        private String deviceName;
        private boolean closed;
        private boolean uriRequested;

        ProvisioningStub(final List<Object> events, final boolean timeout) {
            this.events = events;
            this.timeout = timeout;
        }

        @Override
        public URI getDeviceLinkUri() {
            uriRequested = true;
            return LINK_URI;
        }

        @Override
        public String finishDeviceLink(final String deviceName) throws TimeoutException {
            assertEquals(List.of(Map.of("type", "link", "uri", LINK_URI.toString())), events,
                    "Emit the QR URI before waiting for the phone to pair");
            this.deviceName = deviceName;
            if (timeout) {
                throw new TimeoutException();
            }
            return "+12025550123";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void linkingEmitsOnlySetupUriAndCompletionWithoutPhoneNumberAndClosesSocket() throws Exception {
        final var events = new ArrayList<Object>();
        final var manager = new ProvisioningStub(events, false);
        App.linkAccount(new Namespace(Map.of()), manager, (JsonWriter) events::add);
        assertEquals(List.of(Map.of("type", "link", "uri", LINK_URI.toString()), Map.of("type", "linked")), events);
        assertEquals("signal-cli-readonly", manager.deviceName);
        assertTrue(manager.closed);
        assertFalse(events.toString().contains("+12025550123"));
    }

    @Test
    void linkingUsesExplicitDeviceName() throws Exception {
        final var events = new ArrayList<Object>();
        final var manager = new ProvisioningStub(events, false);
        App.linkAccount(new Namespace(Map.of("name", "Codex context")), manager, (JsonWriter) events::add);
        assertEquals("Codex context", manager.deviceName);
    }

    @Test
    void pairingTimeoutClosesSocketAndDoesNotClaimSuccess() {
        final var events = new ArrayList<Object>();
        final var manager = new ProvisioningStub(events, true);
        assertThrows(UserErrorException.class,
                () -> App.linkAccount(new Namespace(Map.of()), manager, (JsonWriter) events::add));
        assertEquals(List.of(Map.of("type", "link", "uri", LINK_URI.toString())), events);
        assertTrue(manager.closed);
    }

    @Test
    void plainSetupOutputAlsoOmitsTheLinkedPhoneNumber() throws Exception {
        final var output = new StringWriter();
        final var manager = new ProvisioningManager() {
            @Override
            public URI getDeviceLinkUri() {
                return LINK_URI;
            }

            @Override
            public String finishDeviceLink(final String deviceName) {
                assertTrue(output.toString().contains(LINK_URI.toString()));
                return "+12025550123";
            }

            @Override
            public void close() {}
        };
        App.linkAccount(new Namespace(Map.of()), manager, new PlainTextWriterImpl(output));
        assertTrue(output.toString().contains("Read-only client linked."));
        assertFalse(output.toString().contains("+12025550123"));
    }

    @Test
    void setupRejectsAnAccountEvenWhenCalledOutsideTheParser() {
        final var events = new ArrayList<Object>();
        final var manager = new ProvisioningStub(events, false);
        assertThrows(UserErrorException.class,
                () -> App.linkAccount(new Namespace(Map.of("account", "+12025550123")), manager,
                        (JsonWriter) events::add));
        assertFalse(manager.uriRequested);
        assertTrue(manager.closed);
        assertTrue(events.isEmpty());
    }
}
