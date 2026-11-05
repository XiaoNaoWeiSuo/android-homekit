//! Minimal QUIC/HTTP3 probe for the experimental `luftlift receive --quic-addr`
//! listener. Used by the on-device verification procedure; not shipped in the
//! Android APK.
//!
//! Usage: `quic_probe <ip:port> [path]`
//! Exits 0 only when the server answers 200.

use std::net::SocketAddr;
use std::sync::Arc;

use anyhow::{bail, Context, Result};
use bytes::Buf;

#[tokio::main]
async fn main() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let addr: SocketAddr = args
        .next()
        .context("usage: quic_probe <ip:port> [path]")?
        .parse()
        .context("invalid address")?;
    let path = args.next().unwrap_or_else(|| "/Discover".to_string());
    let server_name = args.next().unwrap_or_else(|| "luftlift".to_string());
    let alpn = args.next().unwrap_or_else(|| "h3".to_string());

    let mut crypto = luftlift_rs::tls::build_client_config()?;
    crypto.alpn_protocols = vec![alpn.as_bytes().to_vec()];
    let crypto = quinn::crypto::rustls::QuicClientConfig::try_from(crypto)?;
    let mut endpoint = quinn::Endpoint::client("0.0.0.0:0".parse()?)?;
    endpoint.set_default_client_config(quinn::ClientConfig::new(Arc::new(crypto)));

    eprintln!("connecting {addr} server_name={server_name} alpn={alpn}");
    let conn = endpoint
        .connect(addr, &server_name)?
        .await
        .context("QUIC connect failed")?;
    let (mut driver, mut sender) = h3::client::new(h3_quinn::Connection::new(conn)).await?;
    let drive = tokio::spawn(async move { driver.wait_idle().await });
    let request = http::Request::post(format!("https://luftlift{path}"))
        .body(())
        .unwrap();
    let mut stream = sender.send_request(request).await?;
    stream.finish().await?;
    let response = stream.recv_response().await?;
    let status = response.status();
    let mut body = Vec::new();
    while let Some(mut chunk) = stream.recv_data().await? {
        body.extend_from_slice(&chunk.copy_to_bytes(chunk.remaining()));
    }
    println!("status={status} bytes={}", body.len());
    drop(stream);
    drop(sender);
    drive.abort();
    endpoint.wait_idle().await;
    if status != 200 {
        bail!("unexpected status {status}");
    }
    Ok(())
}
