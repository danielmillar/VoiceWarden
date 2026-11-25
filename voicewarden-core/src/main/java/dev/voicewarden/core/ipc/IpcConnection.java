package dev.voicewarden.core.ipc;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class IpcConnection implements AutoCloseable {

    private final SocketChannel channel;
    private final IpcEndpoint endpoint;
    private final ReentrantLock writeLock = new ReentrantLock();

    public IpcConnection(SocketChannel channel, IpcEndpoint endpoint) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    public IpcEndpoint endpoint() {
        return endpoint;
    }

    public void send(IpcMessage message) throws IOException {
        ByteBuffer frame = IpcFrameCodec.encode(message);
        writeLock.lock();
        try {
            while (frame.hasRemaining()) {
                channel.write(frame);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void send(IpcMessage message, Duration timeout) throws IOException {
        ByteBuffer frame = IpcFrameCodec.encode(message);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try {
            if (!writeLock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new SocketTimeoutException("IPC writer stayed busy for " + timeout.toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted waiting for the IPC writer");
        }
        Duration remaining = Duration.ofNanos(Math.max(1, deadlineNanos - System.nanoTime()));
        try (IpcTransport.Deadline deadline = IpcTransport.deadline(channel, remaining)) {
            try {
                while (frame.hasRemaining()) {
                    channel.write(frame);
                }
            } catch (ClosedChannelException e) {
                if (deadline.expired()) {
                    SocketTimeoutException timeoutException = new SocketTimeoutException(
                            "IPC peer did not drain a frame within " + timeout.toMillis() + " ms");
                    timeoutException.initCause(e);
                    throw timeoutException;
                }
                throw e;
            }
        } finally {
            writeLock.unlock();
        }
    }

    public IpcMessage receive() throws IOException {
        return IpcFrameCodec.readFrame(channel);
    }

    public IpcMessage receive(Duration timeout) throws IOException {
        try (IpcTransport.Deadline deadline = IpcTransport.deadline(channel, timeout)) {
            try {
                return IpcFrameCodec.readFrame(channel);
            } catch (ClosedChannelException e) {
                if (deadline.expired()) {
                    SocketTimeoutException timeoutException =
                            new SocketTimeoutException("No IPC frame within " + timeout.toMillis() + " ms");
                    timeoutException.initCause(e);
                    throw timeoutException;
                }
                throw e;
            }
        }
    }

    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
            return;
        }
    }
}
