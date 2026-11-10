# Verification

## Automated build

Run **./gradlew build** with JDK 25. The default build resolves Fabric, Litematica and MaLiLib using the pinned public coordinates. **verifyProject** checks bidirectional 8:1 conversion, fractional and negative flooring, offsets, numeric limits, archive round-trips, unchanged embedded bytes, no-overwrite export, backup, interrupted saves, duplicate IDs, missing data, invalid coordinates, incompatible versions and localization key parity.

## Packaged client integration

The Windows runner in **verification/client/** accepts a launcher metadata directory, the official dependencies, JDK 25, and a disposable singleplayer world. It copies the fixture to isolated game directories and adds a separate smoke-test mod alongside the production JAR. The driver is excluded from that JAR. Use a fixture that starts in the Overworld.

The smoke test exercises native area capture and loading, visible managed placements, separate native placements, three dimension transitions and return, archive save and reopen, raw export, coordinate locks, and native rotation and mirror. It also checks the menu and world-local placement behavior. Results are written to **dimension-layers-smoke.txt** in the test profile.

## Unverified behavior

Public multiplayer servers, actual Easy Place block placement, and third-party mod combinations require separate testing. The add-on makes no world writes during normal use.
