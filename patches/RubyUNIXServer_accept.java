// Replacements inside core/src/main/java/org/jruby/ext/socket/RubyUNIXServer.java
// After applying: remove the now-unused imports
// jnr.unixsocket.UnixServerSocketChannel / UnixSocketAddress / UnixSocketChannel,
// and add java.nio.channels.Channel.

    @JRubyMethod
    public IRubyObject accept(ThreadContext context) {
        try {
            while (true) {
                boolean ready = context.getThread().select(this, SelectionKey.OP_ACCEPT);
                if (!ready) {
                    context.pollThreadEvents();
                } else {
                    SocketChannel socketChannel = UnixDomain.accept(asUnixServer());
                    RubyUNIXSocket sock = (RubyUNIXSocket) Helpers.invoke(
                            context, Access.getClass(context, "UNIXSocket"), "allocate");
                    sock.init_sock(context.runtime, socketChannel, "");
                    return sock;
                }
            }
        } catch (IOException ioe) {
            throw context.runtime.newIOErrorFromException(ioe);
        }
    }

    public IRubyObject accept_nonblock(ThreadContext context, Ruby runtime, boolean ex) {
        SelectableChannel selectable = (SelectableChannel) getChannel();
        synchronized (selectable.blockingLock()) {
            boolean oldBlocking = selectable.isBlocking();
            try {
                selectable.configureBlocking(false);
                try {
                    SocketChannel socketChannel = ((ServerSocketChannel) selectable).accept();
                    if (socketChannel == null) {
                        if (!ex) return asSymbol(context, "wait_readable");
                        throw runtime.newErrnoEAGAINReadableError("accept(2) would block");
                    }
                    socketChannel.configureBlocking(true);
                    RubyUNIXSocket sock = (RubyUNIXSocket) Helpers.invoke(
                            context, Access.getClass(context, "UNIXSocket"), "allocate");
                    sock.init_sock(context.runtime, socketChannel, "");
                    return sock;
                } finally {
                    selectable.configureBlocking(oldBlocking);
                }
            } catch (IOException ioe) {
                if (ioe.getMessage() != null
                        && ioe.getMessage().contains("Resource temporarily unavailable")) {
                    if (!ex) return asSymbol(context, "wait_readable");
                    throw runtime.newErrnoEAGAINReadableError("accept");
                }
                throw context.runtime.newIOErrorFromException(ioe);
            }
        }
    }

    @JRubyMethod
    public IRubyObject sysaccept(ThreadContext context) {
        RubyUNIXSocket socket = (RubyUNIXSocket) accept(context);
        return asFixnum(context, UnixDomain.fileno(socket.getChannel()));
    }

    private ServerSocketChannel asUnixServer() {
        return (ServerSocketChannel) getChannel();
    }

    // WHY: these overrides used to hard-cast getChannel() to JNR's
    // UnixServerSocketChannel. Under JEP-380 the channel is a plain JDK
    // ServerSocketChannel, so that cast threw ClassCastException on every
    // call. Route through SocketType.forChannel, which already knows how to
    // fetch the local address for either channel flavor (see SocketType_forChannel.java).
    @Override
    protected SocketAddress getUnixSocketAddress() {
        Channel channel = getChannel();
        SocketAddress socketAddress = SocketType.forChannel(channel).getLocalSocketAddress(channel);
        return UnixDomain.isUnixAddress(socketAddress) ? socketAddress : null;
    }

    @Override
    protected SocketAddress getUnixRemoteSocket(ThreadContext context) {
        return getUnixSocketAddress();
    }

    // WHY: delete the stock path() override entirely (let RubyUNIXServer
    // inherit RubyUNIXSocket#path). Stock path() was `return
    // newString(context, openFile.getPath())` -- openFile.getPath() is null
    // for a server built via for_fd (no path was ever bound to it here),
    // and newString(context, null) throws a raw NullPointerException.
    // RubyUNIXSocket#path (see RubyUNIXSocket_init.java) already falls back
    // through UnixDomain.localAddress / UnixDomainNative.pathOf / "" for
    // exactly that null case. addr() below reuses that fallback instead of
    // repeating the same null-unsafe openFile.getPath() call.
    @JRubyMethod
    public IRubyObject addr(ThreadContext context) {
        return newArray(context, newString(context, "AF_UNIX"), path(context));
    }
