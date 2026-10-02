package org.asamk.signal.manager.helper;

import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.api.ReceiveConfig;
import org.asamk.signal.manager.internal.SignalDependencies;
import org.asamk.signal.manager.storage.SignalAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.signalservice.api.messages.EnvelopeResponse;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.websocket.SignalWebSocket;
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState;
import org.whispersystems.signalservice.api.websocket.WebSocketUnavailableException;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import io.reactivex.rxjava3.schedulers.Schedulers;

public class ReceiveHelper {

    private static final Logger logger = LoggerFactory.getLogger(ReceiveHelper.class);
    private static final int MAX_BACKOFF_COUNTER = 9;

    private final SignalAccount account;
    private final SignalDependencies dependencies;
    private final Context context;

    private ReceiveConfig receiveConfig = new ReceiveConfig(true, true, true, true, false);
    private boolean hasCaughtUpWithOldMessages = false;
    private boolean isWaitingForMessage = false;
    private boolean shouldStop = false;
    private Callable authenticationFailureListener;
    private Callable caughtUpWithOldMessagesListener;

    public ReceiveHelper(final Context context) {
        this.account = context.getAccount();
        this.dependencies = context.getDependencies();
        this.context = context;
        dependencies.setAllowStories(false);
    }

    public void setReceiveConfig(final ReceiveConfig receiveConfig) {
        this.receiveConfig = new ReceiveConfig(true, true, true, true, false);
        dependencies.setAllowStories(false);
    }

    public void setAuthenticationFailureListener(final Callable authenticationFailureListener) {
        this.authenticationFailureListener = authenticationFailureListener;
    }

    public void setCaughtUpWithOldMessagesListener(final Callable caughtUpWithOldMessagesListener) {
        this.caughtUpWithOldMessagesListener = caughtUpWithOldMessagesListener;
    }

    public boolean requestStopReceiveMessages() {
        this.shouldStop = true;
        return isWaitingForMessage;
    }

    public void receiveMessagesContinuously(Manager.ReceiveMessageHandler handler) {
        while (!shouldStop) {
            try {
                receiveMessages(Optional.empty(), null, handler);
                break;
            } catch (IOException e) {
                logger.warn("Receiving messages failed, retrying", e);
            }
        }
    }

    public void receiveMessages(
            Optional<Duration> timeout,
            Integer maxMessages,
            Manager.ReceiveMessageHandler handler
    ) throws IOException {
        // Pre-key upload is required protocol maintenance, not a user-visible message.
        context.getPreKeyHelper().refreshPreKeysIfNecessary();
        hasCaughtUpWithOldMessages = false;

        final var signalWebSocket = dependencies.getAuthenticatedSignalWebSocket();
        final var webSocketStateDisposable = signalWebSocket.getState()
                .subscribeOn(Schedulers.computation())
                .observeOn(Schedulers.computation())
                .distinctUntilChanged()
                .subscribe(this::onWebSocketStateChange);
        signalWebSocket.connect();
        signalWebSocket.registerKeepAliveToken("receive");

        try {
            receiveMessagesInternal(signalWebSocket, timeout, maxMessages, handler);
        } finally {
            hasCaughtUpWithOldMessages = false;
            signalWebSocket.removeKeepAliveToken("receive");
            signalWebSocket.disconnect();
            webSocketStateDisposable.dispose();
            shouldStop = false;
        }
    }

    private void receiveMessagesInternal(
            final SignalWebSocket.AuthenticatedWebSocket signalWebSocket,
            Optional<Duration> timeout,
            Integer maxMessages,
            Manager.ReceiveMessageHandler handler
    ) throws IOException {
        int remainingMessages = maxMessages == null ? -1 : maxMessages;
        var backOffCounter = 0;
        isWaitingForMessage = false;

        logger.debug("Start receiving messages");
        while (!shouldStop && remainingMessages != 0) {
            SignalServiceEnvelope envelope;
            final SignalServiceEnvelope[] receivedEnvelope = {null};
            logger.trace("Checking for new message from server");
            try {
                isWaitingForMessage = true;
                final var timeoutMs = timeout.orElseGet(() -> Duration.ofMinutes(1)).toMillis();
                if (timeoutMs <= 0L) {
                    return;
                }
                var queueNotEmpty = signalWebSocket.readMessageBatch(timeoutMs, 1, batch -> {
                    logger.debug("Retrieved {} envelopes!", batch.size());
                    isWaitingForMessage = false;
                    for (final var it : batch) {
                        if (it instanceof EnvelopeResponse.Unparseable) {
                            logger.warn("Received unparseable envelope from server, ignoring.");
                        } else if (it instanceof EnvelopeResponse.Parsed parsed) {
                            SignalServiceEnvelope envelope1 = new SignalServiceEnvelope(parsed.getEnvelope(),
                                    parsed.getServerDeliveredTimestamp());
                            // Keep the envelope in memory only. Transport ACK consumes this delivery;
                            // a crash or output failure after ACK cannot be retried from disk.
                            receivedEnvelope[0] = envelope1;
                        }
                        try {
                            signalWebSocket.sendAck(it);
                        } catch (IOException e) {
                            logger.warn("Failed to acknowledge received envelope: {}", e.getMessage());
                        }
                    }
                });
                isWaitingForMessage = false;
                backOffCounter = 0;

                if (queueNotEmpty) {
                    if (receivedEnvelope[0] == null) {
                        continue;
                    }
                    if (remainingMessages > 0) {
                        remainingMessages -= 1;
                    }
                    envelope = receivedEnvelope[0];
                    logger.debug("New message received from server");
                } else {
                    logger.debug("Received indicator that server queue is empty");
                    hasCaughtUpWithOldMessages = true;
                    caughtUpWithOldMessagesListener.call();

                    // Continue to wait another timeout for new messages
                    continue;
                }
            } catch (AssertionError e) {
                if (e.getCause() instanceof InterruptedException) {
                    break;
                } else {
                    throw e;
                }
            } catch (IOException e) {
                logger.debug("Pipe unexpectedly unavailable: {}", e.getMessage());
                if (e instanceof WebSocketUnavailableException || "Connection closed!".equals(e.getMessage())) {
                    final var sleepMilliseconds = 100 * (long) Math.pow(2, backOffCounter);
                    backOffCounter = Math.min(backOffCounter + 1, MAX_BACKOFF_COUNTER);
                    logger.warn("Connection closed unexpectedly, reconnecting in {} ms", sleepMilliseconds);
                    try {
                        Thread.sleep(sleepMilliseconds);
                    } catch (InterruptedException interruptedException) {
                        return;
                    }
                    hasCaughtUpWithOldMessages = false;
                    signalWebSocket.connect();
                    continue;
                }
                throw e;
            } catch (TimeoutException e) {
                backOffCounter = 0;
                if (timeout.isPresent()) {
                    return;
                }
                continue;
            } catch (Exception e) {
                throw new IOException("Receiving failed; no message cache is kept", e);
            }

            try {
                // Returned actions are deliberately discarded: no delivery/read receipts,
                // session-reset messages, resend requests, sync replies or profile/group changes.
                context.getIncomingMessageHandler().handleEnvelope(envelope, receiveConfig, handler);
            } catch (Exception e) {
                throw new IOException("Message processing failed after transport acknowledgement; delivery cannot be replayed", e);
            }
        }
    }

    private void onWebSocketStateChange(final WebSocketConnectionState s) {
        if (s.equals(WebSocketConnectionState.AUTHENTICATION_FAILED)) {
            account.setRegistered(false);
            authenticationFailureListener.call();
        }
    }

    public interface Callable {

        void call();
    }
}
