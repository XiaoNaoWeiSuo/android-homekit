# Mi Hotspot HomeKit BLE feasibility gate

Checked: 2026-09-14

## Result — corrected after further investigation

Feasibility is NOT disproven. The previous version of this report incorrectly
treated an unavailable specification page as sufficient evidence to stop all
implementation and require MFi access. It also incorrectly attributed a ban on
third-party mirrors to the user. The user requires official protocol definitions
and forbids guessing; the user did not separately prohibit mirrors.

Apple's official open-source HomeKit ADK is still publicly accessible:
https://github.com/apple/HomeKitADK

Its README expressly allows any developer to prototype non-commercial smart-home
accessories. The repository is archived, contains HAP BLE advertising, PDU,
transaction, session, Pair Setup and Pair Verify implementations, and documents
BLE builds with hardware authentication disabled by default. This provides an
official implementation to investigate and potentially port; it does not prove
iOS 26 compatibility or satisfy a requirement to consult a particular version
of the specification PDF.

No runtime BLE advertising test, iPhone Home discovery test, or pairing test has
been performed. No APK has been built. There is no observed protocol-stage
failure and no established evidence that iOS 26 rejects the proposed accessory.

## Official-source findings

1. The checked public page returns Apple's Page Not Found content (a soft 404;
   one check returned HTTP 200):
   `https://developer.apple.com/support/homekit-accessory-protocol/`
2. Two guessed legacy download paths returned 404; these checks do not establish
   that every official download route is unavailable. Apple's current Apple Home
   page says that MFi licensees receive the HomeKit Accessory Protocol
   Specification, HomeKit ADK, and certification tools:
   `https://developer.apple.com/apple-home/`
3. Apple Platform Security still describes direct HAP communication for both IP
   and BLE accessories. It names SRP-3072, Ed25519, Curve25519,
   HKDF-SHA-512, and ChaCha20-Poly1305. It also says that accessories without an
   MFi chip can support software authentication on iOS 11.3 or later:
   `https://support.apple.com/guide/security/communication-security-sec3a881ccb1/web`
4. Item 3 confirms that BLE remains part of Apple's security model; it does not
   publish the transport wire format, promise that an uncertified accessory is
   accepted by iOS 26, or replace the restricted HAP specification.

The investigation has not established the following:

- that the current public non-commercial specification contains enough data to
  implement HAP over BLE;
- that iOS 26 officially promises discovery and pairing of a newly implemented
  direct HAP-over-BLE accessory;
- that commercial software authentication and non-commercial prototype pairing
  have identical requirements. Apple's non-commercial ADK must be considered
  separately from commercial MFi certification.

## Xiaomi 13 findings

Connected device: `510af65c`

- Model property: `2211133C`
- Android: 13 / API 33
- ADB shell root works (`uid=0`, KernelSU SELinux context)
- Package manager reports `android.hardware.bluetooth_le`
- Android exposes the standard Bluetooth LE advertiser and GATT server APIs
- Tethering service is present as `android.net.ITetheringConnector`
- Wi-Fi service explicitly warns that `cmd wifi start-softap` does not activate
  internet tethering
- Wi-Fi SoftAP reports ACS and WPA3-SAE support
- Tethering configuration allows a cellular upstream and has BPF offload enabled

BLE feature presence does not establish peripheral advertising support. That
requires querying the adapter and successfully starting an advertiser/GATT
server on this particular phone. The offload settings above are configuration
observations; active NAT/offload and cellular internet sharing were not tested.

## Next verification steps

1. Review the official ADK BLE implementation and platform abstraction layer,
   including license and prototype limitations. The ADK does not provide a
   ready-made Android APK.
2. Locate the applicable official non-commercial specification if implementing
   strictly against the PDF remains a requirement.
3. Run the Android BLE peripheral probe, then test a ported official HAP BLE
   implementation against the actual iPhone/iOS version stage by stage.

MFi enrollment is not established as a prerequisite for this personal prototype.
Production distribution/certification is a separate question.

Official implementation references:
- https://github.com/apple/HomeKitADK/blob/master/README.md
- https://github.com/apple/HomeKitADK/blob/master/Documentation/getting_started.md
- https://github.com/apple/HomeKitADK/blob/master/HAP/HAPBLEAccessoryServer%2BAdvertising.c
- https://github.com/apple/HomeKitADK/blob/master/HAP/HAPPairingPairSetup.c

An alternative design can use the current open-source Matter SDK, but Matter
uses BLE for commissioning and Thread or Wi-Fi for normal control. It therefore
does not satisfy this project's requirement that daily control be entirely over
BLE.
