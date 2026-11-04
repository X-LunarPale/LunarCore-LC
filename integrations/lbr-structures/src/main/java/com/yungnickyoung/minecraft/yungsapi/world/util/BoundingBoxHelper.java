// Paper port; original source and LGPL-3.0 notice: third-party/yungs/api/
package com.yungnickyoung.minecraft.yungsapi.world.util;

import com.yungnickyoung.minecraft.yungsapi.mixin.accessor.BoundingBoxAccessor;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public class BoundingBoxHelper {
    /**
     * Generates and rotates a block box.
     * The main axis is the primary of the x and z axes, in the direction
     * the structure should generate from the starting point.
     */
    public static BoundingBox boxFromCoordsWithRotation(int x, int y, int z, int secondaryAxisLen, int yLen, int mainAxisLen, Direction mainAxis) {
        BoundingBox boundingBox = new BoundingBox(x, y, z, x, y + yLen - 1, z);
        switch (mainAxis) {
            default -> {
                com.lunarboreal.structures.nms.PaperAccess.view(boundingBox, BoundingBoxAccessor.class).setMaxX(x + (secondaryAxisLen - 1));
                com.lunarboreal.structures.nms.PaperAccess.view(boundingBox, BoundingBoxAccessor.class).setMinZ(z - (mainAxisLen - 1));
            }
            case SOUTH -> {
                com.lunarboreal.structures.nms.PaperAccess.view(boundingBox, BoundingBoxAccessor.class).setMinX(x - (secondaryAxisLen - 1));
                com.lunarboreal.structures.nms.PaperAccess.view(boundingBox, BoundingBoxAccessor.class).setMaxZ(z + (mainAxisLen - 1));
            }
            case WEST -> {
                com.lunarboreal.structures.nms.PaperAccess.view(boundingBox, BoundingBoxAccessor.class).setMinX(x - (mainAxisLen - 1));
                com.lunarboreal.structures.nms.PaperAccess.view(boundingBox, BoundingBoxAccessor.class).setMinZ(z - (secondaryAxisLen - 1));
            }
            case EAST -> {
                com.lunarboreal.structures.nms.PaperAccess.view(boundingBox, BoundingBoxAccessor.class).setMaxX(x + (mainAxisLen - 1));
                com.lunarboreal.structures.nms.PaperAccess.view(boundingBox, BoundingBoxAccessor.class).setMaxZ(z + (secondaryAxisLen - 1));
            }
        }
        return boundingBox;
    }
}
