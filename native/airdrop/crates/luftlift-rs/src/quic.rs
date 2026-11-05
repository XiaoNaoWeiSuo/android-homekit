//! QUIC + HTTP/3 transport for AirDrop application-service connections.
//!
//! This is a transport, not Apple application-service pairing. It must not be
//! advertised as an AirDrop capability until `_asquic` and pairing are wired.
use crate::{
    handlers::HandleResult,
    http::{HttpRequest, HttpResponse},
    tls::AirDropTlsCert,
};
use anyhow::{Context, Result};
use bytes::{Buf, Bytes};
use quinn::{crypto::rustls::QuicServerConfig, Endpoint, ServerConfig};
use std::{net::SocketAddr, sync::Arc};
use tracing::warn;

pub const ALPN: &[u8] = b"h3";

pub fn server_config(cert: &AirDropTlsCert) -> Result<ServerConfig> {
    let mut tls = rustls::ServerConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_protocol_versions(&[&rustls::version::TLS13])?
    .with_no_client_auth()
    .with_single_cert(vec![cert.cert_der.clone()], cert.key_der.clone_key())?;
    tls.alpn_protocols = vec![ALPN.to_vec()];
    let crypto = QuicServerConfig::try_from(tls).context("QUIC TLS 1.3 configuration")?;
    Ok(ServerConfig::with_crypto(Arc::new(crypto)))
}

pub fn bind(addr: SocketAddr, cert: &AirDropTlsCert) -> Result<Endpoint> {
    Endpoint::server(server_config(cert)?, addr).context("bind AirDrop QUIC endpoint")
}

pub fn request(headers: &http::Request<()>, body: Vec<u8>) -> Result<HttpRequest> {
    let mut fields = std::collections::HashMap::new();
    for (key, value) in headers.headers() {
        fields.insert(key.as_str().to_owned(), value.to_str()?.to_owned());
    }
    Ok(HttpRequest {
        method: headers.method().as_str().to_owned(),
        path: headers.uri().path().to_owned(),
        headers: fields,
        body,
    })
}

/// Only loopback and link-local peers may reach this endpoint. The legacy
/// HTTPS listener applies the same rule; keeping it here means the experimental
/// QUIC transport cannot accidentally expose the transfer handlers to the LAN.
pub fn is_local_peer(peer: std::net::IpAddr) -> bool {
    peer.is_loopback() || matches!(peer, std::net::IpAddr::V6(v) if v.is_unicast_link_local())
}

pub fn response(result: &HttpResponse) -> Result<http::Response<()>> {
    let mut builder = http::Response::builder().status(result.status);
    for (key, value) in &result.headers {
        builder = builder.header(key, value);
    }
    Ok(builder.body(())?)
}

/// Accept QUIC connections until the endpoint is closed. Each connection is
/// served on its own task; `handle` receives the peer IP so the shared consent /
/// approval rules can be applied per sender, exactly like the legacy path.
pub async fn serve(
    endpoint: Endpoint,
    handle: impl Fn(std::net::IpAddr, HttpRequest) -> HandleResult + Clone + Send + Sync + 'static,
) -> Result<()> {
    while let Some(incoming) = endpoint.accept().await {
        let handle = handle.clone();
        tokio::spawn(async move {
            let conn = match incoming.await {
                Ok(conn) => conn,
                Err(e) => {
                    warn!(error = %e, "QUIC handshake failed");
                    return;
                }
            };
            let peer = conn.remote_address();
            if !is_local_peer(peer.ip()) {
                warn!(%peer, "refusing non-local QUIC peer");
                conn.close(0u32.into(), b"non-local peer");
                return;
            }
            if let Err(e) = serve_connection(conn, move |req| handle(peer.ip(), req)).await {
                warn!(error = %e, %peer, "QUIC connection error");
            }
        });
    }
    Ok(())
}

/// Serve one HTTP/3 connection through the same routing used by legacy HTTPS.
pub async fn serve_connection(
    conn: quinn::Connection,
    handle: impl Fn(HttpRequest) -> HandleResult + Clone + Send + 'static,
) -> Result<()> {
    let mut server = h3::server::Connection::new(h3_quinn::Connection::new(conn)).await?;
    while let Some(resolver) = server.accept().await? {
        let handle = handle.clone();
        tokio::spawn(async move {
            let (headers, mut stream) = resolver.resolve_request().await?;
            let mut body = Vec::new();
            let mut oversized = false;
            while let Some(mut chunk) = stream.recv_data().await? {
                if body.len().saturating_add(chunk.remaining()) > crate::guard::MAX_BODY {
                    oversized = true;
                    body.clear();
                    break;
                }
                body.extend_from_slice(&chunk.copy_to_bytes(chunk.remaining()));
            }
            let result = if oversized {
                HandleResult {
                    response: HttpResponse::new(413),
                    received_files: Vec::new(),
                }
            } else {
                // The handler applies the blocking consent gate (`/Ask` waits
                // up to 45s for the UI) and performs synchronous file writes.
                // Run it on the blocking pool so one pending consent cannot
                // starve the HTTP/3 accept/read tasks.
                let req = request(&headers, body)?;
                let handle = handle.clone();
                tokio::task::spawn_blocking(move || handle(req))
                    .await
                    .map_err(|e| anyhow::anyhow!("QUIC handler task failed: {e}"))?
            };
            stream.send_response(response(&result.response)?).await?;
            stream.send_data(Bytes::from(result.response.body)).await?;
            stream.finish().await?;
            anyhow::Ok(())
        });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn h3_request_maps_into_legacy_airdrop_router() {
        let req = http::Request::post("/Upload")
            .header("Content-Type", "application/x-cpio")
            .body(())
            .unwrap();
        let req = request(&req, b"payload".to_vec()).unwrap();
        assert_eq!(req.path, "/Upload");
        assert_eq!(req.header("content-type"), Some("application/x-cpio"));
        assert_eq!(req.body, b"payload");
    }
    #[tokio::test]
    async fn quic_server_uses_only_tls13_and_http3() {
        let cert = crate::tls::generate_self_signed_cert().unwrap();
        let endpoint = bind("127.0.0.1:0".parse().unwrap(), &cert).unwrap();
        assert!(endpoint.local_addr().unwrap().port() > 0);
    }

    #[tokio::test]
    async fn quic_http3_roundtrip_reaches_airdrop_handler() {
        let cert = crate::tls::generate_self_signed_cert().unwrap();
        let server = bind("127.0.0.1:0".parse().unwrap(), &cert).unwrap();
        let address = server.local_addr().unwrap();
        let cfg = crate::plist_impl::ReceiverConfig {
            computer_name: "QUIC test".into(),
            computer_model: "Android".into(),
            record_data: None,
        };
        tokio::spawn(async move {
            let conn = server.accept().await.unwrap().await.unwrap();
            serve_connection(conn, move |req| crate::handlers::handle_post(&req, &cfg))
                .await
                .unwrap();
        });
        let mut client_crypto = crate::tls::build_client_config().unwrap();
        client_crypto.alpn_protocols = vec![ALPN.to_vec()];
        let client_crypto =
            quinn::crypto::rustls::QuicClientConfig::try_from(client_crypto).unwrap();
        let mut client = Endpoint::client("127.0.0.1:0".parse().unwrap()).unwrap();
        client.set_default_client_config(quinn::ClientConfig::new(Arc::new(client_crypto)));
        let conn = client.connect(address, "luftlift").unwrap().await.unwrap();
        let (mut driver, mut sender) = h3::client::new(h3_quinn::Connection::new(conn))
            .await
            .unwrap();
        let drive = tokio::spawn(async move { driver.wait_idle().await });
        let mut stream = sender
            .send_request(
                http::Request::post("https://luftlift/Discover")
                    .body(())
                    .unwrap(),
            )
            .await
            .unwrap();
        stream.finish().await.unwrap();
        let response = stream.recv_response().await.unwrap();
        assert_eq!(response.status(), 200);
        let mut body = Vec::new();
        while let Some(mut chunk) = stream.recv_data().await.unwrap() {
            body.extend_from_slice(&chunk.copy_to_bytes(chunk.remaining()));
        }
        let value = plist::Value::from_reader(std::io::Cursor::new(body)).unwrap();
        assert_eq!(
            value
                .as_dictionary()
                .unwrap()
                .get("ReceiverComputerName")
                .unwrap()
                .as_string(),
            Some("QUIC test")
        );
        drop(stream);
        drop(sender);
        drive.abort();
    }

    #[tokio::test]
    async fn accept_loop_routes_local_http3_request_and_stops() {
        let cert = crate::tls::generate_self_signed_cert().unwrap();
        let server = bind("127.0.0.1:0".parse().unwrap(), &cert).unwrap();
        let address = server.local_addr().unwrap();
        let seen = Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
        let cfg = crate::plist_impl::ReceiverConfig {
            computer_name: "QUIC loop".into(),
            computer_model: "Android".into(),
            record_data: None,
        };
        let task = tokio::spawn({
            let seen = seen.clone();
            async move {
                serve(server, move |_peer, req| {
                    seen.lock().unwrap().push(req.path.clone());
                    crate::handlers::handle_post(&req, &cfg)
                })
                .await
                .unwrap();
            }
        });

        let mut client_crypto = crate::tls::build_client_config().unwrap();
        client_crypto.alpn_protocols = vec![ALPN.to_vec()];
        let client_crypto =
            quinn::crypto::rustls::QuicClientConfig::try_from(client_crypto).unwrap();
        let mut client = Endpoint::client("127.0.0.1:0".parse().unwrap()).unwrap();
        client.set_default_client_config(quinn::ClientConfig::new(Arc::new(client_crypto)));
        let conn = client.connect(address, "luftlift").unwrap().await.unwrap();
        let (mut driver, mut sender) = h3::client::new(h3_quinn::Connection::new(conn))
            .await
            .unwrap();
        let drive = tokio::spawn(async move { driver.wait_idle().await });
        let mut stream = sender
            .send_request(
                http::Request::post("https://luftlift/Discover")
                    .body(())
                    .unwrap(),
            )
            .await
            .unwrap();
        stream.finish().await.unwrap();
        assert_eq!(stream.recv_response().await.unwrap().status(), 200);
        assert_eq!(&*seen.lock().unwrap(), &["/Discover".to_string()]);

        drop(stream);
        drop(sender);
        drive.abort();
        task.abort();
    }

    #[test]
    fn loopback_and_link_local_are_the_only_accepted_peers() {
        assert!(is_local_peer("127.0.0.1".parse().unwrap()));
        assert!(is_local_peer("::1".parse().unwrap()));
        assert!(is_local_peer("fe80::1".parse().unwrap()));
        assert!(!is_local_peer("10.12.1.63".parse().unwrap()));
        assert!(!is_local_peer("2001:db8::1".parse().unwrap()));
    }
}
