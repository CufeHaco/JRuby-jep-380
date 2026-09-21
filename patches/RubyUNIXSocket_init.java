// Replacements inside core/src/main/java/org/jruby/ext/socket/RubyUNIXSocket.java
// Keep send_io / recv_io — they already talk POSIX sendmsg on fptr.getFileno().

    @JRubyMethod(meta = true)
    public static IRubyObject for_fd(ThreadContext context, IRubyObject recv, IRubyObject _fileno) {
        int fileno = toInt(context, _fileno);
        RubyClass klass = (RubyClass) recv;
        RubyUNIXSocket unixSocket = (RubyUNIXSocket) Helpers.invoke(context, klass, "allocate");

        if (FilenoUtil.isFake(fileno)) {
            throw typeError(context, "file descriptor is not native");
        }
        jnr.unixsocket.UnixSocketChannel channel = jnr.unixsocket.UnixSocketChannel.fromFD(fileno);
        unixSocket.init_sock(context.runtime, channel);
        return unixSocket;
    }

    @JRubyMethod
    public IRubyObject path(ThreadContext context) {
        String stored = openFile.getPath();
        if (stored != null) return newString(context, stored);

        Channel ch = getChannel();
        try {
            String p = UnixDomain.pathOf(UnixDomain.localAddress(ch));
            if (!p.isEmpty()) return newString(context, p);
        } catch (Exception ignored) {
        }
        if (UnixDomainNative.hasGetsockname()) {
            try {
                int fd = UnixDomain.fileno(ch);
                if (!FilenoUtil.isFake(fd)) {
                    String p = UnixDomainNative.pathOf(fd);
                    if (!p.isEmpty()) return newString(context, p);
                }
            } catch (Exception ignored) {
            }
        }
        return newEmptyString(context);
    }

    @JRubyMethod(name = {"socketpair", "pair"}, optional = 2, checkArity = false, meta = true)
    public static IRubyObject socketpair(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        Arity.checkArgumentCount(context, args, 0, 2);
        final Ruby runtime = context.runtime;
        try {
            Channel[] sp = UnixDomain.pair();
            RubyClass UNIXSocket = Access.getClass(context, "UNIXSocket");

            RubyUNIXSocket sock = (RubyUNIXSocket) Helpers.invoke(context, UNIXSocket, "allocate");
            sock.init_sock(runtime, sp[0], "");

            RubyUNIXSocket sock2 = (RubyUNIXSocket) Helpers.invoke(context, UNIXSocket, "allocate");
            sock2.init_sock(runtime, sp[1], "");

            return newArray(context, sock, sock2);
        } catch (IOException ioe) {
            throw runtime.newIOErrorFromException(ioe);
        }
    }

    protected void init_unixsock(ThreadContext context, IRubyObject _path, boolean server) {
        RubyString strPath = unixsockPathValue(context, _path);
        ByteList path = strPath.getByteList();
        String fpath = Helpers.decodeByteList(context.runtime, path);

        UnixDomain.checkPath(context, fpath);

        java.io.Closeable closeable = null;
        try {
            if (server) {
                ServerSocketChannel channel = UnixDomain.bind(fpath);
                closeable = channel;
                init_sock(context.runtime, channel, fpath);
            } else {
                java.io.File fpathFile = new java.io.File(fpath);
                if (!fpathFile.exists()) {
                    throw context.runtime.newErrnoENOENTError("unix socket");
                }
                SocketChannel channel = UnixDomain.connect(fpath);
                closeable = channel;
                init_sock(context.runtime, channel);
            }
            closeable = null;
        } catch (IOException ioe) {
            throw context.runtime.newIOErrorFromException(ioe);
        } finally {
            if (closeable != null) {
                try { closeable.close(); } catch (IOException ioe2) {}
            }
        }
    }
