plugins {
    java
    id("org.springframework.boot") version "3.3.4" apply false
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
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
            mavenBom("com.google.cloud:spring-cloud-gcp-dependencies:5.1.0")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:2023.0.3")
            mavenBom("io.grpc:grpc-bom:1.75.0")
        }
        dependencies {
            dependency("com.fasterxml.jackson.core:jackson-annotations:2.18.6")
            dependency("com.fasterxml.jackson.core:jackson-core:2.18.6")
            dependency("com.fasterxml.jackson.core:jackson-databind:2.18.6")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.18.6")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.6")
            dependency("com.fasterxml.jackson.module:jackson-module-parameter-names:2.18.6")
            dependency("com.google.protobuf:protobuf-java:4.32.0")
            dependency("com.google.protobuf:protobuf-java-util:4.32.0")
            dependency("at.yawk.lz4:lz4-java:1.10.1")
            // Explicitly manage Spring Kafka (spring-boot-starter-kafka doesn't exist in SB 3.3.4)
            dependency("org.springframework.kafka:spring-kafka:3.2.4")
            dependency("org.springframework.kafka:spring-kafka-test:3.2.4")
        }
    }
}
