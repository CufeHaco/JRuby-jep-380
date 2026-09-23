// Replacement inside core/src/main/java/org/jruby/util/io/FilenoUtil.java
//
// WHY: UnixFdChannel (see org.jruby.ext.socket.UnixFdChannel) doesn't match
// any of the existing cases here -- it's not a sun.nio.ch.SelChImpl (that's
// only real JDK NIO channels), not a FileChannelImpl, and not one of the
// jnr-unixsocket types this method already special-cases by the same
// pattern. Without this, .fileno on a UNIXSocket.pair/for_fd socket falls
// through to a fake JRuby-internal fd number instead of the real OS fd,
// silently breaking anything that needs the real fd (UnixDomainNative's
// FFM sendIo/recvIo/pathOf, or handing the fd to another process).
//
// This mirrors the existing UnixSocketChannel/UnixServerSocketChannel
// cases immediately below it -- same reflective FileDescriptor-boxing
// technique, same method, just one more channel type.
public static FileDescriptor getDescriptorFromChannel(Channel channel) {
    if (ReflectiveAccess.SEL_CH_IMPL_GET_FD_HANDLE != null && ReflectiveAccess.SEL_CH_IMPL.test(channel)) {
        // Pipe Source and Sink, Sockets, and other several other selectable channels
        try {
            return ReflectiveAccess.SEL_CH_IMPL_GET_FD.apply(channel);
        } catch (Exception e) {
            // return bogus below
        }
    } else if (ReflectiveAccess.FILE_CHANNEL_IMPL_GET_FD_HANDLE != null && ReflectiveAccess.FILE_CHANNEL_IMPL.test(channel)) {
        // FileChannels
        try {
            return ReflectiveAccess.FILE_CHANNEL_IMPL_GET_FD.apply(channel);
        } catch (Exception e) {
            // return bogus below
        }
    } else if (ReflectiveAccess.FILE_DESCRIPTOR_SET_FILENO_HANDLE != null) {
        FileDescriptor unixFD = new FileDescriptor();

        try {
            if (channel instanceof org.jruby.ext.socket.UnixFdChannel) {
                ReflectiveAccess.FILE_DESCRIPTOR_SET_FILENO.accept(unixFD, ((org.jruby.ext.socket.UnixFdChannel) channel).fd());
                return unixFD;
            } else if (channel instanceof UnixSocketChannel) {
                // UNIX sockets, from jnr-unixsocket
                ReflectiveAccess.FILE_DESCRIPTOR_SET_FILENO.accept(unixFD, ((UnixSocketChannel)channel).getFD());
                return unixFD;
            } else if (channel instanceof UnixServerSocketChannel) {
                ReflectiveAccess.FILE_DESCRIPTOR_SET_FILENO.accept(unixFD, ((UnixServerSocketChannel)channel).getFD());
                return unixFD;
            }
        } catch (Exception e) {
            // return bogus below
        }
    }
    return new FileDescriptor();
}
