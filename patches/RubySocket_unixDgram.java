// Replacements inside core/src/main/java/org/jruby/ext/socket/RubySocket.java
//
// WHY: Socket.new(:UNIX, :DGRAM) is unrelated to the UNIXSocket/UNIXServer
// migration -- it's the generic Socket class -- but it's the last of the
// original 9 pre-existing (verified against stock jruby/jruby, not caused
// by this PR) unixsocket/unixserver spec failures/errors. initChannelFD's
// SOCK_DGRAM case never branched on PF_UNIX/PF_LOCAL at all (unlike its own
// SOCK_STREAM case just above it), so it silently built a regular
// DatagramChannel.open() -- an ordinary INET UDP socket -- for a UNIX
// domain request. doBind then hit that channel's DatagramSocket#bind with
// a UNIX sockaddr and threw an UnsupportedAddressTypeException with a null
// message, surfacing to Ruby as a content-free "SocketError: No message
// available".
//
// JEP-380 has no Unix-domain DatagramChannel at all --
// DatagramChannel.open(ProtocolFamily) rejects UNIX outright -- so this
// needs raw socket(2)/bind(2) via FFM (UnixDomainNative), same as
// pair()/for_fd: wrap the resulting fd with the existing channelFromFd,
// i.e. a plain UnixFdChannel. See UnixDomainNative#rawSocket's doc comment
// for why UnixFdChannel (bind/send/recv-fd-only dispatch) is enough here
// without a dedicated datagram channel class.

    protected ChannelFD initChannelFD(Ruby runtime) {
        try {
            Channel channel;
            switch (soType) {
                case SOCK_STREAM:
                    if ( soProtocolFamily == ProtocolFamily.PF_UNIX ||
                         soProtocolFamily == ProtocolFamily.PF_LOCAL ) {
                        channel = UnixSocketChannel.open();
                    }
                    else if ( soProtocolFamily == ProtocolFamily.PF_INET ||
                              soProtocolFamily == ProtocolFamily.PF_INET6 ||
                              soProtocolFamily == ProtocolFamily.PF_UNSPEC ) {
                        channel = SocketChannel.open();
                    }
                    else {
                        throw argumentError(runtime.getCurrentContext(), "unsupported protocol family '" + soProtocolFamily + "'");
                    }
                    break;
                case SOCK_DGRAM:
                    if ( soProtocolFamily == ProtocolFamily.PF_UNIX ||
                         soProtocolFamily == ProtocolFamily.PF_LOCAL ) {
                        try {
                            int fd = UnixDomainNative.rawSocket(UnixDomainNative.AF_UNIX, UnixDomainNative.SOCK_DGRAM, 0);
                            channel = UnixDomainNative.channelFromFd(fd);
                        } catch (UnixDomainNative.NativeException ne) {
                            throw sockerr(runtime, "initialize: " + ne.getMessage(), ne);
                        }
                    } else {
                        channel = DatagramChannel.open();
                    }
                    break;
                default:
                    throw argumentError(runtime.getCurrentContext(), "unsupported socket type '" + soType + "'");
            }

            return newChannelFD(runtime, channel);
        }
        catch (IOException e) {
            throw sockerr(runtime, "initialize: " + e.toString(), e);
        }
    }

    protected void doBind(ThreadContext context, SocketAddress iaddr) {
        Ruby runtime = context.runtime;

        Channel channel = getChannel();

        try {
            if (channel instanceof SocketChannel) {
                Socket socket = ((SocketChannel) channel).socket();
                socket.bind(iaddr);
            }
            else if (channel instanceof UnixSocketChannel) {
                // do nothing
            }
            else if (channel instanceof UnixFdChannel ufc) {
                // raw Socket.new(:UNIX, :DGRAM) fd (see initChannelFD above)
                // -- iaddr here is jnr.unixsocket.UnixSocketAddress
                // (Sockaddr's UNIX address parsing predates this PR and
                // wasn't in scope to replace), not JEP-380's
                // UnixDomainSocketAddress.
                String path = iaddr instanceof UnixSocketAddress usa ? usa.path() : null;
                if (path == null) throw runtime.newErrnoENOPROTOOPTError();
                try {
                    UnixDomainNative.bindPath(ufc.fd(), path);
                } catch (UnixDomainNative.NativeException ne) {
                    throw sockerr(runtime, "bind(2): " + ne.getMessage(), ne);
                }
            }
            else if (channel instanceof DatagramChannel) {
                DatagramSocket socket = ((DatagramChannel) channel).socket();
                socket.bind(iaddr);
            }
            else {
                throw runtime.newErrnoENOPROTOOPTError();
            }
        }
        catch (UnknownHostException e) {
            throw SocketUtils.sockerr(runtime, "bind(2): unknown host");
        }
        catch (SocketException e) {
            throw buildSocketException(runtime, e, "bind(2)", iaddr); // throws
        }
        catch (IOException e) {
            throw sockerr(runtime, "bind(2): name or service not known", e);
        }
        catch (IllegalArgumentException e) {
            throw sockerr(runtime, e.getMessage(), e);
        }
    }
