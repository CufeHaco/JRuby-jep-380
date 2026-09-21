package org.jruby.ext.socket;

import org.jruby.runtime.ThreadContext;
import org.jruby.util.io.FilenoUtil;

import java.io.IOException;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JEP-380 Unix-domain backend for JRuby's MRI UNIXSocket/UNIXServer.
 *
 * This is the interpreter path for jruby/jruby#9040. It does not introduce a
 * second IO stack. Callers open a JDK {@link SocketChannel} /
 * {@link ServerSocketChannel} and pass that {@link Channel} to
 * {@code RubyUNIXSocket.init_sock}, which already wraps it in {@code OpenFile}.
 *
 * Capability word (boot-once):
 * <pre>
 *   bit 0  JEP-380 classes present (always on JRuby 10 / JDK 21)
 *   bit 1  channel fileno unwraps via SelChImpl (send_io can stay POSIX)
 *   bit 2  socketpair via temp JEP-380 pair
 * </pre>
 *
 * Ancillary messages ({@code send_io}/{@code recv_io}) stay on the existing
 * POSIX {@code sendmsg} path in {@link RubyUNIXSocket}. JDK Unix channels
 * implement {@code sun.nio.ch.SelChImpl}, so {@link FilenoUtil} already
 * recovers a real fd. FFM is the IPC connection side — see UnixDomainNative.
 */
public final class UnixDomain {
    public static final int BIT_JEP380 = 1 << 0;
    public static final int BIT_FILENO = 1 << 1;
    public static final int BIT_PAIR   = 1 << 2;

    static final ProtocolFamily UNIX = StandardProtocolFamily.UNIX;

    private static final int MAX_PATH = 103;
    private static final AtomicInteger PAIR_SEQ = new AtomicInteger();

    private static final int BITS;
    static {
        int bits = BIT_JEP380 | BIT_PAIR;
        try {
            SocketChannel probe = SocketChannel.open(UNIX);
            try {
                if (!FilenoUtil.isFake(FilenoUtil.filenoFrom(probe))) {
                    bits |= BIT_FILENO;
                }
            } finally {
                probe.close();
            }
        } catch (Throwable t) {
            // leave FILENO off
        }
        BITS = bits;
    }

    private UnixDomain() {}

    public static int bits() { return BITS; }
    public static boolean hasJep380() { return (BITS & BIT_JEP380) != 0; }
    public static boolean hasNativeFileno() { return (BITS & BIT_FILENO) != 0; }

    public static boolean isUnixChannel(Channel channel) {
        try {
            if (channel instanceof SocketChannel sc) {
                SocketAddress local = sc.getLocalAddress();
                SocketAddress remote = sc.getRemoteAddress();
                return local instanceof UnixDomainSocketAddress
                        || remote instanceof UnixDomainSocketAddress;
            }
            if (channel instanceof ServerSocketChannel ssc) {
                return ssc.getLocalAddress() instanceof UnixDomainSocketAddress;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    public static String pathOf(SocketAddress addr) {
        if (addr instanceof UnixDomainSocketAddress u) {
            Path p = u.getPath();
            return p == null ? "" : p.toString();
        }
        return "";
    }

    public static SocketAddress localAddress(Channel channel) throws IOException {
        if (channel instanceof NetworkChannel net) return net.getLocalAddress();
        return null;
    }

    public static SocketAddress remoteAddress(Channel channel) throws IOException {
        if (channel instanceof SocketChannel sc) return sc.getRemoteAddress();
        return null;
    }

    public static SocketChannel connect(String path) throws IOException {
        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(path);
        SocketChannel ch = SocketChannel.open(UNIX);
        ch.configureBlocking(true);
        ch.connect(addr);
        return ch;
    }

    public static ServerSocketChannel bind(String path) throws IOException {
        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(path);
        ServerSocketChannel ch = ServerSocketChannel.open(UNIX);
        ch.configureBlocking(true);
        ch.bind(addr);
        return ch;
    }

    public static SocketChannel accept(ServerSocketChannel server) throws IOException {
        SocketChannel ch = server.accept();
        if (ch != null) ch.configureBlocking(true);
        return ch;
    }

    /**
     * Connected unnamed pair for UNIXSocket.pair / the IPC stack.
     * FFM socketpair(2) first (no temp inode). JEP-380 temp-path pair if FFM
     * is off. Channel wrap of a raw fd uses jnr fromFD as an adapter only.
     */
    public static Channel[] pair() throws IOException {
        if (UnixDomainNative.hasPair()) {
            int[] fds = null;
            try {
                fds = UnixDomainNative.pair();
                return new Channel[] {
                    UnixDomainNative.channelFromFd(fds[0]),
                    UnixDomainNative.channelFromFd(fds[1])
                };
            } catch (Exception e) {
                if (fds != null) {
                    UnixDomainNative.close(fds[0]);
                    UnixDomainNative.close(fds[1]);
                }
            }
        }
        return pairViaTempPath();
    }

    static SocketChannel[] pairViaTempPath() throws IOException {
        Path tmp = Files.createTempFile("jruby-unixpair-", ".sock");
        Files.deleteIfExists(tmp);
        ServerSocketChannel server = ServerSocketChannel.open(UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(tmp));
            SocketChannel a = SocketChannel.open(UNIX);
            a.configureBlocking(true);
            a.connect(UnixDomainSocketAddress.of(tmp));
            SocketChannel b = server.accept();
            b.configureBlocking(true);
            return new SocketChannel[] { a, b };
        } finally {
            try { server.close(); } catch (IOException ignored) {}
            Files.deleteIfExists(tmp);
        }
    }

    public static void checkPath(ThreadContext context, String path) {
        if (path.length() > MAX_PATH) {
            throw org.jruby.api.Error.argumentError(context,
                    "too long unix socket path (max: " + MAX_PATH + "bytes)");
        }
    }

    public static int fileno(Channel channel) {
        return FilenoUtil.filenoFrom(channel);
    }
}
