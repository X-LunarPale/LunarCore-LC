package com.lunarboreal.core.web;

import com.lunarboreal.api.web.HttpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BrandingManagerTest {
    @TempDir Path directory;

    @Test void namePersistsAndResetRestoresDefaultWithoutChangingLogo() throws Exception {
        BrandingManager manager = new BrandingManager(directory);
        assertEquals(BrandingManager.DEFAULT_NAME, manager.info().get("brand"));
        assertEquals("/favicon.svg", manager.info().get("logoUrl"));
        assertEquals("Moon Server", manager.saveName("  Moon Server  ").get("brand"));
        assertEquals("Moon Server", new BrandingManager(directory).name());

        byte[] originalName = Files.readAllBytes(directory.resolve("web-branding.yml"));
        manager.saveName("Second Name");
        assertArrayEquals(originalName, Files.readAllBytes(directory.resolve("web-branding.yml.bak")));
        manager.saveLogo(dataUrl("png", image("png", 32, 32)));
        String uploadedLogo = manager.info().get("logoUrl").toString();
        byte[] secondName = Files.readAllBytes(directory.resolve("web-branding.yml"));

        assertEquals(BrandingManager.DEFAULT_NAME, manager.resetName().get("brand"));
        assertArrayEquals(secondName, Files.readAllBytes(directory.resolve("web-branding.yml.bak")));
        assertEquals(uploadedLogo, manager.info().get("logoUrl"));
        assertEquals(BrandingManager.DEFAULT_NAME, new BrandingManager(directory).name());
    }

    @Test void validatesNamesAndRejectsUnsafeInput() throws Exception {
        BrandingManager manager = new BrandingManager(directory);
        for (String invalid : new String[]{"", "a", "<img>", "line\nfeed", "x".repeat(49)}) {
            assertEquals(400, assertThrows(HttpException.class, () -> manager.saveName(invalid)).status());
        }
        assertEquals(BrandingManager.DEFAULT_NAME, manager.name());
        assertFalse(Files.exists(directory.resolve("web-branding.yml")));
    }

    @Test void nameLengthCountsUnicodeCodePointsAndAcceptsBothBoundaries() throws Exception {
        BrandingManager manager = new BrandingManager(directory);
        String symbol = new String(Character.toChars(0x1f319));
        assertEquals(symbol.repeat(2), manager.saveName(symbol.repeat(2)).get("brand"));
        assertEquals(symbol.repeat(48), manager.saveName(symbol.repeat(48)).get("brand"));
        assertEquals(symbol.repeat(48), new BrandingManager(directory).name());
        for (String invalid : new String[]{symbol, symbol.repeat(49), "ab\u0085cd", "ab\u0000cd"}) {
            assertEquals(400, assertThrows(HttpException.class, () -> manager.saveName(invalid)).status());
        }
        assertEquals(symbol.repeat(48), manager.name());
    }

    @Test void pngAndJpegUploadPersistWithVersionedSameOriginUrl() throws Exception {
        BrandingManager manager = new BrandingManager(directory);
        byte[] png = image("png", 32, 32);
        String pngUrl = manager.saveLogo(dataUrl("png", png)).get("logoUrl").toString();
        assertTrue(pngUrl.matches("/api/branding/logo\\?v=[a-f0-9]{16}"));
        assertArrayEquals(png, new BrandingManager(directory).logo().bytes());
        assertEquals("image/png", manager.logo().contentType());

        byte[] jpeg = image("jpeg", 24, 24);
        String jpegUrl = manager.saveLogo(dataUrl("jpeg", jpeg)).get("logoUrl").toString();
        assertNotEquals(pngUrl, jpegUrl);
        assertEquals("image/jpeg", manager.logo().contentType());
        assertArrayEquals(png, Files.readAllBytes(directory.resolve("web-branding-logo.bak")));

        assertEquals("/favicon.svg", manager.resetLogo().get("logoUrl"));
        assertNull(new BrandingManager(directory).logo());
        assertArrayEquals(jpeg, Files.readAllBytes(directory.resolve("web-branding-logo.bak")));
    }

    @Test void rejectsSvgMismatchedMimeCorruptDataAndLargeImages() throws Exception {
        BrandingManager manager = new BrandingManager(directory);
        byte[] png = image("png", 32, 32);
        byte[] largePixels = image("png", 1025, 1);
        for (String invalid : new String[]{
                dataUrl("svg+xml", "<svg/>".getBytes()),
                dataUrl("jpeg", png),
                dataUrl("png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}),
                dataUrl("png", largePixels),
                dataUrl("png", new byte[BrandingManager.MAX_LOGO_BYTES + 1])
        }) {
            assertEquals(400, assertThrows(HttpException.class, () -> manager.saveLogo(invalid)).status());
        }
        assertEquals("/favicon.svg", manager.info().get("logoUrl"));
        assertFalse(Files.exists(directory.resolve("web-branding-logo")));
    }

    @Test void rejectedUpdatesPreserveSavedNameLogoAndBackups() throws Exception {
        BrandingManager manager = new BrandingManager(directory);
        manager.saveName("First Name");
        manager.saveName("Current Name");
        manager.saveLogo(dataUrl("png", image("png", 16, 16)));
        manager.saveLogo(dataUrl("jpeg", image("jpeg", 24, 24)));
        String[] files = {"web-branding.yml", "web-branding.yml.bak", "web-branding-logo", "web-branding-logo.bak"};
        byte[][] saved = new byte[files.length][];
        for (int i = 0; i < files.length; i++) saved[i] = Files.readAllBytes(directory.resolve(files[i]));
        String logoUrl = manager.info().get("logoUrl").toString();

        assertEquals(400, assertThrows(HttpException.class, () -> manager.saveName("<script>")).status());
        assertEquals(400, assertThrows(HttpException.class, () -> manager.saveLogo(dataUrl("jpeg", image("png", 32, 32)))).status());

        assertEquals("Current Name", manager.name());
        assertEquals(logoUrl, manager.info().get("logoUrl"));
        for (int i = 0; i < files.length; i++) assertArrayEquals(saved[i], Files.readAllBytes(directory.resolve(files[i])));
    }

    private static String dataUrl(String type, byte[] bytes) {
        return "data:image/" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] image(String type, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x4488cc);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, type, output));
        return output.toByteArray();
    }
}
