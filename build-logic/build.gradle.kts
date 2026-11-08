plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("nativeBuild") {
            id = "lunar.native-build"
            implementationClass = "NativeBuildPlugin"
        }
    }
}
