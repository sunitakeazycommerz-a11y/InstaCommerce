#!/usr/bin/env bash
set -euo pipefail

# Protobuf Stub Generation & Synchronization Script
# Generates Go stubs into services/go-shared/pkg/proto from contracts/src/main/proto

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROTO_DIR="${REPO_ROOT}/contracts/src/main/proto"
GO_OUT_DIR="${REPO_ROOT}/services/go-shared/pkg/proto"

echo "=== Compiling Protobuf Contracts to Go Stubs ==="

# 1. Locate working protoc compiler
PROTOC_BIN=""
if command -v protoc >/dev/null 2>&1 && (protoc --version 2>/dev/null || true) | grep -q "libprotoc"; then
    PROTOC_BIN="protoc"
else
    # Find Gradle-cached standalone protoc executable binary from com.google.protobuf
    GRADLE_PROTOC=$(find "${HOME}/.gradle/caches" -type f -path "*/com.google.protobuf/protoc/*" \( -name "protoc-*.exe" -o -name "protoc" \) 2>/dev/null | head -n 1 || true)
    if [[ -n "${GRADLE_PROTOC}" && -x "${GRADLE_PROTOC}" ]]; then
        PROTOC_BIN="${GRADLE_PROTOC}"
    fi
fi

if [[ -z "${PROTOC_BIN}" ]]; then
    echo "ERROR: protoc binary not found. Run ./gradlew :contracts:build first to populate Gradle caches."
    exit 1
fi

echo "Using protoc: ${PROTOC_BIN} ($(${PROTOC_BIN} --version))"

# 2. Check / install protoc-gen-go and protoc-gen-go-grpc
export PATH="${PATH}:${HOME}/go/bin"

if ! command -v protoc-gen-go >/dev/null 2>&1; then
    echo "Installing protoc-gen-go..."
    go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
fi

if ! command -v protoc-gen-go-grpc >/dev/null 2>&1; then
    echo "Installing protoc-gen-go-grpc..."
    go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
fi

# 3. Create target directory
mkdir -p "${GO_OUT_DIR}"

# 4. Find all .proto files
PROTO_FILES=()
while IFS= read -r -d '' file; do
    PROTO_FILES+=("${file}")
done < <(find "${PROTO_DIR}" -type f -name "*.proto" -print0)

if [[ ${#PROTO_FILES[@]} -eq 0 ]]; then
    echo "No proto files found in ${PROTO_DIR}"
    exit 1
fi

echo "Found ${#PROTO_FILES[@]} proto contract files. Compiling..."

"${PROTOC_BIN}" \
    --proto_path="${PROTO_DIR}" \
    --go_out="${GO_OUT_DIR}" \
    --go_opt=module=github.com/instacommerce/go-shared/pkg/proto \
    --go-grpc_out="${GO_OUT_DIR}" \
    --go-grpc_opt=module=github.com/instacommerce/go-shared/pkg/proto \
    "${PROTO_FILES[@]}"

echo "Successfully generated Go Protobuf stubs into ${GO_OUT_DIR}"

# 5. Verify Go compilation
echo "Verifying Go stubs compilation..."
(cd "${REPO_ROOT}/services/go-shared" && go build ./pkg/proto/...)
echo "=== Go Protobuf Compilation & Validation Complete ==="
