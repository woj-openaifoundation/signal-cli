package org.asamk.signal;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

import org.asamk.signal.commands.Command;
import org.asamk.signal.commands.CommandHandler;
import org.asamk.signal.commands.Commands;
import org.asamk.signal.commands.LocalCommand;
import org.asamk.signal.commands.exceptions.CommandException;
import org.asamk.signal.commands.exceptions.IOErrorException;
import org.asamk.signal.commands.exceptions.UnexpectedErrorException;
import org.asamk.signal.commands.exceptions.UserErrorException;
import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.Settings;
import org.asamk.signal.manager.SignalAccountFiles;
import org.asamk.signal.manager.api.AccountCheckException;
import org.asamk.signal.manager.api.NotRegisteredException;
import org.asamk.signal.manager.api.ServiceEnvironment;
import org.asamk.signal.manager.api.TrustNewIdentity;
import org.asamk.signal.output.JsonWriterImpl;
import org.asamk.signal.output.OutputWriter;
import org.asamk.signal.output.PlainTextWriterImpl;
import org.asamk.signal.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Set;

import static net.sourceforge.argparse4j.DefaultSettings.VERSION_0_9_0_DEFAULT_SETTINGS;

public class App {

    private static final Logger logger = LoggerFactory.getLogger(App.class);

    private final Namespace ns;

    static ArgumentParser buildArgumentParser(GlobalConfig config) {
        // Configuration files are deliberately ignored in this distribution.
        var parser = ArgumentParsers.newFor("signal-cli-readonly", VERSION_0_9_0_DEFAULT_SETTINGS)
                .includeArgumentNamesAsKeysInResult(true)
                .build()
                .defaultHelp(true)
                .description("Read-only Signal context client. No sending, RPC, media downloads, or logging.")
                .version("signal-cli-readonly " + BaseConfig.PROJECT_VERSION);
        parser.addArgument("--version").help("Show package version.").action(Arguments.version());
        parser.addArgument("-d", "--data-dir", "-c", "--config")
                .help("Existing signal-cli protocol-state directory (default: $XDG_DATA_HOME/signal-cli).");
        parser.addArgument("-a", "--account", "-u", "--username")
                .help("Account phone number or ACI.");
        parser.addArgument("-o", "--output")
                .help("Output format (default: json).")
                .type(Arguments.enumStringType(OutputType.class))
                .setDefault(OutputType.JSON);
        parser.epilog("Only the listed commands are available. Global configuration files are ignored. "
                + "Message output goes only to stdout; the caller controls its retention.");

        var subparsers = parser.addSubparsers().title("subcommands").dest("command");

        Commands.getCommandSubparserAttachers().forEach((key, value) -> {
            var subparser = subparsers.addParser(key);
            value.attachToSubparser(subparser);
        });

        return parser;
    }

    public App(final Namespace ns) {
        this.ns = ns;
    }

    public void init() throws CommandException {
        rejectUnsafeNamespace(ns);
        var commandKey = ns.getString("command");
        var command = Commands.getCommand(commandKey);
        if (command == null) {
            throw new UserErrorException("Command is unavailable in this read-only distribution.");
        }

        final var outputWriter = getOutputWriter(command);
        final var commandHandler = new CommandHandler(ns, outputWriter);

        var account = ns.getString("account");

        if (command instanceof org.asamk.signal.commands.VersionCommand versionCommand) {
            versionCommand.handleCommand(ns, (Manager) null, outputWriter);
            return;
        }

        final var signalAccountFiles = loadSignalAccountFiles();
        if (command instanceof org.asamk.signal.commands.ListAccountsCommand listAccountsCommand) {
            try {
                listAccountsCommand.outputAccounts(signalAccountFiles.getAllLocalAccountNumbers(), outputWriter);
            } catch (IOException e) {
                throw new IOErrorException("Failed to read account identifiers", e);
            }
            return;
        }

        if (!Manager.isSignalClientAvailable()) {
            throw new UserErrorException("Missing required native library dependency: libsignal-client");
        }

        handleCommand(command, commandHandler, account, signalAccountFiles);
    }

    static void rejectUnsafeNamespace(final Namespace ns) throws UserErrorException {
        if (Boolean.TRUE.equals(ns.getBoolean("global-dbus"))
                || Boolean.TRUE.equals(ns.getBoolean("global-dbus-system"))
                || Boolean.TRUE.equals(ns.getBoolean("send-read-receipts"))
                || ns.get("log-file") != null
                || (ns.getInt("verbose") != null && ns.getInt("verbose") > 0)) {
            throw new UserErrorException("Sending receipts, D-Bus, and logging are disabled in this distribution.");
        }
    }

    private void handleCommand(
            final Command command,
            final CommandHandler commandHandler,
            String account,
            final SignalAccountFiles signalAccountFiles
    ) throws CommandException {
        if (account == null) {
            account = getAccountIfOnlyOne(signalAccountFiles);
        }

        if (command instanceof LocalCommand localCommand) {
            if (!Manager.isValidNumber(account, null) && !Manager.isValidAci(account)) {
                throw new UserErrorException("Invalid account (phone number), make sure you include the country code.");
            }
            handleLocalCommand(localCommand, account, signalAccountFiles, commandHandler);
            return;
        }

        throw new UserErrorException("Command only works in multi-account mode");
    }

    private static String getAccountIfOnlyOne(final SignalAccountFiles signalAccountFiles) throws IOErrorException, UserErrorException {
        Set<String> accounts;
        try {
            accounts = signalAccountFiles.getAllLocalAccountNumbers();
        } catch (IOException e) {
            throw new IOErrorException("Failed to load local accounts file", e);
        }
        if (accounts.isEmpty()) {
            throw new UserErrorException("No linked account in this state directory. Use --data-dir with an existing linked signal-cli account.");
        } else if (accounts.size() > 1) {
            throw new UserErrorException("Multiple users found, you need to specify an account (phone number) with -a");
        }
        return accounts.stream().findFirst().get();
    }

    private OutputWriter getOutputWriter(final Command command) throws UserErrorException {
        final var outputTypeInput = ns.<OutputType>get("output");
        final var outputType = outputTypeInput == null ? OutputType.JSON : outputTypeInput;
        final var writer = new BufferedWriter(new OutputStreamWriter(System.out, IOUtils.getConsoleCharset()));
        final var outputWriter = outputType == null
                ? null
                : outputType == OutputType.JSON ? new JsonWriterImpl(writer) : new PlainTextWriterImpl(writer);

        if (outputWriter != null && !command.getSupportedOutputTypes().contains(outputType)) {
            throw new UserErrorException("Command doesn't support output type " + outputType);
        }
        return outputWriter;
    }

    private SignalAccountFiles loadSignalAccountFiles() throws IOErrorException {
        final File dataPath;
        final var dataDir = ns.getString("data-dir");
        if (dataDir != null) {
            dataPath = new File(dataDir);
        } else {
            dataPath = getDefaultDataPath();
        }

        final var serviceEnvironment = ServiceEnvironment.LIVE;
        final var trustNewIdentity = TrustNewIdentity.ON_FIRST_USE;
        final var disableSendLog = true;

        try {
            return new SignalAccountFiles(dataPath,
                    serviceEnvironment,
                    BaseConfig.USER_AGENT,
                    new Settings(trustNewIdentity, disableSendLog));
        } catch (IOException e) {
            throw new IOErrorException("Failed to read local accounts list", e);
        }
    }

    private void handleLocalCommand(
            final LocalCommand command,
            final String account,
            final SignalAccountFiles signalAccountFiles,
            final CommandHandler commandHandler
    ) throws CommandException {
        try (var m = loadManager(account, signalAccountFiles)) {
            commandHandler.handleLocalCommand(command, m);
        }
    }

    private Manager loadManager(
            final String account,
            final SignalAccountFiles signalAccountFiles
    ) throws CommandException {
        logger.trace("Loading account file for {}", account);
        try {
            if (Manager.isValidAci(account)) {
                return signalAccountFiles.initManagerByAci(account);
            } else {
                return signalAccountFiles.initManagerByNumber(account);
            }
        } catch (NotRegisteredException e) {
            throw new UserErrorException("User " + account + " is not registered.");
        } catch (AccountCheckException ace) {
            if (ace.getCause() instanceof IOException e) {
                throw new IOErrorException("Error while checking account " + account + ": " + e.getMessage(), e);
            } else {
                throw new UnexpectedErrorException("Error while checking account " + account + ": " + ace.getMessage(),
                        ace);
            }
        } catch (Throwable e) {
            throw new UnexpectedErrorException("Error loading state file for user "
                    + account
                    + ": "
                    + e.getMessage()
                    + " ("
                    + e.getClass().getSimpleName()
                    + ")", e);
        }
    }

    /**
     * @return the default data directory to be used by signal-cli.
     */
    private static File getDefaultDataPath() {
        return new File(IOUtils.getDataHomeDir(), "signal-cli");
    }
}
