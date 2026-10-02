package org.asamk.signal.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.spi.ContextAwareBase;

/** Never attach a logger to a file or console in the context-only distribution. */
public class LogConfigurator extends ContextAwareBase implements Configurator {

    @Override
    public ExecutionStatus configure(LoggerContext context) {
        context.reset();
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
        return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
    }
}
