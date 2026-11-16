# Country and location

## Catalog country restrictions

The recovered `spotify.metadata.Restriction` schema separates catalogue membership from country restriction. Catalogue values include ad-supported, subscription, all, shuffle, and commercial. The restriction type includes streaming. A country-restriction oneof contains `countries_allowed` at field 2 or `countries_forbidden` at field 3; `catalogue_str` is repeated field 5.

Track, album, episode, and other metadata messages carry restrictions. This supports availability checks based on market and account product rather than a single global playable flag. The schema alone does not define the country-string encoding or the exact precedence between catalogue and country rules. Those require the matching parser and policy code or a captured metadata response.

Spotify's public track-relinking documentation says account country affects availability and clients can substitute another instance of a track. That documents expected service behavior but does not prove the precise private Android relinking branch used in this build.

## Phone identifier country

Login phone credentials contain number, ISO country code, and country calling code. Native Login5 strings mirror these names. This is evidence of phone-number normalization and identity context. It does not prove a phone country determines the account's licensed streaming market.

## Coordinate collection and consent

The manifest requests both fine and coarse location. A request interface `p.xwi1` posts protobuf data to `user-geo-points/v1/location`. `p.wwi1` exposes GET `user-geo-points/v1/location/consent`, POST `.../consent/grant`, and POST `.../consent/revoke`. Write requests specify `Content-Type: application/x-protobuf` and accept protobuf responses.

The native `DeviceLocationService.RequestLocationSilently` response has status, latitude, longitude, horizontal accuracy, and timestamp. Status can be success, permission unavailable, timeout, or failed. The DEX contains consent-check/grant, permission outcome, fix outcome, and location-write event classes, plus a last-foreground-refresh preference key.

These establish a location subsystem with OS permission handling, backend consent endpoints, and outcome reporting. They do not prove location is collected for every user, at every launch, or as a mandatory streaming prerequisite. Live-events/concert location types are also present, so coordinate-based personalization and catalog country checks must be treated separately.

## Roaming and network policy

Connectivity configuration exposes `isNetworkAllowedIfRoaming` and `setNetworkAllowedIfRoaming`. The app also requests phone-state and network-state permissions. Those identifiers show transport policy support; they do not establish a geofencing implementation.

## Unresolved IP and VPN behavior

This analysis did not observe backend IP geolocation, VPN detection, travel timers, country-change enforcement, or billing-address comparison during music playback. Country-mismatch types also occur in payment code and should not automatically be attributed to streaming. The absence of an obvious VPN literal would not prove the server lacks such checks.

There is build-specific GeoIP evidence. Generated episode/show metadata exposes `INTERPRET_RESTRICTION_USING_GEOIP_FIELD_NUMBER`; this supports a geo-IP interpretation option for those content types. `ValidateGraduationResponse.geoipMismatch` and `GraduationGeoIpMismatchException` belong to the kids graduation flow. They demonstrate a GeoIP mismatch branch in that flow, not a universal music-login block. Android transport constants such as `TRANSPORT_VPN` also occur in bundled connectivity code; those constants do not establish an active anti-VPN rule.

An authorized runtime comparison would need the same account and track across known network locations, actual product state, metadata restrictions, and playback failures. No country spoofing or restriction-bypass implementation is included.

## Evidence links

Evidence: [metadata restrictions](../evidence/native-protos/metadata.proto), [device location schema](../evidence/native-protos/device_location_service.proto), [location write interface](../evidence/source/jadx-primary__p__xwi1.java.txt), [consent interface](../evidence/source/jadx-primary__p__wwi1.java.txt), and [country/GeoIP literals](../evidence/country-strings.json).
