// Addition inside core/src/main/java/org/jruby/util/io/OpenFile.java, right
// after isBlocking().
//
// WHY: found via spec/ruby/library/socket/unixsocket/initialize_spec.rb's
// "sets the socket to nonblock" check -- the official, shared ruby/spec
// suite both MRI and JRuby run, not a JRuby-only addition. A freshly-opened
// UNIXSocket must report IO#nonblock? == true (MRI's own rationale: every
// fd is non-blocking internally, rb_thread_io_blocking_region, regardless
// of what blocking behavior Ruby code actually observes). RubyUNIXSocket_init.java's
// change (leaving a named JEP-380 unix channel's real fd genuinely blocking,
// for UnixSocketNativeChannel's FFM fast path -- see
// ChannelFD_jepUnixNativeChannel.java) means OpenFile#setBlocking can no
// longer be used for this case: it couples the Ruby-visible nonblock flag
// to the real channel's OS-level blocking mode (fd.chSelect.configureBlocking(...)),
// and flipping that mode for real would force the connect-path select()
// back into the hot loop, undoing the whole point of the FFM path.
//
// This method sets ONLY the bookkeeping flag, leaving the real channel's
// mode untouched. Confirmed safe for OpenFile#performRead/performWrite's
// own nonblock branch too: it only ever special-cases a literal 0 return as
// EAGAIN, and UnixSocketNativeChannel already maps real EOF to -1 (the
// ReadableByteChannel contract, via UnixFdChannel#rawRead) instead of a
// literal 0 -- so there is nothing for this flag to misroute.
//
// After applying: spec/ruby/library/socket/unixsocket/initialize_spec.rb
// ("sets the socket to nonblock") passes; verified no regression across
// the rest of spec/ruby/library/socket/{unixsocket,unixserver}.

    /**
     * Mark the Ruby-visible "nonblock?" bookkeeping flag without touching
     * the real channel's actual OS-level blocking mode (setBlocking's own
     * fd.chSelect.configureBlocking(...) call). For a channel whose real
     * read/write already goes around performRead/performWrite's own
     * chSelect-based machinery entirely (see UnixSocketNativeChannel), the
     * two are legitimately independent: MRI reports every fd as
     * non-blocking internally regardless of what blocking behavior Ruby
     * code actually observes (rb_thread_io_blocking_region) -- IO#nonblock?
     * mirrors that MRI-visible bookkeeping fact, which has nothing to do
     * with whether THIS JVM's underlying fd happens to be configured
     * O_NONBLOCK or not. Safe for performRead/performWrite's own nonblock
     * branch too: it only ever special-cases a literal 0 return as EAGAIN,
     * and a channel that already maps real EOF to -1 (the ReadableByteChannel
     * contract -- see UnixFdChannel#rawRead) never produces that literal 0
     * in the first place, so there is nothing for this flag to misroute.
     */
    public void setNonblockFlag(boolean nonblock) {
        boolean locked = lock();
        try {
            this.nonblock = nonblock;
        } finally {
            if (locked) unlock();
        }
    }
