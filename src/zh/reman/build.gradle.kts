import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Reman"
    // Auto-incrementing: derived from the number of commits touching this module's
    // directory, so versionCode only bumps when this extension's own files actually
    // change. Requires a non-shallow checkout in CI.
    versionCode.set(
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD", "--", ".")
            workingDir = projectDir
        }.standardOutput.asText.map { it.trim().toInt() },
    )
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "热漫"
        lang = "zh"
        baseUrl {
            custom("http://www.yueman1.cc")
        }
    }

    deeplink {
        path("/manhua/[0-9]..*\\.html")
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
