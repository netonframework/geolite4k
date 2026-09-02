# Releasing

All five targets are published from one macOS host. geolite4k is pure Kotlin/Native with no
native dependencies, so nothing needs a Linux or Windows machine.

## Prerequisites

`~/.gradle/gradle.properties`:

```
sonatypeUsername=<Central Portal user token>
sonatypePassword=<Central Portal user token password>
signingInMemoryKey=<base64 PGP secret key>
signingInMemoryKeyPassword=<key passphrase>
```

The signing public key must be on a keyserver (`keyserver.ubuntu.com` or `keys.openpgp.org`).

## Publish

Use the Central Portal bundle upload, not the OSSRH staging endpoint: that API keys its implicit
staging repository by client IP, so an exit-IP change during a long upload silently splits the
release across two repositories. A bundle upload is a single request and cannot split.

```bash
# 1. Build, sign and lay out every publication
./gradlew publishAllPublicationsToStagingLocalRepository

# 2. Bundle it (repository-level metadata is not part of a bundle)
cd build/staging-repo
find com -name 'maven-metadata.xml*' -delete
zip -qr ../geolite4k-<version>.zip com
cd -

# 3. Upload with USER_MANAGED so validation can be inspected before anything goes live
TOKEN=$(printf '%s:%s' "$SONATYPE_USER" "$SONATYPE_PASS" | base64)
curl -X POST -H "Authorization: Bearer $TOKEN" \
     -F "bundle=@build/geolite4k-<version>.zip" \
     "https://central.sonatype.com/api/v1/publisher/upload?name=geolite4k-<version>&publishingType=USER_MANAGED"

# 4. Poll until VALIDATED, then publish
curl -X POST -H "Authorization: Bearer $TOKEN" "https://central.sonatype.com/api/v1/publisher/status?id=<deployment id>"
curl -X POST -H "Authorization: Bearer $TOKEN" "https://central.sonatype.com/api/v1/publisher/deployment/<deployment id>"

# 5. Confirm every coordinate (root plus one per target)
curl -H "Authorization: Bearer $TOKEN" \
     "https://central.sonatype.com/api/v1/publisher/published?namespace=com.netonstream&name=geolite4k&version=<version>"
```

Publishing to Central is irreversible. Check the validation result before step 4.

## Verify

Tag the release, then build a throwaway project that resolves only from `mavenCentral()` and
depends on `com.netonstream:geolite4k:<version>`. Source-path builds (`includeBuild`) do not
prove that the published artifacts work.
