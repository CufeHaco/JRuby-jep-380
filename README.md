# JRuby JEP-380 UNIXSocket prototype

Work toward [jruby/jruby#9040](https://github.com/jruby/jruby/issues/9040):
`UNIXSocket` / `UNIXServer` on JDK Unix-domain channels, plus FFM for
the IPC ops JEP-380 does not provide.

Current branch: `jep380-ffm-threadcontext`
Base: `CufeHaco-JEP-380-full-prototype`

JRuby 10.1 still uses JNR for Unix sockets. This repo is the landing
shape for a core patch, not a second IO stack.

## What belongs in jruby/jruby

**`patches/jruby-jep380.diff` is the patch.** A unified diff against
jruby/jruby `0e6e01b` (master, 2026-09-20); apply with
`git apply patches/jruby-jep380.diff` -- verified to apply cleanly. It is the
whole change: the five new `ext/socket/UnixDomain*.java` / `UnixFdChannel` /
`UnixSocketNativeChannel` files plus every edit to existing JRuby files.
It does not include the separate `Builtins.java` race fix (#9116/#9119).

What it does beyond the original #9040 scope (see INTERPRETER.md
Landmines 17-18): no JNR unix channels remain (`Socket.new(:UNIX, :STREAM)`
is JEP-380, `Socket.socketpair` / `UNIXSocket.pair` are FFM and take
`:DGRAM`); `Socket#listen/#accept/#sysaccept`; `sendmsg`/`recvmsg` and
`Socket::AncillaryData`; native `getsockopt`/`setsockopt`, `ioctl`,
`getpeereid`, recv/send flags; MRI-layout packed sockaddrs; and a list of
MRI-compat fixes. All of `spec/ruby/library/socket` passes except one
example that depends on JRuby's general backtrace labeling.

The per-method fragments below are the earlier, hand-maintained form of the
same change; they no longer cover everything -- use the diff.

**`patches/jruby-backtrace-labels.diff`** is a second, independent patch (JRuby
core, not sockets): Ruby 3.4+/4.0 backtrace labels -- `Foo#bar`, `Foo.baz`,
`Array#each`, `block (2 levels) in Foo#bar`, `<top (required)>` -- in every
execution mode, plus the other-thread `block in sleep` mislabel. Applies on its
own to `0e6e01b` or on top of the socket diff. See INTERPRETER.md Landmine 19.

```
core/src/main/java/org/jruby/ext/socket/UnixDomain.java
core/src/main/java/org/jruby/ext/socket/UnixDomainNative.java
core/src/main/java/org/jruby/ext/socket/UnixDomainContext.java
core/src/main/java/org/jruby/ext/socket/UnixFdChannel.java
core/src/main/java/org/jruby/ext/socket/UnixSocketNativeChannel.java
patches/SocketType_forChannel.java     → edit SocketType.java
patches/RubyUNIXSocket_init.java       → edit RubyUNIXSocket.java
patches/RubyUNIXServer_accept.java     → edit RubyUNIXServer.java
patches/RubyBasicSocket_unixAddr.java  → edit RubyBasicSocket.java
patches/FilenoUtil_getDescriptorFromChannel.java → edit FilenoUtil.java
patches/RubySocket_unixDgram.java      → edit RubySocket.java
patches/Helpers_errnoFromMessage.java  → edit runtime/Helpers.java
patches/RubyUDPSocket_unixDgram.java   → edit RubyUDPSocket.java
patches/ChannelFD_jepUnixNativeChannel.java → edit ChannelFD.java
patches/OpenFile_salvagePartialRead.java → edit OpenFile.java
patches/RubyIO_salvagePartialRead.java → edit RubyIO.java
patches/OpenFile_setNonblockFlag.java → edit OpenFile.java
patches/RubySocket_unboundInet.java    → edit RubySocket.java
patches/Addrinfo_socketAddress.java    → edit Addrinfo.java
patches/RubyBasicSocket_nonblockEof.java → edit RubyBasicSocket.java, RubyUDPSocket.java
patches/PosixShim_unixFdNonblock.java  → edit util/io/PosixShim.java
```

See [INTERPRETER.md](INTERPRETER.md).

`src/*Channel.java`, TCP/SSL, and `lib/jruby_sockets.rb` are the old
factory prototype. Do not copy them into JRuby.

## Run

These scripts use stdlib `UNIXSocket` / `UNIXServer`. On stock JRuby
that is still JNR. They check the Ruby API, not the new Java classes.

```
ruby demo.rb
ruby test_sockets.rb
jruby test_sockets.rb
./test.sh
```

FFM path (after the Java files are on JRuby’s classpath):

```
jruby -J--enable-native-access=ALL-UNNAMED test_sockets.rb
```

## Layout

| Path | What it is |
|---|---|
| `core/src/main/java/org/jruby/ext/socket/` | interpreter classes for #9040 |
| `patches/` | edits to existing JRuby files |
| `src/` | old channel-factory prototype |
| `demo.rb`, `test_sockets.rb`, `benchmark.rb` | Unix API checks |
| `unix_hub.rb`, `chat_demo.rb` | multi-process Unix examples |
| `tcp_demo.rb`, `test_tcp_sockets.rb`, `ssl_demo.rb` | TCP/SSL, out of scope for #9040 |
