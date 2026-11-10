package dev.dimensionlayers;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DimensionLayers implements ClientModInitializer {
    static final String ID = "litematica_dimension_layers";
    static final Logger LOG = LoggerFactory.getLogger(ID);
    static LayerSession session;

    @Override
    public void onInitializeClient() {
        session = new LayerSession(FabricLoader.getInstance().getConfigDir().resolve(ID));
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "projects"));
        var key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ldl.projects", InputConstants.UNKNOWN.getValue(), category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            session.tick(client);
            while (key.consumeClick()) {
                if (client.gui.screen() == null) client.gui.setScreen(new LayerScreen(session));
            }
        });
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof fi.dy.masa.litematica.gui.GuiMainMenu menu) {
                var button = new fi.dy.masa.malilib.gui.button.ButtonGeneric(menuX(width), menuY(height), 142, 20,
                    net.minecraft.client.resources.language.I18n.get("ldl.title"), new String[0]);
                menu.addButton(button, (ignored, mouseButton) -> client.gui.setScreen(new LayerScreen(session, menu)));
            }
        });
    }

    static int menuX(int width) { return Math.max(4, width - 148); }
    static int menuY(int height) { return Math.max(4, height - 26); }
}
