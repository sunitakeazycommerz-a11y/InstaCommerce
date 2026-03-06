FROM golang:1.26-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /stream-processor .

FROM gcr.io/distroless/static:nonroot
COPY --from=builder /stream-processor /stream-processor
USER nonroot:nonroot
EXPOSE 8108
ENTRYPOINT ["/stream-processor"]
