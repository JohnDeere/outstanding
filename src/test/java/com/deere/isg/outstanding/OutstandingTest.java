/**
 * Copyright 2016-2020 Deere & Company
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

import org.junit.Test;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;


import static org.junit.Assert.*;

public class OutstandingTest {

    private TestableOutstanding outstanding = new TestableOutstanding();

    @Test
    public void handles1ticket() {
        Outstanding<Integer>.Ticket ticket = assertCreateTicket(outstanding, 1);
        assert1iteration(outstanding, 1);
        assertIterator(outstanding.iterateTickets(), ticket);

        ticket.close();
        assertClosed(ticket);
        assertEmptyIteration(outstanding);
        assertEmptyIterator(outstanding.iterateTickets());

        assertTrue(outstanding.isEmpty());
    }

    @Test
    public void canCloseMiddle() {
        Outstanding<Integer>.Ticket one = assertCreateTicket(outstanding, 1);
        Outstanding<Integer>.Ticket two = assertCreateTicket(outstanding, 2);
        Outstanding<Integer>.Ticket three = assertCreateTicket(outstanding, 3);

        assertIteration(outstanding, 1, 2, 3);
        assertIterator(outstanding.iterateTickets(), one, two, three);

        two.close();
        assertClosed(two);

        assertIteration(outstanding, 1, 3);
        assertIterator(outstanding.iterateTickets(), one, three);

        assertFalse(outstanding.isEmpty());
    }

    @Test
    public void canCloseEnd() {
        Outstanding<Integer>.Ticket one = assertCreateTicket(outstanding, 1);
        Outstanding<Integer>.Ticket two = assertCreateTicket(outstanding, 2);
        Outstanding<Integer>.Ticket three = assertCreateTicket(outstanding, 3);

        assertIteration(outstanding, 1, 2, 3);

        three.close();
        assertClosed(three);

        assertIteration(outstanding, 1, 2);
        assertIterator(outstanding.iterateTickets(), one, two);

        assertFalse(outstanding.isEmpty());
    }

    @Test
    public void canCloseEnds() {
        Outstanding<Integer>.Ticket one = assertCreateTicket(outstanding, 1);
        Outstanding<Integer>.Ticket two = assertCreateTicket(outstanding, 2);
        Outstanding<Integer>.Ticket three = assertCreateTicket(outstanding, 3);

        assertIteration(outstanding, 1, 2, 3);

        one.close();
        assertClosed(one);
        assertIteration(outstanding, 2, 3);
        assertIterator(outstanding.iterateTickets(), two, three);

        three.close();
        assertClosed(three);

        assert1iteration(outstanding, 2);
        assertIterator(outstanding.iterateTickets(), two);

        assertFalse(outstanding.isEmpty());
    }

    @Test
    public void canCleanUpWhenNotIteratingEveryTime() {
        Outstanding<Integer>.Ticket one = assertCreateTicket(outstanding, 1);
        Outstanding<Integer>.Ticket two = assertCreateTicket(outstanding, 2);
        Outstanding<Integer>.Ticket three = assertCreateTicket(outstanding, 3);

        assertIteration(outstanding, 1, 2, 3);

        one.close();
        assertClosed(one);

        two.close();
        assertClosed(two);

        assertIterator(outstanding.iterateTickets(), three);
        assert1iteration(outstanding, 3);
    }

    @Test
    public void willNotLoseConcurrentTickets() {
        Outstanding<Integer>.Ticket one = assertCreateTicket(outstanding, 1);

        Outstanding<Integer>.Ticket two = outstanding.createTicket(2);
        Outstanding<Integer>.Ticket three = outstanding.createTicket(3);

        one.linkNext(two);
        one.linkNext(three);

        assertIteration(outstanding, 1, 2, 3);

        assertFalse(outstanding.isEmpty());
    }

    @Test
    public void willKeepListShortWhenNotIterating() {
        Outstanding<Integer>.Ticket one = assertCreateTicket(outstanding, 1);
        assertEquals(1, outstanding.getNumLinks());
        Outstanding<Integer>.Ticket two = assertCreateTicket(outstanding, 2);
        assertEquals(2, outstanding.getNumLinks());
        Outstanding<Integer>.Ticket three = assertCreateTicket(outstanding, 3);
        assertEquals(3, outstanding.getNumLinks());

        three.close();
        assertEquals("Can't remove self link", 3, outstanding.getNumLinks());
        two.close();
        assertEquals("Don't cleanup the end link", 3, outstanding.getNumLinks());
        one.close();
        assertEquals("Will collapse the beginning and middle links", 1, outstanding.getNumLinks());
    }

    @Test
    public void willNotLeakWithPause() {
        Outstanding<Integer>.Ticket one = assertCreateTicket(outstanding, 1);
        assertEquals(1, outstanding.getNumLinks());
        one.close();
        assertEquals(1, outstanding.getNumLinks());
        Outstanding<Integer>.Ticket two = assertCreateTicket(outstanding, 2);
        two.close();
        assertEquals(1, outstanding.getNumLinks());
    }

    @Test
    public void weakReferences() throws InterruptedException, IllegalArgumentException {
        Outstanding<Object> outstanding = new Outstanding.Weak<>();
        Integer foo = new Integer(123334543);
        Outstanding<Object>.Ticket one = assertCreateTicket(outstanding, foo);
        foo = null;

        System.gc();

        assertClosed(one);
    }

    @Test
    public void weakReferencesCloseManuallyToo() throws InterruptedException, IllegalArgumentException {
        Outstanding<Object> outstanding = new Outstanding.Weak<>();
        Integer foo = new Integer(123334543);
        Outstanding<Object>.Ticket one = assertCreateTicket(outstanding, foo);
        one.close();
        assertClosed(one);
    }

    @Test
    public void doInTransactionTest() {
        outstanding.doInTransaction(1, () -> assert1iteration(outstanding, 1));
        assertTrue(outstanding.isEmpty());
    }


    private static void assert1iteration(Iterable<Integer> outstanding, int expected) {
        boolean run = false;
        for(int value: outstanding) {
            assertEquals(expected, value);
            assertFalse("There should be only one value in the iterator", run);
            run = true;
        }
        assertTrue("Should have had 1 item in the iterator", run);
    }

    @SuppressWarnings("unchecked")
    private static <T> void assertIteration(Outstanding<T> iterable, T... values) {
        Iterator<T> iterator = iterable.iterator();
        assertIterator(iterator, (Object[]) values);
        assertArrayEquals(values, iterable.stream().collect(Collectors.toList()).toArray(values.clone()));
    }

    private static void assertIterator(Iterator<?> iterator, Object... values) {
        for(Object i: values) {
            assertNext(iterator, i);
        }
        assertFalse(iterator.hasNext());
    }

    private static void assertNext(Iterator<?> iterator, Object value) {
        assertTrue(iterator.hasNext());
        assertEquals(value, iterator.next());
    }

    private static void assertEmptyIteration(Iterable<?> outstanding) {
        Iterator<?> iterator = outstanding.iterator();
        assertEmptyIterator(iterator);
    }

    private static void assertEmptyIterator(Iterator<?> iterator) {
        assertFalse(iterator.hasNext());
        try {
            iterator.next();
            fail("Should have thrown NoSuchElementException");
        } catch(NoSuchElementException e) {
            // expected
        }
    }

    private static <T> Outstanding<T>.Ticket assertCreateTicket(Outstanding<T> outstanding, T value) {
        Outstanding<T>.Ticket ticket = outstanding.create(value);
        assertFalse(ticket.isClosed());
        assertEquals(value, ticket.getPayload().get());
        assertEquals(value.toString(), ticket.toString());
        return ticket;
    }

    private static void assertClosed(Outstanding<?>.Ticket ticket) {
        assertTrue(ticket.isClosed());
        assertFalse(ticket.getPayload().isPresent());
        assertEquals("deleted", ticket.toString());
    }
}
