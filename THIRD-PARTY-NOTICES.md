# Third-party dependencies

ShufangTV is independently implemented and distributed under Apache-2.0. Dependencies retain their own licenses. This document is an attribution index; each upstream project's license and notices apply to its code.

| Direct runtime dependency | Version | License | Upstream |
| --- | --- | --- | --- |
| jsoup | 1.21.2 | MIT | https://jsoup.org/license |
| SMBJ | 0.14.0 | Apache-2.0 | https://github.com/hierynomus/smbj |
| OkHttp | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| Apache Commons Compress | 1.27.1 | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| SLF4J NOP | 2.0.16 | MIT | https://www.slf4j.org/license.html |
| Bouncy Castle provider | 1.81 | Bouncy Castle (MIT-style) | https://www.bouncycastle.org/licence.html |
| ZXing core | 3.5.3 | Apache-2.0 | https://github.com/zxing/zxing |
| AndroidSVG | 1.4 | Apache-2.0 | https://bigbadaboom.github.io/androidsvg/ |
| libmobi (separate shared library) | 0.12, revision 906274205c11944b628da1c553b255acb1af7c55 | LGPL-3.0-or-later | https://github.com/bfabiszewski/libmobi |

The dependency graph also includes Okio, Kotlin standard library, JetBrains annotations, ASN.1 support, Bouncy Castle, MBassador and Apache Commons libraries. Resolved versions are visible with `./gradlew :app:dependencies --configuration debugRuntimeClasspath`. The app license, attribution index and notices collected from runtime JARs are bundled at `assets/licenses/OPEN_SOURCE_NOTICES.txt`. MBassador and Bouncy Castle licenses are also supplied separately in that directory.

libmobi's corresponding source, provenance and license are included under `third_party/libmobi`; the independent JNI adapter and complete shared-library build configuration are in `app/src/main/cpp`. Both LGPL-3.0 and GPL-3.0 texts are bundled under `assets/licenses`, along with AndroidSVG's license. Replace the libmobi source and rebuild to relink the application; the application does not restrict modified library builds. The encryption feature is disabled.

JUnit, Robolectric and AndroidX Test are test tools and are not shipped as app runtime dependencies. Android framework components are supplied by the device.

Application icon, banner, typographic covers, Chinese interface text and usage guide were created for ShufangTV. No reference application artwork or decompiled source is included.
