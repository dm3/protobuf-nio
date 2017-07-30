package com.quantran.protobuf.nio.impl;

import com.google.protobuf.Message;
import com.quantran.protobuf.nio.ProtoServerSocketChannel;
import com.quantran.protobuf.nio.ProtoSocketChannel;
import com.quantran.protobuf.nio.handlers.ConnectionHandler;
import com.quantran.protobuf.nio.handlers.DisconnectionHandler;
import com.quantran.protobuf.nio.handlers.MessageReceivedHandler;
import com.quantran.protobuf.nio.handlers.MessageSendFailureHandler;
import com.quantran.protobuf.nio.handlers.MessageSentHandler;
import com.quantran.protobuf.nio.utils.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AsyncProtoServerSocketChannel implements ProtoServerSocketChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncProtoServerSocketChannel.class);
    private static final int DEFAULT_BUFFER_CAPACITY = 8192;
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 0;
    private static final int DEFAULT_WRITE_TIMEOUT_MILLIS = 10000;

    private final SocketAddress serverSocketAddress;
    private final int serverPort;
    private final List<ConnectionHandler> connectionHandlers = new CopyOnWriteArrayList<>();
    private final List<DisconnectionHandler> disconnectionHandlers = new CopyOnWriteArrayList<>();
    private final List<MessageReceivedHandler> messageReceivedHandlers = new CopyOnWriteArrayList<>();
    private final List<MessageSentHandler> messageSentHandlers = new CopyOnWriteArrayList<>();
    private final List<MessageSendFailureHandler> messageSendFailureHandlers = new CopyOnWriteArrayList<>();
    private final Map<SocketAddress, ProtoSocketChannel> socketChannels = new ConcurrentHashMap<>();

    private int readBufferSize = DEFAULT_BUFFER_CAPACITY;
    private int writeBufferSize = DEFAULT_BUFFER_CAPACITY;
    private long readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS;
    private long writeTimeoutMillis = DEFAULT_WRITE_TIMEOUT_MILLIS;
    private AsynchronousServerSocketChannel serverSocketChannel;
    private ExecutorService readExecutor;
    private ExecutorService writeExecutor;

    public AsyncProtoServerSocketChannel(int port) {
        this.serverPort = port;
        this.serverSocketAddress = new InetSocketAddress(port);
    }

    @PostConstruct
    public void init() {
        try {
            serverSocketChannel = AsynchronousServerSocketChannel.open();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to open server socket channel", e);
        }
        readExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(AsyncProtoServerSocketChannel.class.getSimpleName() + "-Reader-" + serverPort));
        writeExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(AsyncProtoServerSocketChannel.class.getSimpleName() + "-Writer-" + serverPort));
    }

    @Override
    public void start() throws IOException {
        serverSocketChannel.bind(serverSocketAddress);
        LOGGER.debug("Bind to port " + serverPort);
        readExecutor.execute(this::acceptNewConnection);
    }

    private void acceptNewConnection() {
        if (!serverSocketChannel.isOpen()) {
            return;
        }
        serverSocketChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
            @Override
            public void completed(AsynchronousSocketChannel socketChannel, Object attachment) {
                readExecutor.execute(() -> {
                    SocketAddress remoteAddress = getRemoteAddress(socketChannel);
                    LOGGER.debug("Accepted connection from " + remoteAddress);
                    AsyncProtoSocketChannel protobufSocketChannel = createProtobufSocketChannel(socketChannel, remoteAddress);
                    connectionHandlers.forEach(handler -> handler.onConnected(remoteAddress));
                    socketChannels.put(remoteAddress, protobufSocketChannel);
                    protobufSocketChannel.startReading();
                    acceptNewConnection();
                });
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                LOGGER.error("Unable to accept new connection at port " + serverPort, exc);
                if (serverSocketChannel.isOpen()) {
                    readExecutor.execute(() -> acceptNewConnection());
                }
            }
        });
    }

    private AsyncProtoSocketChannel createProtobufSocketChannel(AsynchronousSocketChannel socketChannel, SocketAddress remoteAddress) {
        AsyncProtoSocketChannel protobufSocketChannel = new AsyncProtoSocketChannel(remoteAddress);
        protobufSocketChannel.setReadBufferSize(readBufferSize);
        protobufSocketChannel.setWriteBufferSize(writeBufferSize);
        protobufSocketChannel.setReadExecutor(readExecutor);
        protobufSocketChannel.setWriteExecutor(writeExecutor);
        protobufSocketChannel.setReadTimeoutMillis(readTimeoutMillis);
        protobufSocketChannel.setWriteTimeoutMillis(writeTimeoutMillis);
        protobufSocketChannel.setSocketChannel(socketChannel);
        protobufSocketChannel.addDisconnectionHandler((socketAddress) -> {
            LOGGER.debug("Disconnected connection from " + socketAddress);
            socketChannels.remove(socketAddress);
            disconnectionHandlers.forEach(handler -> handler.onDisconnected(socketAddress));
        });
        protobufSocketChannel.addMessageReceivedHandler((socketAddress, message) -> messageReceivedHandlers.forEach(handler -> handler.onMessageReceived(socketAddress, message)));
        protobufSocketChannel.addMessageSentHandler((socketAddress, message) -> messageSentHandlers.forEach(handler -> handler.onMessageSent(socketAddress, message)));
        protobufSocketChannel.addMessageSendFailureHandler((socketAddress, message, t) -> messageSendFailureHandlers.forEach(handler -> handler.onMessageSendFailure(socketAddress, message, t)));
        protobufSocketChannel.init();
        return protobufSocketChannel;
    }

    private SocketAddress getRemoteAddress(AsynchronousSocketChannel socketChannel) {
        try {
            return socketChannel.getRemoteAddress();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to get Remote Address from socket channel", e);
        }
    }

    @Override
    @PreDestroy
    public void stop() {
        socketChannels.values().forEach(ProtoSocketChannel::disconnect);
        socketChannels.clear();
        try {
            serverSocketChannel.close();
        } catch (IOException e) {
            LOGGER.error("Unable to close server socket channel at port " + serverPort, e);
        }
        if (!readExecutor.isShutdown()) {
            readExecutor.shutdown();
        }
        if (!writeExecutor.isShutdown()) {
            writeExecutor.shutdown();
        }
    }


    @Override
    public void sendMessage(SocketAddress socketAddress, Message message) {
        ProtoSocketChannel protoSocketChannel = socketChannels.get(socketAddress);
        protoSocketChannel.sendMessage(message);
    }

    @Override
    public void addConnectionHandler(ConnectionHandler handler) {
        connectionHandlers.add(handler);
    }

    @Override
    public void removeConnectionHandler(ConnectionHandler handler) {
        connectionHandlers.remove(handler);
    }

    @Override
    public void addDisconnectionHandler(DisconnectionHandler handler) {
        disconnectionHandlers.add(handler);
    }

    @Override
    public void removeDisconnectionHandler(DisconnectionHandler handler) {
        disconnectionHandlers.remove(handler);
    }

    @Override
    public void addMessageReceivedHandler(MessageReceivedHandler handler) {
        messageReceivedHandlers.add(handler);
    }

    @Override
    public void removeMessageReceivedHandler(MessageReceivedHandler handler) {
        messageReceivedHandlers.remove(handler);
    }

    @Override
    public void addMessageSentHandler(MessageSentHandler handler) {
        messageSentHandlers.add(handler);
    }

    @Override
    public void removeMessageSentHandler(MessageSentHandler handler) {
        messageSentHandlers.remove(handler);
    }

    @Override
    public void addMessageSendFailureHandler(MessageSendFailureHandler handler) {
        messageSendFailureHandlers.add(handler);
    }

    @Override
    public void removeMessageSendFailureHandler(MessageSendFailureHandler handler) {
        messageSendFailureHandlers.remove(handler);
    }

    public void setReadBufferSize(int readBufferSize) {
        this.readBufferSize = readBufferSize;
    }

    public void setWriteBufferSize(int writeBufferSize) {
        this.writeBufferSize = writeBufferSize;
    }

    public void setReadTimeoutMillis(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public void setWriteTimeoutMillis(long writeTimeoutMillis) {
        this.writeTimeoutMillis = writeTimeoutMillis;
    }

}