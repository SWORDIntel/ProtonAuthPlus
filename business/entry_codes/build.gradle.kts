plugins {
    id("proton.android.authenticator.plugins.libraries.business")
}

android {
    namespace = "proton.android.authenticator.business.entrycodes"
}

androidComponents {
    beforeVariants { variant ->
        variant.enableAndroidTest = false
    }
}

dependencies {
    implementation(libs.kotlinx.datetime)
    implementation(libs.yubikit.android)
    implementation(libs.yubikit.oath)
}
