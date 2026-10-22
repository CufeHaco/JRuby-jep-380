// Replacements inside core/src/main/java/org/jruby/ext/socket/RubyBasicSocket.java
//
// WHY: getpeername/local_address/remote_address/getsockname all funnel through
// getUnixSocketAddress()/getUnixRemoteSocket(), which hard-cast the channel's
// SocketAddress to jnr.unixsocket.UnixSocketAddress. A JEP-380 UnixDomain channel
// returns java.net.UnixDomainSocketAddress instead, so every one of those casts
// silently returns null (NPE downstream) instead of throwing a helpful error.
// Route through UnixDomain.isUnixAddress/pathOf so both address flavors work —
// JNR addresses still occur on the two call sites left on JNR,
// Socket.new(:UNIX, :STREAM) and Socket.socketpair.
//
// Also: local_address on an inet Socket with no local address yet
// (Socket.new(:INET, :STREAM) before bind/connect) fell through to the unix
// branch -- NPE before this patch, a wrong AF_UNIX Addrinfo after the
// isUnixAddress change. It now reports the family wildcard, port 0, protocol 0
// (0.0.0.0 / ::), matching MRI; getsockname uses the same wildcard so an
// unbound AF_INET6 socket reports :: instead of 0.0.0.0. The family comes from
// unboundInetAddress(), overridden in RubySocket_unboundInet.java; see also
// Addrinfo_socketAddress.java.
//
// After applying: remove the now-unused import jnr.unixsocket.UnixSocketAddress.

    @JRubyMethod(name = "getpeername")
    public IRubyObject getpeername(ThreadContext context) {
        InetSocketAddress sock = getInetRemoteSocket(context);

        return sock != null ?
                Sockaddr.pack_sockaddr_in(context, sock) :
                Sockaddr.pack_sockaddr_un(context, UnixDomain.pathOf(getUnixRemoteSocket(context)));
    }

    @JRubyMethod
    public IRubyObject local_address(ThreadContext context) {
        InetSocketAddress address = getInetSocketAddress();

        if (address != null) {
            SocketType socketType = SocketType.forChannel(getChannel());
            return Addrinfo.socketAddress(context.runtime, Access.getClass(context, "Addrinfo"), address, socketType.getSocketType(), socketType);
        }

        SocketAddress unix = getUnixSocketAddress();
        if (unix == null) {
            InetSocketAddress unbound = unboundInetAddress();
            if (unbound != null) {
                SocketType socketType = SocketType.forChannel(getChannel());
                return Addrinfo.socketAddress(context.runtime, Access.getClass(context, "Addrinfo"), unbound, socketType.getSocketType(), socketType);
            }
        }
        return Addrinfo.unix(context, Access.getClass(context, "Addrinfo"), newString(context, UnixDomain.pathOf(unix)));
    }

    /**
     * The wildcard address (port 0) an inet socket reports before it has a
     * local address of its own, e.g. Socket.new(:INET, :STREAM) before
     * bind/connect; null if this isn't an inet socket. Only RubySocket can be
     * in that state, so only it overrides this.
     */
    protected InetSocketAddress unboundInetAddress() {
        return null;
    }

    @JRubyMethod
    public IRubyObject remote_address(ThreadContext context) {
        InetSocketAddress address = getInetRemoteSocket(context);

        if (address != null) {
            SocketType socketType = SocketType.forChannel(getChannel());
            return Addrinfo.socketAddress(context.runtime, Access.getClass(context, "Addrinfo"), address, socketType.getSocketType(), socketType);
        }

        SocketAddress unix = getUnixRemoteSocket(context);

         if (unix == null) throw context.runtime.newErrnoENOTCONNError();

        return Addrinfo.unix(context, Access.getClass(context, "Addrinfo"), newString(context, UnixDomain.pathOf(unix)));
    }

    protected SocketAddress getUnixSocketAddress() {
        SocketAddress socketAddress = getSocketAddress();
        return UnixDomain.isUnixAddress(socketAddress) ? socketAddress : null;
    }

    @Deprecated(since = "10.0.0.0")
    protected SocketAddress getUnixRemoteSocket() {
        return getUnixRemoteSocket(getCurrentContext());
    }

    protected SocketAddress getUnixRemoteSocket(ThreadContext context) {
        SocketAddress socketAddress = getRemoteSocket(context);
        return UnixDomain.isUnixAddress(socketAddress) ? socketAddress : null;
    }

    protected IRubyObject getSocknameCommon(ThreadContext context, String caller) {
        if (getInetSocketAddress() != null) {
            return Sockaddr.pack_sockaddr_in(context, getInetSocketAddress());
        }

        SocketAddress unix = getUnixSocketAddress();
        if (unix != null) {
            return Sockaddr.pack_sockaddr_un(context, UnixDomain.pathOf(unix));
        }

        InetSocketAddress unbound = unboundInetAddress();
        if (unbound != null) return Sockaddr.pack_sockaddr_in(context, unbound);

        return Sockaddr.pack_sockaddr_in(context, 0, "0.0.0.0");
    }

    // WHY: #send on a raw Socket.new(:UNIX, :DGRAM) fd (see
    // RubySocket_unixDgram.java) -- the pre-existing `instanceof
    // DatagramChannel` branch can never match it, since JEP-380 has no
    // Unix-domain DatagramChannel at all (see initChannelFD's doc comment) --
    // the channel is a plain UnixFdChannel wrapping the raw fd. sockaddr here
    // is jnr.unixsocket.UnixSocketAddress, same as doBind (Sockaddr's UNIX
    // address parsing predates this PR and wasn't in scope to replace).
    // After applying: add `import jnr.unixsocket.UnixSocketAddress;`.
    private IRubyObject doSend(ThreadContext context, IRubyObject _mesg, IRubyObject _flags, IRubyObject _to) {
        final SocketAddress sockaddr;

        if (_to instanceof Addrinfo) {
            Addrinfo addr = (Addrinfo) _to;
            sockaddr = addr.getSocketAddress();
        } else if (_to == null || _to.isNil()) {
            sockaddr = null;
        } else {
            sockaddr = Sockaddr.addressFromSockaddr(context, _to);
        }

        RubyString mesg = _mesg.convertToString();
        ByteList mesgByteList = mesg.getByteList();
        ByteBuffer mesgBytes = ByteBuffer.wrap(mesgByteList.unsafeBytes(), mesgByteList.begin(), mesgByteList.realSize());

        Channel channel = getChannel();

        int written = 0;

        // TODO: implement flags
        try {
            if (channel instanceof DatagramChannel && sockaddr != null) {
                written = ((DatagramChannel) channel).send(mesgBytes, sockaddr);

                return asFixnum(context, written);
            } else if (channel instanceof UnixFdChannel ufc && sockaddr != null) {
                // raw Socket.new(:UNIX, :DGRAM) fd -- see RubySocket#initChannelFD.
                // sockaddr here is jnr.unixsocket.UnixSocketAddress, same as
                // doBind (Sockaddr's UNIX address parsing predates this PR).
                String destPath = sockaddr instanceof UnixSocketAddress usa ? usa.path() : null;
                if (destPath == null) throw context.runtime.newErrnoENOPROTOOPTError();
                try {
                    long n = UnixDomainNative.sendTo(ufc.fd(), mesgByteList.unsafeBytes(),
                            mesgByteList.begin(), mesgByteList.realSize(), destPath);
                    return asFixnum(context, (int) n);
                } catch (UnixDomainNative.NativeException ne) {
                    throw context.runtime.newErrnoFromInt(ne.errno, "sendto(2)");
                }
            } else if (channel instanceof DatagramChannel dgram && dgram.isConnected()) {
                // Not syswrite: it skips the syscall for an empty string.
                return asFixnum(context, sendConnectedDatagram(context, dgram, mesgBytes));
            } else if (channel instanceof UnixFdChannel ufc && ufc.sockType() == UnixDomainNative.SOCK_DGRAM) {
                // Same for a connected unix dgram socket (UNIXSocket.pair(:DGRAM)):
                // a real send(2), which unlike write(2) sends a zero-length datagram.
                try {
                    long n = UnixDomainNative.sendTo(ufc.fd(), mesgByteList.unsafeBytes(),
                            mesgByteList.begin(), mesgByteList.realSize(), null);
                    return asFixnum(context, (int) n);
                } catch (UnixDomainNative.NativeException ne) {
                    throw context.runtime.newErrnoFromInt(ne.errno, "send(2)");
                }
            } else {
                return syswrite(context, _mesg);
            }
        } catch (Exception e) {
            throwErrorFromException(context.runtime, e);
            return null; // not reached
        }
    }

    // WHY: #recv_nonblock on any UnixFdChannel (pair()/for_fd/rawSocket, so
    // this covers both stream and raw dgram sockets) reached the generic
    // `if (!(channel instanceof SelectableChannel))` check below and
    // unconditionally raised EAGAIN -- confirmed by testing: it did this
    // even when data was genuinely already available and waiting, not just
    // when it should. UnixFdChannel isn't a SelectableChannel at all, so
    // that check can never distinguish "really would block" from "this
    // channel type isn't supported" for it. recvFromNonBlocking does a real
    // single-attempt recvfrom(2) with the raw fd actually toggled into
    // OS-level O_NONBLOCK (fcntl F_GETFL/F_SETFL) and restored after --
    // works the same for a pair() stream socket (recvfrom(2) on a connected
    // stream socket is just a read, ignoring the sender info UnixDomain
    // discards here) or a raw dgram one.
    protected final ByteList doReadNonblock(ThreadContext context, final ByteBuffer buffer, boolean exception) {
        Channel channel = getChannel();

        // UnixFdChannel (pair()/for_fd/rawSocket) isn't a SelectableChannel,
        // so the generic path below would unconditionally treat it as
        // "doesn't support nonblocking" -- confirmed by testing: it raised
        // EAGAIN even when data was genuinely available, every time, not a
        // real would-block signal. recvFromNonBlocking does a real
        // single-attempt recvfrom(2) with the fd actually toggled into
        // OS-level O_NONBLOCK; works the same for a pair() stream socket
        // (recvfrom(2) on a connected stream socket is just a read,
        // ignoring the sender info) or a raw dgram one.
        if (channel instanceof UnixFdChannel ufc) {
            try {
                UnixDomain.DgramReceived result = UnixDomain.recvFromNonBlocking(context, ufc.fd(), buffer.remaining());
                if (result == null) return null;
                // recvfrom(2) returning 0 is EOF on a stream socket, but a real
                // zero-length datagram on a dgram one.
                if (result.data.isEmpty() && ufc.sockType() != UnixDomainNative.SOCK_DGRAM) return NONBLOCK_EOF;
                return result.data.getByteList();
            } catch (RuntimeException re) {
                if (exception) throw re;
                return null;
            }
        }

        if ( ! (channel instanceof SelectableChannel) ) {
            throw context.runtime.newErrnoEAGAINReadableError(channel.getClass().getName() + " does not support nonblocking");
        }

        SelectableChannel selectable = (SelectableChannel) channel;

        synchronized (selectable.blockingLock()) {
            boolean oldBlocking = selectable.isBlocking();

            try {
                selectable.configureBlocking(false);

                try {
                    int read = readChannel(context, buffer);
                    if (read < 0) return NONBLOCK_EOF;
                    return read == 0 ? null : new ByteList(buffer.array(), 0, buffer.position(), false);
                }
                finally {
                    selectable.configureBlocking(oldBlocking);
                }
            } catch (Exception e) {
                if (exception) throwErrorFromException(context.runtime, e);
                return null;
            }
        }
    }
