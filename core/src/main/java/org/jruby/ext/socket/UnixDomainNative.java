package org.jruby.ext.socket;

import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.FileSystems;

/**
 * FFM (JEP-454) libc bindings for the Unix IPC ops JEP-380 does not provide.
 *
 * JEP-380 owns the Channel. This class owns the connection primitives:
 * socketpair(2), getsockname(2), sendmsg/recvmsg SCM_RIGHTS, close(2), and
 * read(2)/write(2)/shutdown(2) for UnixFdChannel -- the ByteChannel wrapper
 * for fds JEP-380 has no public API to wrap (socketpair/SCM_RIGHTS ends).
 *
 * Boot is Build-Match-Verify-Execute. Probe fails closed without
 * --enable-native-access. Layout is Linux LP64.
 *
 * @author Troy Mallory (CufeHaco) - JEP-380/FFM unix socket support (jruby/jruby#9040)
 */
public final class UnixDomainNative {
    private static final Logger LOG = LoggerFactory.getLogger(UnixDomainNative.class);

    public static final int BIT_FFM         = 1 << 0;
    public static final int BIT_PAIR        = 1 << 1;
    public static final int BIT_SCM         = 1 << 2;
    public static final int BIT_GETSOCKNAME = 1 << 3;
    public static final int BIT_LINUX_LP64  = 1 << 4;
    public static final int BIT_DGRAM       = 1 << 5;
    public static final int BIT_GETPEERNAME = 1 << 6;

    static final int AF_UNIX     = 1;
    static final int SOCK_STREAM = 1;
    public static final int SOCK_DGRAM = 2;
    static final int SOL_SOCKET  = 1;
    static final int SCM_RIGHTS  = 1;
    static final int SUN_PATH    = 108;
    static final int SO_TYPE     = 3;
    static final int F_GETFL     = 3;
    static final int F_SETFL     = 4;
    static final int O_NONBLOCK  = 04000; // octal, Linux

    public static final int SHUT_RD   = 0;
    public static final int SHUT_WR   = 1;
    public static final int SHUT_RDWR = 2;

    /** EAGAIN == EWOULDBLOCK on Linux. Matches this file's existing
     * Linux-only scope (see linuxLp64()). */
    public static final int EAGAIN = 11;

    /** Real getpeername(2) failure when a socket was bound but never
     * connect()'d -- see UnixDomain#remoteAddress and peerPathOf. */
    public static final int ENOTCONN = 107;

    private static final ValueLayout.OfInt  C_INT  = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong C_LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfByte C_BYTE = ValueLayout.JAVA_BYTE;
    private static final AddressLayout      C_PTR  = ValueLayout.ADDRESS;

    /**
     * errno must be read via Linker.Option.captureCallState, not a second
     * __errno_location() downcall after the fact (see UnixDomainContext).
     * The JVM stores it immediately after the raw call returns, before any
     * GC-safepoint or other JVM-internal native call (e.g. the free(3)
     * behind an Arena.close()) gets a chance to clobber it.
     */
    private static final StructLayout  CAPTURE_LAYOUT = Linker.Option.captureStateLayout();
    private static final VarHandle     ERRNO_VH = CAPTURE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");

    // Reused per carrier thread like UnixFdChannel.SCRATCH -- read/write is
    // the hot path, so this must not be a fresh Arena.allocate per call.
    // Fixed-size and never resized, so ofAuto (GC-reclaimed, no close needed)
    // is fine here, unlike SCRATCH's growable data buffer.
    private static final ThreadLocal<MemorySegment> ERRNO_STATE =
            ThreadLocal.withInitial(() -> Arena.ofAuto().allocate(CAPTURE_LAYOUT));

    private static int errnoOf(MemorySegment captureState) {
        return (int) ERRNO_VH.get(captureState, 0L);
    }

    private static final int BITS;
    private static final MethodHandle MH_SOCKETPAIR;
    private static final MethodHandle MH_CLOSE;
    private static final MethodHandle MH_GETSOCKNAME;
    private static final MethodHandle MH_SENDMSG;
    private static final MethodHandle MH_RECVMSG;
    private static final MethodHandle MH_READ;
    private static final MethodHandle MH_WRITE;
    private static final MethodHandle MH_SHUTDOWN;
    private static final MethodHandle MH_SOCKET;
    private static final MethodHandle MH_BIND;
    private static final MethodHandle MH_SENDTO;
    private static final MethodHandle MH_RECVFROM;
    private static final MethodHandle MH_GETSOCKOPT;
    private static final MethodHandle MH_GETSOCKOPT_ERRNO;
    private static final MethodHandle MH_SETSOCKOPT;
    private static final MethodHandle MH_IOCTL_PTR;
    private static final MethodHandle MH_POLL;
    private static final MethodHandle MH_GETPEERNAME;
    private static final MethodHandle MH_FCNTL_GET;
    private static final MethodHandle MH_FCNTL_SET;
    private static final String ERROR;

    static {
        int bits = 0;
        MethodHandle socketpair = null, close = null, getsockname = null, sendmsg = null, recvmsg = null;
        MethodHandle read = null, write = null, shutdown = null;
        MethodHandle rawSocket = null, rawBind = null, sendto = null, recvfrom = null, getsockopt = null;
        MethodHandle getpeername = null, fcntlGet = null, fcntlSet = null;
        MethodHandle getsockoptErrno = null, setsockopt = null, ioctlPtr = null, poll = null;
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
            getpeername = downcall(linker, libc, "getpeername",
                    FunctionDescriptor.of(C_INT, C_INT, C_PTR, C_PTR), CAPTURE_ERRNO);
            sendmsg = downcall(linker, libc, "sendmsg",
                    FunctionDescriptor.of(C_LONG, C_INT, C_PTR, C_INT), CAPTURE_ERRNO);
            recvmsg = downcall(linker, libc, "recvmsg",
                    FunctionDescriptor.of(C_LONG, C_INT, C_PTR, C_INT), CAPTURE_ERRNO);
            read = downcall(linker, libc, "read",
                    FunctionDescriptor.of(C_LONG, C_INT, C_PTR, C_LONG), CAPTURE_ERRNO);
            write = downcall(linker, libc, "write",
                    FunctionDescriptor.of(C_LONG, C_INT, C_PTR, C_LONG), CAPTURE_ERRNO);
            shutdown = downcall(linker, libc, "shutdown",
                    FunctionDescriptor.of(C_INT, C_INT, C_INT));
            bits |= BIT_FFM;
            if (verifyPair(socketpair, close)) {
                bits |= BIT_PAIR;
                bits |= BIT_GETSOCKNAME;
                bits |= BIT_SCM;
                bits |= BIT_GETPEERNAME;
            }

            // Resolved separately from the block above: these four are only
            // needed for raw Socket.new(:UNIX, :DGRAM) support, not the
            // UNIXSocket/UNIXServer/pair() path. A hypothetical resolution
            // failure here (exotic libc) must not take down BIT_PAIR/BIT_SCM
            // /BIT_GETSOCKNAME, which are already verified working above.
            try {
                rawSocket = downcall(linker, libc, "socket",
                        FunctionDescriptor.of(C_INT, C_INT, C_INT, C_INT));
                rawBind = downcall(linker, libc, "bind",
                        FunctionDescriptor.of(C_INT, C_INT, C_PTR, C_INT));
                sendto = downcall(linker, libc, "sendto",
                        FunctionDescriptor.of(C_LONG, C_INT, C_PTR, C_LONG, C_INT, C_PTR, C_INT), CAPTURE_ERRNO);
                recvfrom = downcall(linker, libc, "recvfrom",
                        FunctionDescriptor.of(C_LONG, C_INT, C_PTR, C_LONG, C_INT, C_PTR, C_PTR), CAPTURE_ERRNO);
                getsockopt = downcall(linker, libc, "getsockopt",
                        FunctionDescriptor.of(C_INT, C_INT, C_INT, C_INT, C_PTR, C_PTR));
                // errno-capturing getsockopt/setsockopt for BasicSocket#getsockopt/
                // #setsockopt on any socket fd (see getSockopt/setSockopt)
                getsockoptErrno = downcall(linker, libc, "getsockopt",
                        FunctionDescriptor.of(C_INT, C_INT, C_INT, C_INT, C_PTR, C_PTR), CAPTURE_ERRNO);
                setsockopt = downcall(linker, libc, "setsockopt",
                        FunctionDescriptor.of(C_INT, C_INT, C_INT, C_INT, C_PTR, C_INT), CAPTURE_ERRNO);
                poll = downcall(linker, libc, "poll",
                        FunctionDescriptor.of(C_INT, C_PTR, C_LONG, C_INT));
                // ioctl(int, unsigned long, ...) with one pointer argument
                ioctlPtr = downcall(linker, libc, "ioctl",
                        FunctionDescriptor.of(C_INT, C_INT, C_LONG, C_PTR),
                        Linker.Option.firstVariadicArg(2), CAPTURE_ERRNO);
                // fcntl(2) is variadic in C; declaring the two concrete
                // fixed-arity shapes actually used (F_GETFL takes no extra
                // arg, F_SETFL takes one int) as separate downcall handles
                // on the same symbol is the standard, correct way to call a
                // variadic libc function from FFM when only specific,
                // known-arity call shapes are needed.
                fcntlGet = downcall(linker, libc, "fcntl",
                        FunctionDescriptor.of(C_INT, C_INT, C_INT));
                fcntlSet = downcall(linker, libc, "fcntl",
                        FunctionDescriptor.of(C_INT, C_INT, C_INT, C_INT));
                bits |= BIT_DGRAM;
            } catch (Throwable t) {
                rawSocket = null; rawBind = null; sendto = null; recvfrom = null; getsockopt = null;
                getsockoptErrno = null; setsockopt = null; ioctlPtr = null; poll = null;
                fcntlGet = null; fcntlSet = null;
                LOG.debug("UnixDomainNative dgram probe failed: {}", t);
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
        MH_READ = read;
        MH_WRITE = write;
        MH_SHUTDOWN = shutdown;
        MH_SOCKET = rawSocket;
        MH_BIND = rawBind;
        MH_SENDTO = sendto;
        MH_RECVFROM = recvfrom;
        MH_GETSOCKOPT = getsockopt;
        MH_GETSOCKOPT_ERRNO = getsockoptErrno;
        MH_SETSOCKOPT = setsockopt;
        MH_IOCTL_PTR = ioctlPtr;
        MH_POLL = poll;
        MH_GETPEERNAME = getpeername;
        MH_FCNTL_GET = fcntlGet;
        MH_FCNTL_SET = fcntlSet;
        ERROR = error;
    }

    private UnixDomainNative() {}

    public static int bits() { return BITS; }
    public static boolean available() { return (BITS & BIT_FFM) != 0; }
    public static boolean hasPair() { return (BITS & BIT_PAIR) != 0; }
    public static boolean hasScm() { return (BITS & BIT_SCM) != 0; }
    public static boolean hasGetsockname() { return (BITS & BIT_GETSOCKNAME) != 0; }
    public static boolean hasDgram() { return (BITS & BIT_DGRAM) != 0; }
    public static boolean hasGetpeername() { return (BITS & BIT_GETPEERNAME) != 0; }
    public static String probeError() { return ERROR; }

    public static int[] pair() throws NativeException {
        return pair(SOCK_STREAM);
    }

    /** socketpair(AF_UNIX, type, 0): SOCK_STREAM or SOCK_DGRAM. */
    public static int[] pair(int type) throws NativeException {
        require(BIT_PAIR, "socketpair");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fds = arena.allocate(C_INT, 2);
            int rc = (int) MH_SOCKETPAIR.invokeExact(AF_UNIX, type, 0, fds);
            if (rc != 0) throw new NativeException("socketpair", rc);
            return new int[] { fds.getAtIndex(C_INT, 0), fds.getAtIndex(C_INT, 1) };
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("socketpair", t);
        }
    }

    /**
     * Raw socket(2)/bind(2)/sendto(2)/recvfrom(2) for Socket.new(:UNIX,
     * :DGRAM) -- JEP-380 only added SocketChannel/ServerSocketChannel
     * support (stream); DatagramChannel.open(ProtocolFamily) rejects UNIX
     * outright, so there is no JEP-380 API for this at all. The fd these
     * produce is wrapped the same way as pair()/for_fd -- via
     * channelFromFd, i.e. a plain UnixFdChannel -- since bind/send/recv
     * dispatch (see RubySocket_unixDgram.java / RubyBasicSocket_unixAddr.java)
     * only need Channel.fd(), not stream-vs-dgram-shaped methods.
     */
    public static int rawSocket(int domain, int type, int protocol) throws NativeException {
        require(BIT_DGRAM, "socket");
        try {
            int rc = (int) MH_SOCKET.invokeExact(domain, type, protocol);
            if (rc < 0) throw new NativeException("socket", rc);
            return rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("socket", t);
        }
    }

    public static void bindPath(int fd, String path) throws NativeException {
        require(BIT_DGRAM, "bind");
        try (Arena arena = Arena.ofConfined()) {
            byte[] pathBytes = path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (pathBytes.length >= SUN_PATH) throw new NativeException("bind", "path too long");
            MemorySegment sun = sockaddrUn(arena, pathBytes);
            int addrlen = 2 + pathBytes.length + 1;
            int rc = (int) MH_BIND.invokeExact(fd, sun, addrlen);
            if (rc != 0) throw new NativeException("bind", rc);
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("bind", t);
        }
    }

    public static long sendTo(int fd, byte[] data, int off, int len, String destPath) throws NativeException {
        require(BIT_DGRAM, "sendto");
        MemorySegment capState = ERRNO_STATE.get();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(Math.max(len, 1));
            MemorySegment.copy(data, off, buf, C_BYTE, 0, len);
            MemorySegment dest = MemorySegment.NULL;
            int destLen = 0;
            if (destPath != null) {
                byte[] pathBytes = destPath.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (pathBytes.length >= SUN_PATH) throw new NativeException("sendto", "path too long");
                dest = sockaddrUn(arena, pathBytes);
                destLen = 2 + pathBytes.length + 1;
            }
            long rc = (long) MH_SENDTO.invokeExact(capState, fd, buf, (long) len, 0, dest, destLen);
            if (rc < 0) throw new NativeException("sendto", (int) rc, errnoOf(capState));
            return rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("sendto", t);
        }
    }

    /** Linux recv/send flag values (bits/socket.h). */
    public static final int MSG_OOB = 0x01, MSG_PEEK = 0x02, MSG_DONTWAIT = 0x40;

    /**
     * recv(2) with flags on any socket fd -- including the real fd of a JDK
     * inet channel, for flags (MSG_PEEK, MSG_OOB, ...) java.nio cannot pass.
     */
    public static long recv(int fd, MemorySegment buf, long len, int flags) throws NativeException {
        require(BIT_DGRAM, "recv");
        MemorySegment capState = ERRNO_STATE.get();
        try {
            long rc = (long) MH_RECVFROM.invokeExact(capState, fd, buf, len, flags, MemorySegment.NULL, MemorySegment.NULL);
            if (rc < 0) throw new NativeException("recv", (int) rc, errnoOf(capState));
            return rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("recv", t);
        }
    }

    /** send(2) with flags on any connected socket fd; see {@link #recv}. */
    public static long send(int fd, byte[] data, int off, int len, int flags) throws NativeException {
        require(BIT_DGRAM, "send");
        MemorySegment capState = ERRNO_STATE.get();
        try {
            MemorySegment buf = UnixFdChannel.scratch(Math.max(len, 1));
            MemorySegment.copy(data, off, buf, C_BYTE, 0, len);
            long rc = (long) MH_SENDTO.invokeExact(capState, fd, buf, (long) len, flags, MemorySegment.NULL, 0);
            if (rc < 0) throw new NativeException("send", (int) rc, errnoOf(capState));
            return rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("send", t);
        }
    }

    public static final class RecvFromResult {
        public final long n;
        public final String srcPath; // "" if the kernel gave no/empty address
        RecvFromResult(long n, String srcPath) { this.n = n; this.srcPath = srcPath; }
    }

    public static RecvFromResult recvFrom(int fd, MemorySegment buf, long len) throws NativeException {
        require(BIT_DGRAM, "recvfrom");
        MemorySegment capState = ERRNO_STATE.get();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sun = arena.allocate(2 + SUN_PATH);
            MemorySegment addrlen = arena.allocate(C_INT);
            addrlen.set(C_INT, 0, 2 + SUN_PATH);
            long rc = (long) MH_RECVFROM.invokeExact(capState, fd, buf, len, 0, sun, addrlen);
            if (rc < 0) throw new NativeException("recvfrom", (int) rc, errnoOf(capState));
            String srcPath = parseSockaddrUn(sun, addrlen.get(C_INT, 0));
            return new RecvFromResult(rc, srcPath);
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("recvfrom", t);
        }
    }

    /**
     * getsockopt(SOL_SOCKET, SO_TYPE) -- the one cheap, side-effect-free way
     * to tell a real SOCK_DGRAM fd from a SOCK_STREAM one when a caller only
     * has a bare fd (UNIXSocket.for_fd), used by RubyUNIXSocket#recvfrom to
     * decide whether the kernel's recvfrom(2) source address is meaningful
     * (dgram) or should fall back to the existing connected-peer lookup
     * (stream, where AF_UNIX recvfrom does not reliably populate src_addr).
     * Returns -1 (not SOCK_STREAM/SOCK_DGRAM) on any failure -- callers
     * should treat that as "assume stream, keep existing behavior."
     */
    public static int socketType(int fd) {
        if (fd < 0 || MH_GETSOCKOPT == null) return -1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment val = arena.allocate(C_INT);
            MemorySegment len = arena.allocate(C_INT);
            len.set(C_INT, 0, 4);
            int rc = (int) MH_GETSOCKOPT.invokeExact(fd, SOL_SOCKET, SO_TYPE, val, len);
            if (rc != 0) return -1;
            return val.get(C_INT, 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean hasSockopt() { return MH_GETSOCKOPT_ERRNO != null && MH_SETSOCKOPT != null; }

    /** getsockopt(2) on any socket fd: the raw option bytes, as the kernel returns them. */
    public static byte[] getSockopt(int fd, int level, int optname) throws NativeException {
        if (MH_GETSOCKOPT_ERRNO == null) throw new NativeException("getsockopt", "FFM getsockopt not available");
        MemorySegment capState = ERRNO_STATE.get();
        try {
            MemorySegment scratch = UnixFdChannel.scratch(256 + 8);
            MemorySegment val = scratch.asSlice(0, 256);
            MemorySegment len = scratch.asSlice(256, 4);
            len.set(C_INT, 0, 256);
            int rc = (int) MH_GETSOCKOPT_ERRNO.invokeExact(capState, fd, level, optname, val, len);
            if (rc != 0) throw new NativeException("getsockopt", rc, errnoOf(capState));
            byte[] out = new byte[len.get(C_INT, 0)];
            MemorySegment.copy(val, C_BYTE, 0, out, 0, out.length);
            return out;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("getsockopt", t);
        }
    }

    public static boolean hasIoctl() { return MH_IOCTL_PTR != null; }

    public static final short POLLIN = 0x1, POLLOUT = 0x4;

    private static final MethodHandle MH_GETHOSTNAME = optionalDowncall("gethostname",
            FunctionDescriptor.of(C_INT, C_PTR, C_LONG));

    private static MethodHandle optionalDowncall(String name, FunctionDescriptor desc) {
        try {
            if (!linuxLp64()) return null;
            Linker linker = Linker.nativeLinker();
            return downcall(linker, linker.defaultLookup(), name, desc);
        } catch (Throwable t) {
            return null;
        }
    }

    /** gethostname(2), or null if unavailable. */
    public static String hostname() {
        if (MH_GETHOSTNAME == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(256);
            int rc = (int) MH_GETHOSTNAME.invokeExact(buf, 256L);
            return rc == 0 ? buf.getString(0) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean hasPoll() { return MH_POLL != null; }

    /** poll(2) on one fd: >0 ready (or error/hangup -- let the next syscall report it), 0 timed out. */
    public static int poll(int fd, short events, int timeoutMs) {
        try {
            MemorySegment pfd = UnixFdChannel.scratch(8);
            pfd.set(C_INT, 0, fd);
            pfd.set(ValueLayout.JAVA_SHORT, 4, events);
            pfd.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
            int rc = (int) MH_POLL.invokeExact(pfd, 1L, timeoutMs);
            return rc < 0 ? 0 : rc; // EINTR etc.: treat as a timed-out slice, the caller re-checks
        } catch (Throwable t) {
            return 1;
        }
    }

    /**
     * ioctl(2) with a buffer argument: data is copied in, the call made with a
     * pointer to it, and the (possibly updated) bytes copied back into data.
     */
    public static int ioctl(int fd, long request, byte[] data, int off, int len) throws NativeException {
        if (MH_IOCTL_PTR == null) throw new NativeException("ioctl", "FFM ioctl not available");
        MemorySegment capState = ERRNO_STATE.get();
        try {
            MemorySegment buf = UnixFdChannel.scratch(Math.max(len, 1));
            MemorySegment.copy(data, off, buf, C_BYTE, 0, len);
            int rc = (int) MH_IOCTL_PTR.invokeExact(capState, fd, request, buf);
            if (rc < 0) throw new NativeException("ioctl", rc, errnoOf(capState));
            MemorySegment.copy(buf, C_BYTE, 0, data, off, len);
            return rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("ioctl", t);
        }
    }

    /** setsockopt(2) on any socket fd with the raw option bytes. */
    public static void setSockopt(int fd, int level, int optname, byte[] data, int off, int len) throws NativeException {
        if (MH_SETSOCKOPT == null) throw new NativeException("setsockopt", "FFM setsockopt not available");
        MemorySegment capState = ERRNO_STATE.get();
        try {
            MemorySegment val = UnixFdChannel.scratch(Math.max(len, 1));
            MemorySegment.copy(data, off, val, C_BYTE, 0, len);
            int rc = (int) MH_SETSOCKOPT.invokeExact(capState, fd, level, optname, val, len);
            if (rc != 0) throw new NativeException("setsockopt", rc, errnoOf(capState));
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("setsockopt", t);
        }
    }

    private static MemorySegment sockaddrUn(Arena arena, byte[] pathBytes) {
        MemorySegment sun = arena.allocate(2 + SUN_PATH); // zero-initialized
        sun.set(C_BYTE, 0, (byte) AF_UNIX);
        sun.set(C_BYTE, 1, (byte) 0);
        MemorySegment.copy(pathBytes, 0, sun, C_BYTE, 2, pathBytes.length);
        return sun;
    }

    // Same parsing convention as pathOf(int): skip the 2-byte sun_family,
    // handle Linux's abstract-namespace leading NUL, stop at the first NUL
    // (or end of the reported length) for a regular path.
    private static String parseSockaddrUn(MemorySegment sun, int len) {
        if (len <= 2) return "";
        byte[] bytes = new byte[len - 2];
        MemorySegment.copy(sun, C_BYTE, 2, bytes, 0, bytes.length);
        if (bytes[0] == 0) {
            int end = 1;
            while (end < bytes.length && bytes[end] != 0) end++;
            return end <= 1 ? "" : "\0" + new String(bytes, 1, end - 1);
        }
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, 0, end);
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
        return new UnixFdChannel(fd);
    }

    public static long read(int fd, MemorySegment buf, long count) throws NativeException {
        require(BIT_FFM, "read");
        MemorySegment capState = ERRNO_STATE.get();
        try {
            long rc = (long) MH_READ.invokeExact(capState, fd, buf, count);
            if (rc < 0) throw new NativeException("read", (int) rc, errnoOf(capState));
            return rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("read", t);
        }
    }

    public static long write(int fd, MemorySegment buf, long count) throws NativeException {
        require(BIT_FFM, "write");
        MemorySegment capState = ERRNO_STATE.get();
        try {
            long rc = (long) MH_WRITE.invokeExact(capState, fd, buf, count);
            if (rc < 0) throw new NativeException("write", (int) rc, errnoOf(capState));
            return rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("write", t);
        }
    }

    /**
     * Best-effort, like close(): used to force a thread blocked in read(2)
     * on this fd to return (see UnixFdChannel), not to report success.
     * shutdown(2) is the safe way to do that -- unlike close(2), it doesn't
     * race with fd reuse in another thread, and reliably unblocks a pending
     * recv/read on this exact socket.
     */
    public static int shutdown(int fd, int how) {
        if (fd < 0 || MH_SHUTDOWN == null) return -1;
        try {
            return (int) MH_SHUTDOWN.invokeExact(fd, how);
        } catch (Throwable t) {
            return -1;
        }
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

    /**
     * getpeername(2) -- unlike pathOf/getsockname (always succeeds for any
     * bound-or-not socket), this genuinely fails with ENOTCONN when there is
     * no peer. That's the one reliable, kernel-verified way to tell a really
     * connected UnixFdChannel (pair()/socketpair()/for_fd on an already-
     * connected fd -- unnamed, but a real peer) from a raw Socket.new(:UNIX,
     * :DGRAM) that was only bound, never connected (see
     * UnixDomain#remoteAddress). Captures errno (via CAPTURE_ERRNO, same as
     * read/write/sendto/recvfrom) specifically so the ENOTCONN case is
     * distinguishable from any other failure.
     */
    public static String peerPathOf(int fd) throws NativeException {
        require(BIT_GETPEERNAME, "getpeername");
        MemorySegment capState = ERRNO_STATE.get();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sun = arena.allocate(2 + SUN_PATH);
            MemorySegment len = arena.allocate(C_INT);
            len.set(C_INT, 0, 2 + SUN_PATH);
            int rc = (int) MH_GETPEERNAME.invokeExact(capState, fd, sun, len);
            if (rc != 0) throw new NativeException("getpeername", rc, errnoOf(capState));
            return parseSockaddrUn(sun, len.get(C_INT, 0));
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("getpeername", t);
        }
    }

    /** fcntl(fd, F_GETFL) -- current file status flags. */
    public static int getFlags(int fd) throws NativeException {
        require(BIT_DGRAM, "fcntl");
        try {
            int rc = (int) MH_FCNTL_GET.invokeExact(fd, F_GETFL);
            if (rc < 0) throw new NativeException("fcntl", rc);
            return rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("fcntl", t);
        }
    }

    /** fcntl(fd, F_SETFL, flags) -- replace file status flags wholesale
     * (caller is responsible for OR-ing/clearing bits against a prior
     * getFlags() result, same contract as DatagramChannel#configureBlocking
     * needing the old value to restore it afterward). */
    public static void setFlags(int fd, int flags) throws NativeException {
        require(BIT_DGRAM, "fcntl");
        try {
            int rc = (int) MH_FCNTL_SET.invokeExact(fd, F_SETFL, flags);
            if (rc < 0) throw new NativeException("fcntl", rc);
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("fcntl", t);
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
            MemorySegment capState = arena.allocate(CAPTURE_LAYOUT);
            long rc = (long) MH_SENDMSG.invokeExact(capState, sockFd, msg, 0);
            if (rc < 0) throw new NativeException("sendmsg", (int) rc, errnoOf(capState));
            return (int) rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("sendmsg", t);
        }
    }

    // -- general sendmsg(2)/recvmsg(2), for BasicSocket#sendmsg/#recvmsg --
    // Linux LP64 layouts: struct msghdr is 56 bytes (msg_name@0,
    // msg_namelen@8, msg_iov@16, msg_iovlen@24, msg_control@32,
    // msg_controllen@40, msg_flags@48); struct iovec is 16; struct cmsghdr is
    // a 16-byte header (cmsg_len@0, cmsg_level@8, cmsg_type@12) padded to 8.
    // Every buffer is carved from the thread's scratch segment, one get() per
    // call (see UnixFdChannel#scratch).
    public static final int MSG_TRUNC = 0x20, MSG_CTRUNC = 0x08;
    private static final int MSGHDR = 56, IOVEC = 16, CMSGHDR = 16, NAME_MAX_LEN = 128;

    private static long align8(long n) { return (n + 7) & ~7L; }

    /** One control message: cmsg_level, cmsg_type and its data bytes. */
    public record Cmsg(int level, int type, byte[] data) {}

    /** recvmsg(2) result: bytes read, sender sockaddr (may be empty), msg_flags, control messages. */
    public record RecvMsgResult(long n, byte[] data, byte[] name, int flags, java.util.List<Cmsg> controls) {}

    public static long sendMsg(int fd, byte[] data, int off, int len, byte[] dest,
                               java.util.List<Cmsg> controls, int flags) throws NativeException {
        require(BIT_SCM, "sendmsg");
        long controlLen = 0;
        for (Cmsg c : controls) controlLen += CMSGHDR + align8(c.data().length);
        int nameLen = dest == null ? 0 : dest.length;
        long total = MSGHDR + IOVEC + align8(nameLen) + controlLen + Math.max(len, 1);
        MemorySegment capState = ERRNO_STATE.get();
        try {
            MemorySegment all = UnixFdChannel.scratch((int) total);
            all.asSlice(0, total).fill((byte) 0);
            MemorySegment msg = all.asSlice(0, MSGHDR);
            MemorySegment iov = all.asSlice(MSGHDR, IOVEC);
            long at = MSGHDR + IOVEC;
            MemorySegment name = all.asSlice(at, Math.max(nameLen, 1)); at += align8(nameLen);
            MemorySegment control = all.asSlice(at, Math.max(controlLen, 1)); at += controlLen;
            MemorySegment buf = all.asSlice(at, Math.max(len, 1));

            MemorySegment.copy(data, off, buf, C_BYTE, 0, len);
            iov.set(C_PTR, 0, buf);
            iov.set(C_LONG, 8, (long) len);
            if (nameLen > 0) MemorySegment.copy(dest, 0, name, C_BYTE, 0, nameLen);
            long c = 0;
            for (Cmsg cm : controls) {
                control.set(C_LONG, c, (long) (CMSGHDR + cm.data().length));
                control.set(C_INT, c + 8, cm.level());
                control.set(C_INT, c + 12, cm.type());
                MemorySegment.copy(cm.data(), 0, control, C_BYTE, c + CMSGHDR, cm.data().length);
                c += CMSGHDR + align8(cm.data().length);
            }
            msg.set(C_PTR, 0, nameLen > 0 ? name : MemorySegment.NULL);
            msg.set(C_INT, 8, nameLen);
            msg.set(C_PTR, 16, iov);
            msg.set(C_LONG, 24, 1L);
            msg.set(C_PTR, 32, controlLen > 0 ? control : MemorySegment.NULL);
            msg.set(C_LONG, 40, controlLen);
            long rc = (long) MH_SENDMSG.invokeExact(capState, fd, msg, flags);
            if (rc < 0) throw new NativeException("sendmsg", (int) rc, errnoOf(capState));
            return rc;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("sendmsg", t);
        }
    }

    public static RecvMsgResult recvMsg(int fd, int len, int controlLen, int flags) throws NativeException {
        require(BIT_SCM, "recvmsg");
        long total = MSGHDR + IOVEC + NAME_MAX_LEN + align8(controlLen) + Math.max(len, 1);
        MemorySegment capState = ERRNO_STATE.get();
        try {
            MemorySegment all = UnixFdChannel.scratch((int) total);
            MemorySegment msg = all.asSlice(0, MSGHDR);
            MemorySegment iov = all.asSlice(MSGHDR, IOVEC);
            MemorySegment name = all.asSlice(MSGHDR + IOVEC, NAME_MAX_LEN);
            long at = MSGHDR + IOVEC + NAME_MAX_LEN;
            MemorySegment control = all.asSlice(at, Math.max(controlLen, 1)); at += align8(controlLen);
            MemorySegment buf = all.asSlice(at, Math.max(len, 1));

            iov.set(C_PTR, 0, buf);
            iov.set(C_LONG, 8, (long) len);
            msg.set(C_PTR, 0, name);
            msg.set(C_INT, 8, NAME_MAX_LEN);
            msg.set(C_INT, 12, 0);
            msg.set(C_PTR, 16, iov);
            msg.set(C_LONG, 24, 1L);
            msg.set(C_PTR, 32, controlLen > 0 ? control : MemorySegment.NULL);
            msg.set(C_LONG, 40, (long) controlLen);
            msg.set(C_INT, 48, 0);
            long rc = (long) MH_RECVMSG.invokeExact(capState, fd, msg, flags);
            if (rc < 0) throw new NativeException("recvmsg", (int) rc, errnoOf(capState));

            // with MSG_TRUNC in flags, rc is the datagram's full length; only len bytes are in buf
            byte[] data = new byte[(int) Math.min(rc, len)];
            MemorySegment.copy(buf, C_BYTE, 0, data, 0, data.length);
            byte[] sender = new byte[Math.min(msg.get(C_INT, 8), NAME_MAX_LEN)];
            MemorySegment.copy(name, C_BYTE, 0, sender, 0, sender.length);

            java.util.List<Cmsg> controls = new java.util.ArrayList<>();
            long used = Math.min(msg.get(C_LONG, 40), controlLen);
            for (long c = 0; c + CMSGHDR <= used; ) {
                long cmsgLen = control.get(C_LONG, c);
                if (cmsgLen < CMSGHDR || c + cmsgLen > used) break;
                byte[] cdata = new byte[(int) (cmsgLen - CMSGHDR)];
                MemorySegment.copy(control, C_BYTE, c + CMSGHDR, cdata, 0, cdata.length);
                controls.add(new Cmsg(control.get(C_INT, c + 8), control.get(C_INT, c + 12), cdata));
                c += align8(cmsgLen);
            }
            return new RecvMsgResult(rc, data, sender, msg.get(C_INT, 48), controls);
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw new NativeException("recvmsg", t);
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
            MemorySegment capState = arena.allocate(CAPTURE_LAYOUT);
            long rc = (long) MH_RECVMSG.invokeExact(capState, sockFd, msg, 0);
            if (rc < 0) throw new NativeException("recvmsg", (int) rc, errnoOf(capState));
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
                int ignoredA = (int) close.invokeExact(a);
                int ignoredB = (int) close.invokeExact(b);
            }
        }
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor desc, Linker.Option... options) {
        MemorySegment sym = lookup.find(name).orElseThrow(() -> new IllegalStateException("missing " + name));
        return linker.downcallHandle(sym, desc, options);
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
        /** Reliably-captured errno via Linker.Option.captureCallState, or 0 if none was captured. */
        public final int errno;

        public NativeException(String op, int rc) { super(op + " failed rc=" + rc); this.errno = 0; }
        public NativeException(String op, int rc, int errno) {
            super(op + " failed rc=" + rc + " errno=" + errno);
            this.errno = errno;
        }
        public NativeException(String op, String msg) { super(op + ": " + msg); this.errno = 0; }
        public NativeException(String op, Throwable cause) { super(op + ": " + cause.getMessage(), cause); this.errno = 0; }
    }
}
