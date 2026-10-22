// Changes inside core/src/main/java/org/jruby/ext/socket/Addrinfo.java
//
// WHY: MRI builds a socket's own local_address/remote_address from
// getsockname(2)/getpeername(2) plus the socktype, with protocol 0 -- for a
// bound/connected socket as well as an unbound one's wildcard (see
// RubyBasicSocket_unixAddr.java). The normal constructor derives protocol 6/17
// from the socktype, so socketAddress resets it (basicsocket/local_address_spec,
// remote_address_spec "uses 0 as the protocol"). inspect() then has to label a protocol-0 inet address by socktype the way
// MRI does, instead of printing protocol 0's name.

    // Addition:
    /**
     * A socket's own local_address/remote_address. MRI builds these from
     * getsockname(2)/getpeername(2) plus the socktype, with protocol 0 -- not
     * the 6/17 the general constructor derives from the socktype. Also used for
     * an unbound inet socket's wildcard (0.0.0.0 / ::, port 0).
     */
    static Addrinfo socketAddress(Ruby runtime, RubyClass cls, InetSocketAddress address, Sock sock, SocketType socketType) {
        Addrinfo addrinfo = new Addrinfo(runtime, cls, address, sock, socketType);
        addrinfo.protocol = Protocol.getProtocolByNumber(0);
        return addrinfo;
    }

    // Replacement in inspect(), for the inet stream/dgram branch:
        if ((pfamily == PF_INET || pfamily == PF_INET6) && (sock == SOCK_STREAM || sock == SOCK_DGRAM)) {
            // MRI labels a protocol-0 inet address by its socktype (e.g. an
            // unbound socket's local_address is "0.0.0.0 TCP", protocol 0).
            String name = protocol == null || protocol.getProto() == 0 ?
                    (sock == SOCK_STREAM ? "TCP" : "UDP") : protocol.getName().toUpperCase();
            val.append(" ").append(name);
