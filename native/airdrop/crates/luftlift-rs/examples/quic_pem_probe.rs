//! QUIC/HTTP3 probe using a PEM client identity (CA-signed).
//!
//! AirDrop's QUIC listener on UDP 54970 requires a client certificate and
//! validates it against the system trust store. This probe uses
//! `/tmp/luftlift-client.pem` + `.key` (issued by the local CA) so that,
//! once that CA is installed as trusted, the server-side verification can
//! pass and the AirDrop application protocol becomes observable.
//!
//! Usage: `quic_pem_probe [ip:port] [path] [cert.pem] [key.pem]`

use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use bytes::Buf;

#[tokio::main]
async fn main() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let addr = args
        .next()
        .unwrap_or_else(|| "10.12.1.5:54970".to_string())
        .parse()
        .context("ip:port")?;
    let path = args.next().unwrap_or_else(|| "/Discover".to_string());
    let cert_path = args
        .next()
        .unwrap_or_else(|| "/tmp/luftlift-client.pem".to_string());
    let key_path = args
        .next()
        .unwrap_or_else(|| "/tmp/luftlift-client.key".to_string());
    let body = args.next().unwrap_or_default();
    let content_type = args
        .next()
        .unwrap_or_else(|| "application/json".to_string());

    let mut crypto = luftlift_rs::tls::build_client_config_with_pem(&cert_path, &key_path)?;
    crypto.alpn_protocols = vec![b"h3".to_vec()];
    let crypto = quinn::crypto::rustls::QuicClientConfig::try_from(crypto)?;
    let mut endpoint = quinn::Endpoint::client("0.0.0.0:0".parse()?)?;
    endpoint.set_default_client_config(quinn::ClientConfig::new(Arc::new(crypto)));

    eprintln!("connecting {addr} with {cert_path} (ALPN h3)");
    let conn = tokio::time::timeout(Duration::from_secs(6), endpoint.connect(addr, "airdrop")?)
        .await
        .context("connect timeout")?
        .context("QUIC connect failed")?;
    eprintln!("handshake done, sending h3 POST {path}");

    let (mut driver, mut sender) = h3::client::new(h3_quinn::Connection::new(conn)).await?;
    let drive = tokio::spawn(async move { driver.wait_idle().await });
    let mut request = http::Request::post(format!("https://airdrop{path}"));
    if !content_type.is_empty() {
        request = request.header("content-type", content_type.clone());
    }
    let request = request.body(()).unwrap();
    let mut stream = sender.send_request(request).await?;
    if !body.is_empty() {
        stream.send_data(bytes::Bytes::from(body.clone())).await?;
    }
    stream.finish().await?;
    let response = stream.recv_response().await?;
    let status = response.status();
    let mut body = Vec::new();
    while let Some(mut chunk) = stream.recv_data().await? {
        body.extend_from_slice(&chunk.copy_to_bytes(chunk.remaining()));
    }
    println!(
        "status={status} bytes={} head={:?}",
        body.len(),
        &body[..body.len().min(64)]
    );
    drop(stream);
    drop(sender);
    drive.abort();
    endpoint.wait_idle().await;
    Ok(())
}
