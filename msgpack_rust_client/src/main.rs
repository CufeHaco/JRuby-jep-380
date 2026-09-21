// Rust client for the JRuby JEP-380 UNIXServer (jruby_msgpack_server.rb),
// using the real rmp-serde/serde msgpack crates over a length-prefixed
// frame protocol -- same wire format as the C, assembly, and Python
// clients, but here every byte is produced by a real MessagePack library
// on both the encode and decode side.
use serde::{Deserialize, Serialize};
use std::env;
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::process::exit;

#[derive(Serialize, Deserialize, Debug, PartialEq)]
struct Msg {
    engine: String,
    seq: u32,
    msg: String,
}

fn write_frame(stream: &mut UnixStream, msg: &Msg) -> std::io::Result<()> {
    let payload = rmp_serde::to_vec_named(msg).expect("serialize");
    let len = payload.len() as u32;
    stream.write_all(&len.to_be_bytes())?;
    stream.write_all(&payload)?;
    Ok(())
}

fn read_frame(stream: &mut UnixStream) -> std::io::Result<Msg> {
    let mut len_buf = [0u8; 4];
    stream.read_exact(&mut len_buf)?;
    let len = u32::from_be_bytes(len_buf) as usize;
    let mut payload = vec![0u8; len];
    stream.read_exact(&mut payload)?;
    Ok(rmp_serde::from_slice(&payload).expect("deserialize"))
}

fn main() {
    let path = env::args().nth(1).expect("usage: msgpack_rust_client /path/to/socket");
    let mut stream = UnixStream::connect(&path).expect("connect");
    println!("CLIENT(Rust): connected to {}", path);

    let messages = vec![
        "hello #0 from Rust".to_string(),
        "second message from Rust".to_string(),
        "X".repeat(5000),
    ];

    for (seq, msg) in messages.iter().enumerate() {
        let out = Msg { engine: "Rust".to_string(), seq: seq as u32, msg: msg.clone() };
        write_frame(&mut stream, &out).expect("write_frame");

        let reply = read_frame(&mut stream).expect("read_frame");
        let expected = Msg {
            engine: "JRuby".to_string(),
            seq: seq as u32,
            msg: format!("JRuby-echo:{}", msg),
        };
        let ok = reply == expected;
        println!(
            "CLIENT(Rust): seq={} sent={}B reply={}B engine={} match={}",
            seq,
            msg.len(),
            reply.msg.len(),
            reply.engine,
            ok
        );
        if !ok {
            eprintln!("MISMATCH on seq {}: {:?} != {:?}", seq, reply, expected);
            exit(1);
        }
    }

    println!("CLIENT(Rust): all messages round-tripped correctly");
}
