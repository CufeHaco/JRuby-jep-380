package org.jruby.ext.socket;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.spi.AbstractInterruptibleChannel;

/**
 * ByteChannel wrapping a raw fd via FFM read(2)/write(2), for the fds
 * JEP-380 has no public API to wrap directly: the two ends of an FFM
 * socketpair(2) (UnixDomain.pair) and fds received over SCM_RIGHTS. The
 * named bind/connect/accept path stays on real JEP-380
 * SocketChannel/ServerSocketChannel throughout -- this class only exists
 * for that narrower gap, replacing what used to be
 * jnr.unixsocket.UnixSocketChannel.fromFD.
 *
 * Interruptibility: extends AbstractInterruptibleChannel, the same public,
 * stable JDK mechanism FileChannel/DatagramChannel use (begin()/end()
 * around the blocking call), so Thread#kill/interrupt works the normal
 * JRuby way -- no java.nio.Selector/SelChImpl, no JDK-internal reflection.
 * implCloseChannel does shutdown(2) before close(2): a bare close(2) races
 * fd reuse if another thread is still blocked in read(2) on this exact fd
 * (a real POSIX gotcha), while shutdown(2) reliably forces that blocked
 * read to return first.
 */
public final class UnixFdChannel extends AbstractInterruptibleChannel implements ByteChannel {
    private final int fd;

    UnixFdChannel(int fd) {
        this.fd = fd;
    }

    public int fd() { return fd; }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (!isOpen()) throw new ClosedChannelException();
        int remaining = dst.remaining();
        if (remaining == 0) return 0;

        boolean completed = false;
        try {
            begin();
            long n;
            if (dst.isDirect()) {
                MemorySegment seg = MemorySegment.ofBuffer(dst);
                n = nativeRead(seg, remaining);
                if (n > 0) dst.position(dst.position() + (int) n);
            } else {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment tmp = arena.allocate(remaining);
                    n = nativeRead(tmp, remaining);
                    if (n > 0) {
                        byte[] bytes = new byte[(int) n];
                        MemorySegment.copy(tmp, ValueLayout.JAVA_BYTE, 0, bytes, 0, (int) n);
                        dst.put(bytes);
                    }
                }
            }
            completed = true;
            return n == 0 ? -1 : (int) n; // Channel contract: -1 at EOF, read(2) uses 0
        } finally {
            end(completed);
        }
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (!isOpen()) throw new ClosedChannelException();
        int remaining = src.remaining();
        if (remaining == 0) return 0;

        boolean completed = false;
        try {
            begin();
            long n;
            if (src.isDirect()) {
                MemorySegment seg = MemorySegment.ofBuffer(src);
                n = nativeWrite(seg, remaining);
                if (n > 0) src.position(src.position() + (int) n);
            } else {
                int startPos = src.position();
                byte[] bytes = new byte[remaining];
                src.get(bytes);
                src.position(startPos);
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment tmp = arena.allocate(remaining);
                    MemorySegment.copy(bytes, 0, tmp, ValueLayout.JAVA_BYTE, 0, remaining);
                    n = nativeWrite(tmp, remaining);
                }
                if (n > 0) src.position(startPos + (int) n);
            }
            completed = true;
            return (int) n;
        } finally {
            end(completed);
        }
    }

    private long nativeRead(MemorySegment seg, int len) throws IOException {
        try {
            return UnixDomainNative.read(fd, seg, len);
        } catch (UnixDomainNative.NativeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private long nativeWrite(MemorySegment seg, int len) throws IOException {
        try {
            return UnixDomainNative.write(fd, seg, len);
        } catch (UnixDomainNative.NativeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    protected void implCloseChannel() throws IOException {
        UnixDomainNative.shutdown(fd, UnixDomainNative.SHUT_RDWR);
        UnixDomainNative.close(fd);
    }
}
