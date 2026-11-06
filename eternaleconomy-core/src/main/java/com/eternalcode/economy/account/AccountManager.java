package com.eternalcode.economy.account;

import com.eternalcode.economy.account.database.AccountRepository;
import com.eternalcode.economy.config.implementation.PluginConfig;
import com.eternalcode.economy.leaderboard.LeaderboardService;
import com.eternalcode.economy.leaderboard.LeaderboardServiceImpl;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Logger;

public class AccountManager {

    private final Map<UUID, Account> accountByUniqueId = new ConcurrentHashMap<>();
    private final NavigableMap<String, Account> accountByName = new ConcurrentSkipListMap<>(
        String.CASE_INSENSITIVE_ORDER);

    private final Map<UUID, CompletableFuture<Account>> pendingUuids = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Account>> pendingNames = new ConcurrentHashMap<>();

    private final Object creationLock = new Object();

    private final AccountRepository accountRepository;
    private final LeaderboardServiceImpl leaderboardService;
    private final Logger logger;

    private final CompletableFuture<Void> accountsLoaded = new CompletableFuture<>();

    public AccountManager(AccountRepository accountRepository, PluginConfig config, Logger logger) {
        this.accountRepository = accountRepository;
        this.leaderboardService = new LeaderboardServiceImpl(accountRepository);
        this.logger = logger;
    }

    public static AccountManager create(AccountRepository accountRepository, PluginConfig config, Logger logger) {
        AccountManager accountManager = new AccountManager(accountRepository, config, logger);

        accountRepository.getAllAccounts()
            .thenAccept(accounts -> {
                for (Account account : accounts) {
                    accountManager.updateCache(account);
                }

                accountManager.accountsLoaded.complete(null);
            })
            .exceptionally(throwable -> {
                logger.severe("Failed to load accounts: " + throwable.getMessage());
                throwable.printStackTrace();
                accountManager.accountsLoaded.completeExceptionally(throwable);
                return null;
            });

        return accountManager;
    }

    public CompletableFuture<Void> accountsLoaded() {
        return this.accountsLoaded;
    }

    public Account getAccount(UUID uuid) {
        return this.accountByUniqueId.get(uuid);
    }

    public Account getAccount(String name) {
        return this.accountByName.get(name);
    }

    /**
     * Asynchronous version used when an account may not exist yet.
     * It waits until the database cache has been loaded.
     */
    public CompletableFuture<Account> getOrCreateAsync(UUID uuid, String name) {
        return this.accountsLoaded.thenCompose(ignored -> {
            synchronized (this.creationLock) {
                Account existing = this.accountByUniqueId.get(uuid);

                if (existing != null) {
                    if (existing.name().equals(name)) {
                        return CompletableFuture.completedFuture(existing);
                    }

                    Account renamed = new Account(uuid, name, existing.balance());

                    return this.accountRepository.save(renamed)
                        .thenApply(saveResult -> {
                            synchronized (this.creationLock) {
                                this.updateCache(renamed);
                                this.leaderboardService.invalidateCache();
                            }

                            return renamed;
                        });
                }

                Account nameCollision = this.accountByName.get(name);

                if (nameCollision != null) {
                    return CompletableFuture.failedFuture(
                        new IllegalStateException(
                            "Player name '" + name + "' already belongs to UUID "
                                + nameCollision.uuid()
                        )
                    );
                }
            }

            return this.createAccount(uuid, name, BigDecimal.ZERO);
        });
    }

    /**
     * Creates a new account. Existing UUIDs or names are rejected.
     */
    public CompletableFuture<Account> createAccount(
        UUID uuid,
        String name,
        BigDecimal balance
    ) {
        if (uuid == null || name == null || name.isBlank() || balance == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("UUID, name and balance are required.")
            );
        }

        if (balance.compareTo(BigDecimal.ZERO) < 0) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Balance cannot be negative.")
            );
        }

        return this.accountsLoaded.thenCompose(ignored -> {
            String normalizedName = name.toLowerCase(Locale.ROOT);

            synchronized (this.creationLock) {
                Account existingUuid = this.accountByUniqueId.get(uuid);
                if (existingUuid != null) {
                    return CompletableFuture.failedFuture(
                        new IllegalStateException("Account with this UUID already exists.")
                    );
                }

                Account existingName = this.accountByName.get(name);
                if (existingName != null) {
                    return CompletableFuture.failedFuture(
                        new IllegalStateException(
                            "Account with name '" + name + "' already exists."
                        )
                    );
                }

                CompletableFuture<Account> pendingUuid = this.pendingUuids.get(uuid);
                if (pendingUuid != null) {
                    return pendingUuid;
                }

                CompletableFuture<Account> pendingName = this.pendingNames.get(normalizedName);
                if (pendingName != null) {
                    return pendingName;
                }

                Account account = new Account(uuid, name, balance);
                CompletableFuture<Account> creation = new CompletableFuture<>();

                this.pendingUuids.put(uuid, creation);
                this.pendingNames.put(normalizedName, creation);

                this.accountRepository.save(account)
                    .whenComplete((result, throwable) -> {
                        synchronized (this.creationLock) {
                            this.pendingUuids.remove(uuid);
                            this.pendingNames.remove(normalizedName);

                            if (throwable != null) {
                                creation.completeExceptionally(throwable);
                                return;
                            }

                            this.updateCache(account);
                            this.leaderboardService.invalidateCache();
                            creation.complete(account);
                        }
                    });

                return creation;
            }
        });
    }

    /**
     * Kept for compatibility with existing code.
     * New account creation should use getOrCreateAsync().
     */
    public Account getOrCreate(UUID uuid, String name) {
        if (!this.accountsLoaded.isDone() || this.accountsLoaded.isCompletedExceptionally()) {
            throw new IllegalStateException(
                "Account database has not finished loading. Use getOrCreateAsync()."
            );
        }

        Account existing = this.accountByUniqueId.get(uuid);

        if (existing != null) {
            if (!existing.name().equals(name)) {
                Account updated = new Account(uuid, name, existing.balance());
                this.save(updated);
                return updated;
            }

            return existing;
        }

        Account byName = this.accountByName.get(name);

        if (byName != null) {
            if (!byName.uuid().equals(uuid)) {
                throw new IllegalStateException(
                    "Player name '" + name + "' belongs to another UUID."
                );
            }

            return byName;
        }

        Account newAccount = new Account(uuid, name, BigDecimal.ZERO);
        this.save(newAccount);
        return newAccount;
    }

    public void save(Account account) {
        this.updateCache(account);
        this.leaderboardService.invalidateCache();

        this.accountRepository.save(account).exceptionally(throwable -> {
            this.logger.severe("Failed to save account " + account.uuid() + ": " + throwable.getMessage());
            return null;
        });
    }

    private void updateCache(Account account) {
        Account old = this.accountByUniqueId.get(account.uuid());
        if (old != null && !old.name().equals(account.name())) {
            this.accountByName.remove(old.name());
        }

        Account oldByName = this.accountByName.get(account.name());

        if (oldByName != null && !oldByName.uuid().equals(account.uuid())) {
            throw new IllegalStateException(
                "Cannot cache account: name '" + account.name()
                    + "' is already used by UUID " + oldByName.uuid()
            );
        }

        this.accountByUniqueId.put(account.uuid(), account);
        this.accountByName.put(account.name(), account);
    }

    public Collection<Account> getAccountStartingWith(String prefix) {
        return Collections.unmodifiableCollection(
            this.accountByName.subMap(prefix, true, prefix + Character.MAX_VALUE, true).values());
    }

    public Collection<Account> getAccounts() {
        return Collections.unmodifiableCollection(this.accountByUniqueId.values());
    }

    public LeaderboardService getLeaderboardService() {
        return this.leaderboardService;
    }
}
