// Addition inside core/src/main/java/org/jruby/ext/socket/RubySocket.java
//
// WHY: see RubyBasicSocket_unixAddr.java -- an inet Socket that is not bound
// or connected yet has no local address, and local_address/getsockname must
// report the family wildcard (0.0.0.0 or ::, port 0) like MRI does. Only
// RubySocket knows its family (soDomain), so it supplies the wildcard.
//
// After applying: add `import java.net.InetAddress;`.

    @Override
    protected InetSocketAddress unboundInetAddress() {
        if (soDomain == AddressFamily.AF_INET) return UNBOUND_INET;
        if (soDomain == AddressFamily.AF_INET6) return UNBOUND_INET6;
        return null;
    }

    private static InetSocketAddress wildcard(int addressLength) {
        try {
            return new InetSocketAddress(InetAddress.getByAddress(new byte[addressLength]), 0);
        } catch (UnknownHostException e) {
            throw new AssertionError(e); // only thrown for an illegal address length
        }
    }

    private static final InetSocketAddress UNBOUND_INET = wildcard(4);
    private static final InetSocketAddress UNBOUND_INET6 = wildcard(16);
