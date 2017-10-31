package ibt.ortc.plugins.IbtRealtimeSJ;

import ibt.ortc.api.Strings;
import ibt.ortc.extensibility.EventEnum;
import ibt.ortc.extensibility.OrtcClient;
import ibt.ortc.extensibility.exception.OrtcInvalidMessageException;
import ibt.ortc.extensibility.exception.OrtcNotConnectedException;
import ibt.ortc.extensibility.OnPublishResult;
import ibt.ortc.plugins.IbtRealtimeSJ.OrtcServerErrorException.OrtcServerErrorOperation;
import ibt.ortc.plugins.websocket.WebSocket;
import ibt.ortc.plugins.websocket.WebSocketConnection;
import ibt.ortc.plugins.websocket.WebSocketEventHandler;
import ibt.ortc.plugins.websocket.WebSocketException;
import ibt.ortc.plugins.websocket.WebSocketMessage;
import ibt.ortc.plugins.websocket.WebSocketProxyException;
import ibt.ortc.plugins.websocket.WebSocketStreamException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.HashMap;
import java.util.Random;
import java.util.Timer;

import org.json.simple.*;

public final class IbtRealtimeSJClient extends OrtcClient {
	private static final Integer HEARTBEAT_TIMEOUT = 30;

	private WebSocket socket;

	private Thread heartBeatThread;
	private Date lastHeartBeat;

	@Override
	protected void connect() {
		Random randomGenerator = new Random();
		int port = this.uri.getPort();
		if (port == -1) {
			port = "https".equals(uri.getScheme()) ? 443 : 80;
		}

		String connectionUrl = String.format("%s://%s:%s/broadcast/%s/%s/websocket", this.protocol.getProtocol(), this.uri.getHost(), port,
				randomGenerator.nextInt(1000), Strings.randomString(8));

		try {
			URI connectionUri = new URI(connectionUrl);
			socket = new WebSocketConnection(connectionUri, this.proxy);
			addSocketEventsListener();

			socket.connect();
		} catch (Exception e) {
			raiseOrtcEvent(EventEnum.OnException, (OrtcClient) this, new OrtcNotConnectedException(e.getMessage()));
		} finally {
			if (isReconnecting) {
				raiseOrtcEvent(EventEnum.OnReconnecting, (OrtcClient) this);
			}
		}
	}

	private void initializeHeartBeatThread() {
		if (heartBeatThread != null) {
			heartBeatThread.interrupt();
		}

		final OrtcClient self = this;
		heartBeatThread = new Thread(new Runnable() {
			@Override
			public void run() {
			while (isConnected) {
				try {
					Thread.sleep(HEARTBEAT_TIMEOUT * 1000);
					Date currentDate = new Date();
					if (lastHeartBeat == null || ((currentDate.getTime() - lastHeartBeat.getTime()) / 1000 > HEARTBEAT_TIMEOUT)) {
						try {
							if (socket.isConnected()) {
								socket.close();
							}
						} catch (WebSocketException e) {
							raiseOrtcEvent(EventEnum.OnException, self, e);
						}
					}
				} catch (InterruptedException e) {
					// raiseOrtcEvent(EventEnum.OnException, self,e);
					Thread.currentThread().interrupt();
					break;
				}
			}
			}
		});

		heartBeatThread.start();
	}

    private void onAck(String message){
        JSONObject json = OrtcMessage.parseJSON(message);

        if (json != null && json.containsKey("m") && json.containsKey("seq")){
            try {
                HashMap pendingMsg = (HashMap) pendingPublishMessages.get((String)json.get("m"));

                // cancel publish timeout
                Timer timeout = (Timer) pendingMsg.get("timeout");
                if(timeout != null) {
                    timeout.cancel();
                }

                OnPublishResult callback = (OnPublishResult)pendingMsg.get("callback");
                callback.run(null, (String)json.get("seq"));

                pendingPublishMessages.remove((String)json.get("m"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

	private void addSocketEventsListener() {
		final OrtcClient sender = this;
		socket.setEventHandler(new WebSocketEventHandler() {

			@Override
			public void onOpen() {
			}

			@Override
			public void onMessage(WebSocketMessage socketMessage) {
				try {
					String message = socketMessage.getText();
					lastHeartBeat = new Date();
					if ("h".equals(message)) {
						//lastHeartBeat = new Date();
					} else {
						if ("o".equals(message)) {
							performValidate();
						} else {
							OrtcMessage ortcMessage = OrtcMessage.parseMessage(message);
							OrtcOperation operation = ortcMessage.getOperation();

							switch (operation) {
								case Validated:
									onValidated(ortcMessage);
									break;
								case Subscribed:
									onSubscribed(ortcMessage);
									break;
								case Unsubscribed:
									onUnsubscribed(ortcMessage);
									break;
								case Received:
									onReceived(ortcMessage);
									break;
                                case ack:
                                    onAck(message);
                                    break;
								case Error:
									onError(ortcMessage);
									break;
							}
						}
					}
				} catch (OrtcInvalidMessageException e) {
					isReconnecting = true;
					if (socket != null && socket.isConnected()) {
						try {
							socket.close();
						} catch (WebSocketException e1) {
							raiseOrtcEvent(EventEnum.OnDisconnected, sender);
						}
					} else {
						raiseOrtcEvent(EventEnum.OnDisconnected, sender);
					}
				}
			}

			@Override
			public void onClose() {
				if (heartBeatThread != null)
					heartBeatThread.interrupt();
				raiseOrtcEvent(EventEnum.OnDisconnected, sender);
			}
		});
	}

	private void performValidate() {
		String lAnnouncementSubChannel = Strings.isNullOrEmpty(this.announcementSubChannel) ? "" : this.announcementSubChannel;
		String lSessionId = "";
		String validateMessage = String.format("validate;%s;%s;%s;%s;%s", this.applicationKey, this.authenticationToken, lAnnouncementSubChannel, lSessionId,
				replaceCharsSend(this.connectionMetadata));

		sendMessage(validateMessage);
	}

	private void onValidated(OrtcMessage message) {

		this.channelsPermissions = message.getPermissions();
		raiseOrtcEvent(EventEnum.OnConnected, (OrtcClient) this);
		this.initializeHeartBeatThread();
	}

	private void onSubscribed(OrtcMessage message) {
		try {
			raiseOrtcEvent(EventEnum.OnSubscribed, (OrtcClient) this, message.channelSubscribed());
		} catch (Exception e) {
			raiseOrtcEvent(EventEnum.OnException, (OrtcClient) this, e);
		}
	}

	private void onUnsubscribed(OrtcMessage message) {
		try {
			raiseOrtcEvent(EventEnum.OnUnsubscribed, (OrtcClient) this, message.channelUnsubscribed());
		} catch (Exception e) {
			raiseOrtcEvent(EventEnum.OnException, (OrtcClient) this, e);
		}
	}

	private void onReceived(OrtcMessage message) {
		raiseOrtcEvent(EventEnum.OnReceived, message.getMessageChannel(), message.getMessage(), message.getMessageId(), message.getMessagePart(),
				message.getMessageTotalParts(), message.isFiltered(), message.getSeqId());
	}

	@SuppressWarnings("incomplete-switch")
	private void onError(OrtcMessage message) {
		Exception error;
		OrtcServerErrorException serverError = null;

		try {
			serverError = message.serverError();
			error = serverError;
		} catch (Exception e) {
			error = e;
		}

		if (serverError != null) {
			OrtcServerErrorOperation so = serverError.getOperation();
			if (so != null) {
				switch (serverError.getOperation()) {
				case Validate:
					validateServerError();
					break;
				case Subscribe:
					cancelSubscription(serverError.getChannel());
					break;
				case Subscribe_MaxSize:
					channelMaxSizeError(serverError.getChannel());
					break;
				case Unsubscribe_MaxSize:
					channelMaxSizeError(serverError.getChannel());
					break;
				case Send_MaxSize:
					messageMaxSize();
					break;
				}
			} 
		}

		raiseOrtcEvent(EventEnum.OnException, (OrtcClient) this, error);
	}

	private void validateServerError() {
		stopReconnecting();

		try {
			socket.close();
		} catch (WebSocketException e) {
			raiseOrtcEvent(EventEnum.OnException, (OrtcClient) this, e);
		}
	}

	private void channelMaxSizeError(String channel) {
		cancelSubscription(channel);
		stopReconnecting();

		try {
			socket.close();
		} catch (WebSocketException e) {
			raiseOrtcEvent(EventEnum.OnException, (OrtcClient) this, e);
		}
	}

	private void messageMaxSize() {
		stopReconnecting();

		try {
			socket.close();
		} catch (WebSocketException e) {
			raiseOrtcEvent(EventEnum.OnException, (OrtcClient) this, e);
		}
	}


	@Override
	public void disconnectIntern() {
		this.isConnecting = false;
		this.isDisconnecting = true;

		if (!isConnected && !isReconnecting) {
			raiseOrtcEvent(EventEnum.OnException, (OrtcClient) this, new OrtcNotConnectedException());
		} else if (socket != null) {
			this.isReconnecting = false;
			try {
				socket.close();
			} catch (WebSocketException e) {
				raiseOrtcEvent(EventEnum.OnException, (OrtcClient) this, e);
			}
		}
	}

    @Override
    protected  void publish(String channel, String message, int ttl, String messagePartIdentifier, String permission){
        String escapedMessage = JSONValue.escape(message);

        String messageParsed = String.format("publish;%s;%s;%s;%s;%s;%s",
                this.applicationKey, this.authenticationToken, channel,
                ttl,
                permission,
                String.format("%s_%s", messagePartIdentifier, escapedMessage));
        sendMessage(messageParsed);
    }

	@Override
	protected void send(String channel, String message, String messagePartIdentifier, String permission) {
		String escapedMessage = JSONValue.escape(message);
		// String escapedMessage = CharEscaper.addEsc(message);

		String messageParsed = String.format("send;%s;%s;%s;%s;%s", this.applicationKey, this.authenticationToken, channel, permission,
				String.format("%s_%s", messagePartIdentifier, escapedMessage));
		sendMessage(messageParsed);
	}

	@Override
	protected void send(String applicationKey, String privateKey, String channel, String message, String messagePartIdentifier, String permission) {
		String escapedMessage = JSONValue.escape(message);
		// String escapedMessage = CharEscaper.addEsc(message);

		String messageParsed = String.format("sendproxy;%s;%s;%s;%s", applicationKey, privateKey, channel,
				String.format("%s_%s", messagePartIdentifier, escapedMessage));
		sendMessage(messageParsed);
	}

	@Override
	protected void subscribe(String channel, String permission, boolean withFilter, String filter) {
		String subscribeString = (withFilter == true? "subscribefilter":"subscribe");
		String subscribeMessage = String.format("%s;%s;%s;%s;%s", subscribeString,this.applicationKey, this.authenticationToken, channel, permission == null ? null
				: permission);
		if (withFilter)
			subscribeMessage = subscribeMessage + ";" + filter;
		sendMessage(subscribeMessage);
	}

    @Override
    protected void sendAck(String channel, String messageId, String seqId, String asAllParts){
        String ackMessage = String.format("ack;%s;%s;%s;%s;%s",
                this.applicationKey, channel, messageId, seqId, asAllParts);
        sendMessage(ackMessage);
    }

    @Override
    protected void _subscribeWithOptions(String channel, String permission, boolean subscribeOnReconnected,
                                         String filter, String subscriberId){
        String subscribeMessage = String.format("subscribeoptions;%s;%s;%s;%s;%s;%s;%s;%s",
                this.applicationKey, this.authenticationToken, channel, subscriberId,
                "", "", // device token and push platform not available
                permission,
                String.format("%s", (filter == null ? "":filter)));
        sendMessage(subscribeMessage);
    }

	private void sendMessage(String message) {
		try {
			socket.send(String.format("\"%s\"", message));
		} catch (WebSocketException e) {
			raiseOrtcEvent(EventEnum.OnException, (OrtcClient) this, e);
		} catch (WebSocketStreamException e) {
			try {
				socket.close();
			} catch (WebSocketException ex) {
			}
			raiseOrtcEvent(EventEnum.OnDisconnected, (OrtcClient) this);
		}
	}

	@Override
	protected void unsubscribe(String channel, boolean isValid) {
		if (isValid) {
			String unsubscribeMessage = String.format("unsubscribe;%s;%s", this.applicationKey, channel);
			sendMessage(unsubscribeMessage);
		}
	}

	private static String replaceCharsSend(String message) {
		// CAUSE: Assignment to method parameter
		String lMessage = message;
		if (lMessage == null) {
			lMessage = "";
		}

		return lMessage.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	@Override
	protected void sendHeartbeat() {
		if(heartbeatActive){
			sendMessage("b");
		}
	}
}
