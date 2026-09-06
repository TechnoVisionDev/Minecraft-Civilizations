package io.github.empireage.civilizations.integration;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers a real PlaceholderAPI expansion without a compile-time or bundled
 * PlaceholderAPI dependency. Java's standard class-file API creates the tiny
 * subclass only when PAPI is installed, so this also works on stripped Java
 * runtimes which do not contain a source compiler.
 */
public final class PlaceholderApiIntegration implements AutoCloseable {
    private static final String GENERATED_PACKAGE = "io.github.empireage.civilizations.integration.generated";
    private static final Map<String, PlaceholderValues> RESOLVERS = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;
    private final PlaceholderValues values;
    private final String token = UUID.randomUUID().toString();
    private volatile IntegrationStatus status = IntegrationStatus.missing("PlaceholderAPI");
    private volatile Object expansion;
    private volatile BridgeClassLoader generatedLoader;

    public PlaceholderApiIntegration(JavaPlugin plugin, PlaceholderValues values) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.values = Objects.requireNonNull(values, "values");
    }

    public synchronized IntegrationStatus register() {
        if (expansion != null) return status;
        Plugin placeholderApi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderApi == null || !placeholderApi.isEnabled()) {
            status = IntegrationStatus.missing("PlaceholderAPI");
            return status;
        }
        String version = placeholderApi.getDescription().getVersion();
        try {
            // softdepend in plugin.yml gives this classloader a safe edge to PAPI.
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion", false,
                plugin.getClass().getClassLoader());
            RESOLVERS.put(token, values);
            String simpleName = "CivilizationsExpansion_" + token.replace("-", "");
            String qualifiedName = GENERATED_PACKAGE + "." + simpleName;
            BridgeClassLoader loader = new BridgeClassLoader(plugin.getClass().getClassLoader());
            String author = plugin.getDescription().getAuthors().isEmpty() ? "EmpireAge"
                : String.join(", ", plugin.getDescription().getAuthors());
            Class<?> bridge = loader.define(qualifiedName, bridgeBytes(qualifiedName, token, author,
                plugin.getDescription().getVersion()));
            Object instance = bridge.getDeclaredConstructor().newInstance();
            Object registered = bridge.getMethod("register").invoke(instance);
            if (registered instanceof Boolean success && !success) {
                RESOLVERS.remove(token);
                status = new IntegrationStatus("PlaceholderAPI", true, false, version,
                    "PlaceholderAPI rejected the 'civ' expansion identifier.");
                return status;
            }
            generatedLoader = loader;
            expansion = instance;
            status = new IntegrationStatus("PlaceholderAPI", true, true, version,
                "Registered %civ_*% placeholders through a generated soft-dependency bridge.");
            return status;
        } catch (Throwable failure) {
            RESOLVERS.remove(token);
            status = new IntegrationStatus("PlaceholderAPI", true, false, version,
                "Dynamic expansion registration failed: " + rootMessage(failure));
            plugin.getLogger().warning(status.detail());
            return status;
        }
    }

    public IntegrationStatus status() {
        return status;
    }

    /** Called only by the generated bridge. */
    public static String resolve(String token, UUID playerId, String parameter) {
        PlaceholderValues resolver = RESOLVERS.get(token);
        return resolver == null ? null : resolver.resolve(playerId, parameter);
    }

    @Override
    public synchronized void close() {
        Object current = expansion;
        expansion = null;
        if (current != null) {
            try {
                current.getClass().getMethod("unregister").invoke(current);
            } catch (ReflectiveOperationException ignored) {
                // PlaceholderAPI also unregisters expansions during plugin disable.
            }
        }
        RESOLVERS.remove(token);
        generatedLoader = null;
        Plugin placeholderApi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        status = placeholderApi == null ? IntegrationStatus.missing("PlaceholderAPI")
            : new IntegrationStatus("PlaceholderAPI", true, false, placeholderApi.getDescription().getVersion(),
                "Expansion is unregistered.");
    }

    static byte[] bridgeBytes(String qualifiedName, String token, String author, String pluginVersion) {
        ClassDesc generated = ClassDesc.of(qualifiedName);
        ClassDesc parent = ClassDesc.of("me.clip.placeholderapi.expansion.PlaceholderExpansion");
        ClassDesc offlinePlayer = ClassDesc.of("org.bukkit.OfflinePlayer");
        ClassDesc uuid = ClassDesc.of("java.util.UUID");
        ClassDesc integration = ClassDesc.of(PlaceholderApiIntegration.class.getName());
        MethodTypeDesc noArgsVoid = MethodTypeDesc.of(ConstantDescs.CD_void);
        MethodTypeDesc stringGetter = MethodTypeDesc.of(ConstantDescs.CD_String);
        MethodTypeDesc booleanGetter = MethodTypeDesc.of(ConstantDescs.CD_boolean);
        MethodTypeDesc uniqueId = MethodTypeDesc.of(uuid);
        MethodTypeDesc request = MethodTypeDesc.of(ConstantDescs.CD_String, offlinePlayer, ConstantDescs.CD_String);
        MethodTypeDesc resolve = MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, uuid,
            ConstantDescs.CD_String);
        return ClassFile.of().build(generated, builder -> builder
            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
            .withSuperclass(parent)
            .withMethodBody("<init>", noArgsVoid, ClassFile.ACC_PUBLIC, code -> code
                .aload(0)
                .invokespecial(parent, "<init>", noArgsVoid)
                .return_())
            .withMethodBody("getIdentifier", stringGetter, ClassFile.ACC_PUBLIC, code -> code
                .loadConstant("civ").areturn())
            .withMethodBody("getAuthor", stringGetter, ClassFile.ACC_PUBLIC, code -> code
                .loadConstant(author).areturn())
            .withMethodBody("getVersion", stringGetter, ClassFile.ACC_PUBLIC, code -> code
                .loadConstant(pluginVersion).areturn())
            .withMethodBody("persist", booleanGetter, ClassFile.ACC_PUBLIC, code -> code
                .iconst_1().ireturn())
            .withMethodBody("onRequest", request, ClassFile.ACC_PUBLIC, code -> {
                Label hasPlayer = code.newLabel();
                Label playerLoaded = code.newLabel();
                code.loadConstant(token)
                    .aload(1)
                    .ifnonnull(hasPlayer)
                    .aconst_null()
                    .goto_(playerLoaded)
                    .labelBinding(hasPlayer)
                    .aload(1)
                    .invokeinterface(offlinePlayer, "getUniqueId", uniqueId)
                    .labelBinding(playerLoaded)
                    .aload(2)
                    .invokestatic(integration, "resolve", resolve)
                    .areturn();
            }));
    }

    private static String rootMessage(Throwable failure) {
        Throwable cursor = failure;
        if (cursor instanceof InvocationTargetException invocation && invocation.getTargetException() != null) {
            cursor = invocation.getTargetException();
        }
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private static final class BridgeClassLoader extends ClassLoader {
        private BridgeClassLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
