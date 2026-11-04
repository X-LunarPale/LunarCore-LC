package com.lunarboreal.structures.nms;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NativeField { String value(); }
