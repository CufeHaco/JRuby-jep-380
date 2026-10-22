// Changes inside core/src/main/java/org/jruby/ext/socket/Addrinfo.java
//
// WHY: see RubyBasicSocket_unixAddr.java. MRI reports an unbound socket's
// local_address with protocol 0 ("0.0.0.0 TCP", protocol 0); the normal
// constructor derives protocol 6/17 from the socktype, so unboundLocal resets
// it. inspect() then has to label a protocol-0 inet address by socktype the way
// MRI does, instead of printing protocol 0's name.

    // Addition:
    /**
     * Local address of a socket that has none yet (never bound or connected):
     * the family's wildcard, port 0, protocol 0 -- what getsockname(2) yields
     * and what MRI's local_address reports for it.
     */
    static Addrinfo unboundLocal(Ruby runtime, RubyClass cls, InetSocketAddress wildcard, Sock sock, SocketType socketType) {
        Addrinfo addrinfo = new Addrinfo(runtime, cls, wildcard, sock, socketType);
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
