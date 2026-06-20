import java.io.FileInputStream
import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply { if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile)) }
android {
    namespace = "com.ryu.combopop"
    compileSdk = 34
    defaultConfig { applicationId = "io.github.papaya5rhw1984.combopop"; minSdk = 24; targetSdk = 34; versionCode = 3; versionName = "1.2" }
    signingConfigs { create("release") { if (keystorePropertiesFile.exists()) { storeFile = file(keystoreProperties["storeFile"] as String); storePassword = keystoreProperties["storePassword"] as String; keyAlias = keystoreProperties["keyAlias"] as String; keyPassword = keystoreProperties["keyPassword"] as String } } }
    buildTypes { release { isMinifyEnabled = false; if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui"); implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3"); implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.1"); implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
}
