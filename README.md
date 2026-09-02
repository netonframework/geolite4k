# GeoLite4K

Pure Kotlin/Native reader for MaxMind DB (`.mmdb`) files. It has no JVM/JNI
dependency and does not bundle MaxMind's GeoLite2 database.

```kotlin
implementation("com.netonstream:geolite4k:0.1.0")
```

Targets: `macosArm64`, `macosX64`, `linuxX64`, `linuxArm64`, `mingwX64`.

```kotlin
val resolver = GeoLite4K.open("./data/geoip/GeoLite2-City.mmdb")
when (val result = resolver.lookup("8.8.8.8")) {
    is GeoLiteLookupResult.Found -> println(result.location.displayName)
    is GeoLiteLookupResult.DatabaseUnavailable -> println("database missing")
    else -> Unit
}
```

Applications are responsible for obtaining and updating `GeoLite2-City.mmdb`
in accordance with the MaxMind GeoLite2 EULA. IP geolocation is approximate;
city-level data must not be presented as an exact street or district address.

The Neton applications read the path from `config/geolite.conf` and default to:

```toml
path = "./data/geoip/GeoLite2-City.mmdb"
```

If the file is absent, the resolver returns `DatabaseUnavailable`. The member
module maps that explicit state to `GeoLite2 库未设置`; it never calls a remote
GeoIP API from browser JavaScript.
