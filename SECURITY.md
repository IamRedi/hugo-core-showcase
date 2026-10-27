# Security and privacy

The evaluation APK uses a separate application ID and launcher. It requests no
Internet, Bluetooth, microphone, camera or location permission, and does not
register the private connected-device service or live activity. Runtime body
effects are in-memory simulations. No gateway credential is configured.

Production HUGO secrets belong behind the gateway/on-device governed stores,
never in source or APK constants. The visible memory lab contains only synthetic
ephemeral envelopes; it is not connected to the owner's memory or identity store.

Keep software, emulator and physical evidence distinct. A passing test is not a
security certification or physical stopping-time measurement. Report a concern
privately through the author's GitHub profile; never publish a token, personal
recording, memory export or device address in an issue.
