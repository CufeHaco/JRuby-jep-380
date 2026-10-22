// Changes inside core/src/main/java/org/jruby/util/io/PosixShim.java
//
// WHY: UnixFdChannel (UNIXSocket.pair / for_fd) isn't a SelectableChannel, so
// OpenFile#setNonblock can't flip it and checkForBlocking used to answer every
// read_nonblock on it with a fake EAGAIN without reading -- even with data
// waiting, and at EOF where MRI raises EOFError. write_nonblock did a plain
// blocking write. A regression from routing pair/for_fd through UnixFdChannel
// (JNR's pair channel was selectable). Reads/writes now go to
// UnixFdChannel#readNonBlocking/#writeNonBlocking (real O_NONBLOCK syscalls).
//
// After applying: add `import org.jruby.ext.socket.UnixFdChannel;`.

    // in write(ChannelFD, ByteBuffer, int, int, boolean), replacing
    // `int written = fd.chWrite.write(buffer);`:
            int written = nonblock && fd.ch instanceof UnixFdChannel ufc ?
                    ufc.writeNonBlocking(buffer) : fd.chWrite.write(buffer);

private int performRead(ChannelFD fd, ByteBuffer buffer, boolean nonblock) throws IOException {
        int read = nonblock && fd.ch instanceof UnixFdChannel ufc ?
                ufc.readNonBlocking(buffer) : fd.chRead.read(buffer);

        if (nonblock) {
            if (read == JAVA_EOF) {
                read = NATIVE_EOF; // still treat EOF as EOF
            } else if (read == 0) {
                setErrno(Errno.EAGAIN);
                return -1;
            } else {
                return read;
            }
        } else {
            // NIO channels will always raise for errors, so -1 only means EOF.
            if (read == JAVA_EOF) read = NATIVE_EOF;
        }

        return read;
    }

private boolean checkForBlocking(ChannelFD fd, boolean nonblock) throws IOException {
        if (nonblock) {
            // need to ensure channels that don't support nonblocking IO at least
            // appear to be nonblocking
            if (fd.chSelect != null) {
                // ok...we should have set it nonblocking already in setNonblock
            } else {
                if (fd.chFile != null) {
                    long position = fd.chFile.position();
                    long size = fd.chFile.size();
                    if (position != -1 && size != -1 && position < size) {
                        // there should be bytes available...proceed
                    } else {
                        setErrno(Errno.EAGAIN);
                        return true;
                    }
                } else if (fd.chNative != null && fd.isNativeFile) {
                    // it's a native file, so we don't do selection or nonblock
                } else if (fd.ch instanceof UnixFdChannel) {
                    // UNIXSocket.pair/for_fd: not selectable, but performRead does a
                    // real O_NONBLOCK read(2), so there is no need to fake EAGAIN
                } else {
                    setErrno(Errno.EAGAIN);
                    return true;
                }
            }
        }
        return false;
    }
