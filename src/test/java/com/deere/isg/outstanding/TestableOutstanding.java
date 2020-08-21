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

import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Stream;

class TestableOutstanding extends Outstanding<Integer> {
    Iterator<Ticket> iterateUncleanTickets() {
        return new TicketIterator() {
            @Override
            protected Optional<Ticket> findNext(Ticket ticket) {
                return ticket.findNextUnclean();
            }
        };
    }

    int getNumLinks() {
        Optional<Ticket> current = head.findNextUnclean();
        int count = 0;
        while (current.isPresent()) {
            current = current.get().findNextUnclean();
            count++;
        }
        return count;
    }

    Stream<Ticket> streamTicketsNoClean() {
        return stream(iterateUncleanTickets());
    }
}
