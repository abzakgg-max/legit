# NetherNet Bridge (NeoForge 26.3 · Stage 2 Foundation)

Unified NeoForge `26.3.0.113` (Minecraft `26.3`, Java `25`) project combining:
- CloudburstMC Bedrock Protocol (`v2168`..`v2225`), NetherNet WebRTC DTLS (`UDP 7551` + `libdatachannel`), strict Microsoft JWT verification, and `BedrockProfileMapper` + `BedrockIdentityStore` (from PoC-1 + Stage 2 spec)
- Isolated `VirtualBedrockChannel` (`isMemoryConnection() == false`), Handshake/Login/Configuration->PLAY bridge, `PlayerListGameTestMixin`, and sequential GameTests (from PoC-2)

`poc1-nethernet-discovery` and `poc2-neoforge-bridge` remain untouched.

## Verification Commands (Windows 10 · Acer)

```cmd
gradlew.bat test
gradlew.bat runGameTestServer
gradlew.bat runServer
gradlew.bat runClient
```

- `gradlew.bat test`: runs 17 JUnit 5 tests (`BedrockProfileMapperUnitTest` [8], `VirtualConnectionUnitTest` [5], `NetherNetRuntimeCompatibilityUnitTest` [4]) verifying XUID determinism, anti-spoofing, 4-source collision resolution, Netty 4.2/NetherNet WebRTC loopback compatibility, and compressed fault isolation.
- `gradlew.bat runGameTestServer`: executes `DependencyCompatibilityCheck` inside live NeoForge `GameTestServer` plus sequential `virtual_connection_lifecycle` and `malformed_frame_isolation` GameTests.
