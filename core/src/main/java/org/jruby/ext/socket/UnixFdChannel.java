package org.jruby.ext.socket;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.util.Map;

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
 *
 * RubyBasicSocket#recv/#read hand this a heap ByteBuffer (plain
 * ByteBuffer.allocate, not allocateDirect), so the heap path below is the
 * one real Ruby code actually takes, not a fallback. A fresh
 * Arena.ofConfined() per call measured ~35% slower than JNR's channel on
 * UNIXSocket.pair ping-pong -- SCRATCH reuses one growable native buffer
 * per carrier thread instead. Safe as a plain ThreadLocal (unlike
 * UnixDomainContext's Fiber-sensitive per-ThreadContext state): this
 * buffer is written and consumed within one synchronous read()/write()
 * call and never observed across a fiber-switch boundary, so which OS
 * thread backs it doesn't matter -- it's a scratch buffer, not
 * Ruby-visible state.
 *
 * @author Troy Mallory (CufeHaco) - JEP-380/FFM unix socket support (jruby/jruby#9040)
 */
public final class UnixFdChannel extends AbstractInterruptibleChannel implements ByteChannel {
    private static final int SCRATCH_DEFAULT_SIZE = 8192;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /**
     * Grow-on-demand, shrink-with-governor. Off-heap, so it never shows up
     * as JVM heap pressure -- the growth problem here is unbounded per-
     * thread native memory, not GC, but the fix is the exact same shape as
     * Kestowv Wave's per-core governor: a rare, decisive writer (shrink)
     * gated by a decaying usage signal, a wide hysteresis dead-band so a
     * single small call right after a burst doesn't immediately snap the
     * buffer back down (which would just force the next big call to
     * regrow it -- thrashing), and a floor so it never shrinks below the
     * size that was already fine for ordinary small messages.
     *
     * Without this, one large one-off read/write (e.g. a single big
     * message on an otherwise small-message connection) permanently
     * inflates this carrier thread's native footprint for the rest of the
     * thread's life, even if every later call only ever needs a few KB.
     */
    /**
     * This carrier thread's scratch native buffer, at least minSize bytes,
     * for the other FFM socket calls (recv/send with flags, sockopts, ioctl,
     * sendmsg/recvmsg) -- the same reuse and governor as read/write here.
     * Call once per syscall and carve slices from it: a later get() may
     * resize, which frees the segment an earlier call returned.
     */
    static MemorySegment scratch(int minSize) {
        return SCRATCH.get().get(minSize);
    }

    private static final class Scratch {
        // Never shrink below this -- matches the original fixed default,
        // so ordinary small messages never pay a regrow cost after a
        // single large outlier is done. Same role as Wave's GOV_MIN floor
        // ("JVM stays warm, cores cool" -- here: "don't shrink into a size
        // that's just going to regrow next call").
        private static final int MIN_SIZE = SCRATCH_DEFAULT_SIZE;

        // How many get() calls form one governor "tick" -- checked
        // periodically, not on every call, same as Wave's GOV_INTERVAL
        // being a real time period rather than a continuous check. Purely
        // call-count-based here since this buffer has no wall-clock tick
        // of its own; higher = slower to react but less governor overhead.
        private static final int TICK_CALLS = 64;

        // Wide dead-band before considering a shrink at all: the current
        // size has to be at least 4x recent actual usage. Mirrors
        // GOV_HYSTERESIS -- without a wide margin here, usage sitting
        // right at a boundary would oscillate grow/shrink every tick.
        private static final int HYSTERESIS_RATIO = 4;

        private Arena arena = Arena.ofConfined();
        private MemorySegment segment = arena.allocate(SCRATCH_DEFAULT_SIZE);
        private int recentMax = SCRATCH_DEFAULT_SIZE;
        private int callsThisTick = 0;

        MemorySegment get(int minSize) {
            if (segment.byteSize() < minSize) {
                // Must grow -- this is a correctness requirement for the
                // current call, not a policy decision, so it happens
                // immediately regardless of tick/hysteresis state.
                resize(minSize);
                recentMax = minSize;
                callsThisTick = 0;
                return segment;
            }

            if (minSize > recentMax) recentMax = minSize;

            if (++callsThisTick >= TICK_CALLS) {
                // Halfway back toward observed usage, not an instant snap
                // to recentMax -- same "back off decisively but don't
                // overshoot" shape as Wave's incremental GOV_STEP, so one
                // noisy tick's recentMax can't yank the buffer straight
                // down to a size the very next call would just regrow.
                if ((long) recentMax * HYSTERESIS_RATIO < segment.byteSize()) {
                    int target = Math.max(MIN_SIZE, (int) ((segment.byteSize() + recentMax) / 2));
                    if (target < segment.byteSize()) resize(target);
                }
                recentMax = minSize;
                callsThisTick = 0;
            }

            return segment;
        }

        private void resize(int size) {
            arena.close();
            arena = Arena.ofConfined();
            segment = arena.allocate(size);
        }
    }

    // org.jruby.runtime.Helpers#errnoFromMessage string-matches IOException
    // messages against exactly these literals (the ones the JDK's own
    // native channels happen to throw) to recover an Errno:: class -- it
    // has no other way to see the real errno. Without this, "rescue
    // Errno::EPIPE" silently fails to catch on a pair()/for_fd socket,
    // since UnixDomainNative's own message ("write failed rc=-1 errno=32")
    // doesn't match anything. Linux x86-64 errno values.
    private static final Map<Integer, String> ERRNO_MESSAGES = Map.ofEntries(
            Map.entry(9,   "Bad file descriptor"),       // EBADF
            Map.entry(13,  "Permission denied"),         // EACCES
            Map.entry(28,  "No space left on device"),   // ENOSPC
            Map.entry(32,  "Broken pipe"),                // EPIPE
            Map.entry(89,  "Destination address required"), // EDESTADDRREQ
            Map.entry(90,  "Message too long"),          // EMSGSIZE
            Map.entry(103, "An established connection was aborted by the software in your host machine"), // ECONNABORTED
            Map.entry(104, "Connection reset by peer"),  // ECONNRESET
            Map.entry(107, "Transport endpoint is not connected"), // ENOTCONN
            Map.entry(110, "Operation timed out")        // ETIMEDOUT
    );

    private final int fd;

    // Lazily-computed, then cached for this instance's lifetime: a socket's
    // SOCK_STREAM/SOCK_DGRAM type is fixed at socket(2) creation time and
    // can never change afterward, so re-querying it via getsockopt(SO_TYPE)
    // on every RubyUNIXSocket#recvfrom call (measured ~1.2us/call in
    // isolation -- not huge per call, but pure waste since the answer is
    // always the same) is a repeated syscall for a value that was already
    // known after the first call. Safe as a plain instance field, not a
    // ThreadLocal/shared map: this fd is immutable per instance (final,
    // set once in the constructor), so there's no reopen/rebind path that
    // could make a cached value stale -- same "one fixed key, own slot,
    // compute once" shape as BITS/SCRATCH elsewhere in this file/class.
    private int cachedSockType = Integer.MIN_VALUE;

    UnixFdChannel(int fd) {
        this.fd = fd;
    }

    public int fd() { return fd; }

    int sockType() {
        if (cachedSockType == Integer.MIN_VALUE) {
            cachedSockType = UnixDomainNative.socketType(fd);
        }
        return cachedSockType;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (!isOpen()) throw new ClosedChannelException();
        boolean completed = false;
        try {
            begin();
            long n = rawRead(fd, dst);
            completed = true;
            return readResult(n);
        } finally {
            end(completed);
        }
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (!isOpen()) throw new ClosedChannelException();
        boolean completed = false;
        try {
            begin();
            long n = rawWrite(fd, src);
            completed = true;
            return writeResult(n);
        } finally {
            end(completed);
        }
    }

    // WHY: this channel isn't a SelectableChannel, so OpenFile#setNonblock
    // can't flip it non-blocking and PosixShim used to answer every
    // read_nonblock on it with EAGAIN without reading at all -- even with
    // data waiting, and at EOF (where MRI raises EOFError). write_nonblock
    // likewise did a plain blocking write. These do one real read(2)/write(2)
    // with the fd temporarily in O_NONBLOCK, the same fcntl toggle
    // UnixDomain#recvFromNonBlocking already uses for recv_nonblock.
    /** Non-blocking read. Channel contract: bytes read, 0 if none available, -1 at EOF. */
    public int readNonBlocking(ByteBuffer dst) throws IOException {
        if (!isOpen()) throw new ClosedChannelException();
        int oldFlags = flags();
        setFlags(oldFlags | UnixDomainNative.O_NONBLOCK);
        try {
            return readResult(rawRead(fd, dst));
        } finally {
            setFlags(oldFlags);
        }
    }

    /** Non-blocking write. Channel contract: bytes written, 0 if the socket buffer is full. */
    public int writeNonBlocking(ByteBuffer src) throws IOException {
        if (!isOpen()) throw new ClosedChannelException();
        int oldFlags = flags();
        setFlags(oldFlags | UnixDomainNative.O_NONBLOCK);
        try {
            return writeResult(rawWrite(fd, src));
        } finally {
            setFlags(oldFlags);
        }
    }

    private int flags() throws IOException {
        try {
            return UnixDomainNative.getFlags(fd);
        } catch (UnixDomainNative.NativeException e) {
            throw toIOException(e);
        }
    }

    private void setFlags(int flags) throws IOException {
        try {
            UnixDomainNative.setFlags(fd, flags);
        } catch (UnixDomainNative.NativeException e) {
            throw toIOException(e);
        }
    }

    /**
     * rawRead/rawWrite's result for EAGAIN on a non-blocking fd. It needs its
     * own value: read(2)'s 0 already means EOF.
     */
    static final long WOULD_BLOCK = -2;

    /** rawRead result to Channel contract: -1 at EOF, 0 if nothing available yet. */
    static int readResult(long n) {
        if (n == WOULD_BLOCK) return 0;
        return n == 0 ? -1 : (int) n;
    }

    /** rawWrite result to Channel contract: 0 if the socket buffer is full. */
    static int writeResult(long n) {
        return n == WOULD_BLOCK ? 0 : (int) n;
    }

    // WHY static + fd-parameterized, not instance methods on this class: these
    // are the bare read(2)/write(2) mechanics (scratch-buffer copy, direct-vs-
    // heap ByteBuffer handling, errno mapping) with NO begin()/end() bracketing
    // of their own -- callers decide whether to bracket them. This class's own
    // read()/write() above do (see this class's header comment: that's what
    // gives pair()/for_fd SCM_RIGHTS fds real Thread#kill/interrupt support,
    // at the documented cost of the channel closing if a genuine interrupt
    // lands mid-call). RubyUNIXSocket's named-socket read/write path
    // (see RubyUNIXSocket_init.java / ChannelFD_jepUnixNativeChannel.java)
    // deliberately calls these WITHOUT begin()/end() instead: a named JEP-380
    // UNIXSocket keeps its fd genuinely blocking (unlike pair()/for_fd's
    // channel, which stays non-blocking-capable via chSelect), and for that fd
    // these are a real blocking read(2)/write(2) libc call with no JDK-level
    // interrupt hook at all -- Thread#kill/Thread#raise/Timeout.timeout cannot
    // abort it (matching jnr.unixsocket.UnixSocketChannel's long-standing,
    // already-shipped behavior), but a genuine interrupt landing mid-call also
    // can never tear the connection down, which AbstractInterruptibleChannel's
    // own contract cannot offer. Measured: ~30% faster round-trip throughput
    // than the select()-based non-blocking default these replace (this repo's
    // kestowv_tier_one.rb benchmark against Kestowv 0.5.6's ipc/channel.rb) --
    // see INTERPRETER.md Landmine 12/13 for the full trade-off and why the two
    // obvious-looking alternatives (making the real SocketChannel itself
    // blocking; wrapping it in this class's own begin()/end()-bracketed
    // read/write) were tried first and rejected, not overlooked.
    static long rawRead(int fd, ByteBuffer dst) throws IOException {
        int remaining = dst.remaining();
        if (remaining == 0) return 0;

        long n;
        if (dst.isDirect()) {
            MemorySegment seg = MemorySegment.ofBuffer(dst);
            n = nativeRead(fd, seg, remaining);
            if (n > 0) dst.position(dst.position() + (int) n);
        } else {
            MemorySegment tmp = SCRATCH.get().get(remaining);
            n = nativeRead(fd, tmp, remaining);
            if (n > 0) {
                if (dst.hasArray()) {
                    // Copy straight into dst's backing array -- one copy,
                    // not native->byte[]->dst via an intermediate put().
                    int off = dst.arrayOffset() + dst.position();
                    MemorySegment.copy(tmp, ValueLayout.JAVA_BYTE, 0, dst.array(), off, (int) n);
                    dst.position(dst.position() + (int) n);
                } else {
                    byte[] bytes = new byte[(int) n];
                    MemorySegment.copy(tmp, ValueLayout.JAVA_BYTE, 0, bytes, 0, (int) n);
                    dst.put(bytes);
                }
            }
        }
        return n;
    }

    static long rawWrite(int fd, ByteBuffer src) throws IOException {
        int remaining = src.remaining();
        if (remaining == 0) return 0;

        long n;
        if (src.isDirect()) {
            MemorySegment seg = MemorySegment.ofBuffer(src);
            n = nativeWrite(fd, seg, remaining);
            if (n > 0) src.position(src.position() + (int) n);
        } else {
            MemorySegment tmp = SCRATCH.get().get(remaining);
            if (src.hasArray()) {
                // Copy straight from src's backing array -- one copy,
                // not src->byte[]->native via an intermediate get().
                int off = src.arrayOffset() + src.position();
                MemorySegment.copy(src.array(), off, tmp, ValueLayout.JAVA_BYTE, 0, remaining);
            } else {
                int startPos = src.position();
                byte[] bytes = new byte[remaining];
                src.get(bytes);
                src.position(startPos);
                MemorySegment.copy(bytes, 0, tmp, ValueLayout.JAVA_BYTE, 0, remaining);
            }
            n = nativeWrite(fd, tmp, remaining);
            if (n > 0) src.position(src.position() + (int) n);
        }
        return n;
    }

    private static long nativeRead(int fd, MemorySegment seg, int len) throws IOException {
        try {
            return UnixDomainNative.read(fd, seg, len);
        } catch (UnixDomainNative.NativeException e) {
            if (e.errno == UnixDomainNative.EAGAIN) return WOULD_BLOCK;
            throw toIOException(e);
        }
    }

    private static long nativeWrite(int fd, MemorySegment seg, int len) throws IOException {
        try {
            return UnixDomainNative.write(fd, seg, len);
        } catch (UnixDomainNative.NativeException e) {
            if (e.errno == UnixDomainNative.EAGAIN) return WOULD_BLOCK;
            throw toIOException(e);
        }
    }

    static IOException toIOException(UnixDomainNative.NativeException e) {
        String mapped = ERRNO_MESSAGES.get(e.errno);
        return new IOException(mapped != null ? mapped : e.getMessage(), e);
    }

    @Override
    protected void implCloseChannel() throws IOException {
        UnixDomainNative.shutdown(fd, UnixDomainNative.SHUT_RDWR);
        UnixDomainNative.close(fd);
    }
}
