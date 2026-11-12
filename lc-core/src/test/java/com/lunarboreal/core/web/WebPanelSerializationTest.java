package com.lunarboreal.core.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lunarboreal.api.web.WebPanel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebPanelSerializationTest {
    @Test void optionalFieldGroupsReachThePanelResponseWithoutChangingFieldValues() {
        WebPanel.Form form = new WebPanel.Form("Settings", "config", "config")
                .field(new WebPanel.Field("name", "Name", "text").group("Currency"))
                .field(new WebPanel.Field("decimals", "Decimals", "number").group("Currency").defaultValue(0))
                .field(new WebPanel.Field("enabled", "Enabled", "bool").group("Bridge").defaultValue(false));

        JsonArray fields = Json.GSON.toJsonTree(form).getAsJsonObject().getAsJsonArray("fields");
        assertEquals(3, fields.size());
        assertEquals("name", fields.get(0).getAsJsonObject().get("key").getAsString());
        assertEquals("Currency", fields.get(0).getAsJsonObject().get("group").getAsString());
        assertEquals("Currency", fields.get(1).getAsJsonObject().get("group").getAsString());
        assertEquals(0, fields.get(1).getAsJsonObject().get("defaultValue").getAsInt());
        assertEquals("Bridge", fields.get(2).getAsJsonObject().get("group").getAsString());
        assertFalse(fields.get(2).getAsJsonObject().get("defaultValue").getAsBoolean());
    }

    @Test void legacyFieldsKeepTheirOriginalJsonShapeWhenNoGroupIsSet() {
        WebPanel.Field field = new WebPanel.Field("amount", "Amount", "number").required(true);
        JsonObject before = Json.GSON.toJsonTree(field).getAsJsonObject();
        assertFalse(before.has("group"));
        assertEquals("amount", before.get("key").getAsString());
        assertEquals("number", before.get("type").getAsString());
        assertTrue(before.get("required").getAsBoolean());
        assertTrue(before.getAsJsonArray("options").isEmpty());

        field.group("Rules").group(null);
        assertEquals(before, Json.GSON.toJsonTree(field).getAsJsonObject());
    }

    @Test void enumLabelsAreOptInAndLeaveColumnAndRowContractsUnchanged() {
        WebPanel.Table table = new WebPanel.Table("Records", "records")
                .column("status", "Status", "tag");
        JsonObject legacy = Json.GSON.toJsonTree(table).getAsJsonObject();
        assertFalse(legacy.has("valueLabels"));
        JsonObject column = legacy.getAsJsonArray("columns").get(0).getAsJsonObject();
        assertEquals(4, column.size());

        table.valueLabels("status", Map.of("PENDING", "待审核"));
        JsonObject translated = Json.GSON.toJsonTree(table).getAsJsonObject();
        assertEquals(legacy.get("columns"), translated.get("columns"));
        assertEquals("待审核", translated.getAsJsonObject("valueLabels")
                .getAsJsonObject("status").get("PENDING").getAsString());
    }

    @Test void textTranslationRequiresExplicitFixedContentMetadata() {
        WebPanel.Text text = new WebPanel.Text("Help", "Original content");
        assertFalse(Json.GSON.toJsonTree(text).getAsJsonObject().has("markdownKey"));
        text.markdownKey("固定说明");
        JsonObject serialized = Json.GSON.toJsonTree(text).getAsJsonObject();
        assertEquals("Original content", serialized.get("markdown").getAsString());
        assertEquals("固定说明", serialized.get("markdownKey").getAsString());
    }

    @Test void rowActionConditionsAreOptionalAndUseRawValues() {
        WebPanel.Action action = new WebPanel.Action("withdraw", "Withdraw", "entries/{id}/withdraw");
        JsonObject legacy = Json.GSON.toJsonTree(action).getAsJsonObject();
        assertFalse(legacy.has("visibleWhenField"));
        assertFalse(legacy.has("visibleWhenValues"));

        action.visibleWhen("status", "PENDING", "APPROVED");
        JsonObject serialized = Json.GSON.toJsonTree(action).getAsJsonObject();
        assertEquals("status", serialized.get("visibleWhenField").getAsString());
        assertEquals("PENDING", serialized.getAsJsonArray("visibleWhenValues").get(0).getAsString());
        assertEquals("APPROVED", serialized.getAsJsonArray("visibleWhenValues").get(1).getAsString());
    }
}
