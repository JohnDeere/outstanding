/**
 * Copyright 2016-2023 Deere & Company
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.deere.isg.outstanding;

import org.junit.Ignore;
import org.junit.Test;

import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Ignore("Ignored because these tests are meant for a human to watch how it works with concurrency")
public class ConcurrencyTest {
    private TestableOutstanding outstanding = new TestableOutstanding();

    @Test
    public void integrateWithPayloadPrinting() {
        runIntegrationTest(this::printPayloads);
    }

    @Test
    public void integrateWithUncleanTicketPrinting() {
        runIntegrationTest(this::printTicketsWithoutCleaning);
    }

    private void runIntegrationTest(Consumer<TestableOutstanding> fn) {
        Random random = new Random();
        ForkJoinPool pool = new ForkJoinPool(200);
        for(int i=999; i>100; i--) {
            int temp = i;
            pool.submit(() -> outstanding.doInTransaction(temp, ()->sleep(Math.abs(random.nextInt()) % 10000)));
        }

        while(pool.hasQueuedSubmissions() || pool.getActiveThreadCount() > 0 || !outstanding.isEmpty()) {
            fn.accept(outstanding);
            sleep(100);
        }
        fn.accept(outstanding);
    }

    private void printPayloads(TestableOutstanding outstanding) {
        System.out.println(outstanding.stream()
                .map((i)->Integer.toString(i))
                .collect(Collectors.joining(",")));
    }

    private void printTicketsWithoutCleaning(TestableOutstanding outstanding) {
        System.out.println(outstanding.streamTicketsNoClean()
                .map(Outstanding.Ticket::toString)
                .collect(Collectors.joining(",")));
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (Exception e) {
            System.err.println("Sleep interrupted: "+e.getMessage());
        }
    }
}
