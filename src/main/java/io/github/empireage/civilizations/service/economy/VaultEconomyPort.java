package io.github.empireage.civilizations.service.economy;

import io.github.empireage.civilizations.service.territory.EconomyPort;
import io.github.empireage.civilizations.util.MainThreadExecutor;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Reflection keeps Vault optional while still using its registered Economy service when present. */
public final class VaultEconomyPort implements EconomyPort {
    private static final int MAX_MEMOIZED_INVOCATIONS = 4096;
    private final JavaPlugin plugin;
    private final MainThreadExecutor mainThread;
    private final Object provider;
    private final Class<?> economyClass;
    private final Map<InvocationKey, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<InvocationKey> insertionOrder = new ConcurrentLinkedQueue<>();

    private VaultEconomyPort(JavaPlugin plugin, Object provider, Class<?> economyClass) {
        this.plugin = plugin;
        this.mainThread = new MainThreadExecutor(plugin);
        this.provider = provider;
        this.economyClass = economyClass;
    }

    public static VaultEconomyPort discover(JavaPlugin plugin) {
        try {
            Plugin vault = plugin.getServer().getPluginManager().getPlugin("Vault");
            if (vault == null || !vault.isEnabled()) return null;
            Class<?> economy = vault.getClass().getClassLoader().loadClass("net.milkbowl.vault.economy.Economy");
            @SuppressWarnings({"rawtypes", "unchecked"})
            RegisteredServiceProvider<?> registration = plugin.getServer().getServicesManager().getRegistration((Class) economy);
            if (registration == null || registration.getProvider() == null) return null;
            return new VaultEconomyPort(plugin, registration.getProvider(), economy);
        } catch (ReflectiveOperationException | LinkageError error) {
            plugin.getLogger().log(Level.WARNING, "Vault is installed but its Economy API could not be loaded", error);
            return null;
        }
    }

    public String providerName() {
        try {
            return String.valueOf(economyClass.getMethod("getName").invoke(provider));
        } catch (ReflectiveOperationException ignored) {
            return provider.getClass().getSimpleName();
        }
    }

    @Override
    public CompletableFuture<Result> withdraw(UUID operationId, UUID account, BigDecimal amount) {
        return invokeOnce(new InvocationKey(operationId, "WITHDRAW"), account, amount, "withdrawPlayer");
    }

    @Override
    public CompletableFuture<Result> withdrawTax(UUID operationId, UUID account, BigDecimal amount) {
        return invokeOnce(new InvocationKey(operationId, "TAX_WITHDRAW"), account, amount, "taxWithdrawal");
    }

    @Override
    public CompletableFuture<Result> deposit(UUID operationId, UUID account, BigDecimal amount) {
        return invokeOnce(new InvocationKey(operationId, "DEPOSIT"), account, amount, "depositPlayer");
    }

    @Override
    public CompletableFuture<Result> refund(UUID operationId, UUID account, BigDecimal amount) {
        return invokeOnce(new InvocationKey(operationId, "REFUND"), account, amount, "depositPlayer");
    }

    public CompletableFuture<BigDecimal> balance(UUID account) {
        return mainThread.call(() -> {
            try {
                OfflinePlayer player = plugin.getServer().getOfflinePlayer(account);
                Method method = findPlayerMethod("getBalance");
                return BigDecimal.valueOf(((Number) method.invoke(provider, player)).doubleValue()).setScale(2, java.math.RoundingMode.HALF_UP);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Vault balance lookup failed", exception);
            }
        });
    }

    private CompletableFuture<Result> invokeOnce(InvocationKey key, UUID account, BigDecimal amount, String methodName) {
        Objects.requireNonNull(key.operationId(), "operationId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) return CompletableFuture.completedFuture(Result.failed("Negative economy amount"));
        if (amount.signum() == 0) return CompletableFuture.completedFuture(Result.ok("No currency transfer required"));
        AtomicBoolean created = new AtomicBoolean(false);
        CompletableFuture<Result> invocation = inFlight.computeIfAbsent(key, ignored -> {
            created.set(true);
            return mainThread.call(() -> invoke(account, amount, methodName));
        });
        if (created.get()) {
            insertionOrder.add(key);
            trimCompletedInvocations();
        }
        invocation.whenComplete((result, failure) -> {
            // A declined transfer is safe to retry. Successful and ambiguous
            // outcomes remain memoized for the life of this server process.
            if (failure != null || (result != null && !result.success() && !result.ambiguous())) {
                inFlight.remove(key, invocation);
                insertionOrder.remove(key);
            }
            trimCompletedInvocations();
        });
        return invocation;
    }

    private void trimCompletedInvocations() {
        int attempts = Math.min(insertionOrder.size(), MAX_MEMOIZED_INVOCATIONS);
        while (inFlight.size() > MAX_MEMOIZED_INVOCATIONS && attempts-- > 0) {
            InvocationKey oldest = insertionOrder.poll();
            if (oldest == null) return;
            CompletableFuture<Result> candidate = inFlight.get(oldest);
            if (candidate == null) continue;
            if (candidate.isDone()) inFlight.remove(oldest, candidate);
            else insertionOrder.add(oldest);
        }
    }

    private Result invoke(UUID account, BigDecimal amount, String methodName) {
        try {
            OfflinePlayer player = plugin.getServer().getOfflinePlayer(account);
            if (methodName.equals("taxWithdrawal")) {
                // Check and withdraw in the same main-thread callback. Never infer insolvency from an error string.
                try {
                    Object rawBalance = findPlayerMethod("getBalance").invoke(provider, player);
                    if (!(rawBalance instanceof Number number)) return Result.failed("Invalid account balance from economy provider");
                    double balance = number.doubleValue();
                    if (!Double.isFinite(balance) || balance < 0) return Result.failed("Invalid account balance from economy provider");
                    if (BigDecimal.valueOf(balance).compareTo(amount) < 0) return Result.insufficientFunds();
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    // The withdrawal has not been invoked, so retrying a failed balance lookup is safe.
                    return Result.failed("Could not verify the account balance: " + failure.getMessage());
                }
                methodName = "withdrawPlayer";
            }
            Method method = findPlayerAmountMethod(methodName);
            Object response = method.invoke(provider, player, amount.doubleValue());
            Method successMethod = response.getClass().getMethod("transactionSuccess");
            boolean success = (boolean) successMethod.invoke(response);
            String message = readResponseMessage(response);
            return success ? Result.ok(message) : Result.failed(message.isBlank() ? "Economy provider declined the transfer" : message);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().log(Level.WARNING, "Vault " + methodName + " failed", exception);
            return Result.ambiguous(exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage());
        }
    }

    private Method findPlayerAmountMethod(String name) throws NoSuchMethodException {
        for (Method method : economyClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 2
                && OfflinePlayer.class.isAssignableFrom(parameters[0]) && parameters[1] == double.class) return method;
        }
        throw new NoSuchMethodException(name + "(OfflinePlayer,double)");
    }

    private Method findPlayerMethod(String name) throws NoSuchMethodException {
        for (Method method : economyClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 1 && OfflinePlayer.class.isAssignableFrom(parameters[0])) return method;
        }
        throw new NoSuchMethodException(name + "(OfflinePlayer)");
    }

    private String readResponseMessage(Object response) {
        try {
            Field field = response.getClass().getField("errorMessage");
            Object value = field.get(response);
            return value == null ? "" : String.valueOf(value);
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }

    private record InvocationKey(UUID operationId, String kind) {}
}
