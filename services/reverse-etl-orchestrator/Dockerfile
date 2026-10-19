FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o app ./cmd/server

FROM alpine:3.21
RUN addgroup -g 1001 app && adduser -u 1001 -G app -D app
WORKDIR /app
COPY --from=build /src/app .
USER app
ENV API_ADDR=":8109"
ENV HEALTH_ADDR=":8110"
ENV METRICS_ADDR=":9090"
EXPOSE 8109 8110 9090
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- "http://localhost:8110/health" || exit 1
ENTRYPOINT ["./app"]
