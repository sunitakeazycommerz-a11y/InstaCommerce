plugins {
    java
    id("org.springframework.boot") version "4.0.0" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}


allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

subprojects {
    group = "com.instacommerce"
    version = "0.1.0-SNAPSHOT"

    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("com.google.cloud:spring-cloud-gcp-dependencies:5.1.0")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:2024.0.0")
        }
    }
}


