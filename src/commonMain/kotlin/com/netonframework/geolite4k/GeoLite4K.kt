package com.netonframework.geolite4k

import kotlinx.serialization.Serializable

object GeoLite4K {
    const val DEFAULT_DATABASE_PATH: String = "./data/geoip/GeoLite2-City.mmdb"

    fun open(
        path: String = DEFAULT_DATABASE_PATH,
        locales: List<String> = listOf("zh-CN", "en"),
    ): GeoLiteResolver {
        val bytes = readFileBytes(path)
            ?: return UnavailableGeoLiteResolver(path)
        return try {
            MmdbGeoLiteResolver(path, locales, MmdbReader(bytes))
        } catch (error: Throwable) {
            InvalidGeoLiteResolver(path, error.message ?: "invalid MaxMind database")
        }
    }
}

interface GeoLiteResolver {
    val databasePath: String
    val status: GeoLiteDatabaseStatus
    fun lookup(ip: String): GeoLiteLookupResult
}

@Serializable
sealed class GeoLiteDatabaseStatus {
    @Serializable
    data class Ready(
        val databaseType: String,
        val buildEpoch: Long,
    ) : GeoLiteDatabaseStatus()

    @Serializable
    data class Unavailable(val path: String) : GeoLiteDatabaseStatus()

    @Serializable
    data class Invalid(val path: String, val reason: String) : GeoLiteDatabaseStatus()
}

@Serializable
sealed class GeoLiteLookupResult {
    @Serializable
    data class Found(val location: GeoLiteLocation) : GeoLiteLookupResult()

    @Serializable
    data object NotFound : GeoLiteLookupResult()

    @Serializable
    data class InvalidIp(val ip: String) : GeoLiteLookupResult()

    @Serializable
    data class DatabaseUnavailable(val path: String) : GeoLiteLookupResult()

    @Serializable
    data class DatabaseInvalid(val path: String, val reason: String) : GeoLiteLookupResult()
}

@Serializable
data class GeoLiteLocation(
    val country: String? = null,
    val countryCode: String? = null,
    val province: String? = null,
    val city: String? = null,
    val postalCode: String? = null,
    val timeZone: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val displayName: String,
)

private class UnavailableGeoLiteResolver(
    override val databasePath: String,
) : GeoLiteResolver {
    override val status = GeoLiteDatabaseStatus.Unavailable(databasePath)
    override fun lookup(ip: String) = GeoLiteLookupResult.DatabaseUnavailable(databasePath)
}

private class InvalidGeoLiteResolver(
    override val databasePath: String,
    private val reason: String,
) : GeoLiteResolver {
    override val status = GeoLiteDatabaseStatus.Invalid(databasePath, reason)
    override fun lookup(ip: String) = GeoLiteLookupResult.DatabaseInvalid(databasePath, reason)
}

private class MmdbGeoLiteResolver(
    override val databasePath: String,
    private val locales: List<String>,
    private val reader: MmdbReader,
) : GeoLiteResolver {
    override val status = GeoLiteDatabaseStatus.Ready(
        databaseType = reader.metadata.databaseType,
        buildEpoch = reader.metadata.buildEpoch,
    )

    override fun lookup(ip: String): GeoLiteLookupResult {
        val record = when (val result = reader.lookup(ip)) {
            MmdbLookupResult.InvalidIp -> return GeoLiteLookupResult.InvalidIp(ip)
            MmdbLookupResult.NotFound -> return GeoLiteLookupResult.NotFound
            is MmdbLookupResult.Found -> result.value.asMap() ?: return GeoLiteLookupResult.NotFound
        }

        val countryMap = record.map("country") ?: record.map("registered_country")
        val country = countryMap.localizedName(locales)
        val countryCode = countryMap.string("iso_code")
        val subdivisionNames = record.array("subdivisions")
            ?.mapNotNull { it.asMap().localizedName(locales) }
            .orEmpty()
        val province = subdivisionNames.firstOrNull()
        val city = record.map("city").localizedName(locales)
        val postalCode = record.map("postal").string("code")
        val location = record.map("location")
        val displayParts = (listOfNotNull(country) + subdivisionNames + listOfNotNull(city))
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .distinct()

        return GeoLiteLookupResult.Found(
            GeoLiteLocation(
                country = country,
                countryCode = countryCode,
                province = province,
                city = city,
                postalCode = postalCode,
                timeZone = location.string("time_zone"),
                latitude = location.number("latitude"),
                longitude = location.number("longitude"),
                displayName = displayParts.joinToString(" ").ifBlank { countryCode ?: "未知地区" },
            ),
        )
    }
}

private fun Map<String, MmdbValue>?.localizedName(locales: List<String>): String? {
    val names = this?.get("names")?.asMap() ?: return null
    for (locale in locales) names[locale]?.asString()?.let { return it }
    return names["en"]?.asString() ?: names.values.firstNotNullOfOrNull(MmdbValue::asString)
}

private fun Map<String, MmdbValue>?.map(key: String): Map<String, MmdbValue>? =
    this?.get(key)?.asMap()

private fun Map<String, MmdbValue>?.array(key: String): List<MmdbValue>? =
    this?.get(key)?.asArray()

private fun Map<String, MmdbValue>?.string(key: String): String? =
    this?.get(key)?.asString()

private fun Map<String, MmdbValue>?.number(key: String): Double? =
    this?.get(key)?.asNumber()

internal expect fun readFileBytes(path: String): ByteArray?
