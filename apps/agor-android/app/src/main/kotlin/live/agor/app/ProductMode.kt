package live.agor.app

enum class ProductKind {
    HermesAgor,
    HermesOnly,
}

data class ProductMode(
    val kind: ProductKind,
    val agorEnabled: Boolean,
    val productId: String,
) {
    val hermesOnly: Boolean get() = kind == ProductKind.HermesOnly

    companion object {
        val current: ProductMode = productModeFromBuildConfig(
            agorEnabled = BuildConfig.AGOR_ENABLED,
            productMode = BuildConfig.PRODUCT_MODE,
        )
    }
}

internal fun productModeFromBuildConfig(
    agorEnabled: Boolean,
    productMode: String,
): ProductMode {
    val normalized = productMode.trim().lowercase()
    return if (!agorEnabled || normalized == "hermes-only") {
        ProductMode(ProductKind.HermesOnly, agorEnabled = false, productId = "hermes-only")
    } else {
        ProductMode(ProductKind.HermesAgor, agorEnabled = true, productId = "hermes-agor")
    }
}
