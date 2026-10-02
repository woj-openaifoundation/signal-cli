package org.asamk.signal.json;

import org.asamk.signal.manager.api.GroupId;
import org.asamk.signal.manager.api.MessageEnvelope;
import org.asamk.signal.testutil.ManagerMock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonGroupInfoTest {

    @Test
    void unknownGroupRendersWithoutFetchingOrPersistingMetadata() throws Exception {
        final var groupId = GroupId.fromBase64("AAAAAAAAAAAAAAAAAAAAAA==");
        final var context = new MessageEnvelope.Data.GroupContext(groupId, false, 7);
        final var result = JsonGroupInfo.from(context, ManagerMock.create("+12025550123"));
        assertEquals(groupId.toBase64(), result.groupId());
        assertNull(result.groupName());
        assertEquals(7, result.revision());
    }
}
