package com.ably.chat;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class TestUtils {

    public interface ConditionFn<O> {
        O call();
    }

    public static class ConditionalWaiter {
        public Exception wait(ConditionFn<Boolean> condition, int timeoutInMs) {
            AtomicBoolean taskTimedOut = new AtomicBoolean();
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    taskTimedOut.set(true);
                }
            }, timeoutInMs);
            while (true) {
                try {
                    Boolean result = condition.call();
                    if (result) {
                        return null;
                    }
                    if (taskTimedOut.get()) {
                        throw new Exception("Timed out after " + timeoutInMs + "ms waiting for condition");
                    }
                    Thread.sleep(200);
                } catch (Exception e) {
                    return e;
                }
            }
        }
    }
}
