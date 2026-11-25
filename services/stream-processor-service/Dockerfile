FROM golang:1.25-alpine AS builder
WORKDIR /app
COPY go.mod ./
COPY go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /stream-processor .

FROM alpine:3.23
RUN addgroup -g 1001 app && adduser -u 1001 -G app -D app
WORKDIR /app
COPY --from=builder /stream-processor ./app
USER app
ENV HTTP_PORT=8108
ENV PORT=8108
EXPOSE 8108
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- "http://localhost:${PORT}/health" || exit 1
ENTRYPOINT ["./app"]
