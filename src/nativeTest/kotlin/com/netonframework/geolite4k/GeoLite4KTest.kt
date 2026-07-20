package com.netonframework.geolite4k

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeoLite4KTest {
    @Test
    fun missingDatabaseIsAnExplicitState() {
        val resolver = GeoLite4K.open("build/does-not-exist.mmdb")

        assertIs<GeoLiteDatabaseStatus.Unavailable>(resolver.status)
        assertIs<GeoLiteLookupResult.DatabaseUnavailable>(resolver.lookup("8.8.8.8"))
    }

    @Test
    fun readsOfficialGeoLite2CityTestDatabase() {
        val resolver = GeoLite4K.open("src/nativeTest/resources/GeoLite2-City-Test.mmdb")
        val found = assertIs<GeoLiteLookupResult.Found>(resolver.lookup("81.2.69.142"))

        assertEquals("英国", found.location.country)
        assertEquals("GB", found.location.countryCode)
        assertEquals("England", found.location.province)
        assertEquals("London", found.location.city)
        assertTrue(found.location.displayName.contains("英国"))
        assertEquals("Europe/London", found.location.timeZone)
    }

    @Test
    fun rejectsMalformedIp() {
        val resolver = GeoLite4K.open("src/nativeTest/resources/GeoLite2-City-Test.mmdb")
        assertIs<GeoLiteLookupResult.InvalidIp>(resolver.lookup("999.1.1.1"))
    }
}
