package com.lunarboreal.core.web;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server-wide panel branding, stored separately from the core configuration. */
final class BrandingManager {
    static final String DEFAULT_NAME = "LunarCore 琉月";
    static final int MAX_LOGO_BYTES = 256 * 1024;
    private static final int MAX_LOGO_DIMENSION = 1024;
    private static final Pattern DATA_URL = Pattern.compile("^data:image/(png|jpeg);base64,([A-Za-z0-9+/]+={0,2})$");
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] JPEG_MAGIC = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};

    record Logo(byte[] bytes, String contentType) {}

    private final Path directory;
    private final Path configFile;
    private final Path logoFile;

    BrandingManager(Path directory) {
        this.directory = directory;
        this.configFile = directory.resolve("web-branding.yml");
        this.logoFile = directory.resolve("web-branding-logo");
    }

    synchronized Map<String, Object> info() {
        Logo logo = logo();
        String logoUrl = logo == null ? "/favicon.svg" : "/api/branding/logo?v=" + revision(logo.bytes());
        return Json.of("brand", name(), "logoUrl", logoUrl);
    }

    synchronized String name() {
        try {
            if (!regular(configFile) || Files.size(configFile) > 8192) return DEFAULT_NAME;
            String value = YamlConfiguration.loadConfiguration(configFile.toFile()).getString("name", "");
            return validName(value) ? value.trim() : DEFAULT_NAME;
        } catch (IOException | RuntimeException e) {
            return DEFAULT_NAME;
        }
    }

    synchronized Map<String, Object> saveName(String value) throws IOException {
        if (!validName(value)) throw HttpException.badRequest("面板名称需为 2–48 个字符，且不能包含控制字符或尖括号");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", value.trim());
        write(configFile, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        return info();
    }

    synchronized Map<String, Object> resetName() throws IOException {
        if (regular(configFile)) {
            Files.copy(configFile, configFile.resolveSibling("web-branding.yml.bak"), StandardCopyOption.REPLACE_EXISTING);
            Files.delete(configFile);
        }
        return info();
    }

    synchronized Logo logo() {
        try {
            if (!regular(logoFile) || Files.size(logoFile) > MAX_LOGO_BYTES) return null;
            byte[] bytes = Files.readAllBytes(logoFile);
            String type = contentType(bytes);
            return type == null ? null : new Logo(bytes, type);
        } catch (IOException e) {
            return null;
        }
    }

    synchronized Map<String, Object> saveLogo(String dataUrl) throws IOException {
        if (dataUrl == null || dataUrl.length() > 4 * ((MAX_LOGO_BYTES + 2) / 3) + 64)
            throw HttpException.badRequest("Logo 过大（最大 256 KiB）");
        Matcher match = DATA_URL.matcher(dataUrl);
        if (!match.matches()) throw HttpException.badRequest("仅支持 PNG 或 JPEG 图片");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(match.group(2));
        } catch (IllegalArgumentException e) {
            throw HttpException.badRequest("Logo 图片编码无效");
        }
        if (bytes.length == 0 || bytes.length > MAX_LOGO_BYTES)
            throw HttpException.badRequest("Logo 过大（最大 256 KiB）");
        String type = contentType(bytes);
        if (!("image/" + match.group(1)).equals(type) || !validImage(bytes, type))
            throw HttpException.badRequest("Logo 图片无效或尺寸超过 1024×1024");
        write(logoFile, bytes);
        return info();
    }

    synchronized Map<String, Object> resetLogo() throws IOException {
        if (regular(logoFile)) {
            Files.copy(logoFile, logoFile.resolveSibling("web-branding-logo.bak"), StandardCopyOption.REPLACE_EXISTING);
            Files.delete(logoFile);
        }
        return info();
    }

    private static boolean validName(String value) {
        if (value == null) return false;
        String name = value.trim();
        int length = name.codePointCount(0, name.length());
        if (length < 2 || length > 48) return false;
        return name.codePoints().noneMatch(c -> Character.isISOControl(c) || c == '<' || c == '>');
    }

    private static String contentType(byte[] bytes) {
        if (startsWith(bytes, PNG_MAGIC)) return "image/png";
        if (startsWith(bytes, JPEG_MAGIC)) return "image/jpeg";
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (bytes[i] != magic[i]) return false;
        return true;
    }

    private static boolean validImage(byte[] bytes, String contentType) {
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (contentType.equals("image/png") ? !format.equalsIgnoreCase("png") : !format.equalsIgnoreCase("jpeg")) return false;
                reader.setInput(stream);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                return width >= 1 && height >= 1 && width <= MAX_LOGO_DIMENSION && height <= MAX_LOGO_DIMENSION
                        && reader.read(0) != null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean regular(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private void write(Path target, byte[] data) throws IOException {
        Files.createDirectories(directory);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !regular(target))
            throw new IOException("品牌文件不是普通文件");
        Path temporary = Files.createTempFile(directory, ".web-branding-", ".tmp");
        try {
            Files.write(temporary, data);
            if (regular(target)) Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String revision(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
