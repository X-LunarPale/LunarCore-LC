package com.lunarboreal.land;

import com.lunarboreal.api.service.LandService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class LandServiceImpl implements LandService {

    private final LandModule module;

    LandServiceImpl(LandModule module) {
        this.module = module;
    }

    @Override
    public Optional<LandInfo> landAt(Location location) {
        Land l = module.manager().at(location);
        return l == null ? Optional.empty() : Optional.of(l.info());
    }

    @Override
    public boolean can(Player player, Location location, Flag flag) {
        return module.can(player, module.manager().at(location), flag);
    }

    @Override
    public List<LandInfo> landsOf(UUID owner) {
        List<LandInfo> out = new ArrayList<>();
        for (Land l : module.manager().ownedBy(owner)) out.add(l.info());
        return out;
    }
}
