package org.asamk.signal.commands;

import java.util.Map;
import java.util.TreeMap;

/** The complete, closed command surface of the read-only distribution. */
public final class Commands {

    private static final Map<String, Command> commands = Map.of(
            "receive", new ReceiveCommand(),
            "listAccounts", new ListAccountsCommand(),
            "listContacts", new ListContactsCommand(),
            "listGroups", new ListGroupsCommand(),
            "listIdentities", new ListIdentitiesCommand(),
            "version", new VersionCommand());

    private Commands() {}

    public static Map<String, SubparserAttacher> getCommandSubparserAttachers() {
        final var attachers = new TreeMap<String, SubparserAttacher>();
        commands.forEach((name, command) -> attachers.put(name, ((CliCommand) command)::attachToSubparser));
        return Map.copyOf(attachers);
    }

    public static Command getCommand(String commandKey) {
        return commandKey == null ? null : commands.get(commandKey);
    }
}
