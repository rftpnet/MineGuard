package org.mineguard.bukkit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.mineguard.core.FeedUpdater;
import org.mineguard.core.IpReputationSet;
import org.mineguard.core.Ipv4CidrSet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class MineGuardPlugin extends JavaPlugin implements Listener, EventExecutor {
  private static final String OFFICIAL_FEED_BASE_URL = "https://raw.githubusercontent.com/rftpnet/MineGuard/main/exports";
  private final IpReputationSet reputation = new IpReputationSet();
  private final AtomicReference<Ipv4CidrSet> allowlist = new AtomicReference<Ipv4CidrSet>(new Ipv4CidrSet());
  private final AtomicLong blockedCount = new AtomicLong();
  private final Map<String, String> config = new HashMap<String, String>();
  private final List<String> allowlistValues = new ArrayList<String>();
  private volatile String lastUpdateStatus = "not_loaded";

  public void onEnable() {
    ensureConfig();
    loadConfigFile();
    reloadFeed();
    if (!registerLoginEvents()) scheduleRegisterLoginEvents();
    scheduleUpdates();
  }

  public void execute(Listener listener, Event event) {
    handleLoginEvent(event);
  }

  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("mineguard.admin")) {
      sender.sendMessage("You do not have permission to use this command.");
      return true;
    }
    if (args.length == 0 || "status".equalsIgnoreCase(args[0]) || "stats".equalsIgnoreCase(args[0])) {
      sender.sendMessage("MineGuard: " + reputation.size() + " entries, " + blockedCount.get() + " blocked, " + statusText(lastUpdateStatus));
      return true;
    }
    if ("reload".equalsIgnoreCase(args[0]) || "update".equalsIgnoreCase(args[0])) {
      loadConfigFile();
      reloadFeed();
      sender.sendMessage("MineGuard reloaded: " + reputation.size() + " entries, " + statusText(lastUpdateStatus));
      return true;
    }
    sender.sendMessage("MineGuard commands: status, reload, update, stats");
    return true;
  }

  private void handleLoginEvent(Object event) {
    if (!bool("blocking.enabled", true)) return;
    InetAddress address = addressFrom(event);
    if (address == null) {
      logWarning("Login event did not expose a remote address: " + event.getClass().getName());
      return;
    }
    if (allowlist.get().contains(address)) return;
    if (!reputation.contains(address)) return;
    blockedCount.incrementAndGet();
    if (showIpsOnConsole()) logInfo("Blocked connection from " + address.getHostAddress());
    disallow(event, str("blocking.kick_message", "Disconnected"));
  }

  private InetAddress addressFrom(Object event) {
    Object address = invoke(event, "getAddress");
    if (address instanceof InetAddress) return (InetAddress) address;
    Object hostname = invoke(event, "getHostname");
    if (hostname instanceof String) {
      String host = String.valueOf(hostname);
      int colon = host.indexOf(":");
      if (colon > 0) host = host.substring(0, colon);
      try { return InetAddress.getByName(host); } catch (Exception ignored) {}
    }
    Object legacyKickMessage = invoke(event, "getKickMessage");
    if (legacyKickMessage instanceof String) {
      String host = String.valueOf(legacyKickMessage).trim();
      int colon = host.indexOf(":");
      if (colon > 0) host = host.substring(0, colon);
      if (host.indexOf(".") > 0 || host.indexOf(":") > 0) {
        try { return InetAddress.getByName(host); } catch (Exception ignored) {}
      }
    }
    Object player = invoke(event, "getPlayer");
    Object socket = player == null ? null : invoke(player, "getAddress");
    if (socket != null) {
      Object inet = invoke(socket, "getAddress");
      if (inet instanceof InetAddress) return (InetAddress) inet;
    }
    return null;
  }

  private void disallow(Object event, String message) {
    Class<?> resultClass = null;
    Object[] values = null;
    try {
      resultClass = Class.forName(event.getClass().getName() + "$Result");
      values = resultClass.getEnumConstants();
    } catch (Exception ignored) {
      Class<?>[] nested = event.getClass().getDeclaredClasses();
      for (int i = 0; i < nested.length; i++) {
        if ("Result".equals(nested[i].getSimpleName())) {
          resultClass = nested[i];
          values = resultClass.getEnumConstants();
        }
      }
    }
    if (resultClass == null || values == null) {
      kickPlayer(event, message);
      return;
    }
    Object result = null;
    for (int i = 0; i < values.length; i++) {
      String name = String.valueOf(values[i]);
      if ("KICK_BANNED".equals(name) || "KICK_OTHER".equals(name)) {
        result = values[i];
        break;
      }
    }
    if (result == null && values.length > 0) result = values[0];
    try {
      Method method = event.getClass().getMethod("disallow", resultClass, String.class);
      method.invoke(event, result, message);
    } catch (Exception ex) {
      logWarning("Could not disallow login with disallow(): " + ex.getClass().getName() + ": " + ex.getMessage());
      invoke(event, "setKickMessage", new Class[] { String.class }, new Object[] { message });
      if (result != null) invoke(event, "setResult", new Class[] { resultClass }, new Object[] { result });
    }
  }

  private boolean registerLoginEvents() {
    boolean registered = false;
    registered = registerModern("org.bukkit.event.player.AsyncPlayerPreLoginEvent") || registered;
    registered = registerModern("org.bukkit.event.player.PlayerLoginEvent") || registered;
    registered = registerLegacy("PLAYER_PRELOGIN") || registered;
    registered = registerLegacy("PLAYER_LOGIN") || registered;
    registered = registerLegacy("PLAYER_JOIN") || registered;
    if (registered) logInfo("MineGuard login listener registered.");
    else logWarning("Could not register a supported login event yet; will retry shortly.");
    return registered;
  }

  private void scheduleRegisterLoginEvents() {
    try {
      getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
        public void run() {
          registerLoginEvents();
        }
      }, 1L);
    } catch (Throwable ignored) {
      try {
        getServer().getScheduler().scheduleAsyncDelayedTask(this, new Runnable() {
          public void run() {
            registerLoginEvents();
          }
        }, 20L);
      } catch (Throwable ignoredAgain) {
        logWarning("Could not schedule MineGuard login listener retry.");
      }
    }
  }

  private void kickPlayer(Object event, final String message) {
    invoke(event, "setJoinMessage", new Class[] { String.class }, new Object[] { null });
    final Object player = invoke(event, "getPlayer");
    if (player == null) {
      logWarning("Could not kick player for " + event.getClass().getName());
      return;
    }
    Runnable task = new Runnable() {
      public void run() {
        invoke(player, "kickPlayer", new Class[] { String.class }, new Object[] { message });

      }
    };
    try {
      getServer().getScheduler().scheduleSyncDelayedTask(this, task, 1L);
    } catch (Throwable ex) {
      try {
        getServer().getScheduler().scheduleAsyncDelayedTask(this, task, 1L);
      } catch (Throwable ignored) {
        task.run();
      }
    }
  }
  private boolean registerModern(String eventClassName) {
    try {
      Class<?> eventClass = Class.forName(eventClassName);
      Class<?> priorityClass = Class.forName("org.bukkit.event.EventPriority");
      Object priority = enumValue(priorityClass, "NORMAL");
      Method method = getServer().getPluginManager().getClass().getMethod("registerEvent", Class.class, Listener.class, priorityClass, EventExecutor.class, Plugin.class);
      method.invoke(getServer().getPluginManager(), eventClass, this, priority, this, this);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private boolean registerLegacy(String typeName) {
    try {
      Class<?> typeClass = Class.forName("org.bukkit.event.Event$Type");
      Class<?> priorityClass = Class.forName("org.bukkit.event.Event$Priority");
      Object type = enumValue(typeClass, typeName);
      Object priority = enumValue(priorityClass, "Normal");
      if (priority == null) priority = enumValue(priorityClass, "NORMAL");
      Method method = getServer().getPluginManager().getClass().getMethod("registerEvent", typeClass, Listener.class, EventExecutor.class, priorityClass, Plugin.class);
      method.invoke(getServer().getPluginManager(), type, this, this, priority, this);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private Object enumValue(Class<?> type, String name) {
    Object[] values = type.getEnumConstants();
    if (values == null) return null;
    for (int i = 0; i < values.length; i++) if (name.equals(String.valueOf(values[i]))) return values[i];
    return null;
  }

  private void scheduleUpdates() {
    if (!bool("update.enabled", true)) return;
    int minutes = intValue("update.interval_minutes", 60);
    if (minutes < 1) minutes = 1;
    long ticks = minutes * 60L * 20L;
    try {
      getServer().getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
        public void run() {
          reloadFeed();
        }
      }, ticks, ticks);
    } catch (Throwable ignored) {
      try {
        getServer().getScheduler().scheduleAsyncRepeatingTask(this, new Runnable() {
          public void run() {
            reloadFeed();
          }
        }, ticks, ticks);
      } catch (Throwable ignoredAgain) {
        logInfo("Periodic updates are disabled on this scheduler; use /mineguard update or reload to refresh the feed.");
      }
    }
  }

  private synchronized void reloadFeed() {
    loadAllowlist();
    Path official = officialLocalFile();
    Path additional = additionalLocalFile();
    try {
      Files.createDirectories(getDataFolder().toPath());
      ensureFeedFiles(official, additional);
      if (bool("update.enabled", true)) {
        try {
          FeedUpdater.download(officialFeedUrl(), official);
          lastUpdateStatus = "remote_ok";
        } catch (Exception remoteError) {
          lastUpdateStatus = Files.exists(official) ? "remote_failed_cache_ok" : "remote_failed_empty";
          logWarning("Could not download MineGuard feed: " + remoteError.getMessage());
        }
      } else {
        lastUpdateStatus = Files.exists(official) ? "local_ok" : "local_empty";
      }
      reputation.loadTxt(new Path[] { official, additional });
    } catch (Exception ex) {
      lastUpdateStatus = "failed_empty: " + ex.getClass().getSimpleName();
      try {
        reputation.loadTxt(new Path[0]);
      } catch (Exception ignored) {}
      logWarning("Could not load MineGuard feed: " + ex.getMessage());
    }
    logInfo("MineGuard loaded: " + reputation.size() + " entries, " + statusText(lastUpdateStatus));
  }
  private void loadAllowlist() {
    Ipv4CidrSet next = new Ipv4CidrSet();
    for (int i = 0; i < allowlistValues.size(); i++) {
      try {
        next.add(allowlistValues.get(i));
      } catch (Exception ex) {
        logWarning("Ignoring invalid allowlist entry: " + allowlistValues.get(i));
      }
    }
    allowlist.set(next);
  }

  private void ensureFeedFiles(Path official, Path additional) throws IOException {
    if (official != null && !Files.exists(official)) Files.createFile(official);
    if (additional != null && !Files.exists(additional)) Files.createFile(additional);
  }
  private String officialFeedUrl() {
    return OFFICIAL_FEED_BASE_URL + "/" + retention() + "/ips.txt";
  }

  private String retention() {
    String value = str("update.retention", "30d").trim().toLowerCase();
    if ("30d".equals(value) || "180d".equals(value) || "permanent".equals(value)) return value;
    logWarning("Invalid MineGuard retention '" + value + "', using 30d.");
    return "30d";
  }
  private Path officialLocalFile() {
    return getDataFolder().toPath().resolve("ips-v4.txt");
  }

  private Path additionalLocalFile() {
    String configured = str("additional_local_file", "additional-ips-v4.txt");
    File file = new File(configured);
    if (!file.isAbsolute()) file = new File(getDataFolder(), configured);
    return file.toPath().normalize();
  }

  private void ensureConfig() {
    File dir = getDataFolder();
    if (!dir.exists()) dir.mkdirs();
    File file = new File(dir, "config.yml");
    if (file.exists()) return;
    try {
      FileWriter writer = new FileWriter(file);
      writer.write("update:\n  enabled: true\n  interval_minutes: 60\n  retention: 30d\nadditional_local_file: additional-ips-v4.txt\nblocking:\n  enabled: true\n  kick_message: Disconnected\nshow_ips_on_console: true\nallowlist:\n  - 127.0.0.1\n");
      writer.close();
    } catch (IOException ex) {
      logWarning("Could not create default config: " + ex.getMessage());
    }
  }

  private void loadConfigFile() {
    config.clear();
    allowlistValues.clear();
    File file = new File(getDataFolder(), "config.yml");
    String prefix = "";
    boolean inAllowlist = false;
    try {
      BufferedReader reader = new BufferedReader(new FileReader(file));
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          String raw = line;
          String trimmed = raw.trim();
          if (trimmed.length() == 0 || trimmed.startsWith("#")) continue;
          if (!raw.startsWith(" ") && trimmed.endsWith(":")) {
            prefix = trimmed.substring(0, trimmed.length() - 1);
            inAllowlist = "allowlist".equals(prefix);
            continue;
          }
          if (inAllowlist && trimmed.startsWith("-")) {
            allowlistValues.add(clean(trimmed.substring(1).trim()));
            continue;
          }
          int idx = trimmed.indexOf(':');
          if (idx > 0) {
            String key = trimmed.substring(0, idx).trim();
            String value = clean(trimmed.substring(idx + 1).trim());
            config.put(prefix.length() == 0 ? key : prefix + "." + key, value);
          }
        }
      } finally {
        reader.close();
      }
    } catch (IOException ex) {
      logWarning("Could not read MineGuard config: " + ex.getMessage());
    }
  }

  private String clean(String value) {
    String s = value.trim();
    if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) return s.substring(1, s.length() - 1);
    return s;
  }

  private boolean bool(String key, boolean fallback) {
    String value = config.get(key);
    if (value == null || value.length() == 0) return fallback;
    return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value);
  }

  private boolean showIpsOnConsole() {
    return bool("show_ips_on_console", true);
  }
  private int intValue(String key, int fallback) {
    try {
      String value = config.get(key);
      return value == null ? fallback : Integer.parseInt(value);
    } catch (Exception ex) {
      return fallback;
    }
  }

  private String str(String key, String fallback) {
    String value = config.get(key);
    return value == null ? fallback : value;
  }

  private String statusText(String status) {
    if ("remote_ok".equals(status)) return "feed updated";
    if ("remote_failed_cache_ok".equals(status)) return "using cached feed";
    if ("remote_failed_empty".equals(status)) return "feed unavailable";
    if ("local_ok".equals(status)) return "using local feed";
    if ("local_empty".equals(status)) return "local feed is empty";
    if (status != null && status.startsWith("failed_empty")) return "feed load failed";
    return status == null ? "not loaded" : status;
  }
  private void logInfo(String message) {
    java.util.logging.Logger.getLogger("MineGuard").info(message);
  }

  private void logWarning(String message) {
    java.util.logging.Logger.getLogger("MineGuard").warning(message);
  }
  private Object invoke(Object target, String name) {
    return invoke(target, name, new Class[0], new Object[0]);
  }

  private Object invoke(Object target, String name, Class[] types, Object[] args) {
    try {
      Method method = target.getClass().getMethod(name, types);
      return method.invoke(target, args);
    } catch (Throwable ex) {
      return null;
    }
  }
}