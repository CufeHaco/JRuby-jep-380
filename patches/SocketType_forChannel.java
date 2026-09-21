// Drop-in replacement for SocketType.forChannel + the UNIX enum branch.
// File: core/src/main/java/org/jruby/ext/socket/SocketType.java
//
// WHY: JEP-380 Unix SocketChannel IS a java.nio.channels.SocketChannel.
// Current forChannel() therefore classifies it as SOCKET (TCP) and later
// calls ((SocketChannel)ch).socket() which throws UOE on Unix-domain
// channels. Detect Unix-domain first, and never call .socket() on them.

    UNIX_NIO(Sock.SOCK_STREAM) {
        public SocketAddress getRemoteSocketAddress(Channel channel) {
            try {
                return UnixDomain.remoteAddress(channel);
            } catch (IOException ioe) {
                return null;
            }
        }

        public SocketAddress getLocalSocketAddress(Channel channel) {
            try {
                return UnixDomain.localAddress(channel);
            } catch (IOException ioe) {
                return null;
            }
        }

        public void shutdownInput(Channel channel) throws IOException {
            if (channel instanceof SocketChannel sc) sc.shutdownInput();
        }

        public void shutdownOutput(Channel channel) throws IOException {
            if (channel instanceof SocketChannel sc) sc.shutdownOutput();
        }
    },

    public static SocketType forChannel(Channel channel) {
        if (UnixDomain.isUnixChannel(channel)) {
            return UNIX_NIO;
        }
        if (channel instanceof SocketChannel) {
            return SOCKET;
        }
        if (channel instanceof ServerSocketChannel) {
            return SERVER;
        }
        if (channel instanceof DatagramChannel) {
            return DATAGRAM;
        }
        if (channel instanceof jnr.unixsocket.UnixSocketChannel
                || channel instanceof jnr.unixsocket.UnixServerSocketChannel) {
            return UNIX;
        }
        return UNKNOWN;
    }
