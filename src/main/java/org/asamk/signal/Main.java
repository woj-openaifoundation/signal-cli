/*
  Copyright (C) 2015-2022 AsamK and contributors

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.asamk.signal;

import org.asamk.signal.commands.exceptions.CaptchaRejectedErrorException;
import org.asamk.signal.commands.exceptions.CommandException;
import org.asamk.signal.commands.exceptions.IOErrorException;
import org.asamk.signal.commands.exceptions.RateLimitErrorException;
import org.asamk.signal.commands.exceptions.UnexpectedErrorException;
import org.asamk.signal.commands.exceptions.UntrustedKeyErrorException;
import org.asamk.signal.commands.exceptions.UserErrorException;
import org.asamk.signal.util.SecurityProvider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.security.Security;

public class Main {

    static void main(String[] args) {
        // enable unlimited strength crypto via Policy, supported on relevant JREs
        Security.setProperty("crypto.policy", "unlimited");
        installSecurityProviderWorkaround();

        // Do not read upstream global config: it can enable file logging or D-Bus.
        // The service-loaded LogConfigurator disables logging before any logger is used.
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        final var parser = App.buildArgumentParser(GlobalConfig.DEFAULT);
        final var ns = parser.parseArgsOrFail(args);

        int status = 0;
        try {
            new App(ns).init();
        } catch (CommandException e) {
            status = handleCommandException(e);
        } catch (Throwable e) {
            System.err.println("Read-only Signal operation failed (" + e.getClass().getSimpleName() + ").");
            status = 2;
        }
        Shutdown.shutdownComplete();
        System.exit(status);
    }

    private static int handleCommandException(final CommandException e) {
        System.err.println(e.getMessage());
        return getStatusForError(e);
    }

    private static void installSecurityProviderWorkaround() {
        // Register our own security provider
        Security.insertProviderAt(new SecurityProvider(), 1);
        Security.addProvider(new BouncyCastleProvider());
    }

    private static int getStatusForError(final CommandException e) {
        return switch (e) {
            case UserErrorException _ -> 1;
            case UnexpectedErrorException _ -> 2;
            case IOErrorException _ -> 3;
            case UntrustedKeyErrorException _ -> 4;
            case RateLimitErrorException _ -> 5;
            case CaptchaRejectedErrorException _ -> 6;
            case null -> 2;
        };
    }

}
