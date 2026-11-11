package com._36knight.alpha.prototype;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 回归任务结束和独立心跳竞争，不启动模型、Worker、数据库或调度器。@author Andersen */
class PrototypeGenerationProgressTest {
    /** 已结束任务的迟到 pulse 不访问业务租约，也不能中断下一任务复用的线程。 */
    @Test
    void closedProgressCannotInterruptReusedExecutionThread() throws Exception {
        PrototypeService service = mock(PrototypeService.class);
        RecordingThread owner = new RecordingThread();
        Object progress = progress(service, owner);
        invoke(progress, "close");
        owner.beginNextTask();
        invoke(progress, "pulse");
        assertThat(owner.interruptions).hasValue(0);
        verifyNoInteractions(service);
    }

    /** close 必须等正在访问数据库的 pulse 完成，释放执行槽后旧心跳再也不能碰复用线程。 */
    @Test
    void closingWaitsForInFlightPulseBeforeThreadCanBeReused() throws Exception {
        PrototypeService service = mock(PrototypeService.class);
        RecordingThread owner = new RecordingThread();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        when(service.heartbeat(anyString(), anyString(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("未释放合成数据库等待");
            return false;
        });
        Object progress = progress(service, owner);
        Thread pulse = Thread.ofPlatform().start(() -> {
            try { invoke(progress, "pulse"); }
            catch (Throwable failure) { asynchronousFailure.compareAndSet(null, failure); }
        });
        Thread closer = null;
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            closer = Thread.ofPlatform().start(() -> {
                closing.countDown();
                try { invoke(progress, "close"); }
                catch (Throwable failure) { asynchronousFailure.compareAndSet(null, failure); }
                finally { closed.countDown(); }
            });
            assertThat(closing.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(closed.await(100, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            pulse.join(2000);
            assertThat(asynchronousFailure.get()).isNull();
            assertThat(owner.interruptions).hasValue(1);
            owner.beginNextTask();
            invoke(progress, "pulse");
            assertThat(owner.interruptions).hasValue(0);
            verify(service, times(1)).heartbeat(anyString(), anyString(), anyString(), anyInt(), any());
        } finally {
            release.countDown();
            pulse.join(2000);
            if (closer != null) closer.join(2000);
        }
    }

    /** 反射仅用于私有进度同步边界，不为测试扩大生产 API。 */
    private Object progress(PrototypeService service, Thread owner) throws Exception {
        Class<?> type = Class.forName("com._36knight.alpha.prototype.PrototypeGenerationRunner$Progress");
        var constructor = type.getDeclaredConstructor(PrototypeService.class, PrototypeContracts.Execution.class, Thread.class);
        constructor.setAccessible(true);
        var execution = new PrototypeContracts.Execution("synthetic-run", "synthetic-token", null, "{}", null, null, "");
        return constructor.newInstance(service, execution, owner);
    }

    /** 调用真实同步方法，测试不复制生产租约判断或 finished 条件。 */
    private void invoke(Object progress, String name) throws Exception {
        Method method = progress.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        try { method.invoke(progress); }
        catch (InvocationTargetException wrapper) {
            if (wrapper.getCause() instanceof Exception exception) throw exception;
            if (wrapper.getCause() instanceof Error error) throw error;
            throw wrapper;
        }
    }

    /** 记录拥有者收到的中断请求，避免将 JUnit 工作线程用作真实任务线程。 */
    private static final class RecordingThread extends Thread {
        /** 当前模拟任务期间收到的中断次数。 */
        private final AtomicInteger interruptions = new AtomicInteger();

        /** 仅记录中断对象，不运行外部任务或实际阻塞代码。 */
        @Override public void interrupt() { interruptions.incrementAndGet(); }

        /** 模拟同一个执行线程已开始下一任务。 */
        private void beginNextTask() { interruptions.set(0); }
    }
}
