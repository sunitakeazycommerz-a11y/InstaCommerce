plugins {
    java
    id("org.springframework.boot") version "4.0.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "7.0.2" apply false
    jacoco
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
    apply(plugin = "eclipse")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/*/java/**/*.java")
            targetExclude("build/**", "generated/**", "**/proto/**", "**/grpc/**")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    jacoco {
        toolVersion = "0.8.12"
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    plugins.withId("org.springframework.boot") {
        dependencies {
            "testImplementation"("org.springframework.boot:spring-boot-starter-test-classic")
        }
    }

    configurations.configureEach {
        exclude(group = "org.springframework.cloud", module = "spring-cloud-context")
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
            dependency("tools.jackson.core:jackson-annotations:3.1.7")
            dependency("com.fasterxml.jackson.core:jackson-annotations:2.21")
            dependency("com.fasterxml.jackson.core:jackson-core:2.18.11")
            dependency("com.fasterxml.jackson.core:jackson-databind:2.18.11")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.20.1")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.1")
            dependency("io.jsonwebtoken:jjwt-api:0.12.5")
            dependency("io.jsonwebtoken:jjwt-impl:0.12.5")
            dependency("io.jsonwebtoken:jjwt-jackson:0.12.5")
            dependency("tools.jackson.core:jackson-core:3.1.7")
            dependency("tools.jackson.core:jackson-databind:3.1.7")
            dependency("tools.jackson.datatype:jackson-datatype-jdk8:3.1.0")
            dependency("tools.jackson.datatype:jackson-datatype-jsr310:3.1.0")
            dependency("tools.jackson.module:jackson-module-parameter-names:3.1.0")
            dependency("com.google.protobuf:protobuf-java:4.32.0")
            dependency("com.google.protobuf:protobuf-java-util:4.32.0")
            dependency("at.yawk.lz4:lz4-java:1.12.0")
            dependency("org.springframework.kafka:spring-kafka:4.0.6")
            dependency("org.springframework.kafka:spring-kafka-test:4.0.6")
            dependency("net.javacrumbs.shedlock:shedlock-spring:5.12.0")
            dependency("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.12.0")
            dependency("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
            dependency("io.github.resilience4j:resilience4j-ratelimiter:2.2.0")
            dependency("io.github.resilience4j:resilience4j-micrometer:2.2.0")
            dependency("com.github.ben-manes.caffeine:caffeine:3.1.8")
            dependency("net.logstash.logback:logstash-logback-encoder:7.4")
            dependency("com.google.cloud.sql:postgres-socket-factory:1.15.0")
            dependency("org.testcontainers:testcontainers:1.19.3")
            dependency("org.testcontainers:postgresql:1.19.3")
            dependency("org.testcontainers:kafka:1.19.3")
            dependency("org.testcontainers:junit-jupiter:1.19.3")
            dependency("com.redis.testcontainers:testcontainers-redis:1.6.1")
            dependency("io.temporal:temporal-sdk:1.22.3")
            dependency("org.mapstruct:mapstruct:1.5.5.Final")
            dependency("org.mapstruct:mapstruct-processor:1.5.5.Final")
            dependency("com.stripe:stripe-java:24.18.0")
            dependency("com.google.guava:guava:33.0.0-jre")
            dependency("com.google.cloud:google-cloud-storage:2.38.0")
            dependency("org.apache.parquet:parquet-column:1.13.1")
            dependency("com.squareup.okhttp3:mockwebserver:4.12.0")
            dependency("io.lettuce:lettuce-core:6.3.2.RELEASE")
        }
    }
}
