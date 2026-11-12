package com.lunarboreal.landmarks;

import com.lunarboreal.api.service.LandService;
import java.util.List;
import java.util.Optional;

final class LandmarkAccess {
    private LandmarkAccess() {}
    static Optional<LandService.LandInfo> ownedClaim(Landmark entry, List<LandService.LandInfo> currentClaims) {
        return currentClaims.stream().filter(claim -> claim.id() == entry.landId()
                && claim.owner().equals(entry.owner())
                && claim.contains(entry.world(), entry.x(), entry.y(), entry.z())).findFirst();
    }
}
