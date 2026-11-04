// Paper port; original source and LGPL-3.0 notice: third-party/yungs/api/
package com.yungnickyoung.minecraft.yungsapi.noise;

/**
 * Tagging interface for noise libraries (FastNoise, OpenSimplex2S)
 */
public interface INoiseLibrary {
    float GetNoise(float x, float y, float z);
}
