plugins {
    java
    id("com.google.protobuf") version "0.9.6"
}

dependencies {
    implementation("io.grpc:grpc-protobuf:1.79.0")
    implementation("io.grpc:grpc-stub:1.79.0")
    implementation("io.grpc:grpc-netty-shaded:1.79.0")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.18.11")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.11")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

sourceSets {
    main {
        java {
            srcDir("src/main/java")
            srcDir("build/generated/sources/proto/main/java")
            srcDir("build/generated/sources/proto/main/grpc")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.79.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

tasks.register<Exec>("generateGoProto") {
    description = "Generates Go Protobuf and gRPC stubs into services/go-shared/pkg/proto"
    group = "build"
    workingDir = rootDir
    commandLine("./scripts/generate-proto.sh")
}

