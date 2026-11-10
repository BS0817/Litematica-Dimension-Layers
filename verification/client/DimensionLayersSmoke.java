package dev.dimensionlayers;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.SelectionMode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

public final class DimensionLayersSmoke implements ClientModInitializer {
    private int phase;
    private int age;
    private int stepTicks;
    private Project.Entry surface;
    private Project.Entry nether;
    private Project.Entry end;
    private Project.Entry surfaceSecond;
    private SchematicPlacement manual;
    private Path archive;
    private byte[] original;
    private net.minecraft.world.level.block.state.BlockState expectedBlock;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getSingleplayerServer() == null || client.player == null || client.level == null) return;
            if (++age > 1500) fail(client, new AssertionError("Timed out at step " + phase));
            if (++stepTicks < 24) return;
            try { test(client); }
            catch (Throwable failure) { fail(client, failure); }
        });
    }

    private void test(Minecraft client) throws Exception {
        var session = DimensionLayers.session;
        var manager = DataManager.getSchematicPlacementManager();
        var root = session.directory.resolve("smoke");
        switch (phase) {
            case 0 -> {
                require(client.level.dimension() == Level.OVERWORLD, "Expected overworld start");
                Files.createDirectories(root);
                var selectionManager = DataManager.getSelectionManager();
                if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) selectionManager.switchSelectionMode();
                var selection = selectionManager.getCurrentSelection();
                selection.setName("Smoke fixture");
                var pos = client.player.blockPosition().below();
                expectedBlock = client.level.getBlockState(pos);
                selection.setExplicitOrigin(pos);
                var box = selection.getAllSubRegionBoxes().getFirst();
                box.setPos1(pos);
                box.setPos2(pos);
                var captured = session.bridge.capture(client);
                require(captured.pose().equals(LayerSession.at(pos)), "Selection capture origin");
                Path fixture = root.resolve("input.litematic");
                Files.write(fixture, captured.data());
                original = ProjectFiles.readSchematic(fixture);
                var nativeSchematic = fi.dy.masa.litematica.schematic.LitematicaSchematic.createFromFile(root, "input.litematic");
                require(nativeSchematic != null, "Native Litematica could not open captured file");
                manual = SchematicPlacement.createFor(nativeSchematic, pos, "Manual unrelated placement", true, true);
                manager.addSchematicPlacement(manual, false);
                manager.setSelectedSchematicPlacement(manual);
                require(Arrays.equals(original, session.bridge.selectedPlacement().data()), "Selected placement import");
                session.create("Smoke test", "차원 테스트");
                surface = session.add(original, "Overworld A", Project.OVERWORLD, LayerSession.at(pos));
                surfaceSecond = session.add(original, "Overworld B", Project.OVERWORLD, LayerSession.at(pos.east()));
                nether = session.add(original, "Nether", Project.NETHER, LayerSession.at(new BlockPos(2, 81, 3)));
                end = session.add(original, "End", Project.END, LayerSession.at(new BlockPos(2, 82, 3)));
                session.saveLink(new Project.Link("Portal", Project.OVERWORLD, new BigDecimal("-9"), new BigDecimal("25.5"), true));
                surface = link(session, surface, true, new Project.Point(0, 0, 0));
                nether = link(session, nether, false, new Project.Point(3, -2, -1));
                require(session.resync("Portal") == 1, "Locked layer must be skipped");
                require(session.pose(surface).origin().equals(LayerSession.at(pos).origin()), "Locked layer moved");
                require(session.pose(nether).origin().equals(new Project.Point(1, 79, 2)), "Negative anchor and offset");
                var local = new Project.Pose(new Project.Point(3, 80, 4), Project.Turn.CLOCKWISE_90, Project.Flip.LEFT_RIGHT);
                session.updateEntry(nether, nether, local, false);
                archive = root.resolve("smoke.ldl");
                session.save(archive);
                ProjectFiles.exportSchematic(root.resolve("export.litematic"), nether.data());
                require(Arrays.equals(original, Files.readAllBytes(root.resolve("export.litematic"))), "Export changed original bytes");
                session.close(false);
                session.open(archive);
                nether = session.project.entries.stream().filter(e -> e.id().equals(nether.id())).findFirst().orElseThrow();
                require(session.pose(nether).equals(local), "Saved world transform did not reopen");
                require(session.project.entries.get(2).design().origin().equals(new Project.Point(2, 81, 3)), "World pose leaked into manifest");
                var menu = new GuiMainMenu();
                client.gui.setScreen(menu);
                menu.mouseClicked(new MouseButtonEvent(DimensionLayers.menuX(client.getWindow().getGuiScaledWidth()) + 5,
                    DimensionLayers.menuY(client.getWindow().getGuiScaledHeight()) + 5, new MouseButtonInfo(1, 0)), false);
                require(client.gui.screen() instanceof LayerScreen, "Litematica menu click failed");
                require(I18n.get("ldl.title").equals(client.getLanguageManager().getSelected().equals("ko_kr") ? "차원 레이어" : "Dimension Layers"),
                    "Localization not loaded");
                press(client.gui.screen(), "ldl.metadata");
                require(!(client.gui.screen() instanceof LayerScreen), "Metadata editor did not open");
                press(client.gui.screen(), "ldl.back");
                require(client.gui.screen() instanceof LayerScreen, "Metadata editor did not return");
                client.gui.setScreen(null);
                next();
            }
            case 1 -> {
                var layer = placements("Overworld A", "Overworld B");
                require(manager.getAllSchematicsPlacements().contains(manual), "Unrelated placement was removed");
                require(layer.getMaterialList() != null, "Native material list did not initialize");
                var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
                require(schematicWorld != null && schematicWorld.getBlockState(layer.getOrigin()).equals(expectedBlock),
                    "Native schematic world did not contain the captured block");
                session.project.visible = false;
                session.edited();
                next();
            }
            case 2 -> {
                placements();
                require(manager.getAllSchematicsPlacements().contains(manual), "Hide-all removed unrelated placement");
                session.project.visible = true;
                session.edited();
                move(client, Level.NETHER);
                next();
            }
            case 3 -> {
                if (client.level.dimension() != Level.NETHER || stepTicks < 45) return;
                var actual = placements("Nether");
                require(actual.getOrigin().equals(new BlockPos(3, 80, 4)), "Nether origin");
                require(actual.getRotation() == Rotation.CLOCKWISE_90 && actual.getMirror() == Mirror.LEFT_RIGHT, "Native rotation and mirror");
                move(client, Level.OVERWORLD);
                next();
            }
            case 4 -> {
                if (client.level.dimension() != Level.OVERWORLD || stepTicks < 45) return;
                placements("Overworld A", "Overworld B");
                move(client, Level.END);
                next();
            }
            case 5 -> {
                if (client.level.dimension() != Level.END || stepTicks < 45) return;
                placements("End");
                move(client, Level.OVERWORLD);
                next();
            }
            case 6 -> {
                if (client.level.dimension() != Level.OVERWORLD || stepTicks < 45) return;
                placements("Overworld A", "Overworld B");
                session.save(archive);
                var bad = root.resolve("invalid.ldl");
                Files.writeString(bad, "Invalid archive");
                var current = session.project;
                try { session.open(bad); throw new AssertionError("Opened corrupted archive"); }
                catch (java.io.IOException expected) { require(session.project == current, "Failed open discarded working project"); }
                session.close(false);
                next();
            }
            case 7 -> {
                placements();
                session.open(archive);
                switchWorld(client, "Fixture-B");
                next();
            }
            case 8 -> {
                if (!fixture(client).equals("Fixture-B") || stepTicks < 45) return;
                require(session.pose(findNether(session)).origin().equals(new Project.Point(2, 81, 3)),
                    "World-local transform leaked into another world");
                placements("Overworld A", "Overworld B");
                switchWorld(client, "Fixture");
                next();
            }
            case 9 -> {
                if (!fixture(client).equals("Fixture") || stepTicks < 45) return;
                require(session.pose(findNether(session)).origin().equals(new Project.Point(3, 80, 4)),
                    "Rejoining the original world did not restore its local transform");
                placements("Overworld A", "Overworld B");
                Files.writeString(Path.of("dimension-layers-smoke.txt"), "PASS "
                    + client.getLanguageManager().getSelected()
                    + ": packaged add-on, Litematica menu clicks, capture, native schematic world and material list, linked transforms, lock, archive, raw export, corruption, all dimension transitions, world isolation, and rejoin.\n");
                client.stop();
            }
            default -> throw new AssertionError("Unexpected step " + phase);
        }
    }

    private static void press(net.minecraft.client.gui.screens.Screen screen, String label) {
        var button = Screens.getWidgets(screen).stream().filter(w -> w instanceof Button)
            .map(w -> (Button) w).filter(w -> w.getMessage().getString().equals(I18n.get(label)))
            .findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label));
        require(screen.mouseClicked(new MouseButtonEvent(button.getX() + 5, button.getY() + 5,
            new MouseButtonInfo(1, 0)), false), "Click did not reach " + label);
    }

    private static Project.Entry findNether(LayerSession session) {
        return session.project.entries.stream().filter(e -> e.dimension().equals(Project.NETHER)).findFirst().orElseThrow();
    }

    private static String fixture(Minecraft client) {
        return client.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .normalize().getFileName().toString();
    }

    private static void switchWorld(Minecraft client, String name) {
        client.disconnect(new TitleScreen(), false);
        client.createWorldOpenFlows().openWorld(name, () -> {});
    }

    private static Project.Entry link(LayerSession session, Project.Entry entry, boolean lock, Project.Point offset) throws Exception {
        var linked = new Project.Entry(entry.id(), entry.name(), entry.dimension(), entry.design(), true, lock,
            "Portal", true, offset, entry.data());
        session.updateEntry(entry, linked, null, false);
        return linked;
    }

    private static SchematicPlacement placements(String... expected) {
        var own = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
            .filter(p -> p.getName().startsWith("[LDL] ")).toList();
        require(own.size() == expected.length, "Expected managed placements " + Arrays.toString(expected)
            + "; found " + own.stream().map(SchematicPlacement::getName).toList());
        for (String name : expected) require(own.stream().anyMatch(p -> p.getName().equals("[LDL] " + name)
            && !p.shouldBeSaved() && p.isEnabled()), "Missing enabled transient placement " + name);
        return own.isEmpty() ? null : own.getFirst();
    }

    private void next() { phase++; stepTicks = 0; }

    private static void move(Minecraft client, ResourceKey<Level> dimension) {
        var server = client.getSingleplayerServer();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(server.getLevel(dimension), 5.5, 90, 5.5, Set.of(), 0, 0, true);
        });
    }

    private void fail(Minecraft client, Throwable failure) {
        failure.printStackTrace();
        try { Files.writeString(Path.of("dimension-layers-smoke.txt"), "FAIL step " + phase + ": " + failure + "\n"); }
        catch (Exception reportFailure) { reportFailure.printStackTrace(); }
        client.stop();
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
