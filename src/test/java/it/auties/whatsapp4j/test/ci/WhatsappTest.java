package it.auties.whatsapp4j.test.ci;

import it.auties.whatsapp4j.binary.BinaryArray;
import it.auties.whatsapp4j.listener.WhatsappListener;
import it.auties.whatsapp4j.manager.WhatsappKeysManager;
import it.auties.whatsapp4j.protobuf.chat.Chat;
import it.auties.whatsapp4j.protobuf.chat.GroupPolicy;
import it.auties.whatsapp4j.protobuf.chat.GroupSetting;
import it.auties.whatsapp4j.protobuf.contact.Contact;
import it.auties.whatsapp4j.protobuf.contact.ContactStatus;
import it.auties.whatsapp4j.protobuf.info.MessageInfo;
import it.auties.whatsapp4j.protobuf.message.model.MessageContainer;
import it.auties.whatsapp4j.protobuf.message.model.MessageKey;
import it.auties.whatsapp4j.protobuf.message.standard.*;
import it.auties.whatsapp4j.response.impl.json.UserInformationResponse;
import it.auties.whatsapp4j.test.github.GithubActions;
import it.auties.whatsapp4j.test.utils.ConfigUtils;
import it.auties.whatsapp4j.test.utils.MediaUtils;
import it.auties.whatsapp4j.test.utils.StatusUtils;
import it.auties.whatsapp4j.utils.WhatsappUtils;
import it.auties.whatsapp4j.utils.internal.Validate;
import it.auties.whatsapp4j.whatsapp.WhatsappAPI;
import lombok.NonNull;
import lombok.extern.java.Log;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

/**
 * A simple class to check that the library is working
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Log
@TestMethodOrder(OrderAnnotation.class)
public class WhatsappTest implements WhatsappListener {
    private WhatsappAPI whatsappAPI;
    private CountDownLatch latch;
    private String contactName;
    private boolean noKeys;
    private Contact contact;
    private Chat contactChat;
    private Chat group;

    @BeforeAll
    public void init() throws IOException {
        createApi();
        loadConfig();
        createLatch();
    }

    private void createApi() {
        log.info("Initializing api to start testing...");
        if(GithubActions.isActionsEnvironment()){
            whatsappAPI = new WhatsappAPI(loadGithubKeys());
            return;
        }

        log.info("Detected local environment");
        whatsappAPI = new WhatsappAPI();
    }

    private void loadConfig() throws IOException {
        if(GithubActions.isActionsEnvironment()) {
            log.info("Loading environment variables...");
            this.contactName = System.getenv(GithubActions.CONTACT_NAME);
            log.info("Loaded environment variables...");
            return;
        }

        log.info("Loading configuration file...");
        var props = ConfigUtils.loadConfiguration();
        this.contactName = Objects.requireNonNull(props.getProperty("contact"), "Missing contact property in config");
        this.noKeys = Boolean.parseBoolean(props.getProperty("no_keys", "false"));
        log.info("Loaded configuration file");
    }

    private WhatsappKeysManager loadGithubKeys(){
        log.info("Detected github actions environment");
        var keysJson = Base64.getDecoder().decode(System.getenv(GithubActions.CREDENTIALS_NAME));
        var keys = WhatsappKeysManager.fromJson(keysJson);
        return Validate.isValid(keys, keys.mayRestore(), "WhatsappTest: Cannot start CI as credentials are incomplete");
    }

    private void createLatch() {
        latch = new CountDownLatch(3);
    }

    @Test
    @Order(1)
    public void registerListener() {
        log.info("Registering listener...");
        whatsappAPI.registerListener(this);
        log.info("Registered listener");
    }

    @Test
    @Order(2)
    public void testConnection() throws InterruptedException {
        log.info("Connecting...");
        whatsappAPI.connect();
        latch.await();
        log.info("Connected!");
        deleteKeys();
    }

    private void deleteKeys() {
        if (!noKeys) {
            return;
        }

        log.info("Deleting keys from memory...");
        whatsappAPI.keys().deleteKeysFromMemory();
        log.info("Deleted keys from memory");
    }

    @Test
    @Order(3)
    public void testChangeGlobalPresence() throws ExecutionException, InterruptedException {
        log.info("Changing global presence...");
        var response = whatsappAPI.changePresence(ContactStatus.AVAILABLE).get();
        StatusUtils.checkStatusCode(response, "Cannot change individual presence, %s");
        log.info("Changed global presence...");
    }

    @Test
    @Order(4)
    public void testContactLookup() {
        log.info("Looking up a contact...");
        contact = whatsappAPI.manager().findContactByName(contactName)
                .orElseThrow(() -> new AssertionFailedError("Cannot lookup contact"));
        contactChat = whatsappAPI.manager().findChatByJid(contact.jid())
                .orElseThrow(() -> new AssertionFailedError("Cannot lookup chat"));
        sensitiveLog("Looked up: %s", contact);
    }

    @Test
    @Order(5)
    public void testUserPresenceSubscription() throws ExecutionException, InterruptedException {
        log.info("Subscribing to user presence...");
        var userPresenceResponse = whatsappAPI.subscribeToContactPresence(contact).get();
        StatusUtils.checkStatusCode(userPresenceResponse, "Cannot subscribe to user presence: %s");
        log.info("Subscribed to user presence: %s".formatted(userPresenceResponse));
    }

    @Test
    @Order(6)
    public void testPictureQuery() throws IOException, ExecutionException, InterruptedException {
        log.info("Loading picture...");
        var picResponse = whatsappAPI.queryChatPicture(contactChat).get();
        switch (picResponse.status()){
            case 200 -> {
                if(GithubActions.isActionsEnvironment()){
                    return;
                }

                var file = Files.createTempFile(UUID.randomUUID().toString(), ".jpg");
                Files.write(file, MediaUtils.readBytes(picResponse.url()), StandardOpenOption.CREATE);
                log.info("Loaded picture: %s".formatted(file.toString()));
            }
            case 401 -> log.info("Cannot query pic because the contact blocked you");
            case 404 -> log.info("The contact doesn't have a pic");
            default -> throw new AssertionFailedError("Cannot query pic, erroneous status code: %s".formatted(picResponse));
        }
    }

    @Test
    @Order(7)
    public void testStatusQuery() throws ExecutionException, InterruptedException {
        log.info("Querying %s's status...".formatted(contact.bestName()));
        whatsappAPI.queryUserStatus(contact)
                .get()
                .status()
                .ifPresentOrElse(status -> sensitiveLog("Queried %s", status), () -> sensitiveLog("%s doesn't have a status", contact.bestName()));
    }

    @Test
    @Order(8)
    public void testFavouriteMessagesQuery() throws ExecutionException, InterruptedException {
        log.info("Loading 20 favourite messages...");
        var favouriteMessagesResponse = whatsappAPI.queryFavouriteMessagesInChat(contactChat, 20).get();
        sensitiveLog("Loaded favourite messages: %s", favouriteMessagesResponse.data());
    }

    @Test
    @Order(9)
    public void testGroupsInCommonQuery() throws ExecutionException, InterruptedException {
        log.info("Loading groups in common...");
        var groupsInCommonResponse = whatsappAPI.queryGroupsInCommon(contact).get();
        Assertions.assertEquals(200, groupsInCommonResponse.status(), "Cannot query groups in common: %s".formatted(groupsInCommonResponse));
        sensitiveLog("Loaded groups in common: %s", groupsInCommonResponse.groups());
    }

    @Test
    @Order(10)
    public void testMarkChat() throws ExecutionException, InterruptedException {
        if(contactChat.hasUnreadMessages()){
            markAsRead();
            markAsUnread();
            return;
        }

        markAsUnread();
        markAsRead();
    }

    private void markAsUnread() throws ExecutionException, InterruptedException {
        log.info("Marking chat as unread...");
        var markStatus = whatsappAPI.markAsUnread(contactChat).get();
        StatusUtils.checkStatusCode(markStatus, "Cannot mark chat as unread: %s");
        log.info("Marked chat as unread");
    }

    private void markAsRead() throws ExecutionException, InterruptedException {
        log.info("Marking chat as read...");
        var markStatus = whatsappAPI.markAsRead(contactChat).get();
        StatusUtils.checkStatusCode(markStatus, "Cannot mark chat as read: %s");
        log.info("Marked chat as read");
    }


    @Test
    @Order(11)
    public void testGroupCreation() throws InterruptedException, ExecutionException {
        log.info("Creating group...");
        group = whatsappAPI.createGroup(BinaryArray.random(5).toHex(), contact).get();
        sensitiveLog("Created group: %s", group);
    }

    @Test
    @Order(12)
    public void testChangeIndividualPresence() throws ExecutionException, InterruptedException {
        for(var presence : ContactStatus.values()) {
            log.info("Changing individual presence to %s...".formatted(presence.name()));
            var response = whatsappAPI.changePresence(group, presence).get();
            StatusUtils.checkStatusCode(response, "Cannot change individual presence, %s");
            log.info("Changed individual presence...");
        }
    }

    @Test
    @Order(13)
    public void testChangeGroupName() throws InterruptedException, ExecutionException {
        log.info("Changing group name...");
        var changeGroupResponse = whatsappAPI.changeGroupName(group, "omega").get();
        StatusUtils.checkStatusCode(changeGroupResponse, "Cannot change group name: %s");
        log.info("Changed group name");
    }

    @RepeatedTest(2)
    @Order(14)
    public void testChangeGroupDescription() throws InterruptedException, ExecutionException {
        log.info("Changing group description...");
        var changeGroupResponse = whatsappAPI.changeGroupDescription(group, BinaryArray.random(12).toHex()).get();
        StatusUtils.checkStatusCode(changeGroupResponse, "Cannot change group description, erroneous response: %s".formatted(changeGroupResponse.status()));
        log.info("Changed group description");
    }

    @Test
    @Order(15)
    public void testRemoveGroupParticipant() throws InterruptedException, ExecutionException {
        sensitiveLog("Removing %s...", contact.bestName());
        var changeGroupResponse = whatsappAPI.remove(group, contact).get();
        switch (changeGroupResponse.status()){
            case 200 -> log.info("Cannot remove participant, erroneous response: %s".formatted(changeGroupResponse));
            case 207 -> {
                Assertions.assertTrue(StatusUtils.checkStatusCode(changeGroupResponse), "Cannot remove participant, erroneous response: %s");
                sensitiveLog("Removed %s", contact.bestName());
            }

            default -> throw new AssertionFailedError("Cannot remove participant, erroneous response code: %s".formatted(changeGroupResponse));
        }
    }

    @Test
    @Order(16)
    public void testAddGroupParticipant() throws InterruptedException, ExecutionException {
        sensitiveLog("Adding %s...", contact.bestName());
        var changeGroupResponse = whatsappAPI.add(group, contact).get();
        switch (changeGroupResponse.status()){
            case 200 -> log.info("Cannot add participant, erroneous response: %s".formatted(changeGroupResponse));
            case 207 -> {
                Assertions.assertTrue(StatusUtils.checkStatusCode(changeGroupResponse), "Cannot add participant, erroneous response: %s");
                sensitiveLog("Added %s", contact.bestName());
            }

            default -> throw new AssertionFailedError("Cannot add participant, erroneous response code: %s".formatted(changeGroupResponse));
        }
    }

    @Test
    @Order(17)
    public void testPromotion() throws InterruptedException, ExecutionException {
        log.info("Promoting %s...".formatted(contact.bestName()));
        var changeGroupResponse = whatsappAPI.promote(group, contact).get();
        switch (changeGroupResponse.status()){
            case 200 -> log.info("Cannot promote participant, erroneous response: %s".formatted(changeGroupResponse));
            case 207 -> {
                Assertions.assertTrue(StatusUtils.checkStatusCode(changeGroupResponse), "Cannot promote participant, erroneous response: %s");
                sensitiveLog("Promoted %s", contact.bestName());
            }

            default -> throw new AssertionFailedError("Cannot promote participant, erroneous response code: %s".formatted(changeGroupResponse));
        }
    }

    @Test
    @Order(18)
    public void testDemotion() throws InterruptedException, ExecutionException {
        log.info("Demoting %s...".formatted(contact.bestName()));
        var changeGroupResponse = whatsappAPI.demote(group, contact).get();
        switch (changeGroupResponse.status()){
            case 200 -> log.info("Cannot demote participant, erroneous response: %s".formatted(changeGroupResponse));
            case 207 -> {
                Assertions.assertTrue(StatusUtils.checkStatusCode(changeGroupResponse), "Cannot demote participant, erroneous response: %s");
                sensitiveLog("Demoted %s", contact.bestName());
            }

            default -> throw new AssertionFailedError("Cannot demote participant, erroneous response code: %s".formatted(changeGroupResponse));
        }
    }

    @Test
    @Order(19)
    public void testChangeAllGroupSettings() throws InterruptedException, ExecutionException {
        for (var setting : GroupSetting.values()) {
            for (var policy : GroupPolicy.values()) {
                log.info("Changing setting %s to %s...".formatted(setting.name(), policy.name()));
                var changeGroupResponse = whatsappAPI.changeGroupSetting(group, setting, policy).get();
                Assertions.assertEquals(200, changeGroupResponse.status(), "Cannot change setting %s to %s, %s".formatted(setting.name(), policy.name(), changeGroupResponse));
                log.info("Changed setting %s to %s...".formatted(setting.name(), policy.name()));
            }
        }
    }

    @Test
    @Order(20)
    public void testChangeAndRemoveGroupPicture() {
        log.warning("Not implemented");
    }

    @Test
    @Order(21)
    public void testGroupQuery() throws InterruptedException, ExecutionException {
        sensitiveLog("Querying group %s...", group.jid());
        whatsappAPI.queryChat(group.jid()).get();
        log.info("Queried group");
    }

    @Test
    @Order(22)
    public void testLoadConversation() throws InterruptedException, ExecutionException {
        log.info("Loading conversation(%s)...".formatted(group.messages().size()));
        whatsappAPI.loadChatHistory(group).get();
        log.info("Loaded conversation(%s)!".formatted(group.messages().size()));
    }

    @Test
    @Order(23)
    public void testMute() throws ExecutionException, InterruptedException {
        log.info("Muting chat...");
        var muteResponse = whatsappAPI.mute(group, ZonedDateTime.now().plusDays(14)).get();
        StatusUtils.checkStatusCode(muteResponse, "Cannot mute chat: %s");
        log.info("Muted chat");
    }

    @Test
    @Order(24)
    public void testUnmute() throws ExecutionException, InterruptedException {
        log.info("Unmuting chat...");
        var unmuteResponse = whatsappAPI.unmute(group).get();
        StatusUtils.checkStatusCode(unmuteResponse, "Cannot unmute chat: %s");
        log.info("Unmuted chat");
    }

    @Test
    @Order(25)
    public void testArchive() throws ExecutionException, InterruptedException {
        log.info("Archiving chat...");
        var archiveResponse = whatsappAPI.archive(group).get();
        StatusUtils.checkStatusCode(archiveResponse, "Cannot archive chat: %s");
        log.info("Archived chat");
    }

    @Test
    @Order(26)
    public void testUnarchive() throws ExecutionException, InterruptedException {
        log.info("Unarchiving chat...");
        var unarchiveResponse = whatsappAPI.unarchive(group).get();
        StatusUtils.checkStatusCode(unarchiveResponse, "Cannot unarchive chat: %s");
        log.info("Unarchived chat");
    }

    @Test
    @Order(27)
    public void testPin() throws ExecutionException, InterruptedException {
        if(whatsappAPI.manager().pinnedChats() >= 3){
            log.info("Skipping chat pinning as there are already three chats pinned...");
            return;
        }

        log.info("Pinning chat...");
        var pinResponse = whatsappAPI.pin(group).get();
        StatusUtils.checkStatusCode(pinResponse, "Cannot pin chat: %s");
        log.info("Pinned chat");
    }

    @Test
    @Order(28)
    public void testUnpin() throws ExecutionException, InterruptedException {
        if(whatsappAPI.manager().pinnedChats() >= 3){
            log.info("Skipping chat unpinning as there are already three chats pinned...");
            return;
        }

        log.info("Unpinning chat...");
        var unpinResponse = whatsappAPI.unpin(group).get();
        StatusUtils.checkStatusCode(unpinResponse, "Cannot unpin chat: %s");
        log.info("Unpinned chat");
    }

    @Test
    @Order(29)
    public void testTextMessage() throws ExecutionException, InterruptedException {
        log.info("Sending text...");
        var key = new MessageKey(contactChat);
        var message = new MessageContainer("Test");
        var info = new MessageInfo(key, message);
        var textResponse = whatsappAPI.sendMessage(info).get();
        Assertions.assertEquals(200, textResponse.status(), "Cannot send text: %s".formatted(textResponse));
        log.info("Sent text");
    }

    @Test
    @Order(30)
    public void testImageMessage() throws ExecutionException, InterruptedException, IOException {
        log.info("Sending image...");
        var key = new MessageKey(group);
        var image = ImageMessage.newImageMessage()
                .media(MediaUtils.readBytes("https://2.bp.blogspot.com/-DqXILvtoZFA/Wmmy7gRahnI/AAAAAAAAB0g/59c8l63QlJcqA0591t8-kWF739DiOQLcACEwYBhgL/s1600/pol-venere-botticelli-01.jpg"))
                .caption("Image test")
                .create();
        var message = new MessageContainer(image);
        var info = new MessageInfo(key, message);
        var textResponse = whatsappAPI.sendMessage(info).get();
        Assertions.assertEquals(200, textResponse.status(), "Cannot send image: %s".formatted(textResponse));
        log.info("Sent image");
    }

    @Test
    @Order(31)
    public void testAudioMessage() throws ExecutionException, InterruptedException, IOException {
        log.info("Sending audio...");
        var key = new MessageKey(group);
        var audio = AudioMessage.newAudioMessage()
                .media(MediaUtils.readBytes("https://www.kozco.com/tech/organfinale.mp3"))
                .create();
        var message = new MessageContainer(audio);
        var info = new MessageInfo(key, message);
        var textResponse = whatsappAPI.sendMessage(info).get();
        Assertions.assertEquals(200, textResponse.status(), "Cannot send audio: %s".formatted(textResponse));
        log.info("Sent audio");
    }

    @Test
    @Order(32)
    public void testVideoMessage() throws ExecutionException, InterruptedException, IOException {
        log.info("Sending video...");
        var key = new MessageKey(group);
        var video = VideoMessage.newVideoMessage()
                .media(MediaUtils.readBytes("http://techslides.com/demos/sample-videos/small.mp4"))
                .caption("Video")
                .create();
        var message = new MessageContainer(video);
        var info = new MessageInfo(key, message);
        var textResponse = whatsappAPI.sendMessage(info).get();
        Assertions.assertEquals(200, textResponse.status(), "Cannot send video: %s".formatted(textResponse));
        log.info("Sent video");
    }

    @Test
    @Order(33)
    public void testGifMessage() throws ExecutionException, InterruptedException, IOException {
        log.info("Sending gif...");
        var key = new MessageKey(group);
        var video = VideoMessage.newGifMessage()
                .media(MediaUtils.readBytes("http://techslides.com/demos/sample-videos/small.mp4"))
                .caption("Gif")
                .create();
        var message = new MessageContainer(video);
        var info = new MessageInfo(key, message);
        var textResponse = whatsappAPI.sendMessage(info).get();
        Assertions.assertEquals(200, textResponse.status(), "Cannot send gif: %s".formatted(textResponse));
        log.info("Sent gif");
    }

    @Test
    @Order(34)
    public void testPdfMessage() throws ExecutionException, InterruptedException, IOException {
        log.info("Sending pdf...");
        var key = new MessageKey(group);
        var document = DocumentMessage.newDocumentMessage()
                .media(MediaUtils.readBytes("http://www.orimi.com/pdf-test.pdf"))
                .title("Pdf test")
                .fileName("pdf-test.pdf")
                .pageCount(1)
                .create();
        var message = new MessageContainer(document);
        var info = new MessageInfo(key, message);
        var textResponse = whatsappAPI.sendMessage(info).get();
        Assertions.assertEquals(200, textResponse.status(), "Cannot send pdf: %s".formatted(textResponse));
        log.info("Sent pdf");
    }

    @Test
    @Order(35)
    public void testContactMessage() throws ExecutionException, InterruptedException {
        log.info("Sending contact message...");
        var key = new MessageKey(group);
        var vcard = buildVcard();
        var document = ContactMessage.newContactMessage()
                .displayName(contact.bestName(contact.jid()))
                .vcard(vcard)
                .create();
        var message = new MessageContainer(document);
        var info = new MessageInfo(key, message);
        var textResponse = whatsappAPI.sendMessage(info).get();
        Assertions.assertEquals(200, textResponse.status(), "Cannot send contact message: %s".formatted(textResponse));
        log.info("Sent contact message");
    }

    private String buildVcard() {
        return """
                BEGIN:VCARD
                VERSION:3.0
                N:%s
                FN:%s
                TEL;type=CELL:+%s
                END:VCARD
                """.formatted(contact.shortName(), contact.name(), WhatsappUtils.phoneNumberFromJid(contact.jid()));
    }

    @Test
    @Order(36)
    public void testLocationMessage() throws ExecutionException, InterruptedException {
        log.info("Sending location message...");
        var key = new MessageKey(group);
        var location = LocationMessage.newLocationMessage()
                .degreesLatitude(40.730610)
                .degreesLongitude(-73.935242)
                .degreesClockwiseFromMagneticNorth(0)
                .create();
        var message = new MessageContainer(location);
        var info = new MessageInfo(key, message);
        var textResponse = whatsappAPI.sendMessage(info).get();
        Assertions.assertEquals(200, textResponse.status(), "Cannot send location message: %s".formatted(textResponse));
        log.info("Sent location message");
    }

    @Test
    @Order(37)
    public void testGroupInviteMessage() throws ExecutionException, InterruptedException {
        log.info("Querying group invite code");
        var code = whatsappAPI.queryGroupInviteCode(group).get().code();
        log.info("Queried %s".formatted(code));
        log.info("Sending group invite message...");
        var key = new MessageKey(contactChat);
        var invite = GroupInviteMessage.newGroupInviteMessage()
                .groupJid(group.jid())
                .groupName(group.displayName())
                .inviteExpiration(ZonedDateTime.now().plusDays(3).toInstant().toEpochMilli())
                .inviteCode(code)
                .create();
        var message = new MessageContainer(invite);
        var info = new MessageInfo(key, message);
        var textResponse = whatsappAPI.sendMessage(info).get();
        Assertions.assertEquals(200, textResponse.status(), "Cannot send group invite message: %s".formatted(textResponse));
        log.info("Sent group invite message");
    }


    @Test
    @Order(38)
    public void testEnableEphemeralMessages() throws ExecutionException, InterruptedException {
        log.info("Enabling ephemeral messages...");
        var ephemeralResponse = whatsappAPI.enableEphemeralMessages(group).get();
        StatusUtils.checkStatusCode(ephemeralResponse, "Cannot enable ephemeral messages: %s");
        log.info("Enabled ephemeral messages");
    }

    @Test
    @Order(39)
    public void testDisableEphemeralMessages() throws ExecutionException, InterruptedException {
        log.info("Disabling ephemeral messages...");
        var ephemeralResponse = whatsappAPI.disableEphemeralMessages(group).get();
        StatusUtils.checkStatusCode(ephemeralResponse, "Cannot disable ephemeral messages: %s");
        log.info("Disabled ephemeral messages");
    }

    @Test
    @Order(40)
    public void testLeave() throws ExecutionException, InterruptedException {
        log.info("Leaving group...");
        var ephemeralResponse = whatsappAPI.leave(group).get();
        StatusUtils.checkStatusCode(ephemeralResponse, "Cannot leave group: %s");
        log.info("Left group");
    }

    @Override
    public void onLoggedIn(@NonNull UserInformationResponse info) {
        log.info("Logged in!");
        latch.countDown();
    }

    @Override
    public void onChats() {
        log.info("Got chats!");
        latch.countDown();
    }

    @Override
    public void onContacts() {
        log.info("Got contacts!");
        latch.countDown();
    }

    private void sensitiveLog(String message, Object... params){
        log.info(message.formatted(redactParameters(params)));
    }

    private Object[] redactParameters(Object... params){
        if (!GithubActions.isActionsEnvironment()) {
            return params;
        }

        return Arrays.stream(params)
                .map(entry -> "***")
                .toArray(String[]::new);
    }
}