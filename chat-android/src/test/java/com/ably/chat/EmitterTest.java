package com.ably.chat;

import static com.ably.chat.TestUtilsKt.AssertCondition;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class EmitterTest {

    @Test
    public void testEmitter() {
        AsyncEmitter<String> asyncEmitter = new AsyncEmitter<>();
        ArrayList<String> receivedValues = new ArrayList<>();

        asyncEmitter.emit("1");

        Subscription subscription = asyncEmitter.on((coroutineScope, s, continuation) -> {
            receivedValues.add(s);
            return null;
        });

        asyncEmitter.emit("2");
        asyncEmitter.emit("3");
        asyncEmitter.emit("4");

        subscription.unsubscribe();

        asyncEmitter.emit("5");
        asyncEmitter.emit("6");

        AssertCondition(() -> receivedValues.size() == 3, 5000);

        Assert.assertEquals(Arrays.asList("2", "3", "4"), receivedValues);
    }
}
