# JEP-380 UNIX sockets: PR notes (jruby/jruby#9040)

Branch: [`CufeHaco/jruby` `jep380-pr-9040`](https://github.com/CufeHaco/jruby/tree/jep380-pr-9040),
5 commits on jruby/jruby `master` `94156c4` (2026-10-22). Author: Troy Mallory (CufeHaco).

These notes are for writing the PR and for review. Section 1 says what each commit is and
whether it is in #9040's scope. Section 2 lists every bug found and fixed along the way.
Section 3 lists what was found and deliberately left alone. Section 4 covers the rebase onto
current `master`. Section 5 lists the design trade-offs a reviewer should know about.
Section 6 has the test results. The full history of each finding is in `INTERPRETER.md`
(Landmines 2-20).

## 1. Branch layout and scope

| # | commit | scope | files | builds alone | tested alone |
|---|---|---|---|---|---|
| 1 | Keep bytes from interrupted reads and report socket EOF to recv_nonblock | **out of scope**: general IO bugs, reproduce on stock with TCPSocket | 3 | yes | - |
| 2 | Move UNIX sockets to JEP-380 channels and FFM (#9040) | **in scope** | 16 | yes | unixsocket + unixserver specs 0F/0E, wake-up probe = MRI |
| 3 | Match MRI across library/socket | **out of scope**: MRI compatibility, most of it fails on stock too | 11 | yes | - |
| 4 | Poll native fds before registering a Selector in selectReadyOps | **out of scope**: performance (sysread), needs commit 2's FFM layer | 1 | yes | - |
| 5 | Qualified backtrace labels for Ruby 3.4+ | **out of scope**: unrelated to sockets, can be its own PR | 24 | yes | - |

Commits 1-4 together equal the tested socket diff byte for byte, and commit 5 equals the tested
labels diff. The whole branch was tested as one build (section 6).

Suggested PR shapes, from least to most to review:

- **#9040 only**: commits 1 + 2. Commit 2 needs commit 1's `NONBLOCK_EOF`/`readChannel`.
- **#9040 + socket compatibility**: commits 1-4.
- **Backtrace labels**: commit 5 on its own branch. It applies to `master` without the socket
  commits (checked).

### Things that cross a scope line

The split is by changed line, not by file, so a few out-of-scope lines ride in commit 2 because
the in-scope code needs them:

- `RubyBasicSocket`: `waitFd` (the interruptible poll wait) replaces the `sendmsg_nonblock`
  NotImplemented stub in the same hunk. Between commits 2 and 3, `sendmsg_nonblock` is missing
  from Java; commit 3's `socket.rb` defines it.
- `RubySocket#connect`: removing the jnr `UnixSocketChannel` branch and adding the EISCONN check
  (MRI) are one change.
- `RubySocket#initChannelFD`: the JEP-380 stream channel, the FFM datagram socket and
  `javaFamily()` (INET/INET6 family for java.nio) were written together.
- `UnixDomainNative` is one new file. It holds the FFM bindings that commits 3 and 4 use too
  (`getsockopt`/`setsockopt`, `ioctl`, `recv`/`send` with flags, `poll`).

## 2. Bugs found and fixed

### 2a. In the JEP-380 code itself (caught before they could ship)

These are mistakes or gaps in this patch, found by specs, probes, stress tests or review. None
are in the branch; they are listed so a reviewer knows which edges were already checked.

| bug | effect | fix |
|---|---|---|
| `SocketType.forChannel` read a JEP-380 unix channel as TCP | `.socket()` threw UnsupportedOperationException | unix check first |
| Address helpers assumed jnr's `UnixSocketAddress` (L2) | NPE / ClassCastException in `peeraddr`, `getpeername`, `getsockname`, `local_address`, `remote_address` | `UnixDomain.isUnixAddress`/`pathOf` accept both types |
| `FilenoUtil` did not know `UnixFdChannel` (L3) | `fileno` returned a fake fd; `send_io` got EBADF | real fd from the channel |
| `SocketType` had no `UnixFdChannel` branch (L3) | `close_write` on a pair did nothing; the peer's read **hung forever** | `shutdown(2)` on the fd |
| `localAddress`/`remoteAddress` returned null for a pair (L3) | `getpeername` raised ENOTCONN on a connected pair | empty-path address, as MRI |
| errno read with a second `__errno_location()` downcall (L4) | stale errno under load; `rescue Errno::EPIPE` never matched on pairs | `Linker.Option.captureCallState("errno")`, errno mapped to the messages `Helpers` recognises |
| `recvfrom(-1)` on the native path (L6) | told the kernel the 1-byte buffer was `size_t`-max bytes (**memory safety**) | ArgumentError, as MRI |
| send on an unconnected unix datagram socket (L6) | wrong errno; Linux returns ENOTCONN, not EDESTADDRREQ | both mapped, `Helpers#errnoFromMessage` extended |
| `getsockopt(SO_TYPE)` on every `recvfrom` (L7) | ~1.2us per call | cached per channel |
| `UnixDomainContext` behind one `synchronized` WeakHashMap (L8) | every thread doing fd passing shared one lock | `ConcurrentWeakHashMap` |
| `Socket.new(:UNIX, :DGRAM)#recvfrom` / `recvfrom_nonblock` (L9, L10) | ClassCastException (`(DatagramChannel)` cast) | shared native `recvfrom` helpers |
| `recv_nonblock` on a `UnixFdChannel` (L10) | **always** EAGAIN, even with data waiting | real non-blocking `recvfrom(2)` |
| `remote_address` on a bound, unconnected datagram socket (L10) | empty address instead of ENOTCONN | `getpeername(2)` |
| Scratch buffer only grew (L11) | one large message pinned that much native memory per thread for its life | shrink governor (hysteresis, floor), Wave's shape |
| Named sockets went through select + the JDK channel's read (L12, L13) | ~30% slower than jnr; and the JDK read **closes the channel** on `Thread#raise`/`Timeout` (1000/1000 connections broken in a stress loop) | `UnixSocketNativeChannel`: plain blocking `read(2)`/`write(2)` on the fd, as jnr |
| Keeping the fd blocking left `nonblock?` false (L16) | `unixsocket/initialize_spec` failed | `OpenFile#setNonblockFlag`, flag only |
| `read_nonblock`/`write_nonblock` on pairs (L17) | fake EAGAIN without reading, at EOF too; `write_nonblock` blocked | one syscall with the fd briefly O_NONBLOCK |
| EAGAIN on a named socket's native read (L17) | `recv_nonblock` with no data raised IOError | `WOULD_BLOCK` mapped to the Channel contract's 0 |
| `UNIXSocket.pair(:DGRAM)` with native access denied (found organizing this branch) | raw Java `UnsupportedOperationException` instead of a Ruby error | `Errno::EOPNOTSUPP`, as `Socket.pair` already did |

### 2b. Pre-existing JRuby bugs fixed (stock JRuby has them too)

| bug | where | commit |
|---|---|---|
| `Thread#kill`/`raise` mid-read loses bytes already read from the fd (L14) | `IO#read(n)`, `readpartial`, `read_nonblock`, `sysread`; any IO | 1 |
| `recv_nonblock` raises EAGAIN forever at EOF instead of nil (L17) | every stream socket, TCP too | 1 |
| Zero-length datagrams lost on receive and send (L17) | UDP | 1 (receive), 3 (send) |
| `UNIXSocket`/`UNIXServer` never set binmode or nonblock (L5) | `init_sock` | 2 |
| `UNIXSocket#recvfrom` rejected its 3-argument form (L5) | | 2 |
| `UNIXServer.for_fd(...).path` NPE (L5) | | 2 |
| `Socket.new(:UNIX, :DGRAM)` never worked: an INET DatagramChannel, then a null-message SocketError on bind (L6) | missing capability | 2 |
| `Socket.new(:UNIX, :STREAM)#bind` was a no-op (jnr) (L18) | | 2 |
| Blocking UDP receive could not be interrupted: thread stuck "run" until the socket closed (L18) | | 3 |
| Everything else `library/socket` found: flags, sendmsg/recvmsg, sockopts, ioctl, getpeereid, addresses, constants, `ResolutionError`, `Addrinfo` marshal (L18) | 216 failures + errors on `master` in `library/socket/socket` alone | 3 |
| `IO#sysread` paid ~6us per call for Selector setup (L20) | every channel with a real fd, jnr too | 4 |
| No Ruby 3.4+ qualified backtrace labels; another thread's backtrace showed `block in sleep` for `sleep` (L19) | backtraces | 5 |

## 3. Found, not fixed (pre-existing, each worth its own issue)

- **`Thread.interrupt()` inside any java.nio channel read closes the channel** (L14). `RubyThread#interrupt`
  calls the JVM's `Thread.interrupt()`; if the target is inside an `AbstractInterruptibleChannel`
  read just after `select()`, the channel closes. About 1 in 10 under a sustained 2MB transfer with
  a kill at a random point; plain `TCPSocket`, stock JRuby. Fixing it means changing
  `RubyThread`'s interrupt delivery for every IO.
- **`executeReadWrite` discards a successful read when an interrupt is pending** (L15).
  `postReadWrite` runs `pollThreadEvents()` in a `finally`, so a pending kill/raise replaces the
  already-computed byte count, and the bytes are gone. About 5% in the same stress test on stock
  TCP and jnr; commit 1's `salvagePartialRead` cannot reach it (the count never arrives). Hot path
  for every blocking IO call.
- **Compiled `module_function`**: once JIT-compiled, both copies share one frame name, so a call on
  the module shows `M#name` (interpreted shows `M.name` correctly).
- **`core/thread/backtrace`**: 4 failures left (`master`: 41), all path canonicalization.
- **`IO#isatty is an alias of IO#tty?`** fails once `io/console` is loaded: master's bundled
  io-console defines `PlatformTty#tty?`. Same on `master` without these commits.

## 4. Rebase onto current master (2026-10-22)

The work was built on `0e6e01b` (2026-09-20) and rebased onto `94156c4`, 303 commits later.
Upstream had added Fiber::Scheduler support to sockets meanwhile, mostly by Sampo Kuokkanen and
Albert Álef. Ten conflicts, all resolved by keeping upstream's behaviour on the new channel types:

| file | conflict | resolution |
|---|---|---|
| `RubyUNIXServer` (3) | upstream's scheduler `accept` (#9813) and accept retry were written against jnr's `UnixServerSocketChannel` | same logic on the JDK `ServerSocketChannel`; the non-blocking accept returns null instead of throwing an EAGAIN that upstream matched by message text |
| `RubyUNIXSocket` (2), `RubySocket` (1) | upstream added the block form of `pair` (yield, then close both) | our FFM pair (stream + datagram) feeding upstream's `yieldPair` |
| `RubyTCPServer` (1) | upstream's #9818 made `sysaccept` register its fd: the same fix as ours | upstream's version |
| `RubyTCPSocket` (1) | upstream moved the connect wait into a scheduler-aware `waitConnectable` | upstream's structure plus our `open_timeout` fallback (MRI) |
| `Sockaddr` (1) | Charles's `uint16` helper vs our `familyOf` | `familyOf`, which is built on `uint16` |
| `IRClosure` (1) | Erik Berlin's coverage line next to our `define_method` line | both |

Two upstream fixes landed independently of ours: `sysaccept` registering the accepted fd (#9818)
and `UNIXServer#accept` retrying when no connection was there. Both are kept as upstream wrote
them. None of the conflicting changes were Troy's (his Builtins work, #9174, is in `master` and does not
touch these files), and Charles's changes in that range (packing helper, `pread` EOF, a
thread-wait revert) did not touch our paths.

A Kestowv teardown check that occasionally fails (130/14 instead of 135/9) turned out to be
Kestowv's own timing: 1 in 26 gate runs on the patched build and 1 in 26 on stock JNR.

## 5. Design trade-offs to call out in the PR

- **A blocked read on a named UNIXSocket cannot be interrupted** by Thread#kill/raise/Timeout. This is
  what jnr does today; the JDK channel's interruptible read was the alternative, and it closes the
  connection when interrupted (section 2a). Datagram and flagged calls *are* interruptible
  (`waitFd`).
- **`waitFd` polls in slices** backing off from 1ms to 100ms (poll(2) cannot be woken from Java):
  readiness is immediate, an interrupt is seen within one slice, an idle wait costs ~10 wakeups/s.
- **Native access.** `bin/jruby` already passes `--enable-native-access=org.jruby.dist` (since
  March 2025), so FFM works out of the box. An embedder loading `jruby.jar` from the classpath gets
  the JDK's restricted-method warning (jffi triggers the same warning on stock). With native access
  denied (`--illegal-native-access=deny`), the FFM pieces fail closed: stream pairs fall back to a
  temporary-path JEP-380 pair, named sockets are unaffected, and datagram pairs raise
  `Errno::EOPNOTSUPP` (stock JRuby has no datagram pairs at all). Checked both ways.
- **`Socket#listen` replaces the bound SocketChannel with a ServerSocketChannel** on the same address,
  so the fd number changes at `listen`.
- **`nonblock?` reports MRI's default (true)** without flipping the internal flag, because setting it
  would skip the pre-read select that keeps Thread#kill from closing java.nio channels.
- **jnr's `UnixSocketAddress` survives as a value type** in `Sockaddr`/`Addrinfo`; JDK channels get
  a `UnixDomainSocketAddress` through `RubySocket.jdkAddress`.
- **Per-thread FFM scratch buffer** (`UnixFdChannel.scratch`) is a plain ThreadLocal: it is filled and
  drained within one call and never seen across a fiber switch. Fiber-visible state
  (`UnixDomainContext`) hangs off ThreadContext instead.

## 6. Test results (valenthree, OpenJDK 25, MRI 4.0.5 as reference)

On the rebased branch against plain `master` built the same way:

- `library/socket`: every directory 0 failures, 0 errors, including upstream's new scheduler
  specs. `master`: 216 failures + errors in `socket/`, hangs in `basicsocket/`.
- 25 suites (socket, core/io, thread, fiber, kernel, exception, method, unboundmethod, proc,
  module, binding, language, timeout, io-wait): **no failure on the branch that passes on master**.
  `core/thread` 17 failures vs master's 58; `core/thread/backtrace` 4 vs 41.
- MRI probes (INET addresses, EOF behaviour) and the blocked-read wake-up probe (raise, kill,
  Timeout, close, data, EOF, read_nonblock, IO.select, pipe): identical to MRI.
- Kestowv 0.5.6 regression suite: 135/9 on both builds; stress 0 errors over ~290M operations.
- Benchmark vs master's jnr (UNIXSocket round trips, cores 2-5, 10 shuffled rounds, 25k warm-up,
  shared host under load): 100KB 1.28x (1.24-1.39 per round); 100B-10KB within noise (0.95-1.02x).
  An earlier quieter run on the old base: 1.06x / 1.09x / 1.04x / 1.30x. `syswrite`+`sysread`
  2.5x faster than jnr (commit 4).

Evidence for each run is under `evidence/`.

## 7. Draft PR description

> **Move UNIX sockets to JEP-380 channels and FFM (#9040)**
>
> UNIXSocket, UNIXServer and `Socket.new(:UNIX)` now use the JDK's unix-domain
> SocketChannel/ServerSocketChannel instead of jnr-unixsocket. What java.nio can't do
> (socketpair, unix datagrams, SCM_RIGHTS, getpeername, fcntl, poll) goes through FFM.
> Named sockets read and write with a plain blocking `read(2)`/`write(2)` on the fd, as jnr
> did, because the JDK channel's interruptible read closes the connection on Thread#raise.
>
> Commits: (1) two IO bugs found along the way that reproduce on stock with TCPSocket;
> (2) the migration; (3) the rest of `library/socket` against MRI; (4) a poll(2) fast path
> that makes `sysread` 2.5x faster; (5) Ruby 3.4 backtrace labels, which can be split out.
> 1+2 is the minimal #9040 change; each commit builds on its own.
>
> `library/socket` passes completely; nothing in core/io, thread, fiber, kernel, exception,
> method, proc, module or language that passes on master fails here. FFM works with the
> stock launcher (it already grants native access); with native access denied, the FFM parts
> fail closed.
>
> Trade-off to look at: a blocked read on a named UNIXSocket can't be interrupted (same as jnr
> today). Two pre-existing interrupt/IO bugs found while testing are described but not fixed
> here; they need their own issues.
