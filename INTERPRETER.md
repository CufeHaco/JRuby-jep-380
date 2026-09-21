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
| `patches/SocketType_forChannel.java` | edit `SocketType.java` |
| `patches/RubyUNIXSocket_init.java` | edit `RubyUNIXSocket.java` |
| `patches/RubyUNIXServer_accept.java` | edit `RubyUNIXServer.java` |

TCP/SSL files on this branch stay as prototype history. They do not
belong in the #9040 PR.

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
`channelFromFd` is JNR `fromFD` as a wrapper only.

## CRuby thread frame

Do not use `java.lang.ThreadLocal` for waiting_fd. Fibers swap
`ThreadContext`. Hang `UnixDomainContext.Frame` off that.

```java
UnixDomainContext.blocking(context, fd, "recvmsg", () -> {
    return UnixDomainNative.recvIo(fd);
});
```
