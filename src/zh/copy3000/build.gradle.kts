import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "拷贝漫画 Copy3000"
    // Auto-incrementing: every build/publish should count as a new version, since
    // publish-fork.yml always republishes every extension unconditionally (see
    // publish-repo-fork.py). Monotonic, no overflow risk for millennia.
    versionCode = (System.currentTimeMillis() / 60_000).toInt()
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "zh"
        baseUrl {
            mirrors(
                "https://www.copy3000.com",
                "https://www.copy4000.com",
            )
        }
    }

    deeplink {
        host("copy3000.com")
        host("www.copy3000.com")
        host("copy4000.com")
        host("www.copy4000.com")
        path("/..*")
    }
}

// R8 minification of the release build corrupts some `new`-instruction
// constant pool references when Suwayomi's dex2jar converts the apk back
// to JVM class files (verified: an unminified debug build converts fine,
// a minified release build doesn't, for identical source). Disable it for
// this module's release build so the shipped apk stays dex2jar-safe.
android {
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
