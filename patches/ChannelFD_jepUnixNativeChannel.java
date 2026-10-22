// Replacement inside core/src/main/java/org/jruby/util/io/ChannelFD.java
//
// WHY: jnr.enxio.channels.NativeSelectableChannel is how this class already
// recognizes a "real native descriptor" and, on that strength alone, skips
// the pre-read select() OpenFile#selectForRead otherwise does before every
// blocking read (that method's own comment: MRI never selects before a
// blocking rb_read_internal; JRuby only does it for ordinary SelectableChannels
// so a blocked read can be interrupted without closing the channel -- see
// RubyThread's wakeup() on native channels for why that already doesn't apply
// to a native descriptor either way). A JEP-380 unix-domain SocketChannel/
// ServerSocketChannel (jruby/jruby#9040) is just as real a native descriptor
// as JNR's own UnixSocketChannel -- FilenoUtil/UnixDomain.fileno already
// resolves its actual OS fd reflectively via sun.nio.ch.SelChImpl (used today
// for .fileno, send_io/recv_io) -- it simply doesn't implement JNR's
// enxio-specific marker interface, so it fell through to the generic
// SelectableChannel path and paid for a select() on every single blocking
// read it ever does.
//
// ALSO replaces chRead/chWrite for a connected (not listening) unix-domain
// SocketChannel with UnixSocketNativeChannel (new file -- see
// UnixSocketNativeChannel.java), routing the actual data transfer through raw
// FFM read(2)/write(2) instead of sun.nio.ch.SocketChannelImpl's own
// interruptible ones. Both pieces are needed together: fixing only the
// chNative recognition above (an earlier attempt, kept briefly on its own)
// measured ZERO throughput change, because OpenFile#selectForRead's pre-read
// select was already being skipped for a different reason (the non-blocking
// flip in RubyUNIXSocket#init_sock -- see RubyUNIXSocket_init.java), and the
// real cost was in OpenFile#waitReadable's EAGAIN-driven select, which has no
// chNative guard at all. Removing the non-blocking flip alone (so chNative's
// select-skip would matter) was ALSO tried and reverted: it measured a real
// win but let a genuine Thread.interrupt() landing inside
// SocketChannelImpl.read() throw ClosedByInterruptException and close the
// channel -- confirmed destroying 1000/1000 connections in a jittered
// Thread#raise/Timeout.timeout stress loop. This combination -- chNative
// recognition (harmless, kept for PREAD_TASK/PWRITE_TASK correctness too) +
// routing actual reads/writes through FFM with no begin()/end() bracketing
// (see UnixSocketNativeChannel's own header comment for why not
// AbstractInterruptibleChannel either) + the matching RubyUNIXSocket_init.java
// change to leave the fd genuinely blocking -- is what actually closes the
// gap: measured ~30-60% faster round-trip throughput than the select()-based
// default it replaces, same ballpark as JNR (this repo's kestowv_tier_one.rb
// benchmark against Kestowv 0.5.6's ipc/channel.rb). See INTERPRETER.md
// Landmine 12/13 for the full trade-off history, including the two rejected
// attempts and why.
//
// After applying: no import changes needed -- UnixDomain/UnixSocketNativeChannel
// are referenced here by fully-qualified name (org.jruby.ext.socket....),
// matching the existing style in FilenoUtil.getDescriptorFromChannel's
// UnixFdChannel case rather than adding a new top-level import from this
// lower-level package.

    private void initChannelTypes() {
        assert realFileno != -1 || fakeFileno != -1 : "initialize filenos before initChannelTypes";
        if (ch instanceof ReadableByteChannel) chRead = (ReadableByteChannel)ch;
        else chRead = null;
        if (ch instanceof WritableByteChannel) chWrite = (WritableByteChannel)ch;
        else chWrite = null;
        if (ch instanceof SeekableByteChannel) chSeek = (SeekableByteChannel)ch;
        else chSeek = null;
        if (ch instanceof SelectableChannel) chSelect = (SelectableChannel)ch;
        else chSelect = null;
        if (ch instanceof FileChannel) chFile = (FileChannel)ch;
        else chFile = null;
        if (ch instanceof SocketChannel) chSock = (SocketChannel)ch;
        else chSock = null;
        if (ch instanceof NativeSelectableChannel) chNative = (NativeSelectableChannel)ch;
        else chNative = jepUnixNativeChannel(ch);

        // A connected (not listening) JEP-380 unix-domain SocketChannel gets its
        // actual read/write mechanics replaced too -- see
        // UnixSocketNativeChannel's header comment for the full reasoning (why
        // FFM here, why NOT AbstractInterruptibleChannel-wrapped FFM either).
        // ServerSocketChannel is deliberately excluded: a listening socket is
        // never read from or written to, only accepted on.
        if (chSock != null && org.jruby.ext.socket.UnixDomain.isUnixChannel(ch)) {
            int fd = org.jruby.ext.socket.UnixDomain.fileno(ch);
            if (!FilenoUtil.isFake(fd)) {
                org.jruby.ext.socket.UnixSocketNativeChannel nativeIO =
                        new org.jruby.ext.socket.UnixSocketNativeChannel(ch, fd);
                chRead = nativeIO;
                chWrite = nativeIO;
            }
        }

        if (chNative != null) {
            // we have an ENXIO channel, but need to know if it's a regular file to skip selection
            boolean isFile = false;
            try {
                isFile = posix.fstat(chNative.getFD()).isFile();
            } catch (SystemCallError e) {
                // We check for EPERM due to GH-6129, and because it has only been seen on WSL inotify file descriptors
                // we assume that it means this is not a normal file.
                IRubyObject errno = ((RubySystemCallError) e.getException()).errno();
                var context = errno.getRuntime().getCurrentContext();
                if (errno.isNil() || toInt(context, errno) != Errno.EPERM.intValue()) {
                    // rethrow anything not EPERM
                    throw e;
                }
            }

            if (isFile) {
                chSelect = null;
                isNativeFile = true;
            }
        }
    }

    // WHY: jnr.enxio.channels.NativeSelectableChannel is how this class already
    // recognizes a "real native descriptor" and, on that strength alone, skips
    // the pre-read select() OpenFile#selectForRead otherwise does before every
    // blocking read (that method's own comment: MRI never selects before a
    // blocking rb_read_internal; JRuby only does it for ordinary SelectableChannels
    // so a blocked read can be interrupted without closing the channel -- see
    // RubyThread's wakeup() on native channels for why that already doesn't apply
    // to a native descriptor either way). A JEP-380 unix-domain SocketChannel/
    // ServerSocketChannel (jruby/jruby#9040) is just as real a native descriptor
    // as JNR's own UnixSocketChannel -- FilenoUtil/UnixDomain.fileno already
    // resolves its actual OS fd reflectively via sun.nio.ch.SelChImpl (used today
    // for .fileno, send_io/recv_io) -- it simply doesn't implement JNR's
    // enxio-specific marker interface, so it fell through to the generic
    // SelectableChannel path and paid for a select() on every single blocking
    // read it ever does. Measured cost: ~30% UNIXSocket round-trip throughput on
    // a 256B request/response loop (JRuby-jep-380 repo, kestowv_tier_one.rb
    // benchmark against Kestowv 0.5.6's ipc/channel.rb). Wrapping it here, rather
    // than adding a second "is this native" concept, means every existing
    // chNative consumer (this select-skip, plus PREAD_TASK/PWRITE_TASK's real
    // pread/pwrite) picks it up for free.
    private static NativeSelectableChannel jepUnixNativeChannel(Channel ch) {
        if (!org.jruby.ext.socket.UnixDomain.isUnixChannel(ch)) return null;

        int fd = org.jruby.ext.socket.UnixDomain.fileno(ch);
        if (FilenoUtil.isFake(fd)) return null; // no real fd resolved -- stay on the select path

        return new NativeSelectableChannel() {
            @Override public int getFD() { return fd; }
            @Override public boolean isOpen() { return ch.isOpen(); }
            @Override public void close() throws IOException { ch.close(); }
        };
    }
