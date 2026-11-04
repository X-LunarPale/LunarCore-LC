// Paper port; original source and LGPL-3.0 notice: third-party/yungs/api/
package com.yungnickyoung.minecraft.yungsapi.world.structure.action;

import com.yungnickyoung.minecraft.yungsapi.world.structure.context.StructureContext;
import com.yungnickyoung.minecraft.yungsapi.world.structure.jigsaw.PieceEntry;

/**
 * A serializable class used for modifying pieces of Jigsaw structures during world generation.
 */
public abstract class StructureAction {
    abstract public StructureActionType<?> type();
    abstract public void apply(StructureContext ctx, PieceEntry targetPieceEntry);
}
