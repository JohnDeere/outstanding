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

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * This stub of a collection is a non-blocking work tracker.
 * Worker threads create() tickets with payloads, and return them by close()ing them when the work is done.
 * Iterators and Streams are provided for applications to see what outstanding payloads there are.
 *
 * The goals of this collection are: <ol>
 * <li>No list iteration for worker threads to create or close tickets</li>
 * <li>Non-blocking</li>
 * <li>No exceptional conditions</li>
 * <li>Payloads are ordered by age when iterated</li>
 * <li>Payloads are immediately available for garbage collection when tickets are closed</li>
 * <li>Support try with resources and lambdas so that it is easy to program in a way that won't leak</li>
 * <li>A weak reference implementation for the paranoid</li>
 * <li>Uses links: ie, no large array allocation</li>
 * </ol>
 *
 * Thus, this should be safe to be added to the critical path at the core of any application.
 *
 * No Collection implementation can accomplish all of that because of the nature of Collection interface.
 *
 * To accomplish these goals, Outstanding: <ol>
 * <li>Uses the concept of a ticket that knows how to remove itself, rather than adhering to the Collection interface.</li>
 * <li>Uses work stealing to clean the list.</li>
 * <li>Attempts to keep the list short, but does not guarantee Tickets to be garbage collected immediately.</li>
 * <li>Will always clean the list during iteration, but it does not require iteration to keep the list short.</li>
 * <li>Is only singly linked</li>
 * <li>Allows only as many closed tickets to be still in the list as there are non-closed tickets plus one.</li>
 * <li>Never garbage collects the last ticket created</li>
 * </ol>
 *
 * Typically a work tracker will want to track a start time and some notion of elapsed time. This implementation
 * defers that responsibility to the payload.
 *
 * @param <T> The type of elements tracked and returned by iterators and streams.
 */
public class Outstanding<T> implements Iterable<T> {

    public abstract class Ticket implements AutoCloseable {
        private AtomicReference<Ticket> link = new AtomicReference<>();

        /**
         * @return the payload associated with this Ticket if it still exists.
         */
        public abstract Optional<T> getPayload();

        /**
         * @return true if this Ticket has been closed.
         */
        public abstract boolean isClosed();

        /**
         * Calls @see #clearPayload()
         * then proceeds to clean out any closed tickets that follow this one until it finds one that is not closed.
         */
        @Override
        public final void close() {
            clearPayload();
            clean();
        }

        /**
         * Implementations should de-reference the Payload to prevent memory leaks.
         * Once this is called @see #isClosed() must return true and @see #getPayload() must return an empty Optional.
         */
        protected abstract void clearPayload();

        private void clean() {
            Ticket first = head.link.get();
            if(first == this || first.isClosed()) {
                head.cleanTilNext();
            }
            if(first != this) {
                cleanTilNext();
            }
        }

        final Ticket linkNext(Ticket nextOne) {
            if(!link.compareAndSet(null, nextOne)) {
                return link.get().linkNext(nextOne);
            }
            return nextOne;
        }

        // cleanup closed tickets as we go
        private Optional<Ticket> findNext() {
            for(;;) {
                Ticket evaluating = link.get();
                if (evaluating == null) {
                    return Optional.empty();
                } else {
                    if (evaluating.isClosed()) {
                        Ticket next = evaluating.link.get();
                        if (next != null) {
                            link.compareAndSet(evaluating, next);
                        } else {
                            return Optional.empty();
                        }
                    } else {
                        return Optional.of(evaluating);
                    }
                }
            }
        }

        private void cleanTilNext() {
            for(;;) {
                Ticket evaluating = link.get();
                if (evaluating != null && evaluating.isClosed()) {
                    Ticket next = evaluating.link.get();
                    if (next != null) {
                        link.compareAndSet(evaluating, next);
                        continue;
                    }
                }
                return;
            }
        }

        final Optional<Ticket> findNextUnclean() {
            return Optional.ofNullable(link.get());
        }

        /**
         * This is functional for debugging purposes only.
         * @return debugging information.
         */
        @Override
        public String toString() {
            return getPayload().map(Object::toString).orElse("deleted");
        }
    }

    protected final Ticket head = createTicket(null);
    private Ticket last = head;

    /**
     * Add the given payload towards the end of the collection and returns a ticket you can use to indicate
     * when the work is done. @see Ticket#close()
     * It is usually better to use @see #doInTransaction(T, Runnable) as it will close the ticket for you.
     * @param payload The object that will be tracked and returned in iteration.
     * @return a newly created Ticket
     */
    public Ticket create(T payload) {
        Ticket nextOne = createTicket(payload);
        last = last.linkNext(nextOne);
        return nextOne;
    }

    /**
     * A place to override if you want to change the features of the Ticket and how it holds onto the payload.
     * It should just return a newly created Ticket implementation.
     * By default this holds onto the payload object in the Ticket in a normal strong reference
     * until the ticket is closed.
     *
     * @param payload An object you want to track until the ticket is closed.
     * @return a ticket holding the payload
     */
    protected Ticket createTicket(T payload) {
        return new StrongTicket(payload);
    }

    /**
     * @return an Iterator of the open payloads only.
     */
    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            private Iterator<Ticket> tickets = iterateTickets();
            private T nextPayload;

            /**
             * It is required to call hasNext() before each next() or this iterator will not function.
             */
            @Override
            public boolean hasNext() {
                while(nextPayload == null && tickets.hasNext()) {
                    nextPayload = tickets.next().getPayload().orElse(null);
                }
                return nextPayload != null;
            }

            /**
             * Get the next payload.  You must call hasNext() before each call to this method.
             */
            @Override
            public T next() {
                T temp = nextPayload;
                nextPayload = null;
                if(temp == null) {
                    throw new NoSuchElementException();
                }
                return temp;
            }
        };
    }

    /**
     * @return a Stream of the open payloads only.
     */
    public Stream<T> stream() {
        return stream(iterator());
    }

    /**
     * @return true if all the payloads are closed.
     */
    public boolean isEmpty() {
        Ticket current = head.link.get();
        while(current != null) {
            if(!current.isClosed()) {
                return false;
            }
            current = current.link.get();
        }
        return true;
    }

    /**
     * Provided primarily for testing purposes.
     * @return An Iterator of Tickets that have open payloads or have not yet been cleaned out.
     */
    Iterator<Ticket> iterateTickets() {
        return new TicketIterator() {
            @Override
            protected Optional<Ticket> findNext(Ticket ticket) {
                return ticket.findNext();
            }
        };
    }

    abstract class TicketIterator implements Iterator<Ticket> {
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private Optional<Ticket> current = findNext(head);
        private boolean used = false;

        abstract Optional<Ticket> findNext(Ticket ticket);

        @Override
        public boolean hasNext() {
            if(used && current.isPresent()) {
                current = findNext(current.get());
            }
            return current.isPresent();
        }

        @Override
        @SuppressWarnings("OptionalGetWithoutIsPresent")
        public Ticket next() {
            Ticket ticket = current.get();
            used = true;
            return ticket;
        }
    }

    static <T> Stream<T> stream(Iterator<T> iterator) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
    }

    /**
     * Creates a Ticket for the payload, runs the given transaction in the same thread,
     * then closes the ticket once the transaction is completed (even if exceptionally).
     * @param payload The thing you want tracked during transaction execution.
     * @param transaction A transaction to run. It must not be null.
     */
    public void doInTransaction(T payload, Runnable transaction) {
        try (Outstanding<T>.Ticket ignored = create(payload)) {
            transaction.run();
        }
    }

    /**
     * Keeps the payload in a weak reference.
     * It is completely unnecessary if you use doInTransaction() and light payloads.
     */
    public static class Weak<T> extends Outstanding<T> {
        @Override
        protected Ticket createTicket(T payload) {
            return this.new WeakTicket(payload);
        }
    }

    class WeakTicket extends Ticket {
        private volatile WeakReference<T> payload;

        WeakTicket(T payload) {
            this.payload = new WeakReference<>(payload);
        }

        @Override
        public Optional<T> getPayload() {
            return Optional.ofNullable(payload.get());
        }

        @Override
        public boolean isClosed() {
            return payload.get() == null;
        }

        @Override
        public void clearPayload() {
            this.payload.clear();
        }
    }

    private class StrongTicket extends Ticket {
        private volatile T payload;

        StrongTicket(T payload) {
            this.payload = payload;
        }

        @Override
        public Optional<T> getPayload() {
            return Optional.ofNullable(payload);
        }

        @Override
        public boolean isClosed() {
            return payload == null;
        }

        @Override
        public void clearPayload() {
            this.payload = null;
        }
    }
}
