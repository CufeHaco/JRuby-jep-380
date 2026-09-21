// Throughput benchmark: N round trips of a small fixed message over one
// warm connection to jruby_msgpack_bench_server.rb.
use serde::{Deserialize, Serialize};
use std::env;
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::time::Instant;

#[derive(Serialize, Deserialize)]
struct Msg {
    engine: String,
    seq: u32,
    msg: String,
}

fn write_frame(stream: &mut UnixStream, msg: &Msg) -> std::io::Result<()> {
    let payload = rmp_serde::to_vec_named(msg).expect("serialize");
    stream.write_all(&(payload.len() as u32).to_be_bytes())?;
    stream.write_all(&payload)
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
    let path = args.nth(1).expect("usage: msgpack_rust_bench /path/to/socket N");
    let n: u32 = args.next().unwrap_or_else(|| "2000".to_string()).parse().expect("N");

    let mut stream = UnixStream::connect(&path).expect("connect");
    let msg = Msg { engine: "Rust".to_string(), seq: 0, msg: "ping".to_string() };

    let t0 = Instant::now();
    for _ in 0..n {
        write_frame(&mut stream, &msg).expect("write_frame");
        let _ = read_frame(&mut stream).expect("read_frame");
    }
    let secs = t0.elapsed().as_secs_f64();

    println!(
        "BENCH(Rust): n={} time={:.4}s throughput={:.1} msg/s mean={:.4}ms",
        n,
        secs,
        n as f64 / secs,
        (secs / n as f64) * 1000.0
    );
}
