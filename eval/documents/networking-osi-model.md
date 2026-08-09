# The OSI Model and Core Networking Concepts

The OSI (Open Systems Interconnection) model divides network communication into seven layers,
each responsible for a distinct part of getting data from one machine to another.

## The Seven Layers

1. **Physical** — raw bit transmission over a physical medium (cables, radio signals, voltages).
2. **Data Link** — framing, MAC addressing, and error detection on a single network segment
   (Ethernet operates here).
3. **Network** — logical addressing and routing between different networks (IP operates here).
4. **Transport** — end-to-end delivery, including reliability and flow control (TCP and UDP
   operate here).
5. **Session** — establishing, managing, and terminating sessions between applications.
6. **Presentation** — data translation, encryption, and compression so applications on different
   systems can understand each other.
7. **Application** — the layer applications directly interact with (HTTP, FTP, DNS all operate
   here).

## TCP vs UDP

TCP (Transmission Control Protocol) is connection-oriented: it establishes a connection via a
three-way handshake (SYN, SYN-ACK, ACK), guarantees ordered and reliable delivery via
acknowledgments and retransmission, and provides flow control. UDP (User Datagram Protocol) is
connectionless: it sends packets ("datagrams") with no handshake, no guarantee of delivery or
ordering, and minimal overhead — making it suitable for applications like video streaming or DNS
lookups where speed matters more than guaranteed delivery.

## IP Addressing

An IPv4 address is a 32-bit number, usually written as four decimal octets (e.g., 192.168.1.1). A
subnet mask determines which portion of the address identifies the network and which portion
identifies the host within that network. CIDR notation (e.g., /24) expresses the subnet mask as
the number of leading network bits.

## DNS

The Domain Name System (DNS) translates human-readable domain names (like example.com) into IP
addresses. A DNS resolver queries a hierarchy of servers — root servers, top-level-domain (TLD)
servers, and authoritative name servers — to resolve a name, typically caching the result for a
time-to-live (TTL) period to reduce repeated lookups.

## Three-Way Handshake

TCP's three-way handshake establishes a connection before any data is sent: the client sends a
SYN (synchronize) packet, the server responds with a SYN-ACK, and the client responds with a
final ACK. Only after this exchange completes does either side begin sending application data.
