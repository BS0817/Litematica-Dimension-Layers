# LDL format 1

An .ldl is a ZIP with UTF-8 **manifest.json** and one **schematics/<layer UUID>.litematic** per ordered layer. Each embedded file is the exact imported byte stream. Project UUID and layer UUIDs are independent. Groups are named within a project.

The manifest contains **format**, **id**, **name**, **description**, **visible**, **layers** and **groups**. Each layer has **id**, **name**, **dimension**, **design**, **visible**, **locked**, **group**, **sync** and **offset**. Design contains **origin**, **rotation** and **mirror**. Points contain integer **x**, **y**, **z**. Each group has **name**, **basis**, decimal **x** and **z**, and **enabled**. Dimensions are resource IDs. Custom dimension IDs are kept until that dimension is visited. Only minecraft:overworld and minecraft:the_nether can join 8:1 groups.

Archive files contain no add-on-generated world paths, server addresses, or account IDs. Imported .litematic bytes can contain pre-existing author or block-entity data and are kept as supplied. Capture does not store the account name as its author. Review the data in your own schematics before sharing.

Local transforms live under **config/litematica_dimension_layers/worlds/** and are indexed by a digest of world or server identity, project UUID and canonical archive path. Two identical copies at different paths keep separate overrides. Renaming or moving a project starts a new local placement context. Other users receive the portable defaults.

The reader rejects unknown format versions, repeated layer IDs, duplicate or unexpected ZIP entries, missing members, CRC failures and invalid coordinates. It never extracts archive-supplied paths. Limits are 128 layers, 16 MiB per compressed .litematic, 64 MiB total schematic bytes, 1 MiB of manifest data, and 16,777,216 stored block cells per schematic. Unversioned or future formats require an appropriate converter.
