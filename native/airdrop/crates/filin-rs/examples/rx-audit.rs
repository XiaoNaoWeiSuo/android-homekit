//! Replay captured radiotap frames through the real RX admission parser.
//! Usage: cargo run -p filin-rs --example rx-audit -- LOCAL_MAC FRAME.bin ...
use std::{env, fs};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args().skip(1);
    let mac = args
        .next()
        .ok_or("expected local MAC and radiotap frame paths")?;
    let octets: Vec<u8> = mac
        .split(':')
        .map(|p| u8::from_str_radix(p, 16))
        .collect::<Result<_, _>>()?;
    let local: [u8; 6] = octets.try_into().map_err(|_| "expected six MAC octets")?;
    for path in args {
        let bytes = fs::read(&path)?;
        match filin_rs::data_rx::decode(&bytes, local) {
            Ok(delivery) => println!(
                "{}: accepted source={:02x?} frames={} unicast={}",
                path,
                delivery.source,
                delivery.frames.len(),
                delivery.unicast
            ),
            Err(error) => println!("{}: rejected {:?}", path, error),
        }
    }
    Ok(())
}
