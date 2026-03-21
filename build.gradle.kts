plugins {
    java
    id("org.springframework.boot") version "4.0.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
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

    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("org.lz4:lz4-java"))
                .using(module("at.yawk.lz4:lz4-java:1.10.1"))
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.0")
            mavenBom("com.google.cloud:spring-cloud-gcp-dependencies:6.0.0")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:2024.0.0")
            mavenBom("io.grpc:grpc-bom:1.75.0")
        }
        dependencies {
            dependency("tools.jackson.core:jackson-annotations:3.1.0")
            dependency("tools.jackson.core:jackson-core:3.1.0")
            dependency("tools.jackson.core:jackson-databind:3.1.0")
            dependency("tools.jackson.datatype:jackson-datatype-jdk8:3.1.0")
            dependency("tools.jackson.datatype:jackson-datatype-jsr310:3.1.0")
            dependency("tools.jackson.module:jackson-module-parameter-names:3.1.0")
            dependency("com.google.protobuf:protobuf-java:4.32.0")
            dependency("com.google.protobuf:protobuf-java-util:4.32.0")
            dependency("at.yawk.lz4:lz4-java:1.10.1")
            // Spring Boot 4.0+ manages spring-kafka via BOM, but explicit for clarity
            dependency("org.springframework.kafka:spring-kafka:4.0.0")
            dependency("org.springframework.kafka:spring-kafka-test:4.0.0")
        }
    }
}
