# Interpreter path (this branch)

Branch: `jep380-ffm-threadcontext`
Base: `CufeHaco-JEP-380-full-prototype`

This branch keeps the full prototype and adds the JRuby-core landing
shape for #9040: JEP-380 channel + FFM connection + CRuby thread frame.

## Why this is not the old factory

`UnixSocketChannelFactory` + `RubyUNIXSocketChannel` is a second IO
stack. JRuby UNIXSocket already does:

```
init_unixsock → Channel → init_sock → newChannelFD → OpenFile
```

The interpreter change is: open a JDK Unix `SocketChannel`, hand it to
`init_sock`. Do not wrap a second channel interface.

## Files to drop into jruby/jruby

| Path in this repo | Path in jruby/jruby |
|---|---|
| `core/src/main/java/org/jruby/ext/socket/UnixDomain.java` | same |
| `core/src/main/java/org/jruby/ext/socket/UnixDomainNative.java` | same |
| `core/src/main/java/org/jruby/ext/socket/UnixDomainContext.java` | same |
| `core/src/main/java/org/jruby/ext/socket/UnixFdChannel.java` | same |
| `patches/SocketType_forChannel.java` | edit `SocketType.java` |
| `patches/RubyUNIXSocket_init.java` | edit `RubyUNIXSocket.java` |
| `patches/RubyUNIXServer_accept.java` | edit `RubyUNIXServer.java` |
| `patches/RubyBasicSocket_unixAddr.java` | edit `RubyBasicSocket.java` |
| `patches/FilenoUtil_getDescriptorFromChannel.java` | edit `FilenoUtil.java` |

TCP/SSL files on this branch stay as prototype history. They do not
belong in the #9040 PR.

## Landmine 2

`RubyBasicSocket`'s `getUnixSocketAddress()`/`getUnixRemoteSocket()` used to
assume every UNIX `SocketAddress` is JNR's `jnr.unixsocket.UnixSocketAddress`.
A JEP-380 channel's local/remote address is JDK's `UnixDomainSocketAddress`
instead — same for `RubyUNIXServer`'s overrides, which used to hard-cast
`getChannel()` to JNR's `UnixServerSocketChannel`. Both are wrong under this
patch and throw (NPE / ClassCastException) on `peeraddr`, `getpeername`,
`getsockname`, `local_address`, `remote_address`. Route through
`UnixDomain.isUnixAddress` / `UnixDomain.pathOf`. (JNR addresses used to
also occur via `UNIXSocket.for_fd` on a raw fd; `for_fd` now uses
`UnixFdChannel` instead, so JNR no longer appears here at all -- see
Landmine 3.)

## Landmine 3

Getting `for_fd`/`UNIXSocket.pair` fully off JNR needs three more pieces
beyond `UnixFdChannel` itself, each independently silent-broken if missed:

- **`FilenoUtil.getDescriptorFromChannel`** doesn't know about
  `UnixFdChannel` by default -- it only recognizes `sun.nio.ch.SelChImpl`,
  `FileChannelImpl`, and JNR's Unix channel types. Without teaching it
  the new type, `.fileno` on a `pair`/`for_fd` socket silently returns a
  *fake* JRuby-internal fd number instead of the real OS fd, and anything
  needing the real fd (`sendIo`/`recvIo`, handing the fd to another
  process) breaks with `EBADF`.
- **`SocketType.forChannel`** doesn't recognize `UnixFdChannel` either
  (`UnixDomain.isUnixChannel` only checks real `SocketChannel`/
  `ServerSocketChannel`), so it falls through to `UNKNOWN`, whose
  `shutdownOutput`/`shutdownInput` are silent no-ops. `close_write` on a
  `pair()` socket then does nothing, so the peer's blocking `read` never
  sees EOF -- **hangs forever**, not an exception. `UNIX_NIO`'s
  `shutdownInput`/`shutdownOutput` need an `else if (channel instanceof
  UnixFdChannel ufc) UnixDomainNative.shutdown(ufc.fd(), SHUT_RD/SHUT_WR)`
  branch alongside the existing `SocketChannel` one.
- **`UnixDomain.localAddress`/`remoteAddress`** return `null` for
  `UnixFdChannel` by default (it's neither a `NetworkChannel` nor a
  `SocketChannel`), which reads as "not connected" upstream --
  `getpeername`/`remote_address` then throw `ENOTCONN` on a `pair()`
  socket that's actively connected, just unnamed. MRI reports that as
  `["AF_UNIX", ""]`. Fix: return `UnixDomainSocketAddress.of("")` (a
  legal, empty-path address) for `UnixFdChannel` instead of `null`.

All three were caught by the same mechanism: `spec/ruby/library/socket`'s
`unixsocket`/`unixserver` suite, run against a real build, not eyeballed.

## Split

```
named UNIXSocket / UNIXServer / accept / rw / select
    UnixDomain                 JEP-380 SocketChannel
    → init_sock → OpenFile

UNIXSocket.pair / for_fd / #path / SCM_RIGHTS
    UnixDomainNative           FFM socketpair / getsockname / sendmsg
    → channelFromFd → init_sock

blocking region / errno / waiting_fd
    UnixDomainContext          ThreadContext frame
    = CRuby rb_thread_io_blocking_region
```

## Landmine

`SocketChannel.open(UNIX)` is a `SocketChannel`. Current
`SocketType.forChannel` classifies it as TCP and later calls `.socket()`,
which throws UOE on Unix-domain channels. Patch `forChannel` first.

## FFM flag

```
jruby -J--enable-native-access=ALL-UNNAMED
```

Probe fails closed. Pair falls back to a JEP-380 temp-path pair.
`channelFromFd` returns a `UnixFdChannel` (FFM read(2)/write(2)/close(2),
extends `AbstractInterruptibleChannel` for real `Thread#kill` semantics
via the standard JDK mechanism -- no `java.nio.Selector`/`SelChImpl`, no
JDK-internal reflection). `implCloseChannel` does `shutdown(2)` before
`close(2)`: a bare `close(2)` races fd reuse if another thread is still
blocked in `read(2)` on this exact fd, while `shutdown(2)` reliably
forces that blocked read to return first. This was JNR's job before
(`jnr.unixsocket.UnixSocketChannel.fromFD`); JNR is now gone from this
whole interpreter path.

## CRuby thread frame

Do not use `java.lang.ThreadLocal` for waiting_fd. Fibers swap
`ThreadContext`. Hang `UnixDomainContext.Frame` off that.

```java
UnixDomainContext.blocking(context, fd, "recvmsg", () -> {
    return UnixDomainNative.recvIo(fd);
});
```

Now the actual wiring, not just the pattern: `RubyUNIXSocket#send_io`/
`#recv_io` call `UnixDomainNative.sendIo`/`recvIo` through exactly this,
replacing `context.runtime.getPosix().sendmsg`/`recvmsg` (jnr-posix).
`UnixDomainContext.lastErrno(context)` after a caught exception drives
the same `EAGAIN` + `waitWritable`/`waitReadable` retry loop the old
jnr-posix version had -- same retry behavior, different backend.
