package org.asamk.signal.commands;

import net.sourceforge.argparse4j.inf.Namespace;

import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.api.Recipient;
import org.asamk.signal.manager.api.RecipientAddress;
import org.asamk.signal.output.JsonWriter;
import org.asamk.signal.output.PlainTextWriterImpl;
import org.asamk.signal.util.Util;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyListContactsTest {

    private static final UUID RECIPIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");

    private Manager protocolStateOnlyManager() {
        final var recipient = Recipient.newBuilder().withAddress(new RecipientAddress(RECIPIENT_ID)).build();
        return (Manager) Proxy.newProxyInstance(Manager.class.getClassLoader(), new Class<?>[]{Manager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSelfNumber" -> "+12025550123";
                    case "getRecipients" -> List.of(recipient);
                    default -> throw new AssertionError("Unexpected manager call: " + method.getName());
                });
    }

    @Test
    void jsonListsIdentifiersEvenWhenContactAndProfileWereNeverCached() throws Exception {
        final var result = new AtomicReference<Object>();
        new ListContactsCommand().handleCommand(new Namespace(Map.of("all-recipients", true, "internal", true)),
                protocolStateOnlyManager(), (JsonWriter) result::set);
        final var json = Util.createJsonObjectMapper().valueToTree(result.get());
        assertEquals(RECIPIENT_ID.toString(), json.get(0).get("uuid").asText());
    }

    @Test
    void detailedPlainOutputWorksWithoutCachedProfileOrContact() throws Exception {
        final var output = new StringWriter();
        new ListContactsCommand().handleCommand(new Namespace(Map.of("all-recipients", true,
                        "internal", true, "detailed", true)), protocolStateOnlyManager(), new PlainTextWriterImpl(output));
        assertTrue(output.toString().contains(RECIPIENT_ID.toString()));
    }
}
