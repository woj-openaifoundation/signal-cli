package org.asamk.signal.commands;

import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import org.asamk.signal.commands.exceptions.CommandException;
import org.asamk.signal.json.JsonContact;
import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.api.Contact;
import org.asamk.signal.manager.api.PhoneNumberSharingMode;
import org.asamk.signal.manager.api.Profile;
import org.asamk.signal.output.JsonWriter;
import org.asamk.signal.output.OutputWriter;
import org.asamk.signal.output.PlainTextWriter;
import org.asamk.signal.util.CommandUtil;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

public class ListContactsCommand implements JsonRpcLocalCommand {

    @Override
    public String getName() {
        return "listContacts";
    }

    @Override
    public void attachToSubparser(final Subparser subparser) {
        subparser.help("Show a list of known contacts with names and profiles.");
        subparser.addArgument("recipient").help("Specify one ore more phone numbers to show.").nargs("*");
        subparser.addArgument("-a", "--all-recipients")
                .action(Arguments.storeTrue())
                .help("Include all known recipients, not only contacts.");
        subparser.addArgument("--blocked")
                .type(Boolean.class)
                .help("Specify if only blocked or unblocked contacts should be shown (default: all contacts)");
        subparser.addArgument("--name").help("Find contacts with the given contact or profile name.");
        subparser.addArgument("--detailed")
                .action(Arguments.storeTrue())
                .help("List the contacts with more details. If output=json, then this is always set");
        subparser.addArgument("--internal")
                .action(Arguments.storeTrue())
                .help("Include internal information that's normally not user visible");
    }

    @Override
    public void handleCommand(
            final Namespace ns,
            final Manager m,
            final OutputWriter outputWriter
    ) throws CommandException {
        final var allRecipients = Boolean.TRUE.equals(ns.getBoolean("all-recipients"));
        final var blocked = ns.getBoolean("blocked");
        final var recipientStrings = ns.<String>getList("recipient");
        final var recipientIdentifiers = CommandUtil.getSingleRecipientIdentifiers(recipientStrings, m.getSelfNumber());
        final var name = ns.getString("name");
        final var recipients = m.getRecipients(!allRecipients,
                Optional.ofNullable(blocked),
                recipientIdentifiers,
                Optional.ofNullable(name));

        final var detailed = Boolean.TRUE.equals(ns.getBoolean("detailed"));
        final var internal = Boolean.TRUE.equals(ns.getBoolean("internal"));

        switch (outputWriter) {
            case PlainTextWriter writer -> {
                for (var r : recipients) {
                    final var contact = r.getContact() == null ? Contact.newBuilder().build() : r.getContact();
                    final var profile = r.getProfile() == null ? Profile.newBuilder().build() : r.getProfile();
                    writer.println(
                            "Number: {} ACI: {} Name: {} Profile name: {} Username: {} Color: {} Blocked: {} Message expiration: {}",
                            r.getAddress().number().orElse(""),
                            r.getAddress().aci().orElse(""),
                            contact.getName(),
                            profile.getDisplayName(),
                            r.getAddress().username().orElse(""),
                            Optional.ofNullable(contact.color()).orElse(""),
                            contact.isBlocked(),
                            contact.messageExpirationTime() == 0 ? "disabled" : contact.messageExpirationTime() + "s");
                    if (detailed) {
                        writer.indentedWriter()
                                .println(
                                        "PNI: {} Given name: {} Family name: {}, Nick name: {} Nick given name: {} Nick family name {} Note: {} Archived: {} Hidden: {} Profile sharing: {} About: {} About Emoji: {} Unregistered: {}",
                                        r.getAddress().pni().orElse(""),
                                        Optional.ofNullable(contact.givenName()).orElse(""),
                                        Optional.ofNullable(contact.familyName()).orElse(""),
                                        Optional.ofNullable(contact.nickName()).orElse(""),
                                        Optional.ofNullable(contact.nickNameGivenName()).orElse(""),
                                        Optional.ofNullable(contact.nickNameFamilyName()).orElse(""),
                                        Optional.ofNullable(contact.note()).orElse(""),
                                        contact.isArchived(),
                                        contact.isHidden(),
                                        contact.isProfileSharingEnabled(),
                                        Optional.ofNullable(profile.getAbout()).orElse(""),
                                        Optional.ofNullable(profile.getAboutEmoji()).orElse(""),
                                        contact.unregisteredTimestamp() != null);
                    }
                    if (internal) {
                        writer.indentedWriter()
                                .println(
                                        "Capabilities: {} Unidentified access mode: {} Shares number: {} Discoverable by number: {}",
                                        profile.getCapabilities().stream().map(Enum::name).toList(),
                                        Optional.ofNullable(profile.getUnidentifiedAccessMode()
                                                == Profile.UnidentifiedAccessMode.UNKNOWN
                                                ? null
                                                : profile.getUnidentifiedAccessMode().name()).orElse(""),
                                        profile.getPhoneNumberSharingMode() == null
                                                ? ""
                                                : String.valueOf(profile.getPhoneNumberSharingMode()
                                                                 == PhoneNumberSharingMode.EVERYBODY),
                                        r.getDiscoverable() == null ? "" : String.valueOf(r.getDiscoverable()));
                    }
                }
            }
            case JsonWriter writer -> {
                final var jsonContacts = recipients.stream().map(r -> {
                    final var address = r.getAddress();
                    final var contact = r.getContact() == null ? Contact.newBuilder().build() : r.getContact();
                    final var profile = r.getProfile() == null ? Profile.newBuilder().build() : r.getProfile();
                    final var jsonInternal = !internal
                            ? null
                            : new JsonContact.JsonInternal(profile
                                                           .getCapabilities()
                                                           .stream()
                                                           .map(Enum::name)
                                                           .toList(),
                                    profile.getUnidentifiedAccessMode() == Profile.UnidentifiedAccessMode.UNKNOWN
                                    ? null
                                            : profile.getUnidentifiedAccessMode().name(),
                                    profile.getPhoneNumberSharingMode() == null
                                    ? null
                                            : profile.getPhoneNumberSharingMode()
                                              == PhoneNumberSharingMode.EVERYBODY,
                                    r.getDiscoverable());
                    return new JsonContact(address.number().orElse(null),
                            address.uuid().map(UUID::toString).orElse(null),
                            address.username().orElse(null),
                            contact.getName(),
                            contact.givenName(),
                            contact.familyName(),
                            contact.nickName(),
                            contact.nickNameGivenName(),
                            contact.nickNameFamilyName(),
                            contact.note(),
                            contact.color(),
                            contact.isArchived(),
                            contact.isBlocked(),
                            contact.isHidden(),
                            contact.messageExpirationTime(),
                            contact.isProfileSharingEnabled(),
                            contact.unregisteredTimestamp() != null,
                            r.getProfile() == null
                                    ? null
                                    : new JsonContact.JsonProfile(profile.getLastUpdateTimestamp(),
                                            profile.getGivenName(),
                                            profile.getFamilyName(),
                                            profile.getAbout(),
                                            profile.getAboutEmoji(),
                                            profile.getAvatarUrlPath() != null,
                                            profile.getMobileCoinAddress() == null
                                            ? null
                                                    : Base64.getEncoder()
                                                      .encodeToString(profile.getMobileCoinAddress())),
                            jsonInternal);
                }).toList();
                writer.write(jsonContacts);
            }
        }
    }
}
