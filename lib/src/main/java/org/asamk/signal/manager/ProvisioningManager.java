package org.asamk.signal.manager;

import org.asamk.signal.manager.api.UserAlreadyExistsException;

import java.io.IOException;
import java.io.Closeable;
import java.net.URI;
import java.util.concurrent.TimeoutException;

public interface ProvisioningManager extends Closeable {

    URI getDeviceLinkUri() throws TimeoutException, IOException;

    String finishDeviceLink(String deviceName) throws IOException, TimeoutException, UserAlreadyExistsException;
}
