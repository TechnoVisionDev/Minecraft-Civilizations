package io.github.empireage.civilizations;

import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.runtime.*;
import io.github.empireage.civilizations.service.territory.ProtectionService;
import io.github.empireage.civilizations.service.war.WarService;
import org.bukkit.Server;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.logging.Logger;
import static org.mockito.Mockito.*;

class FoundingLifecycleWiringTest {
    @Test void foundingReceivesJoinEventsAndClosesBeforeDatabase() throws Exception {
        CivilizationsPlugin plugin = mock(CivilizationsPlugin.class, CALLS_REAL_METHODS);
        FoundingCoordinator founding = mock(FoundingCoordinator.class);
        Database database = mock(Database.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        doReturn(server).when(plugin).getServer();
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        when(server.getPluginManager()).thenReturn(manager);
        set(plugin, "founding", founding);
        set(plugin, "database", database);
        set(plugin, "settings", mock(Settings.class));
        Method register = CivilizationsPlugin.class.getDeclaredMethod("registerGameplayListeners",
            ProtectionService.class, WarService.class, CivilizationChat.class);
        register.setAccessible(true);
        register.invoke(plugin, mock(ProtectionService.class), mock(WarService.class), mock(CivilizationChat.class));
        verify(manager).registerEvents(founding, plugin);
        try (var handlers = mockStatic(HandlerList.class)) {
            plugin.onDisable();
        }
        var order = inOrder(founding, database);
        order.verify(founding).close();
        order.verify(database).close();
    }

    private static void set(CivilizationsPlugin plugin, String fieldName, Object value) throws Exception {
        Field field = CivilizationsPlugin.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
