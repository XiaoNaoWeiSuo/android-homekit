//! AirDrop application-service pairing metadata (`PINInfo`).
//!
//! macOS `sharingd` reads the `_airdrop._tcp` TXT key `PINInfo`, decodes its
//! UTF-8 value as JSON, and builds an `SDTempPairingService` from it. The key
//! names below are the ones present in the shipping `sharingd` binary
//! (`strings`/`otool`: `deviceID`, `pairingID`, `applicationLabel`,
//! `supportedApplicationLabels`, `pkData`, `publicKey`,
//! `bonjourListenerUUID`, `endpoint`).
//!
//! This module is deliberately transport-free and is **not** advertised yet:
//! encoding a PINInfo record does not implement the PIN exchange, temporary
//! identity, or application-service connection. Advertising it before those
//! exist would make native senders fail later instead of falling back to the
//! legacy `_airdrop._tcp` + 8771 path.
//!
//! Field optionality is intentional. The exact required/optional split and the
//! encoding of `pkData`/`publicKey` have not been confirmed against a live
//! pair; callers must not treat round-tripping as protocol acceptance.

use serde::{Deserialize, Serialize};
use std::time::{Duration, Instant};

/// Attempts allowed before a temporary PIN session is abandoned. The exact
/// Apple retry policy is not verified; three matches the observed UI behavior
/// and is kept configurable at the session boundary.
pub const MAX_PIN_ATTEMPTS: u8 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairingState {
    WaitingForPin,
    Paired,
    Failed,
}

/// Protocol-neutral PIN session state.
///
/// This only models the local receiver UI contract: show a caller-supplied
/// PIN, accept/retry input, expire the offer, and stop after
/// [`MAX_PIN_ATTEMPTS`]. It deliberately does not encode the AirDrop wire
/// message, pin length, TLV layout, or key exchange; those remain unverified
/// and no advertisement should depend on this type yet.
#[derive(Debug)]
pub struct PinPairingSession {
    pin: String,
    attempts_left: u8,
    state: PairingState,
    expires_at: Instant,
}

impl PinPairingSession {
    pub fn new(pin: impl Into<String>, ttl: Duration) -> Result<Self, &'static str> {
        let pin = pin.into();
        if pin.is_empty() {
            return Err("PIN must not be empty");
        }
        if ttl.is_zero() {
            return Err("PIN TTL must be positive");
        }
        Ok(Self {
            pin,
            attempts_left: MAX_PIN_ATTEMPTS,
            state: PairingState::WaitingForPin,
            expires_at: Instant::now() + ttl,
        })
    }

    /// PIN to display on the receiver. The sender must type it back.
    pub fn pin(&self) -> &str {
        &self.pin
    }

    pub fn state(&self) -> PairingState {
        self.state
    }

    pub fn attempts_left(&self) -> u8 {
        self.attempts_left
    }

    pub fn expires_at(&self) -> Instant {
        self.expires_at
    }

    pub fn is_expired(&self) -> bool {
        Instant::now() >= self.expires_at
    }

    /// Expire a pending offer. Idempotent; a paired/failed session is unchanged.
    pub fn tick(&mut self) -> PairingState {
        if self.state == PairingState::WaitingForPin && self.is_expired() {
            self.state = PairingState::Failed;
        }
        self.state
    }

    /// Verify one sender attempt. A wrong PIN consumes an attempt; the third
    /// wrong attempt fails the session. Input after Paired/Failed is ignored.
    pub fn verify(&mut self, input: &str) -> bool {
        if self.state != PairingState::WaitingForPin {
            return false;
        }
        if self.is_expired() {
            self.state = PairingState::Failed;
            return false;
        }
        if input == self.pin {
            self.state = PairingState::Paired;
            true
        } else {
            self.attempts_left = self.attempts_left.saturating_sub(1);
            if self.attempts_left == 0 {
                self.state = PairingState::Failed;
            }
            false
        }
    }
}

/// JSON payload carried by the `PINInfo` TXT key.
///
/// Unknown keys are preserved on decode (flattened) so a future macOS version
/// adding fields does not make an otherwise valid record unreadable.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct PinInfo {
    #[serde(rename = "deviceID", skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
    #[serde(rename = "pairingID", skip_serializing_if = "Option::is_none")]
    pub pairing_id: Option<String>,
    #[serde(rename = "applicationLabel", skip_serializing_if = "Option::is_none")]
    pub application_label: Option<String>,
    #[serde(
        rename = "supportedApplicationLabels",
        skip_serializing_if = "Option::is_none"
    )]
    pub supported_application_labels: Option<Vec<String>>,
    /// Temporary public key material. Wire encoding (base64 vs hex) is not yet
    /// confirmed; callers supply the already-encoded string.
    #[serde(rename = "pkData", skip_serializing_if = "Option::is_none")]
    pub pk_data: Option<String>,
    #[serde(rename = "publicKey", skip_serializing_if = "Option::is_none")]
    pub public_key: Option<String>,
    #[serde(
        rename = "bonjourListenerUUID",
        skip_serializing_if = "Option::is_none"
    )]
    pub bonjour_listener_uuid: Option<String>,
    #[serde(rename = "endpoint", skip_serializing_if = "Option::is_none")]
    pub endpoint: Option<String>,
    /// Fields observed in a future/other sharingd build. Kept verbatim.
    #[serde(flatten)]
    pub extra: std::collections::BTreeMap<String, serde_json::Value>,
}

impl PinInfo {
    pub fn from_json(value: &str) -> serde_json::Result<Self> {
        serde_json::from_str(value)
    }

    pub fn to_json(&self) -> serde_json::Result<String> {
        serde_json::to_string(self)
    }

    /// Conservative readiness check for advertisement. This is a local
    /// sanity check, not evidence that Apple accepts the record.
    pub fn has_advertisement_fields(&self) -> bool {
        self.device_id.as_deref().is_some_and(|v| !v.is_empty())
            && self.pk_data.as_deref().is_some_and(|v| !v.is_empty())
            && self
                .application_label
                .as_deref()
                .is_some_and(|v| !v.is_empty())
            && self
                .supported_application_labels
                .as_ref()
                .is_some_and(|labels| !labels.is_empty())
            && self
                .bonjour_listener_uuid
                .as_deref()
                .is_some_and(|v| !v.is_empty())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shipping_sharingd_key_names_round_trip() {
        let info = PinInfo {
            device_id: Some("AA:BB:CC:DD:EE:FF".into()),
            pairing_id: Some("pair-1".into()),
            application_label: Some("com.apple.sharingd.AirDrop".into()),
            supported_application_labels: Some(vec![
                "com.apple.sharingd.AirDrop".into(),
                "com.apple.sharingd.FileTransfer".into(),
            ]),
            pk_data: Some("cGtEYXRh".into()),
            public_key: Some("cHVibGljS2V5".into()),
            bonjour_listener_uuid: Some("6C4A4A6E-0000-0000-0000-000000000000".into()),
            endpoint: Some("fe80::1".into()),
            extra: Default::default(),
        };
        let json = info.to_json().unwrap();
        assert!(json.contains("\"deviceID\""));
        assert!(json.contains("\"pkData\""));
        assert!(json.contains("\"supportedApplicationLabels\""));
        assert!(json.contains("\"bonjourListenerUUID\""));
        assert_eq!(PinInfo::from_json(&json).unwrap(), info);
    }

    #[test]
    fn unknown_fields_are_preserved_not_rejected() {
        let info =
            PinInfo::from_json(r#"{"deviceID":"x","pkData":"y","futureField":{"nested":true}}"#)
                .unwrap();
        assert_eq!(info.device_id.as_deref(), Some("x"));
        assert_eq!(
            info.extra.get("futureField"),
            Some(&serde_json::json!({"nested": true}))
        );
        let json = info.to_json().unwrap();
        assert!(json.contains("\"futureField\""));
    }

    #[test]
    fn advertisement_readiness_requires_identity_and_listener() {
        let mut info = PinInfo::default();
        assert!(!info.has_advertisement_fields());
        info.device_id = Some("AA".into());
        info.pk_data = Some("pk".into());
        info.application_label = Some("com.apple.sharingd.AirDrop".into());
        info.supported_application_labels = Some(vec!["com.apple.sharingd.AirDrop".into()]);
        assert!(
            !info.has_advertisement_fields(),
            "listener UUID still missing"
        );
        info.bonjour_listener_uuid = Some("uuid".into());
        assert!(info.has_advertisement_fields());
    }

    #[test]
    fn invalid_json_is_an_error_not_a_panic() {
        assert!(PinInfo::from_json("not json").is_err());
        assert!(PinInfo::from_json(r#"{"deviceID":42}"#).is_err());
    }

    #[test]
    fn pin_session_accepts_the_correct_pin_once() {
        let mut session = PinPairingSession::new("482913", Duration::from_secs(60)).unwrap();
        assert_eq!(session.pin(), "482913");
        assert_eq!(session.state(), PairingState::WaitingForPin);
        assert!(session.verify("482913"));
        assert_eq!(session.state(), PairingState::Paired);
        assert!(!session.verify("482913"));
    }

    #[test]
    fn pin_session_fails_after_three_wrong_attempts() {
        let mut session = PinPairingSession::new("482913", Duration::from_secs(60)).unwrap();
        assert!(!session.verify("000000"));
        assert_eq!(session.attempts_left(), 2);
        assert!(!session.verify("111111"));
        assert_eq!(session.attempts_left(), 1);
        assert!(!session.verify("222222"));
        assert_eq!(session.attempts_left(), 0);
        assert_eq!(session.state(), PairingState::Failed);
        // A correct PIN after lockout must not revive the session.
        assert!(!session.verify("482913"));
        assert_eq!(session.state(), PairingState::Failed);
    }

    #[test]
    fn pin_session_expires_and_rejects_late_input() {
        let mut session = PinPairingSession::new("482913", Duration::from_millis(1)).unwrap();
        std::thread::sleep(Duration::from_millis(5));
        assert!(session.is_expired());
        assert_eq!(session.tick(), PairingState::Failed);
        assert!(!session.verify("482913"));
    }

    #[test]
    fn pin_session_rejects_empty_pin_and_zero_ttl() {
        assert!(PinPairingSession::new("", Duration::from_secs(60)).is_err());
        assert!(PinPairingSession::new("482913", Duration::ZERO).is_err());
    }
}
