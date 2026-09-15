# Manifest entry points.
-keep class dev.local.mihotspot.HomeKitService { *; }
-keep class dev.local.mihotspot.BootReceiver { *; }

# Crypto classes are called directly; keep them for aggressive OEM shrinkers.
-keep class org.bouncycastle.crypto.** { *; }
