/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.connectors.flink;

import io.pravega.client.stream.Checkpoint;
import io.pravega.client.stream.ReaderGroup;
import io.pravega.client.stream.ReaderGroupConfig;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.concurrent.Executors;
import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReaderCheckpointHookTest {

    private static final String HOOK_UID = "test";

    @Test
    public void testConstructor() throws Exception {
        ReaderGroup readerGroup = mock(ReaderGroup.class);
        ReaderGroupConfig readerGroupConfig = mock(ReaderGroupConfig.class);
        TestableReaderCheckpointHook hook = new TestableReaderCheckpointHook(HOOK_UID, readerGroup, Time.minutes(1), readerGroupConfig);
        assertEquals(HOOK_UID, hook.getIdentifier());
        assertTrue(hook.createCheckpointDataSerializer() instanceof CheckpointSerializer);
    }

    @Test
    public void testTriggerCheckpoint() throws Exception {
        ReaderGroup readerGroup = mock(ReaderGroup.class);
        ReaderGroupConfig readerGroupConfig = mock(ReaderGroupConfig.class);
        CompletableFuture<Checkpoint> checkpointPromise = new CompletableFuture<>();
        when(readerGroup.initiateCheckpoint(anyString(), any())).thenReturn(checkpointPromise);
        TestableReaderCheckpointHook hook = new TestableReaderCheckpointHook(HOOK_UID, readerGroup, Time.minutes(1), readerGroupConfig);

        CompletableFuture<Checkpoint> checkpointFuture = hook.triggerCheckpoint(1L, 1L, Executors.directExecutor());
        assertNotNull(checkpointFuture);
        verify(readerGroup).initiateCheckpoint(anyString(), any());

        // complete the checkpoint promise
        Checkpoint expectedCheckpoint = mock(Checkpoint.class);
        checkpointPromise.complete(expectedCheckpoint);
        assertTrue(checkpointFuture.isDone());
        assertSame(expectedCheckpoint, checkpointFuture.get());
        hook.close();
        verify(hook.scheduledExecutorService).shutdownNow();
    }

    @Test
    public void testTriggerCheckpointTimeout() throws Exception {
        ReaderGroup readerGroup = mock(ReaderGroup.class);
        ReaderGroupConfig readerGroupConfig = mock(ReaderGroupConfig.class);
        CompletableFuture<Checkpoint> checkpointPromise = new CompletableFuture<>();
        when(readerGroup.initiateCheckpoint(anyString(), any())).thenReturn(checkpointPromise);
        TestableReaderCheckpointHook hook = new TestableReaderCheckpointHook(HOOK_UID, readerGroup, Time.minutes(1), readerGroupConfig);

        CompletableFuture<Checkpoint> checkpointFuture = hook.triggerCheckpoint(1L, 1L, Executors.directExecutor());
        assertNotNull(checkpointFuture);
        verify(readerGroup).initiateCheckpoint(anyString(), any());

        // invoke the timeout callback
        hook.invokeScheduledCallables();
        assertTrue(checkpointFuture.isCancelled());
        hook.close();
        verify(hook.scheduledExecutorService).shutdownNow();
    }

    @Test
    public void testReset() {
        ReaderGroup readerGroup = mock(ReaderGroup.class);
        ReaderGroupConfig readerGroupConfig = mock(ReaderGroupConfig.class);
        TestableReaderCheckpointHook hook = new TestableReaderCheckpointHook(HOOK_UID, readerGroup, Time.minutes(1), readerGroupConfig);
        hook.reset();
        verify(readerGroup).resetReaderGroup(readerGroupConfig);
    }

    @Test
    public void testClose() {
        ReaderGroup readerGroup = mock(ReaderGroup.class);
        ReaderGroupConfig readerGroupConfig = mock(ReaderGroupConfig.class);
        TestableReaderCheckpointHook hook = new TestableReaderCheckpointHook(HOOK_UID, readerGroup, Time.minutes(1), readerGroupConfig);
        hook.close();
        verify(readerGroup).close();
        assertNull(hook.scheduledExecutorService);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testRestore() throws Exception {
        ReaderGroup readerGroup = mock(ReaderGroup.class);
        ReaderGroupConfig readerGroupConfig = mock(ReaderGroupConfig.class);
        TestableReaderCheckpointHook hook = new TestableReaderCheckpointHook(HOOK_UID, readerGroup, Time.minutes(1), readerGroupConfig);

        Checkpoint checkpoint = mock(Checkpoint.class);
        hook.restoreCheckpoint(1L, checkpoint);
        verify(readerGroup).resetReadersToCheckpoint(checkpoint);
    }

    static class TestableReaderCheckpointHook extends ReaderCheckpointHook {
        private Callable<Void> scheduledCallable;

        @SuppressWarnings("unchecked")
        TestableReaderCheckpointHook(String hookUid, ReaderGroup readerGroup, Time triggerTimeout, ReaderGroupConfig readerGroupConfig) {
            super(hookUid, readerGroup, triggerTimeout, readerGroupConfig);
        }

        @Override
        protected ScheduledExecutorService createScheduledExecutorService() {
            ScheduledExecutorService newScheduledExecutor = mock(ScheduledExecutorService.class);
            when(newScheduledExecutor.schedule(any(Callable.class), anyLong(), any())).thenAnswer(a -> {
                scheduledCallable = a.getArgumentAt(0, Callable.class);
                return null;
            });

            return  newScheduledExecutor;
        }

        public void invokeScheduledCallables() throws Exception {
            if (scheduledCallable != null) {
                scheduledCallable.call();
            }
        }
    }
}
