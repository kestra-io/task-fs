package io.kestra.plugin.fs.sftp;

import com.devskiller.friendly_id.FriendlyId;
import com.google.common.collect.ImmutableMap;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.apache.commons.vfs2.FileSystemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.queues.QueueFactoryInterface;
import io.kestra.core.queues.QueueInterface;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.schedulers.AbstractScheduler;
import io.kestra.core.schedulers.SchedulerExecutionStateInterface;
import io.kestra.core.schedulers.SchedulerTriggerStateInterface;
import io.kestra.core.services.FlowListenersInterface;
import io.kestra.core.utils.TestsUtils;
import io.kestra.core.schedulers.DefaultScheduler;
import io.kestra.plugin.fs.sftp.models.File;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Named;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@MicronautTest
class TriggerTest {
    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private SchedulerTriggerStateInterface triggerState;

    @Inject
    private SchedulerExecutionStateInterface executionState;

    @Inject
    private FlowListenersInterface flowListenersService;

    @Inject
    @Named(QueueFactoryInterface.EXECUTION_NAMED)
    private QueueInterface<Execution> executionQueue;

    @Inject
    protected LocalFlowRepositoryLoader repositoryLoader;

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private SftpUtils sftpUtils;

    @Value("${kestra.variables.globals.random}")
    private String random;

    @BeforeEach
    private void init() throws IOException, URISyntaxException {
        repositoryLoader.load(Objects.requireNonNull(TriggerTest.class.getClassLoader().getResource("flows")));
    }

    @Test
    void flow() throws Exception {
        // mock flow listeners
        CountDownLatch queueCount = new CountDownLatch(1);

        // scheduler
        try (AbstractScheduler scheduler = new DefaultScheduler(
            this.applicationContext,
            this.flowListenersService,
            this.executionState,
            this.triggerState
        )) {
            AtomicReference<Execution> last = new AtomicReference<>();

            // wait for execution
            executionQueue.receive(TriggerTest.class, execution -> {
                last.set(execution);

                queueCount.countDown();
                assertThat(execution.getFlowId(), is("sftp-listen"));
            });


            String out1 = FriendlyId.createFriendlyId();
            sftpUtils.upload("/upload/" + random + "/" + out1);
            String out2 = FriendlyId.createFriendlyId();
            sftpUtils.upload("/upload/" + random + "/" + out2);

            scheduler.run();

            queueCount.await(1, TimeUnit.MINUTES);

            @SuppressWarnings("unchecked")
            java.util.List<File> trigger = (java.util.List<File>) last.get().getTrigger().getVariables().get("files");
            assertThat(trigger.size(), is(2));
        }
    }

    @Test
    @Disabled("don't work on github action")
    void missing() throws Exception {
        // mock flow listeners
        CountDownLatch queueCount = new CountDownLatch(1);

        // scheduler
        try (AbstractScheduler scheduler = new DefaultScheduler(
            this.applicationContext,
            this.flowListenersService,
            this.executionState,
            this.triggerState
        )) {
            AtomicReference<Execution> last = new AtomicReference<>();

            // wait for execution
            executionQueue.receive(TriggerTest.class, execution -> {
                last.set(execution);

                queueCount.countDown();
                assertThat(execution.getFlowId(), is("sftp-listen"));
            });


            scheduler.run();

            Thread.sleep(1000);

            String out1 = FriendlyId.createFriendlyId();
            sftpUtils.upload("/upload/" + random + "/" + out1);

            queueCount.await(1, TimeUnit.MINUTES);

            @SuppressWarnings("unchecked")
            java.util.List<URI> trigger = (java.util.List<URI>) last.get().getTrigger().getVariables().get("files");

            assertThat(trigger.size(), is(1));
        }
    }

    @Test
    void move() throws Exception {
        Trigger trigger = Trigger.builder()
            .id(TriggerTest.class.getSimpleName())
            .type(Trigger.class.getName())
            .host("localhost")
            .port("6622")
            .username("foo")
            .password("pass")
            .from("/upload/" + random + "/")
            .action(Downloads.Action.MOVE)
            .moveDirectory("/upload/" + random + "-move")
            .build();

        String out = FriendlyId.createFriendlyId();
        SftpOutput upload = sftpUtils.upload("/upload/" + random + "/" + out + ".yml");

        Map.Entry<RunContext, TriggerContext> context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> execution = trigger.evaluate(context.getKey(), context.getValue());

        assertThat(execution.isPresent(), is(true));

        @SuppressWarnings("unchecked")
        java.util.List<File> urls = (java.util.List<File>) execution.get().getTrigger().getVariables().get("files");
        assertThat(urls.size(), is(1));

        assertThrows(FileSystemException.class, () -> {
            Download task = Download.builder()
                .id(TriggerTest.class.getSimpleName())
                .type(Download.class.getName())
                .host("localhost")
                .port("6622")
                .username("foo")
                .password("pass")
                .from(upload.getTo().toString())
                .build();

            task.run(TestsUtils.mockRunContext(runContextFactory, task, ImmutableMap.of()));
        });

        Download task = Download.builder()
            .id(TriggerTest.class.getSimpleName())
            .type(Download.class.getName())
            .host("localhost")
            .port("6622")
            .username("foo")
            .password("pass")
            .from("/upload/" + random + "-move/" + out + ".yml")
            .build();

        SftpOutput run = task.run(TestsUtils.mockRunContext(runContextFactory, task, ImmutableMap.of()));
        assertThat(run.getTo().toString(), containsString("kestra://"));
    }
}
