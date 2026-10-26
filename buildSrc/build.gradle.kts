plugins { `java-library` }
repositories { google(); mavenCentral(); gradlePluginPortal() }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation("com.android.tools.build:gradle-api:9.4.1") { exclude(group = "com.android.tools.build", module = "gradle") }
    implementation("org.ow2.asm:asm:9.8")
    testImplementation("junit:junit:4.13.2")
}
