// Addition inside core/src/main/java/org/jruby/runtime/Helpers.java,
// in the switch statement inside the private errnoFromMessage(Throwable)
// method (used by errnoFromException, which UnixFdChannel's errno-message
// mapping -- see its own ERRNO_MESSAGES table -- relies on to actually
// surface Errno::* from a caught IOException; JRuby has no other way to
// see the real errno from a generic exception).
//
// WHY: UnixFdChannel's ERRNO_MESSAGES maps EDESTADDRREQ/ENOTCONN to the
// standard glibc strerror() text for each, but adding entries to that
// table alone does nothing if this switch -- the thing that actually
// turns the message into an Errno:: class -- doesn't recognize the same
// string. Both are real, reachable conditions on a raw
// Socket.new(:UNIX, :DGRAM) fd (see RubySocket_unixDgram.java): #send
// with no destination on an unconnected one. Verified empirically (not
// assumed from POSIX docs, which point at EDESTADDRREQ generically) that
// Linux's actual AF_UNIX write(2)-without-connect(2) returns ENOTCONN
// specifically, not EDESTADDRREQ -- both are added since EDESTADDRREQ is
// still a real, standard errno that could plausibly occur through some
// other path even though it wasn't the one this PR's own repro hit.
//
// Purely additive: two new case labels matching two new, previously
// unmatched literal strings. Cannot change behavior for any string this
// switch already matched. Verified against spec/ruby/library/socket's
// option/constants/ancillarydata directories (byte-for-byte identical
// counts before/after) plus the full unixsocket/unixserver suite.

                case "Protocol family not supported":
                    return Errno.EPFNOSUPPORT;
                case "Destination address required":
                    return Errno.EDESTADDRREQ;
                case "Transport endpoint is not connected":
                    return Errno.ENOTCONN;
