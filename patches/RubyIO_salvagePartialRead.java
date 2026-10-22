// General JRuby bug (see OpenFile_salvagePartialRead.java in this same
// directory for the full WHY, what this actually fixes vs. doesn't, and the
// correction on an earlier overclaim). This is the other two call sites
// with the identical shape, both needing OpenFile#salvagePartialRead (added
// by that same patch, apply it first).
//
// Replacements inside core/src/main/java/org/jruby/RubyIO.java

    private IRubyObject getPartialCommon(ThreadContext context, Ruby runtime, IRubyObject length, IRubyObject str, boolean nonblock, boolean noException) {
        final int len = toInt(context, length);
        if (len < 0 ) throw argumentError(context, "negative length " + len + " given");

        str = EncodingUtils.setStrBuf(runtime, str, len);
        OpenFile fptr = getOpenFileChecked();

        final boolean locked = fptr.lock();
        int n = -1;
        // ByteList whose backing array a successful OpenFile.readInternal call
        // below has ALREADY, irreversibly, filled from the real fd -- tracked
        // across the catch below so it can be salvaged into rbuf if a
        // Thread#kill/Thread#raise abandons this method before `str` is
        // handed back to the caller. See OpenFile#salvagePartialRead's doc
        // comment for the full WHY.
        ByteList readInto = null;
        try {
            fptr.checkByteReadable(context);

            if ( len == 0 ) {
                ((RubyString) str).clear(context);
                return str;
            }

            if ( !nonblock) fptr.READ_CHECK(context);

            ByteList strByteList = ((RubyString) str).getByteList();
            n = fptr.readBufferedData(strByteList.unsafeBytes(), strByteList.begin(), len);
            if (n <= 0) {
                again:
                while (true) {
                    if (nonblock) {
                        fptr.setNonblock(runtime);
                    }
                    str = EncodingUtils.setStrBuf(runtime, str, len);
                    strByteList = ((RubyString) str).getByteList();
                    //                arg.fd = fptr->fd;
                    //                arg.str_ptr = RSTRING_PTR(str);
                    //                arg.len = len;
                    //                rb_str_locktmp_ensure(str, read_internal_call, (VALUE)&arg);
                    //                n = arg.len;
                    readInto = strByteList;
                    n = OpenFile.readInternal(context, fptr, fptr.fd(), strByteList.unsafeBytes(), strByteList.begin(), len);
                    if (n < 0) {
                        Errno e = fptr.errno();
                        if (!nonblock && fptr.waitReadable(context))
                            continue again;
                        if (nonblock && (e == Errno.EWOULDBLOCK || e == Errno.EAGAIN)) {
                            if (noException) return asSymbol(context, "wait_readable");
                            throw runtime.newErrnoEAGAINReadableError("read would block");
                        }
                        return nonblockEOF(runtime, noException);
                    }
                    break;
                }
            }
        } catch (RuntimeException re) {
            if (n > 0 && readInto != null) {
                fptr.salvagePartialRead(readInto.unsafeBytes(), readInto.begin(), n);
            }
            throw re;
        }
        finally {
            if ( locked ) fptr.unlock();
        }

        ((RubyString) str).setReadLength(n);

        return n == 0 ? context.nil : str;
    }

    // MRI: rb_io_sysread
    @JRubyMethod(name = "sysread")
    public IRubyObject sysread(ThreadContext context, IRubyObject _length) {
        return sysreadCommon(context, context.runtime, _length, context.nil);
    }

    // MRI: rb_io_sysread
    @JRubyMethod(name = "sysread")
    public IRubyObject sysread(ThreadContext context, IRubyObject _length, IRubyObject _str) {
        return sysreadCommon(context, context.runtime, _length, _str);
    }

    private RubyString sysreadCommon(ThreadContext context, Ruby runtime, IRubyObject _length, IRubyObject _str) {
        final int length = toInt(context, _length);
        RubyString str = EncodingUtils.setStrBuf(runtime, _str, length);

        if (length == 0) return str;

        final OpenFile fptr = getOpenFileChecked();

        int n = -1;
        ByteList readInto = null; // see OpenFile#salvagePartialRead's doc comment
        boolean locked = fptr.lock();
        try {
            fptr.checkByteReadable(context);

            if (fptr.READ_DATA_BUFFERED()) {
                throw runtime.newIOError("sysread for buffered IO");
            }

            /*
             * MRI COMMENT:
             * FIXME: removing rb_thread_wait_fd() here changes sysread semantics
             * on non-blocking IOs.  However, it's still currently possible
             * for sysread to raise Errno::EAGAIN if another thread read()s
             * the IO after we return from rb_thread_wait_fd() but before
             * we call read()
             */
            context.getThread().select(fptr.channel(), fptr, SelectionKey.OP_READ);

            fptr.checkClosed();

            ByteList strByteList = str.getByteList();
            readInto = strByteList;
            n = OpenFile.readInternal(context, fptr, fptr.fd(), strByteList.unsafeBytes(), strByteList.begin(), length);

            if (n == -1) {
                throw runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
            }
        } catch (RuntimeException re) {
            if (n > 0 && readInto != null) {
                fptr.salvagePartialRead(readInto.unsafeBytes(), readInto.begin(), n);
            }
            throw re;
        }
        finally {
            if (locked) fptr.unlock();
        }

        if (n == 0 && length > 0) throw runtime.newEOFError();

        str.setReadLength(n);

        return str;
    }
