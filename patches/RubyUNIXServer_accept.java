// Replacements inside core/src/main/java/org/jruby/ext/socket/RubyUNIXServer.java

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
