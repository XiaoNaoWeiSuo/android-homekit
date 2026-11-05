//! Try client-certificate shapes against AirDrop's QUIC listener.
//!
//! macOS 27 answers QUIC on UDP 54970 (`sharingd`, bundle id
//! `com.apple.datausage.airdrop`) but rejects our first self-signed client
//! certificate with `CERTIFICATE_VERIFY_FAILED`. This probe sweeps a few
//! plausible shapes so we can learn what the receiver actually requires.
//!
//! Usage: `quic_cert_probe [ip:port]`

use std::sync::Arc;
use std::time::Duration;

use luftlift_rs::tls::{build_client_config_with_identity, generate_cert, CertSpec};

#[tokio::main]
async fn main() {
    let addr = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "10.12.1.5:54970".to_string())
        .parse()
        .expect("ip:port");

    let uuid = "5bccaa37-9f58-4f21-8d96-06f73044eecf".to_string();
    let variants: Vec<(&str, CertSpec)> = vec![
        (
            "cn-luftlift/eku",
            CertSpec {
                common_name: "luftlift".into(),
                san: vec!["luftlift".into()],
                client_auth: true,
            },
        ),
        (
            "cn-airdrop/eku",
            CertSpec {
                common_name: "AirDrop".into(),
                san: vec!["AirDrop".into()],
                client_auth: true,
            },
        ),
        (
            "cn-airdrop/no-eku",
            CertSpec {
                common_name: "AirDrop".into(),
                san: vec!["AirDrop".into()],
                client_auth: false,
            },
        ),
        (
            "cn-uuid/eku",
            CertSpec {
                common_name: uuid.clone(),
                san: vec![uuid.clone()],
                client_auth: true,
            },
        ),
        (
            // Apple's own AirDrop identity is a minimal cert: empty subject
            // and issuer, no extensions at all (239 bytes on macOS 27).
            "apple-shape",
            CertSpec {
                common_name: String::new(),
                san: vec![],
                client_auth: false,
            },
        ),
        (
            "cn-no-san",
            CertSpec {
                common_name: "AirDrop".into(),
                san: vec![],
                client_auth: true,
            },
        ),
    ];

    for (label, spec) in variants {
        let cert = generate_cert(&spec).expect("cert");
        let mut crypto = build_client_config_with_identity(&cert).expect("tls config");
        crypto.alpn_protocols = vec![b"h3".to_vec()];
        let crypto =
            quinn::crypto::rustls::QuicClientConfig::try_from(crypto).expect("quic config");

        let mut endpoint = quinn::Endpoint::client("0.0.0.0:0".parse().unwrap()).expect("endpoint");
        endpoint.set_default_client_config(quinn::ClientConfig::new(Arc::new(crypto)));

        let result = tokio::time::timeout(
            Duration::from_secs(6),
            endpoint.connect(addr, "airdrop").expect("connect"),
        )
        .await;

        match result {
            Ok(Ok(connection)) => {
                println!("{label}: HANDSHAKE OK");
                if label == "cn-airdrop/eku" {
                    if let Some(identity) = connection.peer_identity() {
                        if let Ok(certs) =
                            identity.downcast::<Vec<rustls::pki_types::CertificateDer<'static>>>()
                        {
                            if let Some(cert) = certs.first() {
                                std::fs::write("/tmp/mac-airdrop-cert.der", cert.as_ref())
                                    .expect("write cert");
                                println!("peer cert saved ({} bytes)", cert.as_ref().len());
                            }
                        }
                    }
                }
                connection.close(0u32.into(), b"probe");
            }
            Ok(Err(error)) => println!("{label}: FAIL {error}"),
            Err(_) => println!("{label}: TIMEOUT"),
        }
        endpoint.wait_idle().await;
    }
}
