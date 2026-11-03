# Install and Upgrade

[简体中文](../zh-CN/INSTALL.md) | [Home](../../README.en.md)

## Requirements

Use Java 25, Paper 26.3 build 202 and `LunarCore-0.0.6-preview.jar`. First startup needs Maven Central access for runtime libraries.

## First Installation

1. Stop the server and put the core JAR in `plugins/`.
2. Start and wait for the plugin to enable.
3. Open `http://127.0.0.1:8765` on the server computer.
4. Follow the console login instructions, then change the administrator password.
5. Install optional features through the market and fully restart.

Only basic commands are included. Homes and teleport requests need the teleport module; chat and AI need the chat module.

## Upgrade

1. Stop and back up `plugins/LunarCore/`, server settings and worlds.
2. Replace the old core in `plugins/`. Keep only one LunarCore JAR.
3. Start and check accounts, worlds and modules.
4. Update modules through the market, one set at a time, then restart. Alternatively replace matching modules manually while stopped.

Existing optional modules are kept. The core automatically updates only basic commands. Custom names and logos are preserved; resetting the name restores “LunarCore 琉月”.

The internal name remains `LunarCore`; its data folder stays the same.

## Manual Module Installation

Download JARs matching the core version. Stop and place them in `plugins/LunarCore/modules/`. Replace old files instead of keeping duplicates. Building landmarks requires the land module.

You can select modules from `LunarCore-modules-0.0.6-preview.zip`. Installing more modules uses more resources.

## Remote Panel Access

The panel accepts local connections by default. Edit its address in `plugins/LunarCore/config.yml` for remote access. For public use, place an HTTPS reverse proxy such as Nginx or Caddy in front of the local panel. It must support WebSocket for live logs.

Run `lc web reload` after changing `web` settings. Do not expose the plain HTTP panel port publicly.

## Library Downloads

Check the network. If the default Maven mirror reports checksum errors, add this before `-jar` in the Java command:

```text
-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=https://repo.maven.apache.org/maven2
```

Keep file verification enabled.
