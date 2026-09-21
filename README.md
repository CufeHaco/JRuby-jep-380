# JRuby JEP-380 UNIXSocket prototype

Work toward [jruby/jruby#9040](https://github.com/jruby/jruby/issues/9040):
`UNIXSocket` / `UNIXServer` on JDK Unix-domain channels, plus FFM for
the IPC ops JEP-380 does not provide.

Current branch: `jep380-ffm-threadcontext`
Base: `CufeHaco-JEP-380-full-prototype`

JRuby 10.1 still uses JNR for Unix sockets. This repo is the landing
shape for a core patch, not a second IO stack.

## What belongs in jruby/jruby

```
core/src/main/java/org/jruby/ext/socket/UnixDomain.java
core/src/main/java/org/jruby/ext/socket/UnixDomainNative.java
core/src/main/java/org/jruby/ext/socket/UnixDomainContext.java
patches/SocketType_forChannel.java     → edit SocketType.java
patches/RubyUNIXSocket_init.java       → edit RubyUNIXSocket.java
patches/RubyUNIXServer_accept.java     → edit RubyUNIXServer.java
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
