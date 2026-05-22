plugins {
    id("proton.android.authenticator.plugins.libraries.business")
}

android {
    namespace = "proton.android.authenticator.business.entries"
}

androidComponents {
    beforeVariants { variant ->
        variant.enableAndroidTest = false
    }
}

dependencies {
    implementation(libs.core.accountManager)
    implementation(libs.core.network)
    implementation(libs.kotlinx.datetime)
    implementation(libs.yubikit.android)
    implementation(libs.yubikit.oath)
}
