package com.weathermod.compat.iris;

import net.irisshaders.iris.api.v0.IrisApi;

/**
 * The only class that imports {@code net.irisshaders.iris.api.v0.IrisApi}; it links against Iris and so is loaded
 * only after {@link IrisCompat#isLoaded()} is true, through {@link IrisCompat#isShaderPackInUse()}.
 */
final class IrisApiAccess {
    private IrisApiAccess() {
    }

    /** Asks Iris whether a shader pack is currently in use; a missing API instance counts as no pack. */
    static boolean shaderPackInUse() {
        IrisApi api = IrisApi.getInstance();
        return api != null && api.isShaderPackInUse();
    }
}
