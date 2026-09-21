// Rust client for jruby_msgpack_server_txrx.rb -- two separate socket
// connections (c2s outgoing, s2c incoming) instead of one bidirectional
// connection. Same rmp-serde/serde encode/decode as msgpack_rust_client.
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
    let mut args = env::args();
    let c2s_path = args.nth(1).expect("usage: msgpack_rust_client_txrx /path/to/c2s.sock /path/to/s2c.sock");
    let s2c_path = args.next().expect("missing s2c path");

    let mut c2s = UnixStream::connect(&c2s_path).expect("connect c2s");
    let mut s2c = UnixStream::connect(&s2c_path).expect("connect s2c");
    println!("CLIENT(Rust-txrx): connected c2s={} s2c={}", c2s_path, s2c_path);

    let messages = vec![
        "hello #0 from Rust txrx".to_string(),
        "second message from Rust txrx".to_string(),
        "X".repeat(5000),
    ];

    for (seq, msg) in messages.iter().enumerate() {
        let out = Msg { engine: "Rust".to_string(), seq: seq as u32, msg: msg.clone() };
        write_frame(&mut c2s, &out).expect("write_frame");

        let reply = read_frame(&mut s2c).expect("read_frame");
        let expected = Msg {
            engine: "JRuby".to_string(),
            seq: seq as u32,
            msg: format!("JRuby-echo:{}", msg),
        };
        let ok = reply == expected;
        println!(
            "CLIENT(Rust-txrx): seq={} sent={}B reply={}B engine={} match={}",
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

    println!("CLIENT(Rust-txrx): all messages round-tripped correctly");
}
