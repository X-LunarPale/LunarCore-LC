package com.lunarboreal.webuieffects;

import com.lunarboreal.api.module.LcModule;

public final class WebUiEffectsModule extends LcModule {
    @Override
    public void onEnable() {
        web().registerStatic(id(), moduleClassLoader(), "webui-effects", this);
    }
}
