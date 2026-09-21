// Replacements inside core/src/main/java/org/jruby/ext/socket/RubyBasicSocket.java
//
// WHY: getpeername/local_address/remote_address/getsockname all funnel through
// getUnixSocketAddress()/getUnixRemoteSocket(), which hard-cast the channel's
// SocketAddress to jnr.unixsocket.UnixSocketAddress. A JEP-380 UnixDomain channel
// returns java.net.UnixDomainSocketAddress instead, so every one of those casts
// silently returns null (NPE downstream) instead of throwing a helpful error.
// Route through UnixDomain.isUnixAddress/pathOf so both address flavors work —
// JNR addresses still occur via UNIXSocket.for_fd on a raw fd.
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
            return new Addrinfo(context.runtime, Access.getClass(context, "Addrinfo"), address, socketType.getSocketType(), socketType);
        }

        SocketAddress unix = getUnixSocketAddress();
        return Addrinfo.unix(context, Access.getClass(context, "Addrinfo"), newString(context, UnixDomain.pathOf(unix)));
    }

    @JRubyMethod
    public IRubyObject remote_address(ThreadContext context) {
        InetSocketAddress address = getInetRemoteSocket(context);

        if (address != null) {
            SocketType socketType = SocketType.forChannel(getChannel());
            return new Addrinfo(context.runtime, Access.getClass(context, "Addrinfo"), address, socketType.getSocketType(), socketType);
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

        return Sockaddr.pack_sockaddr_in(context, 0, "0.0.0.0");
    }
