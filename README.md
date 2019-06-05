[![Build Status](https://travis-ci.org/JohnDeere/outstanding.svg?branch=master)](https://travis-ci.org/JohnDeere/outstanding)

# outstanding
A library for the highly reused Outstanding class.

This stub of a collection is a non-blocking work tracker. 
Worker threads create() tickets with payloads, and return them by close()ing them when the work is done. 
Iterators and Streams are provided for applications to see what outstanding payloads there are. 

## Installation
Outstanding requires Java 8+ to run.

```xml
<dependency>
    <groupId>com.deere.isg</groupId>
    <artifactId>outstanding</artifactId>
    <version>1.2.0</version>
</dependency>
```

## Module support for Java 9 and later
```
 requires com.deere.isg.outstanding;
```

## Usage
```java
private static Outstanding<String> outstanding = new Outstanding<>();

String payload = UUID.randomUUID().toString();  // make some kind of tracking information.
outstanding.doInTransaction(payload, Example::doWork);

System.out.println("In progress work:");
outstanding.stream().forEach(System.out::println);

```

## Goals
The goals of this collection are:
* No list iteration for worker threads to create or close tickets
* Non-blocking
* No exceptional conditions
* Payloads are ordered by age when iterated
* Payloads are immediately available for garbage collection when tickets are closed
* Support try with resources and lambdas so that it is easy to program in a way that won't leak
* A weak reference implementation for the paranoid
* Uses links: ie, no large array allocation

Thus, this should be safe to be added to the critical path at the core of any application. 
No Collection implementation can accomplish all of that because of the nature of Collection interface. 

## Design Choices
To accomplish these goals, Outstanding:
* Uses the concept of a ticket that knows how to remove itself, 
rather than adhering to the Collection interface.
* Uses work stealing to clean the list.
* Attempts to keep the list short, but does not guarantee Tickets to be garbage collected immediately.
* Will always clean the list during iteration, but it does not require iteration to keep the list short.
* Is only singly linked
* Allows only as many closed tickets to be still in the list as there are non-closed tickets plus one.
* Never garbage collects the last ticket created

Typically a work tracker will want to track a start time and some notion of elapsed time. 
This implementation defers that responsibility to the payload.

## Known Uses
See [work-tracker](https://github.com/JohnDeere/work-tracker) 
for an payload that tracks elapsed time, and lots of other things too.

## Testing Locally
This builds with [Maven 3.6.1](https://maven.apache.org/docs/3.6.1/release-notes.html) 
and [Java 11](http://openjdk.java.net/install/).

```bash
mvn clean verify
```

The best way to see Outstanding in action is to run the 
[Concurrency Test](./outstanding-java/src/test/java/com/deere/isg/outstanding/ConcurrencyTest.java) from your IDE.

## Contributing
Please see the [Contribution Guidelines](./.github/CONTRIBUTING.md).
