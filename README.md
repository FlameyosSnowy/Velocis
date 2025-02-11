# Velocis
Velocis is a high-performance data structure library for efficient caching and optimized data manipulation. It provides custom implementations of cache algorithms such as LRU (Least Recently Used), LFU (Least Frequently Used), and LFRU (Least Frequently Recently Used), along with other specialized structures focused on concurrency and performance. Velocis aims to deliver thread-safe, scalable, and lock-free solutions for modern applications requiring fast and efficient data handling. It provides custom implementations of cache algorithms such as:

- **LRU (Least Recently Used)** – Automatically evicts the least recently accessed item.
- **LFU (Least Frequently Used)** – Removes the least frequently accessed item.
- **LFRU (Least Frequently Recently Used)** – A hybrid approach combining LRU and LFU.
- **Thread-Safe & Lock-Free Structures** – Optimized for concurrent environments.

## Features
- Custom caching structures tailored for performance.
- Lock-free and thread-safe implementations using `AtomicReference` and concurrent collections.
- Designed for modern high-throughput applications.

## Installation
To use Velocis in your project, include it as a dependency:

```xml
<dependency>
    <groupId>com.github.FlameyosSnowy</groupId>
    <artifactId>Velocis</artifactId>
    <version>1.0.0</version>
</dependency>
```
