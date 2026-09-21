package org.jruby.ext.socket;

import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.FileSystems;

/**
 * FFM (JEP-454) libc bindings for the Unix IPC ops JEP-380 does not provide.
 *
 * JEP-380 owns the Channel. This class owns the connection primitives:
 * socketpair(2), getsockname(2), sendmsg/recvmsg SCM_RIGHTS, close(2).
 *
 * Boot is Build-Match-Verify-Execute. Probe fails closed without
 * --enable-native-access. Layout is Linux LP64.
 */
public final class UnixDomainNative {
    private static final Logger LOG = LoggerFactory.getLogger(UnixDomainNative.class);

    public static final int BIT_FFM         = 1 << 0;
    public static final int BIT_PAIR        = 1 << 1;
    public static final int BIT_SCM         = 1 << 2;
    public static final int BIT_GETSOCKNAME = 1 << 3;
    public static final int BIT_LINUX_LP64  = 1 << 4;

    static final int AF_UNIX     = 1;
    static final int SOCK_STREAM = 1;
    static final int SOL_SOCKET  = 1;
    static final int SCM_RIGHTS  = 1;
    static final int SUN_PATH    = 108;

    private static final ValueLayout.OfInt  C_INT  = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong C_LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfByte C_BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout        C_PTR  = ValueLayout.ADDRESS;

    private static final int BITS;
    private static final MethodHandle MH_SOCKETPAIR;
    private static final MethodHandle MH_CLOSE;
    private static final MethodHandle MH_GETSOCKNAME;
    private static final MethodHandle MH_SENDMSG;
    private static final MethodHandle MH_RECVMSG;
    private static final String ERROR;

    static {
        int bits = 0;
        MethodHandle socketpair = null, close = null, getsockname = null, sendmsg = null, recvmsg = null;
        String error = null;
        try {
            if (!linuxLp64()) {
                throw new UnsupportedOperationException("FFM Unix IPC is Linux LP64 only");
            }
            bits |= BIT_LINUX_LP64;
            Linker linker = Linker.nativeLinker();
            SymbolLookup libc = linker.defaultLookup();
            socketpair = downcall(linker, libc, "socketpair",
                    FunctionDescriptor.of(C_INT, C_INT, C_INT, C_INT, C_PTR));
            close = downcall(linker, libc, "close",
                    FunctionDescriptor.of(C_INT, C_INT));
            getsockname = downcall(linker, libc, "getsockname",
                    FunctionDescriptor.of(C_INT, C_INT, C_PTR, C_PTR));
            sendmsg = downcall(linker, libc, "sendmsg",
                    FunctionDescriptor.of(C_LONG, C_INT, C_PTR, C_INT));
            recvmsg = downcall(linker, libc, "recvmsg",
                    FunctionDescriptor.of(C_LONG, C_INT, C_PTR, C_INT));
            bits |= BIT_FFM;
            if (verifyPair(socketpair, close)) {
                bits |= BIT_PAIR;
                bits |= BIT_GETSOCKNAME;
                bits |= BIT_SCM;
            }
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            LOG.debug("UnixDomainNative probe failed: {}", error);
        }
        BITS = bits;
        MH_SOCKETPAIR = socketpair;
        MH_CLOSE = close;
        MH_GETSOCKNAME = getsockname;
        MH_SENDMSG = sendmsg;
        MH_RECVMSG = recvmsg;
        ERROR = error;
    }

    private UnixDomainNative() {}

    public static int bits() { return BITS; }
    public static boolean available() { return (BITS & BIT_FFM) != 0; }
    public static boolean hasPair() { return (BITS & BIT_PAIR) != 0; }
    public static boolean hasScm() { return (BITS & BIT_SCM) != 0; }
    public static boolean hasGetsockname() { return (BITS & BIT_GETSOCKNAME) != 0; }
    public static String probeError() { return ERROR; }

    public static int[] pair() throws NativeException {
        require(BIT_PAIR, "socketpair");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fds = arena.allocate(C_INT, 2);
            int rc = (int) MH_SOCKETPAIR.invokeExact(AF_UNIX, SOCK_STREAM, 0, fds);
            if (rc != 0) throw new NativeException("socketpair", rc);
            return new int[] { fds.getAtIndex(C_INT, 0), fds.getAtIndex(C_INT, 1) };
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("socketpair", t);
        }
    }

    public static int close(int fd) {
        if (fd < 0 || MH_CLOSE == null) return -1;
        try {
            return (int) MH_CLOSE.invokeExact(fd);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static java.nio.channels.Channel channelFromFd(int fd) {
        return jnr.unixsocket.UnixSocketChannel.fromFD(fd);
    }

    public static String pathOf(int fd) throws NativeException {
        require(BIT_GETSOCKNAME, "getsockname");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sun = arena.allocate(2 + SUN_PATH);
            MemorySegment len = arena.allocate(C_INT);
            len.set(C_INT, 0, 2 + SUN_PATH);
            int rc = (int) MH_GETSOCKNAME.invokeExact(fd, sun, len);
            if (rc != 0) throw new NativeException("getsockname", rc);
            int n = len.get(C_INT, 0);
            if (n <= 2) return "";
            byte[] bytes = new byte[n - 2];
            MemorySegment.copy(sun, C_BYTE, 2, bytes, 0, bytes.length);
            if (bytes[0] == 0) {
                int end = 1;
                while (end < bytes.length && bytes[end] != 0) end++;
                return "\0" + new String(bytes, 1, end - 1);
            }
            int end = 0;
            while (end < bytes.length && bytes[end] != 0) end++;
            return new String(bytes, 0, end);
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("getsockname", t);
        }
    }

    public static int sendIo(int sockFd, int passFd) throws NativeException {
        require(BIT_SCM, "sendmsg");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(1);
            payload.set(C_BYTE, 0, (byte) 0);
            MemorySegment iov = arena.allocate(16);
            iov.set(C_PTR, 0, payload);
            iov.set(C_LONG, 8, 1L);
            int cmsgLen = 16 + 4;
            MemorySegment cmsg = arena.allocate(24);
            cmsg.set(C_LONG, 0, cmsgLen);
            cmsg.set(C_INT, 8, SOL_SOCKET);
            cmsg.set(C_INT, 12, SCM_RIGHTS);
            cmsg.set(C_INT, 16, passFd);
            MemorySegment msg = arena.allocate(56);
            msg.set(C_PTR, 0, MemorySegment.NULL);
            msg.set(C_INT, 8, 0);
            msg.set(C_PTR, 16, iov);
            msg.set(C_LONG, 24, 1L);
            msg.set(C_PTR, 32, cmsg);
            msg.set(C_LONG, 40, cmsgLen);
            msg.set(C_INT, 48, 0);
            long rc = (long) MH_SENDMSG.invokeExact(sockFd, msg, 0);
            if (rc < 0) throw new NativeException("sendmsg", (int) rc);
            return (int) rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("sendmsg", t);
        }
    }

    public static int recvIo(int sockFd) throws NativeException {
        require(BIT_SCM, "recvmsg");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(1);
            MemorySegment iov = arena.allocate(16);
            iov.set(C_PTR, 0, payload);
            iov.set(C_LONG, 8, 1L);
            MemorySegment cmsg = arena.allocate(24);
            MemorySegment msg = arena.allocate(56);
            msg.set(C_PTR, 0, MemorySegment.NULL);
            msg.set(C_INT, 8, 0);
            msg.set(C_PTR, 16, iov);
            msg.set(C_LONG, 24, 1L);
            msg.set(C_PTR, 32, cmsg);
            msg.set(C_LONG, 40, 24L);
            msg.set(C_INT, 48, 0);
            long rc = (long) MH_RECVMSG.invokeExact(sockFd, msg, 0);
            if (rc < 0) throw new NativeException("recvmsg", (int) rc);
            return cmsg.get(C_INT, 16);
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("recvmsg", t);
        }
    }

    private static boolean verifyPair(MethodHandle socketpair, MethodHandle close) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fds = arena.allocate(C_INT, 2);
            int rc = (int) socketpair.invokeExact(AF_UNIX, SOCK_STREAM, 0, fds);
            if (rc != 0) return false;
            int a = fds.getAtIndex(C_INT, 0);
            int b = fds.getAtIndex(C_INT, 1);
            try {
                return a >= 0 && b >= 0;
            } finally {
                close.invokeExact(a);
                close.invokeExact(b);
            }
        }
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor desc) {
        MemorySegment sym = lookup.find(name).orElseThrow(() -> new IllegalStateException("missing " + name));
        return linker.downcallHandle(sym, desc);
    }

    private static boolean linuxLp64() {
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        return os.toLowerCase().contains("linux") && (arch.contains("64") || arch.equals("aarch64") || arch.equals("amd64"));
    }

    private static void require(int bit, String op) throws NativeException {
        if ((BITS & bit) == 0) {
            throw new NativeException(op, "FFM " + op + " not available (" +
                    (ERROR == null ? "bit off" : ERROR) + ")");
        }
    }

    public static final class NativeException extends Exception {
        public NativeException(String op, int rc) { super(op + " failed rc=" + rc); }
        public NativeException(String op, String msg) { super(op + ": " + msg); }
        public NativeException(String op, Throwable cause) { super(op + ": " + cause.getMessage(), cause); }
    }
}
