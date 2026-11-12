package com.lunarboreal.api.service;

/** Optional bridge for native generation overlays; does not expose NMS types in the LC API. */
public interface NativeGeneratorDelegate {
    Object underlyingGenerator();

    static Object unwrap(Object generator) {
        Object current = generator;
        for (int depth = 0; depth < 16 && current instanceof NativeGeneratorDelegate delegate; depth++) {
            Object next = delegate.underlyingGenerator();
            if (next == null || next == current) throw new IllegalStateException("Invalid native generator delegate");
            current = next;
        }
        if (current instanceof NativeGeneratorDelegate) throw new IllegalStateException("Cyclic native generator delegates");
        return current;
    }
}
