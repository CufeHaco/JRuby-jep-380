// Replacement inside core/src/main/java/org/jruby/ext/socket/RubyUDPSocket.java
// After applying: add `import static org.jruby.api.Create.newEmptyString;`
// (newArray/newString are already statically imported in this file).
//
// WHY: found while auditing the DGRAM feature for other gaps, not by a
// failing spec. Socket.new(:UNIX, :DGRAM)'s recvfrom(2) support
// (RubySocket_unixDgram.java) was only ever exercised through
// UNIXSocket.for_fd-wrapped fds (RubyUNIXSocket#recvfrom). Calling
// #recvfrom directly on the raw Socket object -- the much more natural
// way to use it -- goes through this shared static method instead
// (RubySocket#recvfrom -> RubyUDPSocket.recvfrom), which hits a hard
// `(DatagramChannel) socket.getChannel()` cast a few lines down in
// doReceive/doReceiveTuple. UnixFdChannel isn't a DatagramChannel, so
// this crashed with a raw ClassCastException surfaced as a
// content-free "SocketError", not a clean Ruby-level error -- confirmed
// with a direct repro before fixing.
//
// UnixFdChannel only ever reaches this method via RubySocket
// (SOCK_DGRAM + PF_UNIX/PF_LOCAL branch of initChannelFD) -- the
// SOCK_STREAM branch uses a real JNR UnixSocketChannel -- so, unlike
// RubyUNIXSocket#recvfrom, no socket-type check is needed here: if the
// channel is a UnixFdChannel at all in this reachable code path, it's
// always the dgram case.
//
// The actual native recvfrom(2)+EAGAIN-retry-loop lives in
// UnixDomain.recvFromBlocking, shared with RubyUNIXSocket#recvfrom so
// the retry shape (same as send_io/recv_io) isn't duplicated between
// the two entry points into the same underlying fd type.
//
// recvfrom_nonblock (the private recvfrom_nonblock overload below) had
// the identical (DatagramChannel) cast in doReceiveNonblockTuple and the
// identical crash for a raw UNIX dgram socket -- fixed the same session,
// using UnixDomain.recvFromNonBlocking (fcntl F_GETFL/F_SETFL toggles the
// raw fd into real OS-level O_NONBLOCK around a single recvfrom(2)
// attempt, then restores it -- UnixDomainNative.recvFrom has no
// configureBlocking(false) equivalent of its own, so this has to be done
// by hand). Also fixed the same bug's non-crashing sibling,
// RubyBasicSocket#recv_nonblock, which silently returned EAGAIN even with
// data genuinely available -- see RubyBasicSocket_unixAddr.java.

    public static IRubyObject recvfrom(RubyBasicSocket socket, ThreadContext context, IRubyObject length) {
        final Ruby runtime = context.runtime;

        if (socket.getChannel() instanceof UnixFdChannel ufc) {
            UnixDomain.DgramReceived result = UnixDomain.recvFromBlocking(
                    context, socket.getOpenFileChecked(), ufc.fd(), toInt(context, length));
            RubyString path = result.srcPath.isEmpty() ? newEmptyString(context) : newString(context, result.srcPath);
            IRubyObject address = newArray(context, newString(context, "AF_UNIX"), path);
            return newArray(context, result.data, address);
        }

        try {
            ReceiveTuple tuple = doReceiveTuple(socket, runtime, false, toInt(context, length));

            IRubyObject addressArray = socket.addrFor(context, tuple.sender, false);

            return newArray(context, tuple.result, addressArray);
        }
        catch (UnknownHostException e) {
            throw SocketUtils.sockerr(runtime, "recvfrom: name or service not known");
        }
        catch (PortUnreachableException e) {
            throw runtime.newErrnoECONNREFUSEDError();
        }
        catch (IOException e) { // SocketException
            throw runtime.newIOErrorFromException(e);
        }
        catch (RaiseException e) { throw e; }
        catch (Exception e) {
            throw sockerr(runtime, e.getLocalizedMessage(), e);
        }
    }

    private static IRubyObject recvfrom_nonblock(RubyBasicSocket socket, ThreadContext context,
                                                 IRubyObject length, IRubyObject flags, IRubyObject str, boolean exception) {
        if (socket.getChannel() instanceof UnixFdChannel ufc) {
            UnixDomain.DgramReceived result = UnixDomain.recvFromNonBlocking(context, ufc.fd(), toInt(context, length));
            if (result == null) {
                if (!exception) return Convert.asSymbol(context, "wait_readable");
                throw context.runtime.newErrnoEAGAINReadableError("recvfrom(2)");
            }
            IRubyObject data = result.data;
            if (str != null && !str.isNil()) {
                str = str.convertToString();
                ((RubyString) str).setValue(result.data.getByteList());
                data = str;
            }
            RubyString path = result.srcPath.isEmpty() ? newEmptyString(context) : newString(context, result.srcPath);
            IRubyObject addressArray = newArray(context, newString(context, "AF_UNIX"), path);
            return newArray(context, data, addressArray);
        }

        try {
            ReceiveTuple tuple = doReceiveNonblockTuple(socket, context.runtime, toInt(context, length));

            if (tuple == null) {
                if (!exception) return Convert.asSymbol(context, "wait_readable");
                throw context.runtime.newErrnoEAGAINReadableError("recvfrom(2)");
            }

            // TODO: make this efficient
            if (str != null && !str.isNil()) {
                str = str.convertToString();
                ((RubyString) str).setValue(tuple.result.getByteList());
            } else {
                str = tuple.result;
            }

            IRubyObject addressArray = socket.addrFor(context, tuple.sender, false);

            return newArray(context, str, addressArray);
        } catch (UnknownHostException e) {
            throw SocketUtils.sockerr(context.runtime, "recvfrom: name or service not known");
        } catch (PortUnreachableException e) {
            throw context.runtime.newErrnoECONNREFUSEDError();
        } catch (IOException e) { // SocketException
            throw context.runtime.newIOErrorFromException(e);
        } catch (RaiseException e) {
            throw e;
        } catch (Exception e) {
            throw sockerr(context.runtime, e.getLocalizedMessage(), e);
        }
    }
