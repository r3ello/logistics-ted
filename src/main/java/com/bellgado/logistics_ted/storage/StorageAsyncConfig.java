package com.bellgado.logistics_ted.storage;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Thread pool for bucket housekeeping that must not sit inside a request.
 *
 * <p>Sized small on purpose: this work is never urgent. A CSV import of 150 houses queues 150 mirror
 * tasks at once (12 PUTs each), and draining them slowly over a few minutes on two threads is
 * exactly the desired behaviour — the import request itself already returned.
 *
 * <p>{@code DiscardPolicy} rather than {@code CallerRuns}: the marker objects are cosmetic (S3
 * creates prefixes implicitly on first upload), so dropping a few under an absurd burst is far
 * better than pushing the work back onto a request thread and re-creating the stall this exists to
 * remove. The rejection is visible in the executor's own metrics; nothing breaks.
 */
@Configuration
@EnableAsync
public class StorageAsyncConfig {

    public static final String EXECUTOR = "storageMirrorExecutor";

    @Bean(EXECUTOR)
    public Executor storageMirrorExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(1000);
        ex.setThreadNamePrefix("storage-mirror-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        // Don't hold shutdown for cosmetic work; anything unfinished is re-created on first upload.
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }
}
