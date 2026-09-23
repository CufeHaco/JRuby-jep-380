// Replacements inside core/src/main/java/org/jruby/ext/socket/RubyUNIXSocket.java

    // WHY: was jnr.unixsocket.UnixSocketChannel.fromFD(fileno) -- JEP-380 has
    // no public API to wrap an existing raw fd, so this is the one place
    // that still needs a hand-built Channel. UnixFdChannel (FFM read/write,
    // see its own doc comment) replaces the JNR one; nothing else changes.
    @JRubyMethod(meta = true)
    public static IRubyObject for_fd(ThreadContext context, IRubyObject recv, IRubyObject _fileno) {
        int fileno = toInt(context, _fileno);
        RubyClass klass = (RubyClass) recv;
        RubyUNIXSocket unixSocket = (RubyUNIXSocket) Helpers.invoke(context, klass, "allocate");

        if (FilenoUtil.isFake(fileno)) {
            throw typeError(context, "file descriptor is not native");
        }
        Channel channel = UnixDomainNative.channelFromFd(fileno);
        unixSocket.init_sock(context.runtime, channel);
        return unixSocket;
    }

    // WHY: was context.runtime.getPosix().sendmsg/recvmsg (jnr-posix) --
    // UnixDomainNative.sendIo/recvIo (FFM sendmsg/recvmsg with SCM_RIGHTS)
    // do the exact same job. Wrapped in UnixDomainContext.blocking for the
    // same per-ThreadContext errno capture already used elsewhere in this
    // PR (WeakHashMap-keyed, not ThreadLocal, since Fibers swap ThreadContext
    // -- see UnixDomainContext's own doc comment), not a new mechanism.
    @JRubyMethod
    public IRubyObject send_io(ThreadContext context, IRubyObject arg) {
        OpenFile fptr = getOpenFileChecked();
        int fd;

        if (arg.callMethod(context, "kind_of?", ioClass(context)).isTrue()) {
            fd = ((RubyIO) arg).getOpenFileChecked().getFileno();
        } else if (arg.callMethod(context, "kind_of?", fixnumClass(context)).isTrue()) {
            fd = ((RubyFixnum) arg).asInt(context);
        } else {
            throw typeError(context, "neither IO nor file descriptor");
        }

        if (FilenoUtil.isFake(fd)) throw typeError(context, "file descriptor is not native");

        int sockFd = fptr.getFileno();
        if (FilenoUtil.isFake(sockFd)) throw typeError(context, "file descriptor is not native");

        final int passFd = fd;
        boolean locked = fptr.lock();
        try {
            while (true) {
                try {
                    UnixDomainContext.blocking(context, sockFd, "sendmsg",
                            () -> UnixDomainNative.sendIo(sockFd, passFd));
                    break;
                } catch (Exception e) {
                    int errno = UnixDomainContext.lastErrno(context);
                    if (errno == UnixDomainNative.EAGAIN && fptr.waitWritable(context)) continue;
                    throw context.runtime.newErrnoFromInt(errno, "sendmsg(2)");
                }
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return context.nil;
    }

    @JRubyMethod(optional = 2, checkArity = false)
    public IRubyObject recv_io(ThreadContext context, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 0, 2);
        OpenFile fptr = getOpenFileChecked();
        IRubyObject klass = argc > 0 ? args[0] : ioClass(context);
        IRubyObject mode = argc > 1 ? args[1] : context.nil;

        int sockFd = fptr.getFileno();
        if (FilenoUtil.isFake(sockFd)) throw typeError(context, "file descriptor is not native");

        int receivedFd;
        boolean locked = fptr.lock();
        try {
            while (true) {
                try {
                    receivedFd = UnixDomainContext.blocking(context, sockFd, "recvmsg",
                            () -> UnixDomainNative.recvIo(sockFd));
                    break;
                } catch (Exception e) {
                    int errno = UnixDomainContext.lastErrno(context);
                    if (errno == UnixDomainNative.EAGAIN && fptr.waitReadable(context)) continue;
                    throw context.runtime.newErrnoFromInt(errno, "recvmsg(2)");
                }
            }
        } finally {
            if (locked) fptr.unlock();
        }

        IRubyObject fdObj = asFixnum(context, receivedFd);

        if (klass.isNil()) {
            return fdObj;
        } else if (mode.isNil()) {
            return Helpers.invoke(context, klass, "for_fd", fdObj);
        } else {
            return Helpers.invoke(context, klass, "for_fd", fdObj, mode);
        }
    }

    // WHY: getUnixRemoteSocket(context) can now return a JEP-380
    // UnixDomainSocketAddress, not just JNR's UnixSocketAddress — .path()
    // only exists on the JNR type. Use UnixDomain.pathOf, which handles both.
    @JRubyMethod
    public IRubyObject peeraddr(ThreadContext context) {
        final String _path = UnixDomain.pathOf(getUnixRemoteSocket(context));
        final RubyString path = _path.isEmpty() ? newEmptyString(context) : newString(context, _path);
        return newArray(context, newString(context, "AF_UNIX"), path);
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
