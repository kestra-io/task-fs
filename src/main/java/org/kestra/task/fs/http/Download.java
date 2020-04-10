package org.kestra.task.fs.http;

import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.DefaultHttpClient;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.commons.io.FilenameUtils;
import org.kestra.core.models.annotations.Documentation;
import org.kestra.core.models.annotations.InputProperty;
import org.kestra.core.models.annotations.OutputProperty;
import org.kestra.core.models.executions.metrics.Counter;
import org.kestra.core.models.tasks.RunnableTask;
import org.kestra.core.models.tasks.Task;
import org.kestra.core.runners.RunContext;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Documentation(
    description = "Download file from http server",
    body = "This task connects to http server and copy file to kestra file storage"
)
public class Download extends Task implements RunnableTask<Download.Output> {
    @InputProperty(
        description = "The fully-qualified URIs that point to destination http server",
        dynamic = true
    )
    protected String uri;

    @InputProperty(
        description = "The http method to use"
    )
    @Builder.Default
    protected HttpMethod method = HttpMethod.GET;

    @InputProperty(
        description = "The full body as string",
        dynamic = true
    )
    protected String body;

    @InputProperty(
        description = "The header to pass to current request"
    )
    protected Map<CharSequence, CharSequence> headers;

    public Download.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger(getClass());

        // path
        URI from = new URI(runContext.render(this.uri));

        // temp file where download will be copied
        File tempFile = File.createTempFile(
            this.getClass().getSimpleName().toLowerCase() + "_",
            "." + FilenameUtils.getExtension(from.getPath())
        );

        // @todo
        // configuration
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        // configuration.setSslConfiguration(new SslConfiguration());

        // request
        MutableHttpRequest<String> request = HttpRequest
            .create(method, from.toString());

        if (this.body != null) {
            request.body(runContext.render(body));
        }

        if (this.headers != null) {
            request.headers(this.headers);
        }

        // output
        Output.OutputBuilder builder = Output.builder();

        // do it
        try (
            DefaultHttpClient client = new DefaultHttpClient(from.toURL(), configuration);
            BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(tempFile));
        ) {
            Long size = client
                .exchangeStream(request)
                .map(response -> {
                    if (builder.code == null) {
                        builder
                            .code(response.code())
                            .headers(response.getHeaders().asMap());
                    }

                    if (response.getBody().isPresent()) {
                        byte[] bytes = response.getBody().get().toByteArray();
                        output.write(bytes);

                        return (long) bytes.length;
                    } else {
                        return 0L;
                    }
                })
                .reduce(Long::sum)
                .blockingGet();

            if (builder.headers.containsKey("Content-Length")) {
                long length = Long.parseLong(builder.headers.get("Content-Length").get(0));
                if (length != size) {
                    throw new IllegalStateException("Invalid size, got " + size + ", expexted " + length);
                }
            }

            output.flush();

            runContext.metric(Counter.of("content.length", size));
            builder.uri(runContext.putTempFile(tempFile));

            logger.debug("File '{}' download to '{}'", from, builder.uri);

            return builder.build();
        }
    }

    @Builder
    @Getter
    public static class Output implements org.kestra.core.models.tasks.Output {
        @OutputProperty(
            description = "The url of the downloaded file on kestra storage"
        )
        private final URI uri;

        @OutputProperty(
            description = "The status code of the response"
        )
        private final Integer code;

        @OutputProperty(
            description = "The headers of the response"
        )
        private final Map<String, List<String>> headers;
    }
}