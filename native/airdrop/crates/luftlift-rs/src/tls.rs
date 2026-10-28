//! TLS self-signed certificate generation and `rustls::ServerConfig` creation
//! for the AirDrop HTTPS server (port 8771). Apple accepts self-signed certs
//! for AirDrop; we generate an ephemeral one at startup, like opendrop.
//!
//! Reference: `opendrop-patch/server.py.patched` (`get_ssl_context`).

use anyhow::Result;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::client::ClientConfig;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::server::ServerConfig;
use rustls::DigitallySignedStruct;
use std::sync::Arc;

/// A self-signed certificate + private key for the AirDrop HTTPS server.
pub struct AirDropTlsCert {
    pub cert_der: CertificateDer<'static>,
    pub key_der: PrivateKeyDer<'static>,
}

/// Generate an ephemeral self-signed certificate suitable for AirDrop.
pub fn generate_self_signed_cert() -> Result<AirDropTlsCert> {
    // Install the ring crypto provider as the rustls default (idempotent).
    let _ = rustls::crypto::ring::default_provider().install_default();

    let key_pair = rcgen::KeyPair::generate()?;
    let params = rcgen::CertificateParams::new(vec!["luftlift".into()])?;
    let cert = params.self_signed(&key_pair)?;
    Ok(AirDropTlsCert {
        cert_der: cert.der().clone(),
        key_der: PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_pair.serialize_der())),
    })
}

/// Build a `rustls::ServerConfig` from a self-signed cert. No client-cert
/// verification. Everyone mode does not authenticate an Apple account here.
pub fn build_server_config(cert: &AirDropTlsCert) -> Result<ServerConfig> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert.cert_der.clone()], cert.key_der.clone_key())?;
    Ok(config)
}

// ---- Client side (sender) ----

/// A certificate verifier that accepts any server certificate, mirroring
/// OpenDrop's Everyone-mode TLS policy (self-signed certs are accepted).
/// Handshake signatures are still verified, but this does not authenticate
/// an Apple account or establish that a nearby peer is trusted. AirDrop only.
#[derive(Debug)]
struct AcceptAnyCert;

impl ServerCertVerifier for AcceptAnyCert {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// Build an Everyone-mode client with one ephemeral identity for this
/// sender process. All Discover/Ask/Upload connections share the config.
/// Like OpenDrop's load_cert_chain, offer it when the receiver requests a
/// client certificate. This is not an Apple-signed validation record.
pub fn build_client_config() -> Result<ClientConfig> {
    let cert = generate_self_signed_cert()?;
    build_client_config_with_identity(&cert)
}

/// Use an already-owned TLS identity without extracting account credentials.
pub fn build_client_config_with_identity(cert: &AirDropTlsCert) -> Result<ClientConfig> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    Ok(ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AcceptAnyCert))
        .with_client_auth_cert(vec![cert.cert_der.clone()], cert.key_der.clone_key())?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_cert_produces_non_empty_der() {
        let cert = generate_self_signed_cert().unwrap();
        assert!(!cert.cert_der.as_ref().is_empty());
        // The key's usability is validated by build_server_config below.
    }

    #[test]
    fn build_server_config_from_generated_cert_succeeds() {
        let cert = generate_self_signed_cert().unwrap();
        // Just verify it builds without panicking.
        let _config = build_server_config(&cert).unwrap();
    }

    // ---- client side (sender) ----

    #[test]
    fn build_client_config_succeeds_and_accepts_self_signed() {
        let config = build_client_config().unwrap();
        // A client config that can be used to connect to an AirDrop receiver.
        let _ = config;
    }

    fn exchange_handshake(
        client_config: ClientConfig,
        server_config: ServerConfig,
    ) -> Result<rustls::ServerConnection, rustls::Error> {
        use std::io::Cursor;
        let mut client = rustls::ClientConnection::new(
            Arc::new(client_config),
            ServerName::try_from("airdrop").unwrap(),
        )?;
        let mut server = rustls::ServerConnection::new(Arc::new(server_config))?;
        for _ in 0..16 {
            let mut outbound = Vec::new();
            while client.wants_write() {
                client.write_tls(&mut outbound).unwrap();
            }
            if !outbound.is_empty() {
                server.read_tls(&mut Cursor::new(outbound)).unwrap();
                server.process_new_packets()?;
            }
            let mut inbound = Vec::new();
            while server.wants_write() {
                server.write_tls(&mut inbound).unwrap();
            }
            if !inbound.is_empty() {
                client.read_tls(&mut Cursor::new(inbound)).unwrap();
                client.process_new_packets()?;
            }
            if !client.is_handshaking() && !server.is_handshaking() {
                return Ok(server);
            }
        }
        panic!("TLS handshake did not converge");
    }

    fn requesting_server(
        client: &AirDropTlsCert,
        version: &'static rustls::SupportedProtocolVersion,
    ) -> ServerConfig {
        let server = generate_self_signed_cert().unwrap();
        let mut roots = rustls::RootCertStore::empty();
        roots.add(client.cert_der.clone()).unwrap();
        let verifier = rustls::server::WebPkiClientVerifier::builder(Arc::new(roots))
            .build()
            .unwrap();
        ServerConfig::builder_with_protocol_versions(&[version])
            .with_client_cert_verifier(verifier)
            .with_single_cert(vec![server.cert_der], server.key_der)
            .unwrap()
    }

    #[test]
    fn requested_client_identity_is_present_and_verified_in_tls12_and_tls13() {
        let identity = generate_self_signed_cert().unwrap();
        for version in [&rustls::version::TLS12, &rustls::version::TLS13] {
            let server = exchange_handshake(
                build_client_config_with_identity(&identity).unwrap(),
                requesting_server(&identity, version),
            )
            .unwrap();
            assert_eq!(
                server.peer_certificates().unwrap(),
                std::slice::from_ref(&identity.cert_der)
            );
            assert_eq!(server.protocol_version(), Some(version.version));
        }
    }

    #[test]
    fn server_request_rejects_client_without_certificate() {
        let identity = generate_self_signed_cert().unwrap();
        let anonymous = ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(AcceptAnyCert))
            .with_no_client_auth();
        let result = exchange_handshake(
            anonymous,
            requesting_server(&identity, &rustls::version::TLS13),
        );
        assert!(matches!(
            result,
            Err(rustls::Error::NoCertificatesPresented)
        ));
    }

    #[test]
    fn unrequested_identity_is_not_sent() {
        let server_cert = generate_self_signed_cert().unwrap();
        let server = exchange_handshake(
            build_client_config().unwrap(),
            build_server_config(&server_cert).unwrap(),
        )
        .unwrap();
        assert!(server.peer_certificates().is_none());
    }
}
