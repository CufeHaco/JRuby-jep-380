package org.jruby.ext.socket;

import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.WeakHashMap;

/**
 * CRuby-like per-execution-context frame for Unix-domain IPC.
 *
 * CRuby (thread.c):
 *   rb_thread_io_blocking_region(func, data, fd)
 *     th-&gt;waiting_fd = fd
 *     BLOCKING_REGION { val = func(); saved_errno = errno; }
 *     th-&gt;waiting_fd = -1
 *     RUBY_VM_CHECK_INTS_BLOCKING(th)
 *     errno = saved_errno
 *
 * JRuby already has the execution context: ThreadContext.
 * Fibers swap it. Do not hang this off java.lang.ThreadLocal —
 * that follows the carrier thread and lies when a fiber migrates.
 */
public final class UnixDomainContext {
    public static final Object KEY = new Object();

    public static final class Frame {
        public int waitingFd = -1;
        public int lastErrno;
        public String lastOp;
        public int lastRc;
        public long enteredNanos;
    }

    private static volatile MethodHandle MH_ERRNO_LOC;
    private static volatile boolean ERRNO_PROBED;
    private static final WeakHashMap<ThreadContext, Frame> FRAMES = new WeakHashMap<>();

    private UnixDomainContext() {}

    public static Frame current(ThreadContext context) {
        synchronized (FRAMES) {
            return FRAMES.computeIfAbsent(context, ignored -&gt; new Frame());
        }
    }

    public static <T> T blocking(ThreadContext context, int fd, String op, Call<T> body) throws Exception {
        Frame frame = current(context);
        int prevFd = frame.waitingFd;
        String prevOp = frame.lastOp;
        frame.waitingFd = fd;
        frame.lastOp = op;
        frame.enteredNanos = System.nanoTime();
        try {
            T value = body.get();
            frame.lastErrno = 0;
            return value;
        } catch (Exception e) {
            frame.lastErrno = captureErrno();
            frame.lastRc = -1;
            throw e;
        } finally {
            frame.waitingFd = prevFd;
            frame.lastOp = prevOp;
            context.pollThreadEvents();
        }
    }

    public static int lastErrno(ThreadContext context) {
        return current(context).lastErrno;
    }

    public static int waitingFd(ThreadContext context) {
        return current(context).waitingFd;
    }

    public static IRubyObject lastErrnoAsFixnum(ThreadContext context) {
        return context.runtime.newFixnum(current(context).lastErrno);
    }

    public static int captureErrno() {
        MethodHandle loc = errnoLocation();
        if (loc == null) return 0;
        try {
            MemorySegment ptr = (MemorySegment) loc.invokeExact();
            return ptr.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void record(ThreadContext context, String op, int rc) {
        Frame frame = current(context);
        frame.lastOp = op;
        frame.lastRc = rc;
        frame.lastErrno = rc &lt; 0 ? captureErrno() : 0;
    }

    private static MethodHandle errnoLocation() {
        if (ERRNO_PROBED) return MH_ERRNO_LOC;
        synchronized (UnixDomainContext.class) {
            if (ERRNO_PROBED) return MH_ERRNO_LOC;
            try {
                Linker linker = Linker.nativeLinker();
                MemorySegment sym = linker.defaultLookup()
                        .find("__errno_location")
                        .or(() -&gt; linker.defaultLookup().find("__error"))
                        .orElseThrow();
                MH_ERRNO_LOC = linker.downcallHandle(sym,
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
            } catch (Throwable t) {
                MH_ERRNO_LOC = null;
            }
            ERRNO_PROBED = true;
            return MH_ERRNO_LOC;
        }
    }

    @FunctionalInterface
    public interface Call<T> {
        T get() throws Exception;
    }
}
