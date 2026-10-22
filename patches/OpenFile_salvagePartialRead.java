// General JRuby bug, not specific to unix domain sockets, JEP-380, or FFM --
// reproduces identically on plain TCPSocket, stock JRuby. Found while
// stress-testing interrupt safety for this repo's own unix-socket work (see
// ChannelFD_jepUnixNativeChannel.java / INTERPRETER.md Landmine 13-15).
// Harmless to land either way, so it's kept in the main file-drop table
// rather than split out -- but be accurate about what it does and doesn't
// fix: it closes a real, narrower case (a retry loop's OWN prior iteration
// recording a successful read, then losing it on a LATER iteration's
// abandonment -- confirmed separately via white-box tracing). It does NOT
// close the much larger data-loss rate this repo's stress tests measured
// (16% on this PR's FFM path, 5% on JNR, both pre-existing, neither
// specific to this PR) -- that is a DEEPER, universal mechanism in
// RubyThread#executeReadWrite/postReadWrite that this fix architecturally
// cannot reach (confirmed via a Java-level ledger: n is 0, not >0, in every
// one of those cases). See INTERPRETER.md Landmine 15 for the full
// explanation and why that deeper issue is its own, separate JRuby core
// investigation, not something to bundle here.
//
// Replacement inside core/src/main/java/org/jruby/util/io/OpenFile.java
//
// WHY: Thread#kill/Thread#raise (ThreadKill/RaiseException -- both unchecked
// RuntimeExceptions, see org.jruby.exceptions) can abandon ioBufread's
// accumulating retry loop between iterations, after readInternal has
// ALREADY, irreversibly, consumed `offset` bytes from the real fd straight
// into ptrBytes -- the CALLER's destination array, which this method never
// gets to hand back if nothing intervenes (the kill/raise propagates past
// the normal return). Those bytes vanish from the Ruby-visible world
// forever (already consumed from the kernel, not sitting in the fd to be
// re-read), while bytes NOT yet read remain in the kernel and land on
// whoever reads this IO object next -- silently misdelivering mid-stream
// data into an unrelated, later read. Confirmed on plain TCPSocket, stock
// JRuby, via a large-transfer (2MB+) stress test with a reader thread
// killed/raised at a random point mid-transfer, checked by summing every
// byte any reader ever got against bytes actually sent (a naive string
// comparison produces false positives here: when nobody has consumed
// bytes yet, a later reader legitimately getting earlier-in-stream data
// instead of what a test naively expected is correct stream ordering, not
// corruption).
//
// Same shape, same fix, needed at two more call sites in RubyIO.java --
// see RubyIO_salvagePartialRead.java in this same directory.

    // io_bufread
    private int ioBufread(ThreadContext context, byte[] ptrBytes, int ptr, int len) {
        int offset = 0;
        int n = len;
        int c;

        boolean locked = lock();
        try {
            if (!READ_DATA_PENDING()) {
                outer:
                try {
                    while (n > 0) {
                        again:
                        while (true) {
                            c = readInternal(context, this, fd, ptrBytes, ptr + offset, n);
                            if (c == 0) break outer;
                            if (c < 0) {
                                if (waitReadable(context, fd))
                                    continue again;
                                return -1;
                            }
                            break;
                        }
                        offset += c;
                        if ((n -= c) <= 0) break outer;
                    }
                } catch (RuntimeException re) {
                    // See salvagePartialRead's doc comment for the full WHY.
                    // readInternal has ALREADY, irreversibly, consumed `offset`
                    // bytes from the real fd straight into ptrBytes by the time
                    // a Thread#kill/Thread#raise abandons this loop between
                    // iterations -- preserve them before the kill/raise
                    // continues, or they silently corrupt a later read instead.
                    salvagePartialRead(ptrBytes, ptr, offset);
                    throw re;
                }
                return len - n;
            }

            while (n > 0) {
                c = readBufferedData(ptrBytes, ptr + offset, n);
                if (c > 0) {
                    offset += c;
                    if ((n -= c) <= 0) break;
                }
                checkClosed();
                if (fillbuf(context) < 0) {
                    break;
                }
            }
        } finally {
            if (locked) unlock();
        }
        return len - n;
    }

    /**
     * Preserve bytes already, irreversibly, read from the real fd into a
     * caller-local buffer, when the Ruby-level call that was going to return
     * them is being abandoned by a propagating RuntimeException (in
     * practice: Thread#kill/Thread#raise -- ThreadKill/RaiseException, see
     * org.jruby.exceptions -- landing between one successful native read and
     * this method actually handing the bytes back to Ruby).
     *
     * Without this, those bytes silently vanish from the Ruby-visible
     * stream -- they were already consumed from the kernel, so they are not
     * sitting in the fd waiting to be re-read either -- while any bytes NOT
     * yet read remain in the kernel and get delivered to whoever reads this
     * IO object next, corrupting that later, logically unrelated read with
     * leftover mid-stream data. Confirmed on plain TCPSocket, stock JRuby,
     * nothing channel-type- or method-specific about it: a large-transfer,
     * randomly-timed Thread#kill stress test loses exactly one
     * readpartial-sized chunk; ioBufread (IO#read(length)) has the identical
     * shape for its own accumulating retry loop. Every caller that reads
     * directly from the real fd into a throwaway buffer (not rbuf itself)
     * needs this -- see ioBufread, RubyIO#getPartialCommon (readpartial/
     * read_nonblock), RubyIO#sysread.
     *
     * Deliberately NOT used by pread/IO::Buffer#pread call sites: those read
     * at an explicit, non-sequential offset, so stashing their bytes into
     * rbuf (which represents the NEXT sequential bytes) would actively
     * corrupt a later ordinary read with data from the wrong place in the
     * file/stream -- worse than the data loss this fixes.
     *
     * Only intervenes if rbuf is currently empty: if something else
     * concurrently filled it while we were blocked, don't clobber it --
     * reading the same IO from two threads at once has no ordering
     * guarantee to begin with, so this is "don't make that case worse," not
     * a claim of full correctness under that pathological race.
     */
    public void salvagePartialRead(byte[] bytes, int off, int len) {
        if (len <= 0 || rbuf.len != 0) return;
        if (rbuf.ptr == null || rbuf.capa < len) {
            rbuf.capa = Math.max(len, IO_RBUF_CAPA_FOR());
            rbuf.ptr = new byte[rbuf.capa];
        }
        System.arraycopy(bytes, off, rbuf.ptr, 0, len);
        rbuf.off = 0;
        rbuf.len = len;
    }
