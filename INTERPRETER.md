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
| `core/src/main/java/org/jruby/ext/socket/UnixSocketNativeChannel.java` | same |
| `patches/SocketType_forChannel.java` | edit `SocketType.java` |
| `patches/RubyUNIXSocket_init.java` | edit `RubyUNIXSocket.java` |
| `patches/RubyUNIXServer_accept.java` | edit `RubyUNIXServer.java` |
| `patches/RubyBasicSocket_unixAddr.java` | edit `RubyBasicSocket.java` |
| `patches/FilenoUtil_getDescriptorFromChannel.java` | edit `FilenoUtil.java` |
| `patches/RubySocket_unixDgram.java` | edit `RubySocket.java` |
| `patches/Helpers_errnoFromMessage.java` | edit `runtime/Helpers.java` |
| `patches/RubyUDPSocket_unixDgram.java` | edit `RubyUDPSocket.java` |
| `patches/ChannelFD_jepUnixNativeChannel.java` | edit `ChannelFD.java` |
| `patches/OpenFile_salvagePartialRead.java` | edit `OpenFile.java` |
| `patches/RubyIO_salvagePartialRead.java` | edit `RubyIO.java` |
| `patches/OpenFile_setNonblockFlag.java` | edit `OpenFile.java` |

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
`UnixDomain.isUnixAddress` / `UnixDomain.pathOf`, which accept BOTH address
flavors. JNR's `UnixSocketAddress` still occurs: `UNIXSocket.for_fd` no
longer produces it (it uses `UnixFdChannel` now -- see Landmine 3), but
`Socket.new(:UNIX, :STREAM)` and `Socket.socketpair` are still JNR channels.
An earlier revision dropped the JNR case on the theory that it no longer
occurred; that broke `remote_address` (ENOTCONN), `getpeername` (`""`),
`local_address` (empty path) and `getsockname` (ArgumentError on unpack) for
those two entry points. ruby/spec doesn't cover address methods on a raw
`Socket` UNIX stream, so only a direct patched/stock/MRI probe caught it.

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

## Landmine 4

errno was being read the unsafe way: `UnixDomainContext.captureErrno()`
called `__errno_location()` as a second, separate downcall, after the
failing syscall had already returned and after that call's
`Arena.ofConfined()` had already run `close()` on the way out (which frees
native memory through the JVM's own allocator -- capable of calling
`mmap`/`munmap` internally and resetting `errno` before Java-level code
ever reads it). Two consequences, both silent:

- `send_io`/`recv_io`'s `EAGAIN` retry loop could read a stale/wrong
  errno intermittently -- not reproducible on demand, would show as rare
  flakiness under real load.
- `UnixFdChannel`'s plain `read`/`write` never attached errno to anything
  at all. `org.jruby.runtime.Helpers#errnoFromException` recovers
  `Errno::*` from a caught `IOException` by string-matching its message
  against literals like `"Broken pipe"` / `"Connection reset by peer"` --
  `UnixFdChannel`'s old message (`"read failed rc=-1"`) never matched any
  of them, so `rescue Errno::EPIPE` silently failed to catch on a
  `pair()`/`for_fd` socket specifically (named bind/connect/accept sockets
  are real JDK `SocketChannel`s and already throw exceptions `Helpers`
  recognizes).

Fixed by adding `Linker.Option.captureCallState("errno")` to the
`read`/`write`/`sendmsg`/`recvmsg` downcall handles in
`UnixDomainNative` -- the JVM stores errno immediately after the raw call
returns, before any GC-safepoint or other JVM-internal native call can
touch it -- and reading it from the returned capture-state segment
instead of a second `__errno_location()` round-trip. `UnixFdChannel` maps
that reliable errno to the exact literal `Helpers#errnoFromMessage`
switches on before building its `IOException`, restoring `Errno::EPIPE`
/ `Errno::ECONNRESET` / `Errno::EBADF` etc. on `pair()`/`for_fd` sockets.
`NativeException` carries the captured errno as a public field
(`0` for the ops that don't capture it -- `socketpair`/`getsockname`/
`close`/`shutdown` are unchanged, no Ruby-visible errno consumer reads
their failures today).

Verified with a forced repro (write past `EPIPE` on a closed `pair()`
peer, and a `close(2)`'d-out-from-under-us fd for `EBADF`) plus a full
`unixsocket`/`unixserver` spec run: 90 examples, 4 failures, 5 errors --
identical to the pre-existing baseline, zero net-new regressions.

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

## Landmine 5

The `unixsocket`/`unixserver` spec baseline (90 examples, 4 failures, 5
errors) was never actually diffed against genuine stock jruby/jruby --
it was carried forward as "don't make it worse" without checking whether
"worse" was the right bar. Stashing this whole patch and running the
identical spec files against real jnr-based jruby/jruby master reproduces
the exact same 9 failures/errors, confirming all of them predate this PR
and have nothing to do with JEP-380/FFM. Fixed 7 of the 9 anyway, since
they're real bugs and the files involved are ones this PR already
touches or are one call away from them:

- **binmode never set.** `RubyUNIXSocket#init_sock` builds its own
  `OpenFile` by hand and never called `setAscii8bitBinmode()` (unlike
  `RubyBasicSocket#initSocket`, which this class bypasses). Inherited by
  `RubyUNIXServer` too (`UNIXServer < UNIXSocket`, same as MRI), so one
  fix covered both `UNIXSocket#initialize` and `UNIXServer#initialize`.
- **nonblock never set.** `OpenFile#isBlocking()` is a separately-tracked
  internal flag, not read from the real channel -- calling
  `configureBlocking(false)` on the raw JEP-380 channel (an earlier,
  wrong attempt at this fix) does nothing for it. The real fix is
  `openFile.setBlocking(runtime, false)`, which sets the flag *and*
  calls `configureBlocking(false)` on the real channel via
  `ChannelFD.chSelect`. Guarded on `channel instanceof SelectableChannel`:
  `UnixFdChannel` (`pair()`/`for_fd`/SCM_RIGHTS) is a plain FFM
  `ByteChannel`, not selectable, and marking it non-blocking turns its
  EAGAIN/wait_readable retry into a CPU-spinning busy loop instead of a
  real blocking `read(2)` -- caught by `pair_spec.rb` hanging outright in
  mspec, not a quiet regression.
- **`recvfrom` rejected its own documented 3-arg form.** MRI's
  `recvfrom(maxlen, flags = 0, outbuf = nil)` takes an output buffer as a
  3rd arg; this method only accepted 1-2. Fixed to accept 3, writing into
  the buffer via `RubyString#setValue` and restoring the buffer's own
  encoding afterward (matches `IO#read(len, buf)`'s contract).
- **`UNIXServer.for_fd` NPE'd computing `#path`.** Its own `path()`
  override did `newString(context, openFile.getPath())` --
  `openFile.getPath()` is null for a server built via `for_fd`, and
  `newString(context, null)` throws. Deleted the override entirely;
  `RubyUNIXSocket#path` (already patched in this PR) already has the
  right null fallback through `UnixDomain.localAddress`/
  `UnixDomainNative.pathOf`/`""`.

All verified against a real build: full `unixsocket`/`unixserver` spec
run at 90 examples, 0 failures, 2 errors after this batch (down from
4/5) -- the 2 remaining errors were one root cause, `Socket.new(:UNIX,
:DGRAM)` + `bind` raising a null-message `SocketError`.

## Landmine 6

Fixed the `Socket.new(:UNIX, :DGRAM)` gap from Landmine 5 -- the real
9th pre-existing issue, not a bugfix but a missing capability: JEP-380
never added Unix-domain `DatagramChannel` support at all
(`DatagramChannel.open(ProtocolFamily)` rejects `UNIX` outright), so
`RubySocket#initChannelFD`'s `SOCK_DGRAM` case never branched on
`PF_UNIX`/`PF_LOCAL` the way its own `SOCK_STREAM` case does one line
above it -- it silently built an ordinary INET `DatagramChannel` for a
UNIX-domain request, and `#bind` then threw a null-message
`UnsupportedAddressTypeException` handing that channel's `DatagramSocket`
a Unix sockaddr.

Fixed with new raw FFM bindings in `UnixDomainNative`
(`rawSocket`/`bindPath`/`sendTo`/`recvFrom`/`socketType`, resolved in
their own fail-closed `BIT_DGRAM` bit so a hypothetical resolution
failure can't take down the already-verified `BIT_PAIR`/`BIT_SCM`
capabilities) plus three call sites:

- `RubySocket#initChannelFD`'s `SOCK_DGRAM` case now branches on
  `PF_UNIX`/`PF_LOCAL`, creating the fd via raw `socket(2)` and wrapping
  it the same way `pair()`/`for_fd` do -- a plain `UnixFdChannel`, not a
  new channel class (see `RubySocket_unixDgram.java`).
- `RubySocket#doBind` and `RubyBasicSocket#doSend` each gained a
  `UnixFdChannel` branch alongside their existing `DatagramChannel` one,
  calling `bindPath`/`sendTo` directly on the fd.
- `RubyUNIXSocket#recvfrom` needed a real fix, not just wiring: a raw
  `Socket.new(:UNIX, :DGRAM)` fd wrapped via `UNIXSocket.for_fd` has no
  connected peer (`peeraddr()`'s existing lookup), it has a different
  sender per datagram, which only genuine `recvfrom(2)` reports. Rather
  than switch every call to the new path (real risk: Linux does not
  reliably populate `recvfrom(2)`'s source address for AF_UNIX
  `SOCK_STREAM` the way it does for `SOCK_DGRAM`, and one already-passing
  spec depends on stream `recvfrom` reporting a real bound peer path),
  branch on the fd's actual type via `getsockopt(SO_TYPE)` -- side-effect
  free, no guessing from the Java wrapper (`UnixFdChannel` backs both
  `pair()` stream fds and raw dgram fds identically). Only real
  `SOCK_DGRAM` takes the new native path (through
  `UnixDomainContext.blocking`, same `EAGAIN`/`waitReadable` retry shape
  as `send_io`/`recv_io`); every existing stream case is untouched, still
  on `recv()` + `peeraddr()`.

Verified: the specific failing spec (`unixsocket/recvfrom_spec.rb`'s
`for_fd`/DGRAM case) passes, full `unixsocket`/`unixserver` suite is
clean at 91 examples/0 failures/0 errors, and a plain `UDPSocket`
bind/send/recvfrom smoke test confirms the shared `doBind`/`doSend`
changes don't affect the existing (non-UNIX) `DatagramChannel` path.

**Follow-up, found by code review (no spec caught this):**
`RubyUNIXSocket#recvfrom`'s new native path took `_length` from the
caller, allocated a native buffer sized `Math.max(len, 1)`, but passed
the original, unclamped `len` straight to `recvfrom(2)` as the buffer
size. `socket.recvfrom(-1)` allocated a 1-byte buffer while telling the
kernel the buffer was `size_t`-max bytes (sign-extension of a negative
`int` widened to `long`) -- a real memory-safety bug, reachable from
pure Ruby, not just a logic error. Fixed with an explicit `len < 0`
check raising the same `ArgumentError` MRI's own `IO#read(-1)` raises.
Verified with a direct repro (`recvfrom(-1)` rejected cleanly, socket
still usable normally afterward) plus the full suite re-run clean.

**Also checked and confirmed NOT regressions** (identical on stock
jruby/jruby, unaffected by this PR either way): `spec/ruby/library/socket/option`
(3 errors), `constants` (2 failures), `ancillarydata` (101 errors) are
byte-for-byte identical counts between stock and patched builds.
`addrinfo` showed one difference (233-example `initialize_spec.rb`'s
`SOCK_SEQPACKET` case) that turned out to be pre-existing mspec
test-order flakiness (reproduces identically whether patched or not,
depending only on whether the file runs alone or as part of the full
directory) -- not something this PR causes or can fix.
`basicsocket`/`socket` still hang on both stock and patched builds; this
sandbox has no network access, confirmed environmental in an earlier
pass, not revisited here.

**Follow-up: fixed the errno-mapping gap above.** `#send` with no
destination on an unconnected raw `Socket.new(:UNIX, :DGRAM)` now
correctly raises `Errno::ENOTCONN`. Two things had to change together:
`UnixFdChannel.ERRNO_MESSAGES` needed the entry, *and* JRuby core's
`Helpers#errnoFromMessage` needed to recognize the same literal string
(see `Helpers_errnoFromMessage.java`) -- adding the message to one table
without the other does nothing, since `errnoFromMessage` is the thing
that actually turns a message into an `Errno::` class.

This one is worth calling out on its own: the initial fix used
`EDESTADDRREQ` (89), based on general POSIX docs -- Linux's actual
AF_UNIX `write(2)`-without-`connect(2)` behavior turned out to return
`ENOTCONN` (107) instead, caught by writing a direct repro and checking
the real errno rather than trusting the doc-derived assumption. Both
errnos are mapped now (`EDESTADDRREQ` kept as a real, standalone errno
that could plausibly occur through some other path, even though it
wasn't the one this PR's repro actually hit). Verified with a direct
repro (`Errno::ENOTCONN` raised, correct message) and the full
`unixsocket`/`unixserver` + `option`/`constants`/`ancillarydata` suites
re-run clean (`Helpers.java`'s change is purely additive -- two new
switch cases matching two new, previously-unmatched strings -- so it
can't change behavior for anything already matched there).

## Landmine 7

Asked to re-run benchmarks after this whole batch of fixes and watch
for regressions. `RubyUNIXSocket#recvfrom`'s new socket-type check
(Landmine 6/`getsockopt(SO_TYPE)`) was calling into native code on
*every* `recvfrom` call to decide the stream-vs-dgram branch -- measured
~1.2us/call in isolation. A socket's type is fixed at `socket(2)`
creation and can never change afterward, so this was a pure repeated
cost for an answer that's always the same. Moved to
`UnixFdChannel#sockType()`, which computes it once and caches it on the
instance -- safe because `UnixFdChannel.fd` is `final` (one fd per
instance for its whole lifetime, no reopen/rebind path that could make
a cached value stale). Same "one fixed key, its own slot, compute once"
shape as `UnixDomainNative.BITS` and `SCRATCH` elsewhere in this file.

Also dropped the separate `UnixDomain.fileno()` + `FilenoUtil.isFake()`
checks in favor of `getChannel() instanceof UnixFdChannel` directly --
a `UnixFdChannel` is only ever constructed from a genuine native fd
(`pair()`/`for_fd`/`rawSocket()`), so the fake-fd check was dead weight
for this specific decision, and `instanceof` skips
`UnixDomain.fileno()`'s reflective lookup entirely for the common
(non-`UnixFdChannel`) case.

Verified: full `unixsocket`/`unixserver` suite still 91/0/0. Benchmarked
before/after with a `pair()` ping-pong-style script comparing plain
`read`/`write` against `recvfrom` on the same socket pair -- before this
fix, `recvfrom` measurably trailed `read`/`write`; after, throughput
tracks it (within noise, sometimes ahead, sometimes behind run-to-run --
the added syscall being gone means the two are no longer distinguishable
by this benchmark, versus the earlier consistent last-place trend for
`recvfrom` before caching).

## Landmine 8

`UnixDomainContext.current()` was still `synchronized (FRAMES)` around a
`WeakHashMap<ThreadContext, Frame>` -- the one place in this whole PR
that never got the lock-free-per-key-isolation treatment
`UnixDomainNative.BITS`/`UnixFdChannel.SCRATCH`/`UnixFdChannel.sockType()`
all have. Every thread/fiber doing `send_io`/`recv_io`/dgram-`recvfrom`
contended on one shared monitor, even though each only ever touches its
own `Frame`.

Fixed by swapping to `org.jruby.util.collections.ConcurrentWeakHashMap`
-- already in JRuby's own codebase (Doug Lea/JSR-166, public domain),
not a new dependency. `get()` doesn't lock at all; `put`/`remove` only
lock their own segment (16 by default), not the whole table -- no
single monitor shared by every thread doing IPC across the process.
Still weak-keyed, so a `Frame` is reclaimed once its `ThreadContext`
actually becomes unreachable, same guarantee `WeakHashMap` had.
`current()` was rewritten as `get()` then `putIfAbsent()` rather than
`computeIfAbsent()` -- `ConcurrentWeakHashMap` doesn't override
`computeIfAbsent`, so it would fall back to `Map`'s default
(non-atomic get-then-put) implementation, which is unsafe here: this
map is genuinely read and written from different threads at once (one
thread's `blocking()` vs. another thread inspecting its `waitingFd`),
and two threads racing to create the first `Frame` for the same
`ThreadContext` under the default implementation could silently
clobber each other's instance.

Verified three ways: (1) full `unixsocket`/`unixserver` suite still
91/0/0; (2) a 24-thread x 300-round concurrent `send_io`/`recv_io`
stress test (each thread creating its own `Frame` on first use,
racing every other thread) -- zero errors, zero corruption, across
repeated runs; (3) checked the "automatic cleanup" claim wasn't just
assumed -- a direct test found `ThreadContext` objects survive a plain
`GC.start()` even after their thread terminates and joins, traced to
JRuby's own `ThreadService` caching them via
`ThreadLocal<SoftReference<ThreadContext>>` (soft references aren't
cleared by an ordinary GC pass, only real memory pressure) -- this is
identical, pre-existing behavior for the old `WeakHashMap` too, not a
regression; confirmed reclamation genuinely works by forcing real
memory pressure (large allocation before triggering GC), which did
shrink the map. `send_io`/`recv_io` benchmark numbers unchanged
before/after (this lock was never on the byte-copy hot path, only the
fd-passing one).

## Landmine 9

Found by auditing the DGRAM feature for other gaps, not by a failing
spec: `Socket.new(:UNIX, :DGRAM)#recvfrom`, called *directly* on the raw
`Socket` object (not wrapped via `UNIXSocket.for_fd`, which is the only
way Landmine 6's own tests exercised it), crashed with a raw
`ClassCastException` -- `RubySocket#recvfrom` delegates to
`RubyUDPSocket.recvfrom`, which hard-casts `(DatagramChannel)
socket.getChannel()` a few lines down. `UnixFdChannel` isn't a
`DatagramChannel`. Confirmed with a direct repro before fixing (a
generic, content-free `SocketError` wrapping the Java exception, not
anything resembling MRI behavior).

Fixed by extracting the native recvfrom(2)+`EAGAIN`-retry-loop out of
`RubyUNIXSocket#recvfrom` into a shared `UnixDomain.recvFromBlocking`
helper, used by both `RubyUNIXSocket#recvfrom` (a `for_fd`-wrapped raw
fd) and the new `RubyUDPSocket.recvfrom` branch (the `Socket` object
itself) -- one retry-loop implementation, not two copies that could
drift. `UnixFdChannel` only ever reaches `RubyUDPSocket.recvfrom` via
`RubySocket`'s `SOCK_DGRAM` branch (`SOCK_STREAM` uses a real JNR
channel), so no socket-type check is needed there the way
`RubyUNIXSocket#recvfrom` needs one.

Verified: the specific repro (`Socket.new(:UNIX,:DGRAM); ...;
sock.recvfrom(5)`) now returns `["hello", ["AF_UNIX", path]]`
correctly, full `unixsocket`/`unixserver` suite still 91/0/0, and every
other repro script from this session (errno, nonblock, negative-length,
`ENOTCONN`, concurrent `send_io`/`recv_io` stress) still passes.

## Landmine 10

Three more gaps found by systematically auditing every common `Socket`
op against a raw `Socket.new(:UNIX, :DGRAM)` pair after Landmine 9,
not by a failing spec:

- **`#recvfrom_nonblock`** -- the identical `(DatagramChannel)` cast as
  Landmine 9's `#recvfrom` bug, in `doReceiveNonblockTuple`. Crashed the
  same way.
- **`#recv_nonblock`** -- worse than a crash: silently *wrong*.
  `RubyBasicSocket#doReadNonblock`'s `if (!(channel instanceof
  SelectableChannel))` check unconditionally raised `EAGAIN` for any
  `UnixFdChannel` -- confirmed with a direct repro that it did this
  even 0.5s after data was genuinely sent and sitting there, not a
  timing fluke. A caller retry-looping on this would spin forever or
  wrongly conclude no data ever arrives. `UnixFdChannel` isn't a
  `SelectableChannel` at all, so that check can't distinguish "really
  would block" from "this channel type isn't supported."
- **`#remote_address`** on a raw, bound-but-never-`connect()`'d dgram
  socket returned a fake empty address instead of `Errno::ENOTCONN`.
  `UnixDomain.remoteAddress` treated every `UnixFdChannel` as always
  connected -- correct for `pair()`/`for_fd` (genuinely connected at
  the kernel level, just unnamed) but wrong for a socket that was only
  bound.

All three needed real new capability, not patches to existing logic --
`UnixDomainNative` gained `fcntl` (`F_GETFL`/`F_SETFL`, declared as two
separate fixed-arity downcall handles on the same variadic C symbol --
the standard way to call a variadic libc function from FFM when only
specific, known-arity call shapes are needed) and `getpeername(2)`
(with `Linker.Option.captureCallState`, same as read/write/sendto,
since telling `ENOTCONN` apart from any other failure requires the
real errno):

- `UnixDomain.recvFromNonBlocking`: toggles the raw fd into real
  OS-level `O_NONBLOCK` (`fcntl`) around a single `recvfrom(2)`
  attempt, then restores the original flags -- the hand-rolled
  equivalent of `DatagramChannel#configureBlocking(false)`/`(oldValue)`
  for a channel type that has no such API. Returns `null` for "would
  block" (caller decides `:wait_readable` vs. raising, matching
  `exception: false`) rather than retrying, unlike
  `recvFromBlocking`'s retry loop. Used by both the new
  `RubyUDPSocket.recvfrom_nonblock` branch and
  `RubyBasicSocket#doReadNonblock`'s new `UnixFdChannel` branch --
  works identically for a `pair()` stream socket (`recvfrom(2)` on a
  connected stream socket is just a read, ignoring the sender info
  `UnixDomain` discards there) or a raw dgram one, so one fix covers
  both `#recv_nonblock` (any `UnixFdChannel`) and `#recvfrom_nonblock`
  (the `Socket`-object dgram case).
- `UnixDomain.remoteAddress` now calls the new `peerPathOf` (built on
  `getpeername(2)`) for any `UnixFdChannel` instead of assuming
  success: a real `ENOTCONN` maps to `null` (which
  `RubyBasicSocket#remote_address` already correctly turns into
  `Errno::ENOTCONN`), anything else falls back to the prior
  always-empty-but-connected behavior so genuinely-connected
  `pair()`/`for_fd` sockets are unaffected.

Verified: the three original repros now behave correctly
(`recvfrom_nonblock`/`recv_nonblock` both correctly return data when
available and correctly report `EAGAIN`/`:wait_readable` when not --
checked both directions, not just "no longer crashes"; `remote_address`
raises `ENOTCONN` for the unconnected case). Confirmed `pair()` stream
sockets are unaffected: `remote_address` still returns `""`, and
`recv_nonblock` still correctly raises `EAGAIN` when empty and returns
real data once available. Full `unixsocket`/`unixserver` suite still
91/0/0, and every other repro script from this session still passes.

## Landmine 11

The `SCRATCH` shrink-governor, deferred earlier in this session as a
low-priority "documented but not built" item. `Scratch.get(minSize)`
only ever grew, never shrank: one large one-off transfer on an
otherwise small-message connection permanently inflated that carrier
thread's off-heap native buffer for the rest of the thread's life, even
if every later call only ever needed a few KB. Not a GC problem (this
memory was already off-heap, invisible to heap pressure) but a real,
unbounded-until-thread-death native memory footprint problem.

Fixed with the exact same shape as Kestowv Wave's per-core governor
(`core/wave.rb`): a rare, decisive writer (shrink) gated by a decaying
usage signal (`recentMax`, reset each tick), a wide hysteresis dead-band
(`recentMax * 4 < currentSize`) so a single small call right after a
burst doesn't immediately snap the buffer back down (which would just
force the *next* big call to regrow it -- thrashing), a gradual step
(halfway back toward observed usage per tick, not an instant snap) so
one noisy tick's reading can't yank the buffer down too far, and a
floor (`MIN_SIZE` = the original fixed default) so it never shrinks
below the size that was already fine for ordinary small messages. The
tick is call-count-based (every 64 `get()` calls), not time-based --
this buffer has no wall-clock tick of its own the way Wave's per-core
sampling loop does.

Verified by reflecting into the live `Scratch` instance's native
segment size at each tick boundary: after a single 500KB transfer
(grows to exactly 500000), then a run of small 16-byte transfers, the
size converged 500000 -> 250008 -> 62514 -> 15640 -> 8192 (the floor)
over four ticks, then stayed flat at 8192 for three more ticks with no
further oscillation -- confirmed both the gradual convergence and the
stable floor, not just "does it eventually shrink." Full
`unixsocket`/`unixserver` suite still 91/0/0, every other repro script
still passes, and `pair()`/`send_io`/`recv_io` steady-size-message
throughput is unchanged (the governor's own bookkeeping is a few
integer ops on the already-hot path, and the shrink logic itself only
ever runs once every 64 calls).

## Landmine 12

`ChannelFD.initChannelTypes()` recognizes a "real native descriptor" --
and, on that strength alone, lets `OpenFile#selectForRead` skip the
pre-read `select()` it otherwise does before every blocking read --
only via `jnr.enxio.channels.NativeSelectableChannel`, JNR's own
marker interface. A JEP-380 unix-domain `SocketChannel`/
`ServerSocketChannel` doesn't implement it, so it fell through to the
generic `SelectableChannel` path and paid for a `select()` on every
single blocking read. `patches/ChannelFD_jepUnixNativeChannel.java`
fixes the recognition gap: wrap it in an inline
`NativeSelectableChannel` using the real fd `UnixDomain.fileno()`
already resolves reflectively.

That fix is correct and worth keeping (it also fixes `PREAD_TASK`/
`PWRITE_TASK`'s fd resolution for this channel type) but **by itself
changes observed throughput by approximately nothing**. The actual
~30% UNIXSocket round-trip cost (measured against Kestowv 0.5.6's
`ipc/channel.rb`, 256B request/response loop, `kestowv_tier_one.rb`
benchmark in this repo) lives in a second, separate gap:
`RubyUNIXSocket#init_sock` flips every `SelectableChannel` -- this one
included -- to non-blocking, which reroutes every wait through
`OpenFile#waitReadable`'s EAGAIN-driven `select()` instead, a path
with **no** `chNative` guard at all (by design -- it exists for
channels Java can't block-read natively). `chNative` only gates the
*other* select call, the one that's already being skipped here for a
different reason, so fixing it alone cannot touch this cost.

Also excluding unix channels from that non-blocking flip (so the
channel stays genuinely blocking, and `chNative` gates the select that
actually matters) was tried and reverted. It works for throughput --
43,055 rt/s on the same benchmark, past the JNR baseline -- but a real
`Thread.interrupt()` landing while genuinely blocked inside
`sun.nio.ch.SocketChannelImpl.read()` (a JDK
`AbstractInterruptibleChannel`) throws `ClosedByInterruptException`
and closes the channel as a side effect. Confirmed destructively: a
1000-trial jittered `Thread#raise`/`Timeout.timeout` stress loop (the
`Net::HTTP`/`Timeout` pattern -- the common real-world way Ruby code
aborts a blocking read) broke **1000/1000** connections. This is why
the original non-blocking flip exists at all -- a deliberate safety
trade-off, not an oversight -- and it reproduces with a bare
`Thread#kill` too, no stress loop needed.

The real fix needs the data-path read/write for a named JEP-380
`UNIXSocket` to go through FFM directly -- `UnixDomainNative`'s raw
read/write, the same mechanism `UnixFdChannel` already uses -- instead
of through the JDK channel's own interruptible `read()`/`write()`,
so a genuine interrupt can't tear the channel down as a side effect.
That's materially larger than a `ChannelFD` recognition fix and needs
its own interrupt-safety stress testing (the same jittered
`Thread#raise`/`Timeout` loop, not just a throughput number) before
it's safe to land.

## Landmine 13

Follow-up to Landmine 12: closing the ~30% UNIXSocket round-trip gap
needed both pieces together, not the `ChannelFD` recognition fix
alone.

**What actually closes it**: `UnixSocketNativeChannel.java` (new
file) replaces `ChannelFD.chRead`/`chWrite` -- not `ch` itself -- for
a connected, named JEP-380 unix-domain `SocketChannel` with a thin
wrapper around `UnixFdChannel.rawRead`/`rawWrite` (the same FFM
read(2)/write(2) mechanics `UnixFdChannel` already uses for
pair()/for_fd, extracted into fd-parameterized static methods so both
classes share one implementation). `patches/RubyUNIXSocket_init.java`
also stops flipping this specific channel non-blocking, so its fd
stays genuinely blocking (`O_NONBLOCK=false`, matching JNR) -- the
non-blocking flip is what forced every wait through
`OpenFile#waitReadable`'s EAGAIN-driven `select()`, the actual cost
Landmine 12 identified. Everything else about the channel --
`getLocalAddress`/`getRemoteAddress`, `close`, `accept`,
`shutdownInput`/`shutdownOutput` (`SocketType.UNIX_NIO`) -- stays on
the real `sun.nio.ch.SocketChannelImpl` throughout; only the actual
byte transfer moves.

**Why not just make the real channel blocking and call its own
`read()`/`write()`** (the obvious first attempt, and literally the
first thing tried): `sun.nio.ch.SocketChannelImpl` extends
`AbstractInterruptibleChannel`. A genuine `Thread.interrupt()`
landing while truly blocked inside its `read()` throws
`ClosedByInterruptException` and closes the channel as a side
effect -- confirmed destroying 1000/1000 connections in a jittered
`Thread#raise`/`Timeout.timeout` stress loop (the `Net::HTTP`/
`Timeout` pattern). Reverted.

**Why not wrap the fd in `UnixFdChannel` itself instead of a new
class** (the obvious second attempt): `UnixFdChannel` *also* extends
`AbstractInterruptibleChannel`, on purpose -- its own header comment
says so, specifically so `Thread#kill`/interrupt works "the normal
JRuby way" for pair()/for_fd SCM_RIGHTS fds. Confirmed empirically:
`Timeout.timeout(1) { pair_socket.read(100) }` raises promptly, but
the connection is dead afterward (`Errno::EPIPE` on the next write) --
the exact same hazard as the first attempt, already shipped elsewhere
in this same patch. Reusing it as-is would not have been an
improvement, just the same trade-off under a different name.

**What `UnixSocketNativeChannel` actually does**: calls the raw FFM
`read(2)`/`write(2)` downcall with **no** `begin()`/`end()`
bracketing at all -- no JDK interrupt hook of any kind. This matches
`jnr.unixsocket.UnixSocketChannel`'s long-standing, already-shipped
behavior exactly: `Thread#kill`/`Thread#raise`/`Timeout.timeout`
cannot abort a genuinely blocked call (confirmed: a thread blocked
this way does not die within 5s of `Thread#kill`, and
`Timeout.timeout(1)` does not fire within 10s on stock JRuby/JNR
either -- this is not a new limitation, it is the status quo for
every UNIXSocket user today), but nothing can tear the connection
down as a side effect of attempting to interrupt it. That is a real,
known trade-off against the current (safe, slower) default -- not a
free improvement -- and affects every future JEP-380 UNIXSocket user,
not just this repo's benchmark, so it deserves to be called out
explicitly in the PR, not buried.

**A separate, pre-existing JRuby bug found while stress-testing
this** (not caused by this patch, not fixed by it, logged here so it
isn't mistaken for a regression next time someone hits it): killing a
thread mid-`IO#read` from a *different* thread, timed to land right
as new data arrives, can leave `OpenFile`'s internal read buffer
(`rbuf`) holding a partial, abandoned fragment that silently
corrupts a *later* read on the same IO object -- no exception, just
wrong bytes. Reproduces on plain `TCPSocket` on stock JRuby (no
JEP-380, no FFM, nothing related to unix sockets at all) at close to
100% under a tight race (interrupt fired 0.5-2.5ms before data
arrives); reproduces on the current JEP-380 select-based default at
the same near-100% rate (its `Thread#kill`/`raise` responsiveness
makes the race window wide open); reproduces on this patch's FFM path
too, but at only ~0.4-0.8% (500 trials), because a thread almost
never actually gets interrupted out of the blocking FFM call in the
first place, so the race window is far smaller, not open by design.
Worth its own upstream JRuby bug report independent of #9040.

**Verified**: full correctness suite (round-trip, EOF/close_write,
1B-to-1MB messages, `pair()`/`for_fd`/`send_io`/`recv_io` all
unaffected by the `UnixFdChannel` refactor), Kestowv regression suite
(129/3, the known floor) and `stress_uds.rb` (44.8M ops, 0 errors) on
both runtimes. Throughput: median 44,218 rt/s vs stock JNR's 40,543
(**1.09x** -- now *faster* than JNR) and vs the raw-`java.nio` control
tier's 40,852 (same benchmark as Landmine 12, this repo's
`kestowv_tier_one.rb` against Kestowv 0.5.6's `ipc/channel.rb`).

## Landmine 14

Follow-up to Landmine 13: went after making `UnixSocketNativeChannel`'s
raw FFM read genuinely interruptible (Thread#kill/Thread#raise/
`Timeout.timeout` should abort a blocked read without the
`ClosedByInterruptException`-closes-the-channel hazard either prior
attempt hit) and fixing a data-loss bug found along the way. One of
those turned out smaller than expected; the other turned out to be a
pre-existing, general JRuby core bug with nothing to do with unix
sockets, JEP-380, or this PR at all.

**Confirmed and fixed**: a real, narrower data-loss bug. `Thread#kill`/
`Thread#raise` (`ThreadKill`/`RaiseException`, both unchecked
`RuntimeException`s) can abandon a read between iterations of an
accumulating retry loop, after the native read has ALREADY,
irreversibly, consumed bytes from the real fd into a caller-local
buffer that never makes it back to Ruby if the call is abandoned.
Those bytes vanish from the Ruby-visible stream entirely (not sitting
in the fd to be re-read), while bytes NOT yet read remain in the
kernel and land on whoever reads next -- silently misdelivering
mid-stream data into an unrelated, later read. Confirmed on plain
`TCPSocket`, stock JRuby -- nothing channel-type-specific. Three call
sites have this shape: `OpenFile#ioBufread` (`IO#read(length)`),
`RubyIO#getPartialCommon` (`readpartial`/`read_nonblock`, one fix
covers both), `RubyIO#sysreadCommon` (`sysread`). Fixed with a shared
`OpenFile#salvagePartialRead` helper: on an abandoning exception, push
whatever was already, successfully read into `rbuf` -- the same
shared, ordered buffer every other read on that IO already drains
first -- instead of letting it vanish. Deliberately NOT applied to
`pread`/`IO::Buffer#pread`: those read at an explicit, non-sequential
offset, so stashing their bytes into `rbuf` would corrupt a later
ordinary sequential read with data from the wrong place, which is
worse than the bug being fixed. Verified with a rigorous total-byte-
accounting test (not a naive string comparison -- an earlier version
of this test had its own false-positive problem, see below) across
large transfers with randomly-timed kills; zero loss once isolated to
exactly this mechanism.

**UPDATE, corrected after deeper investigation**: `patches/
OpenFile_salvagePartialRead.java` / `patches/RubyIO_salvagePartialRead.java`
are KEPT in the main file-drop table -- they fix a real, narrow,
general bug (confirmed via white-box instrumentation: `getPartialCommon`'s
own retry loop recording a successful `n` from one iteration, then
losing it on a later iteration's abandonment) and are harmless to
land regardless. But the headline "16% vs 5%, this PR triples a
fixable bug" claim from the first pass at this investigation was
WRONG, and is corrected here rather than left to mislead a reviewer.

Rigorous white-box tracing (a Java-level `AtomicLong` ledger inside
`getPartialCommon`, incremented only on its own successful return,
compared against bytes actually sent) showed `n` is **0** in every
single one of the stress test's "lost chunk" cases -- meaning
`getPartialCommon`'s own retry loop never even recorded the
successful read `salvagePartialRead` was designed to catch. The real
mechanism is one level deeper and outside what this fix (or any fix
scoped to `RubyIO`/`OpenFile`'s read methods) can reach:
`RubyThread#executeReadWrite` --

```java
try {
    preReadWrite(context, data, task);
    return task.run(context, data, bytes, start, length);
} finally {
    postReadWrite(context, oldStatus);   // unconditionally calls pollThreadEvents()
}
```

-- runs `postReadWrite` -> `pollThreadEvents()` in a `finally` block
*after every single native read/write call, success or not*. If
`task.run(...)` has *already successfully computed* a return value
(real bytes, genuinely read from the kernel) and `pollThreadEvents()`
*then* notices a pending `Thread#kill`/`Thread#raise` and raises it,
Java's own `finally`-throws-replaces-the-try's-pending-return
semantics discard that successful value outright -- `ThreadKill`/
`RaiseException` propagates instead, and the caller (`readInternal`,
then `getPartialCommon`) never sees `n` at all. Confirmed by timing
(`reader.join` after a kill returns in ~50ms, consistent with the
*normal* checkpoint path, not some slow fallback) and by minimal
reproduction: a trivial in-memory `x = compute(); accumulate(x)` loop
killed mid-execution essentially never loses anything (0/200, the
window is nanoseconds), but the exact same shape through
`executeReadWrite`'s checkpoint loses data at a real, measurable rate
specifically because that checkpoint sits *between* a successful
native call and the moment its result reaches Ruby code.

This is **universal to every blocking IO read or write in JRuby that
goes through `executeReadWrite`** -- JNR, TCP's existing select-based
`SocketChannelImpl` path, and this PR's own `UnixSocketNativeChannel`
all share the identical mechanism, because none of them touch
`RubyThread` at all; it is shared, core plumbing underneath all of
them. It is not introduced by this PR, not specific to unix domain
sockets, and not something `salvagePartialRead` -- or any fix scoped
to the `RubyIO`/`OpenFile` layer -- can close, because the data is
discarded one level below where any of those methods ever get a
chance to run their own exception handling. The differing rates
measured across channel types (JNR, TCP, this PR's FFM path) reflect
how much wall-clock time each spends actually inside a completing
native call relative to everything else, not a difference in
correctness between them.

A real fix requires `RubyThread#postReadWrite`/`executeReadWrite`
itself to stop discarding an already-successful result when the
interrupt checkpoint fires immediately after it -- shared, universal,
extremely hot-path code touching every IO operation in JRuby, not
scoped to sockets at all. That is squarely its own JRuby core
investigation and PR, not something to attempt as a rider on #9040.

**Discovered, NOT fixed, out of scope for this PR**: a bigger,
separate, pre-existing bug. `RubyThread#interrupt()` -- the single
method both `Thread#kill` and `Thread#raise` funnel through
identically (`pendingInterruptEnqueue(...); interrupt();`, same code,
same `thread.getNativeThread().interrupt()` call either way) -- calls
the JVM's own `Thread.interrupt()` as part of waking a blocked
operation. If the target thread happens, at that exact instant, to be
inside `sun.nio.ch.SocketChannelImpl`'s own (`AbstractInterruptibleChannel`-
based) read call -- the brief, normally-safe data-fetch step
immediately after a successful `select()`, which a thread doing
sustained reads spends a real, non-negligible fraction of its time
in -- that interrupt throws `ClosedByInterruptException` and closes
the channel as a side effect, the exact hazard Landmine 13 already
identified for the FFM path's two rejected attempts. The difference
here: this is the CURRENT, shipped, "safe" select-based default
(`chNative == null`, ordinary `SelectableChannel` path) -- the thing
both rejected FFM attempts were measured against as the safe baseline
-- and it is not actually immune. It is lower-risk than those two
attempts (most of a blocked thread's time is in the genuinely-safe
`select()` wait, not the brief read), but not risk-free: a rigorous
total-byte-accounting stress test (sustained 2MB transfer, a reader
thread killed/raised at a random point mid-transfer, while a second,
fresh reader drains whatever is left) measured roughly a **1-in-10**
chance of the connection being destroyed outright (`Errno::EBADF` on
the next read) -- confirmed on plain `TCPSocket`, stock JRuby,
nothing to do with unix domain sockets, JEP-380, or FFM. Confirmed
identical for `Thread#kill` and `Thread#raise` (150-trial batches,
16/150 and comparable respectively) -- not a kill-vs-raise
difference; a property of `Thread.interrupt()` landing inside any
`AbstractInterruptibleChannel` call, regardless of what asked for the
interrupt.

This means the earlier "1000/1000 clean" interrupt-safety claim for
the current default (Landmine 12/13, `interrupt_test2.rb`) was real
but incomplete: that test's `Timeout.timeout(jittered) { read }` never
sent any data, so the blocked thread was always safely parked in
`select()`, never in the vulnerable post-select read -- the one
dimension that matters for this bug never got exercised. A thread
only enters the vulnerable window when data is actively arriving,
which is exactly the condition a THROUGHPUT-focused benchmark creates
and a "does Timeout even fire" smoke test does not.

**Why this stays unfixed here**: closing this properly needs either
(a) JRuby's own interrupt-delivery to stop blindly calling
`Thread.interrupt()` when the target is inside an
`AbstractInterruptibleChannel` call it doesn't want torn down -- a
change to `RubyThread`'s shared, universally-used interrupt plumbing,
not anything specific to this patch -- or (b) something hand-rolled
and FFM-based, at the cost of giving up Java's own interruptibility
guarantee entirely (this PR's current, deliberately-chosen trade-off
for the unix-socket path specifically, documented in Landmine 13).
Properly fixing (a) is a general JRuby core project, with its own
risk of regressing unrelated, widely-used interrupt behavior if done
without equal rigor -- worth its own upstream issue and its own
dedicated investigation, not a drive-by fix bundled into #9040.

**Test harnesses** (JRuby-jep-380 scratchpad): `dataloss_test.rb` /
`dataloss_ledger.rb` -- rigorous total-byte-accounting tests (sum of
every chunk any reader ever got, against bytes actually sent) for
exactly this class of bug. A naive string-equality check (what this
repo's `interrupt_test5_stress.rb` first used, see Landmine 13's test
harness note) produces FALSE positives here: when nobody has
consumed bytes yet, the next reader legitimately getting
earlier-in-stream data instead of what a test naively expected is
correct stream-ordering behavior, not corruption -- only a byte-
accounting check that sums ALL reads, not a single expected-string
comparison, tells the two apart.

## Landmine 15

Follow-up to Landmine 14's `salvagePartialRead` correction above: a
second, distinct, also pre-existing, also universal-to-JRuby data-loss
mechanism, found while verifying why `salvagePartialRead` didn't
actually close the stress test's observed loss rate. Not the same bug
as Landmine 14's "discovered, not fixed" `ClosedByInterruptException`
finding -- that one *closes the channel* (`Errno::EBADF` afterward);
this one leaves the channel fully open and usable, and just silently
drops data that was already, successfully read from the kernel.

**Root cause, pinned precisely**: `RubyThread#executeReadWrite`:

```java
public <Data> int executeReadWrite(...) throws InterruptedException {
    Status oldStatus = STATUS.get(this);
    try {
        preReadWrite(context, data, task);
        return task.run(context, data, bytes, start, length);
    } finally {
        postReadWrite(context, oldStatus);   // always calls pollThreadEvents()
    }
}
```

`postReadWrite` unconditionally calls `pollThreadEvents(context)` in
the `finally` block -- run *after every single native read/write call
returns, success or not*. If `task.run(...)` already computed a
genuine, successful return value (real bytes just read from the
kernel) and `pollThreadEvents()` then notices a pending
`Thread#kill`/`Thread#raise` and raises it, Java's own
finally-throws-replaces-the-try's-pending-return semantics discard
that successful value outright: `ThreadKill`/`RaiseException`
propagates instead of the computed `int`, and the caller
(`readInternal`, then whichever `RubyIO` method invoked it) never
sees it. The bytes were genuinely, irreversibly consumed from the
kernel; they are simply never handed back to anything, Ruby or Java.

**Why `salvagePartialRead` (Landmine 14) cannot reach this**: by the
time `getPartialCommon`'s own `catch (RuntimeException)` runs, `n`
was never assigned the successful value in the first place -- the
exception replaced it one level further down, inside
`executeReadWrite`, before `readInternal` itself ever got to return.
Confirmed by a Java-level `AtomicLong` ledger inside
`getPartialCommon`, incremented only on ITS OWN successful return: in
every single stress-test "lost chunk" trial, `n` was `0` in the catch
block. `salvagePartialRead` is still correct and worth keeping for
the narrower case it does cover (a retry loop's *own* prior,
already-recorded iteration getting abandoned on a *later* one -- a
real, different scenario, confirmed separately) -- it just does not,
and architecturally cannot, close this one.

**Confirmed universal, not specific to this PR**: reproduces on plain
`TCPSocket` and on JNR `UNIXSocket`, both completely untouched by
#9040, at measurable (if lower, ~5%) rates, and on this PR's own
`UnixSocketNativeChannel` path at a higher one (~16%) under a 2MB
sustained-transfer stress test with a reader thread killed/raised at
a random point. The *channel-type-dependent rate* reflects how much
wall-clock time each spends actually inside a completing native call
(where the vulnerable checkpoint sits) relative to everything else --
not a difference in correctness between implementations. Ruled out
timing-artifact explanations specifically: `reader.join` after a kill
returns in ~50ms (the *normal* checkpoint path, not some slow
fallback or forced JVM-level thread termination), and a trivial
in-memory `x = compute(); accumulate(x)` loop killed mid-execution
essentially never loses anything (0/200 trials) because that
operation's own "just succeeded, not yet used" window is nanoseconds
-- confirming the mechanism is specifically about
`executeReadWrite`'s checkpoint placement, not something that would
show up in ordinary Ruby code manipulating local variables.

**Real fix, not attempted here**: `RubyThread#postReadWrite`/
`executeReadWrite` need to stop discarding an already-successful
`task.run()` result when the interrupt checkpoint fires immediately
after it returns -- e.g., snapshot the result before polling, and
re-raise the pending interrupt only after making the already-earned
value available somehow (not a trivial fix: `executeReadWrite`'s
signature returns a bare `int`, with no channel to stash a successful
read into the way `salvagePartialRead` does for its own narrower
case). This is shared, universal, extremely hot-path code underneath
*every* blocking IO read and write in JRuby -- files, pipes, every
socket type -- not scoped to sockets at all. Squarely its own JRuby
core investigation and PR, with its own need for equally rigorous
testing before touching it; not a rider on #9040.

**Test harnesses** (JRuby-jep-380 scratchpad, reusable for that future
investigation): `dataloss_ground_truth.rb` -- the same total-byte
stress scenario as Landmine 14's `dataloss_test.rb`, but compares a
Java-level ledger (`org.jruby.RubyIO::LEDGER`, incremented only inside
`getPartialCommon`'s own successful-return path -- a TEMPORARY
instrumentation hook, already removed from the shipped diff, re-add
it the same way if resuming this investigation) against bytes
actually sent, eliminating any Ruby-level "read succeeded but the
*test's own* bookkeeping statement got interrupted before recording
it" ambiguity that a purely Ruby-level test cannot rule out on its
own.

## Landmine 16

Found running the actual `jruby/jruby` `ruby/spec` suite (the shared,
cross-implementation compatibility tests both MRI and JRuby run --
not a JRuby-only addition) against a clean build of this PR:
`spec/ruby/library/socket/unixsocket/initialize_spec.rb`'s "sets the
socket to nonblock" case failed. `UNIXSocket#initialize` must leave
`IO#nonblock?` reporting `true` -- MRI's own documented rationale
(every fd is non-blocking internally, `rb_thread_io_blocking_region`,
regardless of what blocking behavior Ruby code actually observes).

`RubyUNIXSocket_init.java`'s change (Landmine 12/13: leave a named
JEP-380 unix channel's real fd genuinely blocking, for
`UnixSocketNativeChannel`'s FFM fast path) broke this: the ORIGINAL
`init_sock` tied the Ruby-visible `nonblock?` flag directly to the
real channel's OS-level blocking mode via `OpenFile#setBlocking`
(`nonblock = !blocking; fd.chSelect.configureBlocking(blocking);` --
one call, two effects), and since this patch deliberately skips
calling `setBlocking` at all for a unix channel (to keep the real fd
blocking), `nonblock?` fell through to its default, `false`.

Fixed with `OpenFile_setNonblockFlag.java` -- a new, narrow method
that sets *only* the bookkeeping flag (`this.nonblock = nonblock;`),
decoupled from the real channel's mode entirely. Confirmed safe
against `OpenFile#performRead`/`performWrite`'s own `nonblock`
branch, which only ever special-cases a literal `0` return as EAGAIN
-- `UnixSocketNativeChannel` already maps a real EOF to `-1` (the
`ReadableByteChannel` contract, via `UnixFdChannel#rawRead`), so it
never produces that literal `0` in the first place; there is nothing
for the flag to misroute. Verified: `spec/ruby/library/socket/
{unixsocket,unixserver}` both 0 failures/0 errors after the fix
(63/72 and 28/32 examples/expectations respectively); real OS-level
fd confirmed still `O_NONBLOCK=false` via `/proc/self/fdinfo`
(the FFM fast path's actual performance characteristic, unaffected);
`salvagePartialRead`/`setNonblockFlag`/the FFM throughput gain all
re-verified together afterward with no regressions.

Caught specifically because this session ran the *official* `mspec`
suite (`spec/mspec/bin/mspec-run -B spec/jruby.mspec spec/ruby/
library/socket/<dir>`) against a clean, `#9040`-scope-only build, not
just this repo's own custom probes/benchmarks -- worth running before
calling ANY change to this area "PR ready." `spec/ruby/library/
socket/{basicsocket,socket,tcpsocket}` hang in this sandbox and
`spec/ruby/library/socket/{udpsocket,tcpserver,ipsocket}` show
pre-existing failures -- confirmed identical (same counts, same
hangs) on a completely vanilla, unpatched JRuby checkout, unrelated
to this PR; `unixsocket`/`unixserver` are the ones that matter here
and both are clean.

## Landmine 17

**Non-blocking reads/writes and EOF on the FFM channels.** Found 2026-10-21
by the valenthree pre-PR gate (patched vs stock vs MRI probe, plus
`basicsocket/recv_nonblock_spec`, which hung forever). Three separate
mechanisms:

1. `UnixFdChannel` (pair/for_fd) is not a `SelectableChannel`, so
   `OpenFile#setNonblock` can't flip it and `PosixShim#checkForBlocking`
   returned a fake EAGAIN for every `read_nonblock` without reading --
   with data waiting, and at EOF (MRI: EOFError). `write_nonblock` did a
   plain blocking write. JNR's pair channel was selectable, so this was a
   regression from this patch. Fix: `UnixFdChannel#readNonBlocking` /
   `#writeNonBlocking` (one syscall with the fd temporarily O_NONBLOCK, same
   fcntl toggle as `UnixDomain#recvFromNonBlocking`), routed from
   `PosixShim`. See `patches/PosixShim_unixFdNonblock.java`.
2. A named socket's `UnixSocketNativeChannel` shares its fd with the
   JDK `SocketChannel`; `recv_nonblock`/`read_nonblock` flip that channel
   non-blocking around the call, so the raw FFM `read(2)` can see EAGAIN.
   `nativeRead`/`nativeWrite` turned EAGAIN into an IOException
   (`recv_nonblock` with no data raised IOError -- also a regression). They
   now return `UnixFdChannel.WOULD_BLOCK`, which `readResult`/`writeResult`
   map to the Channel contract's 0. It needs its own value because
   `read(2)`'s 0 already means EOF.
3. Not caused by this patch, fixed alongside since the same spec exercises
   it: `RubyBasicSocket#doRead` folded EOF and "no data" into one null, so
   `recv_nonblock` raised EAGAIN at EOF forever on every stream socket
   (TCP too) instead of returning nil. `readChannel` keeps the raw count;
   `NONBLOCK_EOF` carries EOF. Empty datagrams were also lost both ways
   (receive side read `buffer.position()` instead of `receive()`'s return;
   send side went through syswrite/`DatagramChannel.write`, which skip the
   syscall for an empty buffer). See `patches/RubyBasicSocket_nonblockEof.java`.

Still open, deliberately: `UNIXSocket.pair(:DGRAM)` creates a stream pair
(`UnixDomainNative.pair` hard-codes SOCK_STREAM; stock JNR does the same).
`basicsocket/*` also has many failures identical on stock that are plain
missing JRuby features (`recvmsg`/`sendmsg`/`getpeereid`, `ioctl` with a
String, send/recv flags, TTL/CORK/V6ONLY socket options) -- out of scope.

## Landmine 18

**The full `library/socket` pass (2026-10-21).** Everything `spec/ruby/library/socket`
found, fixed against MRI 4.0.5 on the same host (MRI passes all of it there, so every
failure was JRuby's). Design points a reviewer will ask about:

- **No JNR unix channels.** `Socket.new(:UNIX, :STREAM)` opens a JEP-380 channel
  (`bind` was a no-op on the JNR one); `Socket.socketpair`/`UNIXSocket.pair` use FFM
  `socketpair(2)` and honor `:DGRAM`. JNR's `UnixSocketAddress` survives only as the
  address value type `Sockaddr`/`Addrinfo` hand around; `RubySocket.jdkAddress`
  converts it for JDK channels.
- **Blocking waits.** Every FFM call that can block issues `MSG_DONTWAIT` and waits in
  `RubyBasicSocket#waitFd`: `poll(2)` run as a `RubyThread` task (status "sleep",
  kill/raise wake it), in slices backing off 1 -> 100 ms (Wave governor shape). The
  same wait now backs blocking datagram `recv`/`recvfrom`, which were unkillable
  ("run" forever) on stock JRuby too. `OpenFile#waitReadable` can't be used for these:
  for a channel in blocking mode it returns at once.
- **Scratch + bits.** New FFM calls carve their buffers from `UnixFdChannel.scratch()`
  (one get per syscall -- a later get may resize). `RubyBasicSocket.sockBits` caches
  per-socket facts (unix/dgram/native fd) once, read lock-free, Builtins-style; not
  cached while a java.nio socket is still unbound.
- **`Socket#listen`** swaps the bound `SocketChannel` for a `ServerSocketChannel` on
  the same address (shared options copied). The fd number changes at `listen`.
- **`nonblock?`** reports MRI's default (true) without flipping the internal flag:
  setting `fptr.nonblock` would skip the pre-read select that keeps Thread#kill from
  closing a java.nio channel (Landmine 12/13).
- **Packed sockaddrs** are the platform's real struct layout (native-order family;
  `sin6_flowinfo` present) -- they go to the kernel via `sendmsg`.
- **Options** go to the kernel's getsockopt/setsockopt when FFM is available, except
  `SO_RCVTIMEO`/`SO_SNDTIMEO`, `SO_ERROR` and `IP_ADD_MEMBERSHIP`, which JRuby emulates
  on java.nio.

Not fixed (not a socket issue): a Java-implemented method in another thread's
`Thread#backtrace` is labeled `block in accept` instead of MRI's `TCPServer#accept`
(same for `sleep`, `Queue#pop`, stock too) -- `tcpserver/accept_spec`'s SIGVTALRM example.

## Landmine 19

**Backtrace labels (separate patch: `patches/jruby-backtrace-labels.diff`).** JRuby targets
Ruby 4.0 but never produced Ruby 3.4's qualified labels. Design: resolve each frame's Ruby
identity once, when it becomes known, never on the call path.

- **Java-implemented methods**: `AnnotationBinder`'s generated populators now pass the target
  module (`runtime.addBoundMethods(cls, ...)`) and mark `meta` methods with a leading `.`;
  `Ruby#addBoundMethods(RubyModule, ...)` stores `Owner#name` / `Owner.name`. Old precompiled
  populators (extension jars) still call the old overload and get bare names.
- **Ruby methods**: the `def` helpers record the owner label on the `IRScope`
  (`recordBacktraceLabel`) *before* the method object exists (with `jit.threshold=0` it JITs
  at once). The interpreter pushes it (one field read); the JIT names its frame from it. A
  scope defined into two different owners falls back to the bare name -- never a wrong owner.
- **Whole compiled scripts** are compiled before their methods are defined, so their frame
  names are bare: `JVMVisitor` registers frame name -> scope for script classes
  (`Ruby#registerCompiledFrames`, weak refs) and `BacktraceData` resolves the label at
  backtrace time. Per-method/block JIT classes are *not* registered: every JIT class from one
  file shares a class name, so lookups could hit another method's scope.
- **Blocks**: depth and top computed once per block scope (`IRScope#getBlockFrameName`,
  cached in `IRBlockBody`); interpreted block frames carry the full `block ... in X` so an
  eval frame that copies the frame name keeps it. Script tops are `<main>` vs
  `<top (required)>` (`IRScriptBody#getTopLabel`).
- **Other-thread bug**: masking a native frame duplicated the next Ruby frame *with that
  frame's type* -- a block -- so `sleep` became `block in sleep`.
- **module_function**: the singleton copy gets a per-method-object label `M.name`; the scope
  keeps `M#name`.

- **define_method**: the body is a block converted to an `IRMethod`; it remembers its block
  (`IRMethod#getDefinedFrom`) and is labeled as it ("block in <class:C>"), in interpreter, JIT
  and registry paths; nesting depth counts through it.
- **eval / binding in compiled code**: compiled frames push no backtrace elements, so
  `Kernel#eval` and `Binding` creation take label/file/line from the innermost Ruby frame on
  the Java stack (`ThreadContext#getCurrentFrameLabel`, `BacktraceData#currentCompiledFrame`):
  a short stack walk, only at eval/binding time.

Known limit: once compiled, a module_function's two copies share one frame name, so a call on
the module shows `M#name` (interpreted calls show `M.name` / `M#name` correctly).
Results: `core/thread/backtrace` 4 failures (stock 41; all 4 are path canonicalization),
`core/thread` 17 (stock 58), and no spec anywhere in core/thread, kernel, exception,
method, unboundmethod, proc, module, language that passes on stock fails here.

## Landmine 20

**"Small-message overhead" was the benchmark, and the real per-call cost was `sysread`'s
pre-read Selector.**

The first pinned/shuffled benchmark showed JEP-380 behind JNR at 100B/1KB (p50 +2-3us).
Measured three ways, that gap was not the socket path:

- Single-thread, no context switch (`kj-micro.rb`): `send`/`recv` 2.3us vs JNR 2.7us per pair.
- Long-lived connection, cross-thread ping-pong (`kj-pp.rb`): p50 10.0us vs JNR 11.3us.
- Fresh connection every 5000 round trips (`kj-pp2.rb`): both builds take ~20-25k round
  trips to reach steady state (c0 25-35us -> c4+ ~10.5us). `bench_long.rb`'s 2000-iteration
  warm-up timed the JIT, not the socket. With `BENCH_WARMUP=25000` every size is ahead:
  100B 1.06x, 1KB 1.09x, 10KB 1.04x, 100KB 1.30x (evidence/20261022-bench-long-pinned-warm25k).

The micro-test did show one real cost: `IO#sysread` was ~8.5us per 100-byte read on both
builds. JFR: `RubyIO#sysreadCommon` calls `RubyThread#select` before every read (MRI's
`rb_thread_wait_fd`), which configures the channel non-blocking, registers it with a pooled
Selector, selects, deregisters and configures it blocking again.

Fix (`RubyThread#selectReadyOps`): when the channel has a real OS fd (`ChannelFD#chNative` --
jnr's channels and, via `ChannelFD#jepUnixNativeChannel`, JEP-380 unix sockets), ask
`poll(2)` with a zero timeout first. Ready -> `pollThreadEvents()` (as the selector path does
after waking) and return; not ready with timeout 0 -> return 0; otherwise fall through to the
unchanged Selector wait, so kill/raise/Timeout/close still wake a blocked reader. The pollfd is
written into the calling thread's FFM scratch segment (`UnixFdChannel.scratch`), so the probe
allocates nothing. Single-op requests only (the probe says ready-or-not, not which op).

Result: `syswrite`+`sysread` 8.5us -> 3.2us (JNR 8.0-8.8us). Blocked-reader wake cases
(`kj-wake.rb`: raise/kill/Timeout/close/data/EOF/read_nonblock/IO.select/pipe) identical to MRI 4.0.5.

## Landmine 21

**Rebase onto master `94156c4` and the scope split (2026-10-22).** 303 upstream commits had added
Fiber::Scheduler support to sockets (Sampo Kuokkanen, Albert Álef). Ten conflicts; upstream's
scheduler `accept`, `sysaccept` fd registration, accept retry and block-form `pair` were kept and
moved onto the JEP-380/FFM channel types (details: `PR-9040-NOTES.md` section 4).

The branch `jep380-pr-9040` splits the work by changed line into five commits (general IO fixes,
#9040, MRI compatibility, sysread poll, backtrace labels); each compiles on its own, and commit 2
alone passes `unixsocket`/`unixserver`.

Two findings while organizing:

- `bin/jruby` already passes `--enable-native-access=org.jruby.dist` (since March 2025), so FFM
  works without `-J--enable-native-access=ALL-UNNAMED`; the harness flag was never needed.
  Embedders loading `jruby.jar` from the classpath get the JDK's restricted-method warning, as
  jffi already causes on stock.
- With native access denied (`--illegal-native-access=deny`, embedded), `UNIXSocket.pair(:DGRAM)`
  leaked a Java `UnsupportedOperationException`. Now `Errno::EOPNOTSUPP`, as `Socket.pair` was.
  Stream pairs fall back to a JEP-380 pair and named sockets are unaffected (checked).

## Landmine 22

**The first CI round: Java 21, and jruby's own suites.** Everything before had run on JDK 25 only
(valenthree's default `java`; `source`/`target 21` still compiles against the running JDK's class
library, so `java.lang.foreign` compiled). jruby's minimum is Java 21, where FFM is a preview API,
so every Java 21 job failed to compile. `UnixDomainNative` is now a facade with no FFM types that
picks a backend at class init (Kestowv's detect-then-route shape): `UnixDomainFFM` from
`core/src/main/java22` on 22+, built by an `ffm` Maven profile only on a 22+ build JDK; otherwise
every capability bit is off. Enabling preview instead would tie the class files to exactly Java 21
and make every user pass `--enable-preview`.

Running jruby's CI suites locally on JDK 21 and 25 (`ci.yml`'s bootstrap build + `rake <target>`)
then found the rest: fallback gaps against master on 21 (flags, fd passing, for_fd, the sendmsg API
surface, IPv6 option constants, the temp-path pair's peer address), GH-3799 and a scheduler-read hang
on the FFM pair path, missing `MSG_CMSG_CLOEXEC`, SEQPACKET pairs, a Ruby label leaking into
Java-format frames, jruby tests pinned to pre-3.4 labels and AF_UNSPEC, and `File.identical?` on
pipes. Full table: `PR-9040-NOTES.md` section 8. Every remaining local failure also fails on master
on the same host.
