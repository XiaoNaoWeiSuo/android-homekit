//! Generate a local CA plus an AirDrop-shaped client identity.
//!
//! Why: macOS 27's AirDrop QUIC listener (UDP 54970) requires a client
//! certificate and validates it with the normal trust store; a self-signed
//! cert fails with "root not trusted". Installing this CA as a trusted root
//! (a deliberate, reversible change on the owner's Mac) lets our client pass
//! that check so the AirDrop application protocol can be explored.
//!
//! Writes:
//!   /tmp/luftlift-ca.pem       CA certificate (install as trusted root)
//!   /tmp/luftlift-client.pem   client certificate (signed by the CA)
//!   /tmp/luftlift-client.key   client private key (PKCS#8 PEM)

use anyhow::Result;
use rcgen::{
    BasicConstraints, CertificateParams, DnType, ExtendedKeyUsagePurpose, IsCa, KeyPair,
    KeyUsagePurpose,
};

fn main() -> Result<()> {
    // 64 hex chars: AirDrop identities use a hash-shaped CN, so keep that shape.
    let air_drop_id = "6c7566746c6966746c7566746c6966746c7566746c6966746c7566746c696674";

    let ca_key = KeyPair::generate()?;
    let mut ca_params = CertificateParams::new(Vec::<String>::new())?;
    ca_params
        .distinguished_name
        .push(DnType::CommonName, "luftlift AirDrop CA");
    ca_params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    ca_params.key_usages = vec![
        KeyUsagePurpose::KeyCertSign,
        KeyUsagePurpose::CrlSign,
        KeyUsagePurpose::DigitalSignature,
    ];
    let ca_cert = ca_params.self_signed(&ca_key)?;
    std::fs::write("/tmp/luftlift-ca.pem", ca_cert.pem())?;

    let client_key = KeyPair::generate()?;
    let mut client_params = CertificateParams::new(Vec::<String>::new())?;
    client_params
        .distinguished_name
        .push(DnType::CommonName, air_drop_id);
    client_params.is_ca = IsCa::NoCa;
    // macOS validates the peer certificate with the SSL *server* policy here,
    // so serverAuth must be present as well; clientAuth alone fails with
    // "extended key usage does not match certificate use".
    client_params.extended_key_usages = vec![
        ExtendedKeyUsagePurpose::ClientAuth,
        ExtendedKeyUsagePurpose::ServerAuth,
    ];
    let client_cert = client_params.signed_by(&client_key, &ca_cert, &ca_key)?;
    std::fs::write("/tmp/luftlift-client.pem", client_cert.pem())?;
    std::fs::write("/tmp/luftlift-client.key", client_key.serialize_pem())?;

    println!("CA      /tmp/luftlift-ca.pem");
    println!("client  /tmp/luftlift-client.pem (CN={air_drop_id})");
    println!("key     /tmp/luftlift-client.key");
    Ok(())
}
