package com.instacommerce.mobilebff.client;

import com.instacommerce.mobilebff.config.MobileBffProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Component
public class UpstreamClientFactory {

    private final WebClient catalogClient;
    private final WebClient searchClient;
    private final WebClient featureFlagClient;

    public UpstreamClientFactory(WebClient.Builder webClientBuilder, MobileBffProperties properties) {
        var upstream = properties.getUpstream();
        int timeoutMs = upstream.getTimeoutMs();

        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
            .responseTimeout(Duration.ofMillis(timeoutMs))
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS))
                .addHandlerLast(new WriteTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS)));

        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);

        this.catalogClient = webClientBuilder.clone()
            .baseUrl(upstream.getCatalogUrl())
            .clientConnector(connector)
            .build();

        this.searchClient = webClientBuilder.clone()
            .baseUrl(upstream.getSearchUrl())
            .clientConnector(connector)
            .build();

        this.featureFlagClient = webClientBuilder.clone()
            .baseUrl(upstream.getFeatureFlagUrl())
            .clientConnector(connector)
            .build();
    }

    public WebClient catalogClient() {
        return catalogClient;
    }

    public WebClient searchClient() {
        return searchClient;
    }

    public WebClient featureFlagClient() {
        return featureFlagClient;
    }
}
