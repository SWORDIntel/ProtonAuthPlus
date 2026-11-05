plugins {
    id("proton.android.authenticator.plugins.libraries.business")
}

android {
    namespace = "proton.android.authenticator.business.entrycodes"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
