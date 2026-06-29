# MineGuard

MineGuard is a defensive security project for the Minecraft ecosystem. It operates passive Minecraft-related honeypots, records public IP addresses that interact with those honeypots, and publishes IP blocklist feeds that server administrators can use for filtering.

## How it works

There are several bots and services on the internet that scan public IP addresses in search of unprotected Minecraft servers. We operate honeypots, capture the crawlers’ addresses, and provide a public list. As an admin, you can block these IP addresses directly in your firewall (recommended). If you don’t have firewall access, you can use the MineGuard plugin to block them at the application layer.

**The standard recommendation remains to use a private server protected by a strict whitelist, `online mode`, and regular backups. MineGuard should be treated as an additional layer that helps prevent bots from indexing the server, not as a replacement for proper access control. On servers without a whitelist, any authenticated player can connect. If `offline mode` is enabled, players may also connect without account authentication, which may allow them to spoof a valid username and bypass the whitelist. In these cases, using a login/authentication plugin is recommended. <u>A server without a whitelist remains vulnerable to attacks, and no tool can fully prevent such risks.</u>**

MineGuard feeds consist of IP addresses observed interacting with Minecraft honeypots. They should not be interpreted as confirmed attackers, criminals, compromised hosts, or evidence of abuse

## How to use

1. Use the /exports list to block IP addresses directly in the firewall (recommended)

2. Use the plugin with your preferred Minecraft server software. See the compatibility table below.


## Minecraft Plugin

| Software* | Minecraft Version | Result / Test |
|:---|:---|:---|
| CraftBukkit | Beta 1.2 - 1.3_01 | Kicked after 1 tick** |
| CraftBukkit | Beta 1.4 - 1.8.1 | Blocked before joining via ```PlayerLoginEvent``` |
| CraftBukkit | Release 1.0 - 1.7.10 | Blocked before joining via ```PlayerLoginEvent``` |
| Paper | Release 1.8 - 26.2 | Blocked before joining via ```PlayerLoginEvent``` |

*Other "Bukkit" forks may also work.

**Initial server packets, such as spawn packets, may still be sent.

## Plugin configuration (config.yml)

```text
update:
  enabled: true
  interval_minutes: 60
  retention: "30d"

additional_local_file: "additional-ips-v4.txt"

blocking:
  enabled: true
  kick_message: "Disconnected"

show_ips_on_console: true

allowlist:
  - "127.0.0.1"
  - "10.0.0.0/8"
  - "172.16.0.0/12"
  - "192.168.0.0/16"
```

You can add a custom IP blocklist to supplement MineGuard's list, or disable the MineGuard list download and rely entirely on your own list. 
MineGuard has 3 lists: ```30d```(recommended), ```180d``` (for more aggressive blocking), and ```permanent``` (not recommended)

---
<p align="center">MineGuard is not an official Mojang, Microsoft, or Minecraft project.</p>
