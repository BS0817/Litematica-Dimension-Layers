# Verification

## Automated build

Run **./gradlew build** with JDK 25. The default build resolves Fabric, Litematica and MaLiLib using the pinned public coordinates. **verifyProject** checks bidirectional 8:1 conversion, fractional and negative flooring, offsets, numeric limits, archive round-trips, unchanged embedded bytes, no-overwrite export, backup, interrupted saves, duplicate IDs, missing data, invalid coordinates, incompatible versions and localization key parity.

## Packaged client integration

The Windows runner in **verification/client/** accepts a launcher metadata directory, the official dependencies, JDK 25, and a disposable singleplayer world. It copies the fixture to isolated game directories and adds a separate smoke-test mod alongside the production JAR. The driver is excluded from that JAR. Use a fixture that starts in the Overworld.

The smoke test exercises native area capture and loading, visible managed placements, separate native placements, three dimension transitions and return, archive save and reopen, raw export, coordinate locks, and native rotation and mirror. It also checks the menu and world-local placement behavior. Results are written to **dimension-layers-smoke.txt** in the test profile.

## Recorded results

- **Pass — default Gradle build and archive checks.** Built with Java 25, Minecraft 26.3 and Fabric Loader 0.19.5 using pinned public dependencies. The test runner also passed against the official installed artifacts.
- **Pass — packaged game test.** In isolated copies of a Minecraft 26.3 world with Litematica 0.29.1 and MaLiLib 0.30.2, the test opened the native menu and editor, captured a real area, loaded and exported unchanged schematic bytes, and found the expected block in Litematica's schematic world. It initialized the native Material List and verified that toggling or closing the add-on did not remove a separate ordinary placement.
- **Pass — dimension and world transitions.** Overworld → Nether → Overworld → End → Overworld activated the expected layer set. A second world used the portable defaults and rejoining the original world recovered its local override.
- **Pass — English and Korean resources and game UI.** Both languages loaded translated UI in the game. The Korean run tested actual clicks in the Litematica and add-on menus.

## Unverified behavior

Public multiplayer servers, actual Easy Place block placement, and third-party mod combinations require separate testing. The add-on makes no world writes during normal use.
