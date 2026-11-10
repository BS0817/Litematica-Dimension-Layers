# Litematica Dimension Layers

A client-side Fabric add-on that organizes Litematica schematics for several Minecraft dimensions in a single portable project. [한국어 안내](docs/README.ko.md)

- Create or open a **.ldl** project, capture a Litematica selection, or import an existing **.litematic** file.
- Show only the active dimension's layers. Multiple layers per dimension and custom dimension IDs are supported.
- Set each layer's placement, rotation, mirror, visibility, and coordinate lock.
- Link Overworld and Nether placements to an independent portal anchor with 8:1 X/Z conversion, per-layer offsets, and a live coordinate preview.
- Share portable defaults in the project. Actual world-local placement overrides stay in the local profile.

## Install

Use Minecraft Java **26.3**, Java **25**, Fabric Loader **0.19.5** or later, and Fabric API **0.165.2+26.3**. This release targets [Litematica 0.29.1](https://modrinth.com/mod/litematica/version/0.29.1) with [MaLiLib 0.30.2](https://modrinth.com/mod/malilib/version/0.30.2). Put the installable **litematica-dimension-layers-0.1.0+mc26.3.jar** in the Fabric profile's **mods** folder. The sources JAR is for development.

Open Litematica's main menu and select **Dimension Layers**, or bind **Open Dimension Layers** in Minecraft's Controls. The key is unassigned by default. See [installation](docs/installation.md) and [usage](docs/usage.md) for the workflow.

## Build and report issues

With JDK 25, run **./gradlew build** or **gradlew.bat build**. The Gradle wrapper resolves the official dependencies at pinned versions. The build runs **verifyProject** and writes the installable JAR to **build/libs/**.

See [project format](docs/project-format.md), [compatibility and limitations](docs/compatibility.md), [tests](docs/testing.md), and [contributing](CONTRIBUTING.md). Report problems on [GitHub Issues](https://github.com/BS0817/Litematica-Dimension-Layers/issues) with your mod versions and reproduction steps.

## License

Original add-on code is licensed under [LGPL-3.0-only](LICENSE). [GPL 3.0](COPYING.GPL-3.0) supplies the base terms referenced by the LGPL. Litematica and MaLiLib are separately installed dependencies. Both are distributed by their respective authors under LGPL-3.0-only. The add-on JAR does not bundle their classes, assets, or Minecraft.
