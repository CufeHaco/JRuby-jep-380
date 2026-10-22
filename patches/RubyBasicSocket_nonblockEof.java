// Changes inside core/src/main/java/org/jruby/ext/socket/RubyBasicSocket.java
// (and one in RubyUDPSocket.java, at the bottom)
//
// WHY: found by the valenthree pre-PR gate (basicsocket/recv_nonblock_spec hung
// forever; patched/stock/MRI EOF probe):
// - doRead folded EOF (-1) and "no data yet" (0) into one null, so
//   recv_nonblock raised EAGAIN at EOF forever instead of returning nil
//   (pre-existing; TCP too). readChannel keeps the raw count and doReadNonblock
//   returns NONBLOCK_EOF at EOF; recv_nonblock maps it to nil. For a
//   UnixFdChannel stream socket recvfrom(2)==0 is EOF; on a dgram one it is a
//   real empty datagram (doReadNonblock in RubyBasicSocket_unixAddr.java).
// - DatagramChannel.receive's return value (not buffer.position()) now decides
//   "nothing waiting", so a zero-length datagram is "" in recv_nonblock and EOF
//   in read_nonblock, as in MRI (pre-existing).
// - A connected datagram socket's send("") never sent anything: syswrite and the
//   JDK's DatagramChannel.write/send skip the syscall for an empty buffer.
//   sendConnectedDatagram does a real send(2) for that one case (pre-existing).
//
// After applying: add `import org.jruby.exceptions.RaiseException;`.

public IRubyObject recv_nonblock(ThreadContext context, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 1, 4);

        boolean exception = true;
        Ruby runtime = context.runtime;
        IRubyObject opts = ArgsUtil.getOptionsArg(runtime, args);
        if (opts != context.nil) {
            argc--;
            exception = extractExceptionOnlyArg(context, (RubyHash) opts);
        }

        IRubyObject length, flags, str;
        length = flags = context.nil; str = null;

        switch (argc) {
            case 3: str = args[2];
            case 2: flags = args[1];
            case 1: length = args[0];
        }

        // TODO: implement flags

        final ByteBuffer buffer = ByteBuffer.allocate(toInt(context, length));

        ByteList bytes;

        Channel channel = getChannel();

        if (channel instanceof DatagramChannel) {
            try {
                DatagramChannel dgram = (DatagramChannel) channel;

                getOpenFile().setBlocking(runtime, false);

                // receive() returns null only when no datagram was waiting; a
                // zero-length datagram is real data ("" in MRI), not EAGAIN.
                if (dgram.receive(buffer) == null) {
                    bytes = null;
                } else {
                    flipBuffer(buffer);
                    bytes = new ByteList(buffer.array(), buffer.position(), buffer.limit());
                }
            } catch (Exception e) {
                if (exception) throwErrorFromException(runtime, e);
                return context.nil;
            }
        } else {
            bytes = doReadNonblock(context, buffer, exception);
        }

        if (bytes == NONBLOCK_EOF) return context.nil; // MRI: nil once the peer has shut down

        return handleReturnBytes(context, bytes, str, "recvfrom(2)", exception);
    }

private IRubyObject readNonblockCommon(ThreadContext context, DatagramChannel channel, IRubyObject length, IRubyObject str, boolean exception) {
        final ByteBuffer buffer = ByteBuffer.allocate(toInt(context, length));

        ByteList bytes;

        try {
            DatagramChannel dgram = channel;

            getOpenFile().setBlocking(context.runtime, false);

            if (dgram.receive(buffer) == null) {
                bytes = null;
            } else if (buffer.position() == 0) {
                // MRI's read_nonblock reads a zero-length datagram as EOF
                if (!exception) return context.nil;
                throw context.runtime.newEOFError();
            } else {
                flipBuffer(buffer);
                bytes = new ByteList(buffer.array(), buffer.position(), buffer.limit());
            }
        } catch (RaiseException re) {
            throw re;
        } catch (Exception ex) {
            if (exception) throwErrorFromException(context.runtime, ex);
            return context.nil;
        } finally {
            if (Platform.IS_LINUX) {
                // unset nonblocking to emulate recv(... MSG_DONTWAIT) leaving it blocking
                getOpenFile().setBlocking(context.runtime, true);
            }
        }

        return handleReturnBytes(context, bytes, str, "read_nonblock", exception);
    }

protected ByteList doRead(ThreadContext context, final ByteBuffer buffer) {
        return readChannel(context, buffer) <= 0 ? null : new ByteList(buffer.array(), 0, buffer.position(), false);
    }

    /**
     * doReadNonblock's result at EOF (peer has shut down), as opposed to null
     * for "no data yet". doRead folds both into null, which made recv_nonblock
     * raise EAGAIN at EOF forever instead of returning nil.
     */
    private static final ByteList NONBLOCK_EOF = new ByteList(0);

    /** One read into buffer: bytes read, 0 if a non-blocking channel has nothing yet, -1 at EOF. */
    private int readChannel(ThreadContext context, final ByteBuffer buffer) {
        OpenFile fptr;

        fptr = getOpenFileInitialized();
        fptr.checkReadable(context);

        try {
            context.getThread().beforeBlockingCall(context);

            return openFile.readChannel().read(buffer);
        } catch (Exception e) {
            throwErrorFromException(context.runtime, e);
            return -1; // not reached
        } finally {
            context.getThread().afterBlockingCall();
        }
    }

    /**
     * send(2) on a connected DatagramChannel. The JDK's send()/write() return 0
     * without a syscall for an empty buffer, so a zero-length datagram -- which
     * MRI's send(2) does transmit -- goes straight to the fd via FFM.
     */
    protected int sendConnectedDatagram(ThreadContext context, DatagramChannel dgram, ByteBuffer buf) throws IOException {
        if (buf.hasRemaining()) return dgram.write(buf);

        int fd = getOpenFile().fd().realFileno;
        if (fd < 0 || !UnixDomainNative.hasDgram()) return 0; // no real fd/FFM: old behavior

        try {
            return (int) UnixDomainNative.sendTo(fd, ByteList.NULL_ARRAY, 0, 0, null);
        } catch (UnixDomainNative.NativeException ne) {
            throw context.runtime.newErrnoFromInt(ne.errno, "send(2)");
        }
    }

    // RubyUDPSocket.java, inside send(ThreadContext, IRubyObject, IRubyObject),
    // replacing `int written = ((DatagramChannel) this.getChannel()).write(buf);`:
            DatagramChannel dgram = (DatagramChannel) this.getChannel();
            if (!dgram.isConnected()) throw new NotYetConnectedException();
            int written = sendConnectedDatagram(context, dgram, buf);
